package com.tr069.acs.acs.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.acs.auth.CpeAuthenticator;
import com.tr069.acs.acs.service.DeviceCapabilityService;
import com.tr069.acs.acs.service.InformProvisionService;
import com.tr069.acs.acs.service.InformService;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.CwmpSessionMutex;
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
import com.tr069.acs.queue.RpcDeliveryWaiter;
import com.tr069.acs.queue.RpcTaskQueue;
import com.tr069.acs.queue.config.QueueProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CWMP 接入端点，设备侧 HTTP 入口：接收 Inform、空 POST 与 RPC 响应。
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
    private final InformProvisionService informProvisionService;
    private final QueueProperties queueProperties;
    private final ObjectMapper objectMapper;
    private final RpcDeliveryWaiter deliveryWaiter;
    private final CwmpSessionMutex sessionMutex;

    /** 留空即免认证，任何设备凭据都放行；填真实账号才校验，见 {@link CpeAuthenticator#isOpenAccess}。 */
    @Value("${acs.auth.cpe-to-acs-username:}")
    private String defaultCpeUser;

    @Value("${acs.auth.cpe-to-acs-password:}")
    private String defaultCpePassword;

    @Value("${acs.session.wait-post-null-timeout:60}")
    private long waitPostNullTimeout;

    @Value("${acs.session.wait-rpc-response-timeout:60}")
    private long waitRpcResponseTimeout;

    /**
     * 队列空时把这一跳请求挂起多久再回 204（毫秒）。挂起期间不占 Tomcat 线程，
     * 指令入队时按 SN 唤醒，见 {@link RpcDeliveryWaiter}。
     */
    @Value("${acs.session.piggyback-wait-ms:1500}")
    private long piggybackWaitMs;

    /**
     * 会话 Cookie 的路径。留空则不写 Path 属性，部分固件对带 Path 的 Cookie 认不全。
     */
    @Value("${acs.cwmp.cookies-path:}")
    private String cookiesPath;

    /** 会话号 Cookie 的宽松解析，分隔符认逗号也认分号：部分设备不合规范。 */
    private static final Pattern SESSION_COOKIE = Pattern.compile(
            "\\s*([a-zA-Z0-9\\-_]+?)\\s*=\\s*\"?([a-zA-Z0-9\\-_]*?)\"?\\s*(,|;|$)");

    /** 等下一条指令时的轮询间隔，仅在队列关闭（内存模式）时用得上。 */
    private static final long PIGGYBACK_POLL_INTERVAL_MS = 20L;

    /** 单条指令最多尝试下发几次。只有没收到回应才重试；设备回了 fault 直接判失败。 */
    @Value("${acs.session.max-delivery-attempts:2}")
    private int maxDeliveryAttempts;

    /** 重发前的基本等待（秒），每次按 2 的幂递增，取值参照设备上报周期。 */
    @Value("${acs.session.retry-delay-seconds:60}")
    private long retryDelaySeconds;

    /** 报文顺序不对时是否直接回绝。默认 false，只记日志。 */
    @Value("${acs.session.strict-state-check:false}")
    private boolean strictStateCheck;

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

    /** 处理 CPE 的空 POST：有指令则下发，否则回 204。 */
    private void handleEmptyPost(String connId, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        // 请求来自哪台设备：优先按 Cookie 里的会话号，退而求其次才是 ip:端口
        String sn = resolveSn(connId, request);
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
        deliverOrSuspend(sn, session, request, response);
    }

    /**
     * 捎带下一条指令；队列暂时是空的就把这一跳挂起来等入队信号。
     *
     * <p>队列关闭（内存 outbox）时没有入队信号，退回短轮询。</p>
     */
    private void deliverOrSuspend(String sn, CpeSession session,
                                  HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (deliverNextRpc(sn, session, response)) {
            return;
        }
        if (!queueProperties.isEnabled()) {
            if (!deliverNextRpcWithWait(sn, session, response)) {
                log.debug("无待下发任务, 回复 204: sn={}", sn);
                endSessionRound(sn, session, response);
            }
            return;
        }
        // 挂起等待：指令来得晚就可能要挂 1.5 秒，这段时间不该占着容器线程不放
        suspendForNextRpc(sn, request, response);
    }

    /**
     * 把这一跳请求挂起，等指令入队信号把它叫起来。
     */
    private void suspendForNextRpc(String sn, HttpServletRequest request, HttpServletResponse response) {
        if (request == null || !request.isAsyncSupported()) {
            // 拿不到原生请求（降级处理、内嵌调用）或容器不支持异步，
            // 就按老办法收尾，不值得为等一条指令把链路搞复杂
            log.debug("该请求不支持异步挂起，本轮直接结束: sn={}", sn);
            endSessionRound(sn, sessionManager.find(sn), response);
            return;
        }
        AsyncContext async;
        try {
            async = request.startAsync();
        } catch (Exception e) {
            log.warn("无法挂起等待指令，本轮直接结束: sn={}, err={}", sn, e.getMessage());
            sendStatus(response, 204);
            return;
        }
        async.setTimeout(piggybackWaitMs);
        PendingDelivery delivery = new PendingDelivery(sn, async, response);
        async.addListener(new AsyncListener() {
            @Override
            public void onTimeout(AsyncEvent event) {
                deliveryWaiter.expire(sn);
            }

            @Override
            public void onComplete(AsyncEvent event) {
                deliveryWaiter.cancel(sn, delivery);
            }

            @Override
            public void onError(AsyncEvent event) {
                deliveryWaiter.expire(sn);
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
            }
        });
        deliveryWaiter.register(sn, delivery);
    }

    /**
     * 取一条待下发指令捎带出去，队列暂时空时轮询一小会儿（仅内存模式用）。
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
        // 「取指令 -> 标记在途 -> 写响应」必须整段串行：并发进来会互相覆盖在途那条
        if (!session.getDeliveryLock().tryLock()) {
            log.debug("该设备正在下发中，本次不捎带: sn={}", sn);
            return false;
        }
        try {
            PendingRpc rpc = null;
            if (queueProperties.isEnabled()) {
                RpcTaskQueue.RpcTask task = rpcTaskQueue.poll(sn);
                if (task != null) {
                    if (task.getNotBefore() > System.currentTimeMillis()) {
                        // 退避期未结束：放回队首，本跳不发给它
                        rpcTaskQueue.pushBack(task);
                        log.debug("指令尚在退避期: sn={}, rpc={}, 还需等待={}ms",
                                sn, task.getRpcName(),
                                task.getNotBefore() - System.currentTimeMillis());
                        return false;
                    }
                    Map<String, Object> args = rpcTaskQueue.parseArgs(task.getArgsJson());
                    rpc = new PendingRpc(task.getTaskId(), task.getRpcName(), args, task.getWorklistId());
                    // 批次与重试轮次要带回，超时重排队列时用得上
                    rpc.setBatch(task.getBatch());
                    rpc.setAttempts(task.getAttempts());
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
                // 方法名不合法的残留指令：判失败并丢弃，不让它把这次下发打成 500
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

            log.info("下发 RPC: sn={}, rpc={}, taskId={}, attempts={}",
                    sn, rpc.getRpcName(), rpc.getRpcId(), rpc.getAttempts());
            writeXml(response, soap);
            return true;
        } finally {
            session.getDeliveryLock().unlock();
        }
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
        // TransferComplete 是设备下载完文件后的主动上报，与 Inform 一样由 CPE 发起，
        // 必须给回应，否则设备会认为 ACS 没收到而反复重报
        if ("TransferComplete".equals(methodName)) {
            handleTransferComplete(connId, message, request, response);
            return;
        }

        // 其余均为 CPE 对我方下发的响应
        String sn = resolveSn(connId, request);
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

        // 会话顺序校验：没在等回应却来了响应，说明两边进度已不一致。
        // 默认只告警，回绝只在 strictStateCheck 打开时做
        if (session.getState() != SessionState.WAIT_RPC_RESPONSE) {
            log.warn("会话状态与报文不匹配: sn={}, state={}, 收到={}",
                    sn, session.getState(), methodName);
            if (strictStateCheck) {
                sendStatus(response, 400);
                return;
            }
        }

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
        // 在同一响应里捎带下一条指令：设备收到 204 就认为本轮结束，不一定再发空 POST
        deliverOrSuspend(sn, session, request, response);
    }

    /** 抢占这一轮会话的操作权；被别的实例抢到时回 400，让设备下次再来。 */
    private boolean acquireSession(String sn, String sessionId, String previousSessionId,
                                   HttpServletResponse response) throws IOException {
        if (previousSessionId != null && !previousSessionId.equals(sessionId)) {
            sessionMutex.release(sn, previousSessionId);
        }
        if (sessionMutex.acquire(sn, sessionId)) {
            return true;
        }
        log.warn("设备已在会话中，本次 Inform 不作处理: sn={}", sn);
        sendStatus(response, 400);
        return false;
    }

    /**
     * 本轮会话到此为止：放开会话锁，之后设备的下一次请求落到哪个实例都能接住。
     */
    private void endSessionRound(String sn, CpeSession session, HttpServletResponse response) {
        if (session != null) {
            sessionMutex.release(sn, session.getSessionId());
        }
        sendStatus(response, 204);
    }

    /**
     * 处理 TransferComplete：设备下载完文件后的主动上报。
     *
     * <p>下载结果不记在 Download 指令的账上：那条指令在设备回 DownloadResponse 时就已经结了，
     * 这条上报是几分钟之后才到的独立结果，只用来确认这次下载到底成没成。</p>
     */
    private void handleTransferComplete(String connId, SoapMessage message,
                                        HttpServletRequest request,
                                        HttpServletResponse response) throws IOException {
        String sn = resolveSn(connId, request);
        if (sn == null) {
            log.warn("TransferComplete 无法定位设备, conn={}", connId);
            sendStatus(response, 400);
            return;
        }
        CpeSession session = sessionManager.find(sn);
        if (session == null) {
            sendStatus(response, 400);
            return;
        }
        session.touch();

        String commandKey = message.stringValue("CommandKey");
        String faultCode = nested(message, "FaultCode");
        String faultString = nested(message, "FaultString");
        if (faultCode.isEmpty() || "0".equals(faultCode)) {
            log.info("设备下载完成: sn={}, commandKey={}", sn, commandKey);
        } else {
            log.warn("设备下载失败: sn={}, commandKey={}, fault={} {}",
                    sn, commandKey, faultCode, faultString);
        }

        String soap = SoapEnvelopeBuilder.build(RpcMethod.TRANSFER_COMPLETE_RESPONSE, Map.of(),
                message.getCwmpId(), session.getCwmpVersion());
        writeXml(response, soap);
        session.setState(SessionState.WAIT_POST_NULL);
        armWaitPostNullTimer(session);
    }

    /** FaultStruct 有的设备摊平写、有的套一层 Fault，两种都取一遍。 */
    private static String nested(SoapMessage message, String key) {
        String value = message.stringValue(key);
        if (!value.isEmpty()) {
            return value;
        }
        Object fault = message.getBody().get("Fault");
        if (fault instanceof Map<?, ?> inner) {
            Object hit = inner.get(key);
            return hit == null ? "" : String.valueOf(hit);
        }
        return "";
    }

    /**
     * 处理 Inform：认证、建会话、回复 InformResponse，参数入库与事件调度交给队列消费者。
     *
     * @param cwmpId 设备报文头里的会话 ID，必须原样回显，否则设备判定本次未被接受
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

        // 疑似双会话检测。连接变了属于正常的会话接管，因此只记日志：关掉会话会把
        // 排队中的指令一并作废，是否离线交给 SessionManager 按无通信时长判定
        if (sessionManager.detectDuplicateSession(sn, connId)) {
            log.warn("疑似双会话，按会话接管处理: sn={}, connId={}", sn, connId);
        }

        // 本轮会话先拿到互斥权；本进程上一轮握着的锁要先放开，免得把设备自己挡在门外
        CpeSession previous = sessionManager.find(sn);
        String previousSessionId = previous != null ? previous.getSessionId() : null;

        CpeSession session = sessionManager.getOrCreate(oui, sn, connId);

        if (!acquireSession(sn, session.getSessionId(), previousSessionId, response)) {
            return;
        }

        // 采集指令必须在回响应之前入队，紧接着的空 POST 才取得到
        deviceCapabilityService.onInform(session, inform);
        // 纳管同样要抢这个窗口：把 ACS 地址与上报间隔写回设备，否则设备下轮还报去原厂平台
        informProvisionService.onInform(session, inform);

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

        // 会话号要先于响应体下发，设备后续请求才能凭 Cookie 续接这一轮会话
        String informResponse = SoapEnvelopeBuilder.buildInformResponse(cwmpId, session.getCwmpVersion());
        writeSessionCookie(response, session);
        writeXml(response, informResponse);

        session.setState(SessionState.WAIT_POST_NULL);
        armWaitPostNullTimer(session);

        log.debug("Inform 处理完成, 等待空 POST: sn={}, queued={}", sn, queued);
    }

    // ==================== 超时控制 ====================

    /**
     * 等待 CPE 空 POST 的定时器。超时只结束本轮下发窗口、作废没等到回应的指令，
     * 不判设备离线：部分光猫 Inform 后不发空 POST，是否离线由 SessionManager
     * 按无通信时长判定。
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
                settleUndelivered(session, pending, "等待空 POST 超时，指令未送达");
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
                settleUndelivered(session, rpc, "等待 RPC 响应超时");
            }
        }, waitRpcResponseTimeout, TimeUnit.SECONDS));
    }

    /**
     * 没等到回应的指令怎么收场：还能再试的重排到队列队首，次数用尽则判失败。
     *
     * <p>只重排队列里的指令（taskId &gt; 0）：内存 outbox 的指令已随 failAwaiting 完成给
     * 调用方，再塞回队列两边就对不上了。</p>
     */
    private void settleUndelivered(CpeSession session, PendingRpc rpc, String reason) {
        String sn = session.getSn();
        if (rpc.getRpcId() <= 0) {
            // 队列关闭时指令走内存 outbox，用的是负数序号，没有对应的任务记录
            log.warn("{}，本次不作处理: sn={}, rpc={}", reason, sn, rpc.getRpcName());
            return;
        }
        // 没收到回应时并不知道设备执行了没有：设值与取值重发一次结果一样，
        // 重启 / 下载这类重发就是真干了第二次，只能直接按失败结
        RpcMethod method = RpcMethod.of(rpc.getRpcName());
        if (!queueProperties.isEnabled()
                || rpc.getAttempts() >= maxDeliveryAttempts
                || (method != null && !method.isRetriable())) {
            rpcDispatchService.failTask(rpc.getRpcId(), "8002", reason);
            return;
        }
        // 退避：下一次延迟是这一次的两倍
        long delayMs = retryDelaySeconds * 1000L * (1L << Math.min(rpc.getAttempts(), 8));

        RpcTaskQueue.RpcTask task = new RpcTaskQueue.RpcTask();
        task.setTaskId(rpc.getRpcId());
        task.setSn(sn);
        task.setRpcName(rpc.getRpcName());
        task.setArgsJson(rpcTaskQueue.writeArgs(rpc.getArgs()));
        task.setWorklistId(rpc.getWorklistId());
        task.setBatch(rpc.getBatch());
        task.setAttempts(rpc.getAttempts() + 1);
        task.setNotBefore(System.currentTimeMillis() + delayMs);
        rpcTaskQueue.pushBack(task);
        rpcTaskQueue.saveQueued(task);
        log.warn("{}，已重排队列，{}秒后再试: sn={}, rpc={}, attempts={}/{}",
                reason, delayMs / 1000, sn, rpc.getRpcName(), task.getAttempts(), maxDeliveryAttempts);
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

    // ==================== 挂起的下发 ====================

    /**
     * 挂起来等着的那一跳下发请求。唤醒与超时可能同时到达，用 settled 保证只结算一次。
     */
    private final class PendingDelivery implements RpcDeliveryWaiter.Waiter {

        private final String sn;

        private final AsyncContext async;

        private final HttpServletResponse response;

        private final AtomicBoolean settled = new AtomicBoolean(false);

        PendingDelivery(String sn, AsyncContext async, HttpServletResponse response) {
            this.sn = sn;
            this.async = async;
            this.response = response;
        }

        @Override
        public void onSignal() {
            settle();
        }

        @Override
        public void onTimeout() {
            settle();
        }

        /** 再试着取一次指令：取到就捎带，取不到回 204 结束本轮。 */
        private void settle() {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            boolean delivered = false;
            try {
                CpeSession session = sessionManager.find(sn);
                if (session != null) {
                    delivered = deliverNextRpc(sn, session, response);
                }
                if (!delivered) {
                    endSessionRound(sn, session, response);
                }
            } catch (Exception e) {
                log.warn("唤醒后下发失败: sn={}, err={}", sn, e.getMessage());
            } finally {
                try {
                    async.complete();
                } catch (Exception e) {
                    log.debug("异步响应已结束: sn={}", sn);
                }
            }
        }
    }

    // ==================== 工具 ====================

    private String connectionId(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        return ip + ":" + request.getRemotePort();
    }

    /**
     * 把本轮会话号写进 Set-Cookie 下发给设备。设备后续请求常从另一个源端口过来，
     * 凭它续接会话；不认 Cookie 的设备由 {@link #resolveSn} 按 ip:端口 兜底。
     */
    private void writeSessionCookie(HttpServletResponse response, CpeSession session) {
        String sessionId = session.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String cookie = "session=" + sessionId;
        // Path 有时会被固件认死，配了才加；默认留空
        if (cookiesPath != null && !cookiesPath.isBlank()) {
            cookie += "; Path=" + cookiesPath;
        }
        response.setHeader("Set-Cookie", cookie);
    }

    /** 从请求头里取会话号。分隔符除分号还要认逗号：部分设备不合规范。 */
    private String sessionCookie(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String header = request.getHeader("Cookie");
        if (header == null || header.isBlank()) {
            return null;
        }
        Matcher matcher = SESSION_COOKIE.matcher(header);
        while (matcher.find()) {
            if ("session".equals(matcher.group(1))) {
                String value = matcher.group(2);
                return value == null || value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * 请求来自哪台设备：Cookie 里的会话号优先，退而求其次才是 TCP 连接标识。
     */
    private String resolveSn(String connId, HttpServletRequest request) {
        String sessionId = sessionCookie(request);
        if (sessionId != null) {
            String sn = sessionManager.findSnBySessionId(sessionId);
            if (sn != null) {
                // 会话中途换连接是常态，把会话绑到当前这条连接上，保住后续按连接的定位
                sessionManager.rebindConnection(sn, connId);
                return sn;
            }
            log.debug("Cookie 里的会话号已失效，退回按连接定位: conn={}", connId);
        }
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
