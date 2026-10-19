package com.tr069.acs.acs.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.acs.auth.CpeAuthenticator;
import com.tr069.acs.acs.service.DeviceCapabilityService;
import com.tr069.acs.acs.service.InformService;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.PendingRpc;
import com.tr069.acs.acs.session.SessionManager;
import com.tr069.acs.acs.session.SessionState;
import com.tr069.acs.agent.RpcDispatchService;
import com.tr069.acs.cwmp.RpcMethod;
import com.tr069.acs.cwmp.SoapEnvelopeBuilder;
import com.tr069.acs.cwmp.SoapParser;
import com.tr069.acs.cwmp.model.InformRequest;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.queue.ReportQueueService;
import com.tr069.acs.queue.RpcTaskQueue;
import com.tr069.acs.queue.config.QueueProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CWMP 接入端点，设备侧 HTTP 入口。
 *
 * <p>对应 Python 版 webserver.py 的 {@code handle_soap}。遵循反向轮询模型：</p>
 * <ul>
 *   <li>Inform：回复 InformResponse，不能夹带下发</li>
 *   <li>空 POST：若有待下发 RPC 则在响应体捎带，否则返回 204</li>
 *   <li>RPC 响应：完成对应等待，回复 204</li>
 * </ul>
 *
 * <p>设备能力采集 ({@link DeviceCapabilityService}) 正是搭在这条链路上：Inform 阶段就把
 * {@code GetParameterNames} 排进下发队列，CPE 紧跟的空 POST 把它捎带下去，
 * 拿到路径后再用 {@code GetParameterValues} 分批读值。</p>
 *
 * <p>两条链路都接了 Redis 队列做削峰：</p>
 * <ul>
 *   <li>上报：只做解析、认证、建会话与回响应，参数处理交给 {@link ReportQueueService} 的消费者</li>
 *   <li>下发：指令从 {@link RpcTaskQueue} 取出捎带，不占用任何等待线程</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CwmpController {

    private final SessionManager sessionManager;
    private final CpeAuthenticator authenticator;
    private final InformService informService;
    private final ReportQueueService reportQueue;
    private final RpcTaskQueue rpcTaskQueue;
    private final RpcDispatchService rpcDispatchService;
    private final DeviceCapabilityService deviceCapabilityService;
    private final QueueProperties queueProperties;
    private final ObjectMapper objectMapper;

    /**
     * 默认值刻意留空：配置里不写或写 auto 即免认证，任何设备凭据都放行。
     * 需要认证时填真实账号密码即可，见 {@link CpeAuthenticator#isOpenAccess}。
     */
    @Value("${acs.auth.cpe-to-acs-username:}")
    private String defaultCpeUser;

    @Value("${acs.auth.cpe-to-acs-password:}")
    private String defaultCpePassword;

    @Value("${acs.session.wait-post-null-timeout:60}")
    private long waitPostNullTimeout;

    @Value("${acs.session.wait-rpc-response-timeout:60}")
    private long waitRpcResponseTimeout;

    /**
     * 队列空时还要等一会儿才回 204 的时长（毫秒）。
     *
     * <p>见 {@link #deliverNextRpcWithWait}：能力采集是一问一答编排出来的，
     * 上一条的响应刚被处理完、下一条往往几十毫秒后才入队。此刻回 204 就等于掐断会话。</p>
     */
    @Value("${acs.session.piggyback-wait-ms:1500}")
    private long piggybackWaitMs;

    /** 等下一条指令时的轮询间隔。 */
    private static final long PIGGYBACK_POLL_INTERVAL_MS = 20L;

    private final ScheduledExecutorService timeoutScheduler =
            Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "acs-timeout");
                t.setDaemon(true);
                return t;
            });

    /**
     * CPE 上报入口，同时兼容 CPE 的 /cpe 路径。
     */
    @RequestMapping(value = {"${acs.cwmp.cpe-path:/ACS-server/ACS}", "/cpe"},
            method = {org.springframework.web.bind.annotation.RequestMethod.POST})
    public void handleCpe(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String connId = connectionId(request);
        String body = readBody(request);

        try {
            if (body == null || body.isBlank()) {
                handleEmptyPost(connId, request, response);
                return;
            }
            handleSoap(connId, body, request, response);
        } catch (Exception e) {
            log.error("处理 CPE 请求异常, conn={}", connId, e);
            sendStatus(response, 500);
        }
    }

    // ==================== 空 POST ====================

    /**
     * 处理 CPE 的空 POST：有任务则下发 RPC，否则 204。
     *
     * <p>指令来源是 Redis 队列而不是内存 outbox，这样设备离线或处理慢时
     * 指令不会丢，也不会有线程挂在这里等。</p>
     */
    private void handleEmptyPost(String connId, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String sn = resolveSn(connId);
        if (sn == null) {
            log.warn("空 POST 无法定位设备, conn={}", connId);
            sendStatus(response, 400);
            return;
        }
        CpeSession session = sessionManager.find(sn);
        if (session == null) {
            sendStatus(response, 400);
            return;
        }
        session.touch();

        // 同一时刻只允许一个已下发但未回应的 RPC
        PendingRpc awaiting = session.getAwaiting();
        if (awaiting != null && !awaiting.getResponse().isDone()) {
            sendStatus(response, 204);
            return;
        }
        if (!deliverNextRpcWithWait(sn, session, response)) {
            log.debug("无待下发任务, 回复 204: sn={}", sn);
            sendStatus(response, 204);
        }
    }

    /**
     * 取一条待下发指令捎带出去，队列暂时空时稍等一会儿。
     *
     * <p>队列空<strong>不等于</strong>这一轮该结束了：能力采集是一问一答编排出来的，
     * 上一条响应刚被处理、下一条往往几十毫秒后才入队（要拿上一轮的返回值拼出下一批）。
     * 这时候回 204，华为电信这类固件就认为会话结束、不再发空 POST，剩下的指令只能等它
     * 下一次上报——周期常常是十几分钟，采一棵树要拖上几个小时，设备多半中途掉线，
     * 采集就停在半路了。所以先等一小会儿，等到就继续捎带，等不到再收尾。</p>
     *
     * <p>等的是 Tomcat 线程，时间很短（默认 1.5 秒），且只发生在会话末尾，
     * 不会长期占用。</p>
     */
    private boolean deliverNextRpcWithWait(String sn, CpeSession session, HttpServletResponse response)
            throws IOException {
        if (deliverNextRpc(sn, session, response)) {
            return true;
        }
        long deadline = System.currentTimeMillis() + piggybackWaitMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(PIGGYBACK_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (deliverNextRpc(sn, session, response)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取一条待下发指令并写进当前 HTTP 响应。
     *
     * @return 是否真的下发了指令；{@code false} 表示队列已空（或指令非法已被丢弃），
     *         由调用方回复 204 结束这一轮
     */
    private boolean deliverNextRpc(String sn, CpeSession session, HttpServletResponse response)
            throws IOException {
        PendingRpc rpc = null;
        if (queueProperties.isEnabled()) {
            RpcTaskQueue.RpcTask task = rpcTaskQueue.poll(sn);
            if (task != null) {
                Map<String, Object> args = rpcTaskQueue.parseArgs(task.getArgsJson());
                rpc = new PendingRpc(task.getTaskId(), task.getRpcName(), args, task.getWorklistId());
                markRunning(task);
            }
        }
        // 队列关闭或没有待发指令时，退回内存 outbox
        if (rpc == null) {
            rpc = session.pollForSend();
        }
        if (rpc == null) {
            return false;
        }

        RpcMethod method = RpcMethod.of(rpc.getRpcName());
        if (method == null) {
            // 队列里可能残留方法名不合法的旧指令（如写成枚举名 GET_PARAMETER_NAMES），
            // 不能让它把这次下发打成 500：判失败并丢弃，设备下次照常来取后面的指令
            log.error("待下发指令的 CWMP 方法无法识别，丢弃该指令: sn={}, taskId={}, rpc={}",
                    sn, rpc.getRpcId(), rpc.getRpcName());
            if (rpc.getRpcId() > 0) {
                rpcDispatchService.failTask(rpc.getRpcId(), "8005", "未知 CWMP 方法: " + rpc.getRpcName());
            } else {
                rpc.getResponse().complete(null);
            }
            return false;
        }
        String soap = SoapEnvelopeBuilder.build(method, rpc.getArgs(),
                null, session.getCwmpVersion());
        session.markAwaiting(rpc);
        session.setState(SessionState.WAIT_RPC_RESPONSE);
        armRpcResponseTimer(session);

        log.info("下发 RPC: sn={}, rpc={}, taskId={}", sn, rpc.getRpcName(), rpc.getRpcId());
        writeXml(response, soap);
        return true;
    }

    // ==================== SOAP 报文 ====================

    private void handleSoap(String connId, String body, HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        SoapMessage message = SoapParser.parse(body);
        String methodName = message.getMethodName();

        if ("Inform".equals(methodName)) {
            handleInform(connId, body, request, response, message.getCwmpId());
            return;
        }

        // 其余均为 CPE 对我方下发的响应
        String sn = resolveSn(connId);
        if (sn == null) {
            log.warn("响应报文无法定位设备, conn={}", connId);
            sendStatus(response, 400);
            return;
        }
        CpeSession session = sessionManager.find(sn);
        if (session == null) {
            sendStatus(response, 400);
            return;
        }
        session.touch();
        PendingRpc completed = session.completeAwaiting(message);
        if (completed != null) {
            log.info("收到 RPC 响应: sn={}, rpc={}, fault={}",
                    sn, completed.getRpcName(), message.isFault());
            if (message.isFault() && message.getStrResult() != null) {
                // 设备给的故障明细（含逐参数失败）只在这一刻拿得到，打出来便于定位是哪一个参数不对
                log.warn("设备故障明细: sn={}, rpc={}, {}",
                        sn, completed.getRpcName(),
                        message.getStrResult().replace("\n", " | "));
            }
            // 回填结果并唤醒排在该指令后面的工单步骤
            rpcDispatchService.completeTask(completed.getRpcId(), message);
        } else {
            log.warn("收到未预期的 RPC 响应: sn={}, method={}", sn, methodName);
        }
        session.setState(SessionState.READY);
        // 在同一个 HTTP 响应里直接带上下一条指令：这是 CWMP 允许的捎带方式，也几乎是
        // 华为电信这类固件的唯一通路——它们收到 204 就认为会话结束，不会再发空 POST。
        // 不捎带的话一条指令就要等一个上报周期，采一棵树得拖上几十分钟，
        // 设备还常常就此掉线（采集卡在半路）
        if (!deliverNextRpcWithWait(sn, session, response)) {
            sendStatus(response, 204);
        }
    }

    /**
     * 处理 Inform：认证、建会话、回复 InformResponse。
     *
     * <p>参数入库与事件调度交给队列消费者，这里只保留必须在响应前完成的动作，
     * 让 Tomcat 线程尽快释放。</p>
     *
     * @param cwmpId 设备报文头里的会话 ID，必须原样回显：
     *               CWMP 规定响应的 {@code cwmp:ID} 要与请求一致，华为电信固件
     *               （HS8145C5 / B866 这类）对不上就认为这次会话没被接受，
     *               会带着递增的 RetryCount 反复重发 BOOTSTRAP，也不发空 POST，
     *               ACS 于是永远没有下发窗口
     */
    private void handleInform(String connId, String body, HttpServletRequest request,
                              HttpServletResponse response, String cwmpId) throws IOException {
        InformRequest inform = SoapParser.parseInform(body);
        if (inform.getDeviceId() == null || inform.getDeviceId().getSerialNumber() == null
                || inform.getDeviceId().getSerialNumber().isBlank()) {
            log.warn("Inform 缺少设备标识, conn={}", connId);
            sendStatus(response, 400);
            return;
        }
        String oui = inform.getDeviceId().getOui();
        String sn = inform.getDeviceId().getSerialNumber();

        // CPE -> ACS 认证
        CpeAuthenticator.AuthResult auth = authenticator.authenticate(
                request.getHeader("Authorization"),
                request.getMethod(),
                request.getRequestURI(),
                defaultCpeUser, defaultCpePassword,
                defaultCpeUser, defaultCpePassword,
                CpeAuthenticator.AUTH_TYPE_DIGEST);
        if (auth == CpeAuthenticator.AuthResult.NO_AUTHENTICATION) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", authenticator.digestChallenge());
            log.info("等待设备携带认证重发 Inform: sn={}", sn);
            return;
        }
        if (auth == CpeAuthenticator.AuthResult.FAIL) {
            log.warn("设备认证失败: sn={}", sn);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", authenticator.digestChallenge());
            return;
        }

        // 疑似双会话检测，对应原版 is_2session1cpe。
        // CWMP 里 CPE 是反向通道：每 60 秒周期上报一次，每次都可能是新的 TCP 连接
        // （源端口不同），也可能关掉旧连接、另开一条把指令回应发回来。这些都是正常的
        // 会话接管，会话还在，设备就还活着，只是"告知 ACS 我在线"。
        // 这里只记日志供排查，不关闭会话：关掉会连带把设备打离线、清空待下发队列、
        // 作废正在等待的指令，代价远大于收益。是否离线交给 sweepExpiredSessions
        // 按无通信时长判定。
        if (sessionManager.detectDuplicateSession(sn, connId)) {
            log.warn("疑似双会话，按会话接管处理: sn={}, connId={}", sn, connId);
        }

        CpeSession session = sessionManager.getOrCreate(oui, sn, connId);

        // 抢窗口：Inform 的响应体只能是 InformResponse，下发要等 CPE 紧接着的那次空 POST，
        // 而那次空 POST 就走在本条 TCP 连接上。采集指令必须在回复之前排进下发队列，
        // 空 POST 到达时才能被立刻取走，否则只能等设备下一次周期上报。
        deviceCapabilityService.onInform(session, inform);

        // 上报处理入队；队列不可用时就地处理，保证功能不中断
        boolean queued = false;
        if (queueProperties.isEnabled()) {
            try {
                ReportQueueService.ReportMessage msg = new ReportQueueService.ReportMessage();
                msg.setSn(sn);
                msg.setOui(oui);
                msg.setConnId(connId);
                msg.setRawXml(body);
                msg.setInformJson(objectMapper.writeValueAsString(inform));
                queued = reportQueue.offer(msg);
            } catch (Exception e) {
                log.warn("Inform 入队失败，就地处理: sn={}, err={}", sn, e.getMessage());
            }
        }
        if (!queued) {
            informService.handle(session, inform, body);
        }

        String informResponse = SoapEnvelopeBuilder.buildInformResponse(cwmpId, session.getCwmpVersion());
        writeXml(response, informResponse);

        session.setState(SessionState.WAIT_POST_NULL);
        armWaitPostNullTimer(session);

        log.debug("Inform 处理完成, 等待空 POST: sn={}, queued={}", sn, queued);
    }

    // ==================== 超时控制 ====================

    /**
     * 等待 CPE 空 POST 的定时器。
     *
     * <p>超时只意味着<strong>这一轮的下发窗口结束了</strong>，不等于设备离线：
     * 相当一部分光猫 Inform 之后根本不发空 POST（ACS 没有待发请求时它们就直接挂起连接），
     * 等到下一个周期再从新连接上报。以前这里直接关会话，于是设备每分钟被判一次离线，
     * 刚排进队列的指令也跟着被作废。</p>
     *
     * <p>现在只结束窗口、作废这一轮没等到回应的指令，会话继续留着，
     * 设备是否离线交给 {@code SessionManager#sweepExpiredSessions} 按无通信时长判定。</p>
     */
    private void armWaitPostNullTimer(CpeSession session) {
        session.setWaitPostNullTimer(timeoutScheduler.schedule(() -> {
            if (session.getState() != SessionState.WAIT_POST_NULL) {
                return;
            }
            PendingRpc pending = session.failAwaiting("wait empty post timeout");
            if (pending != null) {
                log.warn("下发窗口结束，指令未收到回应: sn={}, rpc={}",
                        session.getSn(), pending.getRpcName());
                // 队列关闭时指令走内存 outbox，用的是负数序号，没有对应的任务记录
                if (pending.getRpcId() > 0) {
                    rpcDispatchService.failTask(pending.getRpcId(), "8002", "等待空 POST 超时，指令未送达");
                }
            }
            session.setState(SessionState.READY);
            log.info("下发窗口结束, 等待设备下一次上报: sn={}", session.getSn());
        }, waitPostNullTimeout, TimeUnit.SECONDS));
    }

    private void armRpcResponseTimer(CpeSession session) {
        session.setWaitRpcResponseTimer(timeoutScheduler.schedule(() -> {
            PendingRpc rpc = session.failAwaiting("wait rpc response timeout");
            if (rpc != null) {
                log.warn("等待 RPC 响应超时: sn={}, rpc={}", session.getSn(), rpc.getRpcName());
                rpcDispatchService.failTask(rpc.getRpcId(), "8002", "等待 RPC 响应超时");
            }
        }, waitRpcResponseTimeout, TimeUnit.SECONDS));
    }

    /**
     * 指令被捎带出去后标记为执行中，便于调用方看到进度。
     */
    private void markRunning(RpcTaskQueue.RpcTask task) {
        RpcTaskQueue.RpcTaskResult running = new RpcTaskQueue.RpcTaskResult();
        running.setTaskId(task.getTaskId());
        running.setSn(task.getSn());
        running.setRpcName(task.getRpcName());
        running.setStatus("running");
        running.setSubmitTime(task.getCreatedAt());
        rpcTaskQueue.saveResult(running);
    }

    // ==================== 工具 ====================

    private String connectionId(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        return ip + ":" + request.getRemotePort();
    }

    /**
     * 按 TCP 连接定位设备，对应原版 sessionno -> CPE 的映射。
     */
    private String resolveSn(String connId) {
        return sessionManager.findSnByConnection(connId);
    }

    private String readBody(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readAllBytes();
        if (bytes.length == 0) {
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void writeXml(HttpServletResponse response, String soap) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.TEXT_XML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("SOAPAction", "");
        response.getOutputStream().write(soap.getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }

    private void sendStatus(HttpServletResponse response, int status) {
        response.setStatus(status);
        response.setContentLength(0);
    }
}
