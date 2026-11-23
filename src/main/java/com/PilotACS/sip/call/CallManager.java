/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.call;

import com.PilotACS.common.log.Logs;
import com.PilotACS.sip.SipProperties;
import com.PilotACS.sip.core.Sdp;
import com.PilotACS.sip.core.SipMessage;
import com.PilotACS.sip.h248.GatewayRegistry;
import com.PilotACS.sip.h248.H248Controller;
import com.PilotACS.sip.queue.SipQueue;
import com.PilotACS.sip.queue.SipQueueService;
import com.PilotACS.sip.record.SipCallRecordService;
import com.PilotACS.sip.reg.ExtensionRegistry;
import com.PilotACS.sip.rtp.RtpAgent;
import com.PilotACS.sip.server.SipUdpServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 呼叫管理：建会话、忙线判断、B 腿外呼与应答接线、超时清理、20ms 媒体节拍，含 H.248 双向路由 */
@Component
public class CallManager {

    private final SipProperties props;
    private final SipUdpServer server;
    private final ExtensionRegistry registry;
    private final GatewayRegistry h248Gateways;
    private final SipQueueService queueService;
    private final SipCallRecordService recordService;

    private final Map<String, CallEntry> byCallA = new ConcurrentHashMap<>();
    private final Map<String, CallEntry> byCallB = new ConcurrentHashMap<>();
    /** MG 主叫 → SIP 被叫：UA 腿 Call-ID → 会话 */
    private final Map<String, CallEntry> byCallUa = new ConcurrentHashMap<>();
    /** H.248 控制器惰性注入（避免与 H248Server 构造环） */
    @Autowired
    private ObjectProvider<H248Controller> h248;
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService mediaTimer;

    public CallManager(SipProperties props, SipUdpServer server, ExtensionRegistry registry,
                       GatewayRegistry h248Gateways, SipQueueService queueService,
                       SipCallRecordService recordService) {
        this.props = props;
        this.server = server;
        this.registry = registry;
        this.h248Gateways = h248Gateways;
        this.queueService = queueService;
        this.recordService = recordService;
    }

    private H248Controller h248c() {
        return h248.getIfAvailable();
    }

    @PostConstruct
    public void start() {
        if (!props.isEnabled()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        mediaTimer = Executors.newSingleThreadScheduledExecutor();
        mediaTimer.scheduleAtFixedRate(this::tickMedia, 20, 20, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::housekeep, props.getRegistrationSweepSeconds(),
                props.getRegistrationSweepSeconds(), TimeUnit.SECONDS);
        // 会话仅存于内存：断电/异常退出重启后自动清空，不会遗留僵尸会话；运行中客户端异常退出由 RTP 空闲超时兜底
        Logs.info(Logs.Module.SIP, "会话状态初始化", "活跃会话", byCallA.size(),
                "RTP空闲拆线", props.getRtpIdleSeconds() + "s");
        if (props.isH248Enabled()) {
            H248Controller h = h248.getIfAvailable();
            if (h != null) {
                h.init();
            }
        }
    }

    @PreDestroy
    public void stop() {
        H248Controller h = h248.getIfAvailable();
        // 优雅停机：先向两侧终端发 BYE 清理全部会话，再停线程（须在 SipUdpServer 关闭前执行）
        for (CallEntry e : new ArrayList<>(byCallA.values())) {
            try {
                if (e.session.getState() == CallSession.State.ACTIVE) {
                    endActive(e, h, "服务停止");
                } else {
                    teardown(e, "服务停止");
                }
            } catch (Exception ex) {
                Logs.error(Logs.Module.SIP, "停机清理会话失败", "主叫", e.session.getCallerExt(),
                        "错误", ex.toString());
            }
        }
        Logs.info(Logs.Module.SIP, "停机清理完成", "残留会话", byCallA.size());
        if (mediaTimer != null) {
            mediaTimer.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (h != null) {
            h.shutdown();
        }
    }

    /** 分机是否正在通话（振铃/接通都算） */
    public boolean isBusy(String ext) {
        for (CallEntry e : byCallA.values()) {
            if (e.session.getState() != CallSession.State.ENDED
                    && (e.session.getCallerExt().equals(ext) || e.session.getCalleeExt().equals(ext))) {
                return true;
            }
        }
        return false;
    }

    /** 活跃会话列表（管理端查询用） */
    public List<CallSession> activeSessions() {
        List<CallSession> out = new ArrayList<>();
        for (CallEntry e : byCallA.values()) {
            if (e.session.getState() != CallSession.State.ENDED) {
                out.add(e.session);
            }
        }
        return out;
    }

    // ------------------------------------------------ 信令入口（SipController 调）

    /** A 腿 INVITE 进来：校验、建腿、回 183/180、外呼 B（SIP 分机 / H.248 终结点 / 呼叫队列） */
    public void onInvite(SipMessage invite, InetSocketAddress remote) {
        String callerExt = SipMessage.userOf(SipMessage.uriOf(invite.header("From")));
        String calleeExt = SipMessage.userOf(invite.getUri());
        boolean mgCallee = props.getH248Terminals().containsKey(calleeExt);
        List<SipQueue> queues = mgCallee ? List.of() : queueService.matchQueues(callerExt, calleeExt);
        if (!mgCallee && queues.isEmpty() && !registry.isConfigured(calleeExt)) {
            respondToA(invite, remote, null, 404, "Not Found", null);
            return;
        }
        if (!registry.isConfigured(callerExt) || registry.findAlive(callerExt) == null) {
            respondToA(invite, remote, null, 403, "Forbidden", null);
            return;
        }
        GatewayRegistry.Gateway mg = null;
        String calleeTermId = null;
        if (mgCallee) {
            calleeTermId = props.getH248Terminals().get(calleeExt);
            mg = resolveGateway(calleeTermId);
            if (mg == null) {
                respondToA(invite, remote, null, 480, "Temporarily Unavailable", null);
                return;
            }
        } else if (registry.findAlive(calleeExt) == null) {
            respondToA(invite, remote, null, 480, "Temporarily Unavailable", null);
            return;
        }
        if (isBusy(callerExt) || isBusy(calleeExt)) {
            respondToA(invite, remote, null, 486, "Busy Here", null);
            Logs.info(Logs.Module.SIP, "忙线拒绝", "主叫", callerExt, "被叫", calleeExt);
            return;
        }
        Sdp sdpA = Sdp.parse(invite.getBody());
        Sdp.Media mediaA = sdpA.getAudio();
        if (mediaA == null || (mediaA.ip == null && remote.getAddress() == null)) {
            respondToA(invite, remote, null, 488, "Not Acceptable Here", null);
            return;
        }
        // 选编解码：优先 PCMA(8)，否则 PCMU(0)
        boolean alaw = mediaA.payloadTypes.contains(8) || mediaA.codecs.containsValue("PCMA");
        RtpAgent agentA = createAgent(alaw, callerExt + ">" + calleeExt + "-a");
        RtpAgent agentB = createAgent(true, callerExt + ">" + calleeExt + "-b");
        if (agentA == null || agentB == null) {
            respondToA(invite, remote, null, 500, "Server Internal Error", null);
            return;
        }
        agentA.start();
        agentB.start();
        // A 腿媒体目标：优先 SDP 地址，缺 IP 时贴住信令源
        java.net.InetAddress sdpIp = mediaA.ip != null ? null : remote.getAddress();
        if (mediaA.ip != null) {
            try {
                sdpIp = java.net.InetAddress.getByName(mediaA.ip);
            } catch (Exception ignored) {
                sdpIp = remote.getAddress();
            }
        }
        agentA.setTarget(new InetSocketAddress(sdpIp, mediaA.port));

        CallEntry entry = new CallEntry(new CallSession(invite.callId(), callerExt, calleeExt,
                agentA, alaw, new RingbackSource(props.getRingbackWav()), props.isRecordEnabled()));
        entry.inviteA = invite;
        entry.aAddr = remote;
        entry.aTag = invite.fromTag();
        entry.aServerTag = randTag();
        entry.bServerTag = randTag();
        entry.session.setCallIdB("ACS-" + UUID.randomUUID());
        entry.session.setAgentB(agentB);
        byCallA.put(entry.session.getCallIdA(), entry);
        byCallB.put(entry.session.getCallIdB(), entry);

        // 183 带本端 SDP（早媒体回铃）+ 180 振铃
        respondToA(invite, remote, entry.aServerTag, 183, "Session Progress",
                Sdp.build(server.publicIp(), agentA.getLocalPort(), alaw ? 8 : 0, alaw ? "PCMA" : "PCMU"));
        respondToA(invite, remote, entry.aServerTag, 180, "Ringing", null);

        if (!queues.isEmpty()) {
            // 队列呼叫：按成员匹配优先的队列顺序找第一个空闲目标，忙音顺延（8888 忙 → 9999）
            String target = pickQueueTarget(queues);
            if (target == null) {
                respondToA(invite, remote, entry.aServerTag, 486, "Busy Here", null);
                teardown(entry, "队列 " + calleeExt + " 全忙");
                return;
            }
            entry.session.setCalleeExt(target);
            Logs.info(Logs.Module.SIP, "队列呼叫", "主叫", callerExt, "队列", calleeExt, "接通目标", target);
            calleeExt = target;
        }

        if (mgCallee) {
            // 被叫是 H.248 终结点：Add 进上下文（G.711A），网关侧振铃，摘机后接通
            entry.mgCallee = true;
            entry.mgTermCallee = stripGatewayOf(calleeTermId);
            entry.mgAddr = mg.address;
            H248Controller h = h248c();
            if (h != null) {
                h.addForIncomingCall(entry.session.getCallIdB(), entry.mgTermCallee, mg.address,
                        agentB.getLocalPort(), server.publicIp());
            }
            Logs.info(Logs.Module.SIP, "新呼叫（SIP→H.248）", "主叫", callerExt, "被叫", calleeExt,
                    "终结点", entry.mgTermCallee, "B腿口", agentB.getLocalPort());
            return;
        }
        // 外呼 B（SIP 分机）
        SipMessage inviteB = SipMessage.request("INVITE", "sip:" + calleeExt + "@" + server.publicIp());
        inviteB.addHeader("Via", "SIP/2.0/UDP " + server.publicIp() + ":" + server.port());
        inviteB.addHeader("From", "<sip:" + callerExt + "@" + server.publicIp() + ">;tag=" + entry.bServerTag);
        inviteB.addHeader("To", "<sip:" + calleeExt + "@" + server.publicIp() + ">");
        inviteB.setHeader("Call-ID", entry.session.getCallIdB());
        inviteB.setHeader("CSeq", "101 INVITE");
        inviteB.setHeader("Contact", "<sip:acs@" + server.publicIp() + ":" + server.port() + ">");
        inviteB.setHeader("Max-Forwards", "70");
        inviteB.setHeader("User-Agent", "PilotACS-SIP");
        inviteB.setBody(Sdp.build(server.publicIp(), agentB.getLocalPort(), alaw ? 8 : 0, alaw ? "PCMA" : "PCMU"));
        InetSocketAddress bAddr = registry.findAlive(calleeExt).getAddress();
        entry.bAddr = bAddr;
        server.send(inviteB, bAddr);
        Logs.info(Logs.Module.SIP, "新呼叫", "主叫", callerExt, "被叫", calleeExt,
                "主叫端口", agentA.getLocalPort(), "被叫端口", agentB.getLocalPort());
    }

    /** 响应进来（B 腿 180/200/4xx、MG 主叫呼叫的 UA 腿响应、BYE/CANCEL 的 200 等） */
    public void onResponse(SipMessage resp, InetSocketAddress remote) {
        CallEntry uaEntry = byCallUa.get(resp.callId());
        if (uaEntry != null) {
            handleUaResponse(uaEntry, resp, remote);
            return;
        }
        CallEntry entry = byCallB.get(resp.callId());
        if (entry == null) {
            return;
        }
        int code = resp.getStatusCode();
        if (code == 200 && "INVITE".equals(cseqMethod(resp))) {
            if (entry.session.getState() != CallSession.State.RINGING) {
                return;
            }
            Sdp sdpB = Sdp.parse(resp.getBody());
            Sdp.Media mediaB = sdpB.getAudio();
            if (mediaB != null) {
                try {
                    java.net.InetAddress ip = java.net.InetAddress.getByName(mediaB.ip);
                    entry.session.getAgentB().setTarget(new InetSocketAddress(ip, mediaB.port));
                } catch (Exception e) {
                    Logs.error(Logs.Module.SIP, "被叫 SDP 地址非法", "错误", e.toString());
                }
            }
            // ACK B 腿
            SipMessage ack = SipMessage.request("ACK", "sip:" + entry.session.getCalleeExt() + "@" + server.publicIp());
            ack.addHeader("Via", "SIP/2.0/UDP " + server.publicIp() + ":" + server.port());
            ack.addHeader("From", "<sip:" + entry.session.getCallerExt() + "@" + server.publicIp() + ">;tag=" + entry.bServerTag);
            ack.addHeader("To", resp.header("To"));
            ack.setHeader("Call-ID", resp.callId());
            ack.setHeader("CSeq", "101 ACK");
            ack.setHeader("Max-Forwards", "70");
            server.send(ack, entry.bAddr);
            entry.session.answer();
            answerSipLeg(entry);
            return;
        }
        if (code >= 400 && entry.session.getState() == CallSession.State.RINGING) {
            // B 侧拒绝/无应答 → 原样回给 A
            respondToA(entry.inviteA, entry.aAddr, entry.aServerTag, code, reasonOf(code, resp), null);
            teardown(entry, "被叫返回 " + code);
        }
    }

    /** 200 OK 给 SIP 主叫（A 腿），带 A 腿 SDP */
    private void answerSipLeg(CallEntry entry) {
        if (entry.inviteA == null) {
            return;
        }
        respondToA(entry.inviteA, entry.aAddr, entry.aServerTag, 200, "OK",
                Sdp.build(server.publicIp(), entry.session.getAgentA().getLocalPort(),
                        entry.session.isAlawA() ? 8 : 0, entry.session.isAlawA() ? "PCMA" : "PCMU"));
    }

    /** MG 主叫呼叫的 UA 腿响应（180/200/4xx） */
    private void handleUaResponse(CallEntry entry, SipMessage resp, InetSocketAddress remote) {
        int code = resp.getStatusCode();
        H248Controller h = h248c();
        if (code == 200 && "INVITE".equals(cseqMethod(resp))) {
            if (entry.session.getState() != CallSession.State.RINGING) {
                return;
            }
            Sdp sdp = Sdp.parse(resp.getBody());
            Sdp.Media media = sdp.getAudio();
            if (media != null) {
                try {
                    entry.session.getAgentB().setTarget(new InetSocketAddress(
                            java.net.InetAddress.getByName(media.ip), media.port));
                } catch (Exception e) {
                    Logs.error(Logs.Module.SIP, "UA SDP 地址非法", "错误", e.toString());
                }
            }
            entry.uaToHeader = resp.header("To");
            // ACK UA
            SipMessage ack = SipMessage.request("ACK", "sip:" + entry.session.getCalleeExt() + "@" + server.publicIp());
            ack.addHeader("Via", "SIP/2.0/UDP " + server.publicIp() + ":" + server.port());
            ack.addHeader("From", "<sip:" + entry.session.getCallerExt() + "@" + server.publicIp() + ">;tag=" + entry.uaFromTag);
            ack.addHeader("To", entry.uaToHeader);
            ack.setHeader("Call-ID", entry.uaCallId);
            ack.setHeader("CSeq", "1 ACK");
            ack.setHeader("Max-Forwards", "70");
            server.send(ack, entry.uaAddr);
            entry.session.answer();
            // MG 主叫远端指向本端 A 腿 RTP（停拨号音/回铃音）
            if (h != null && entry.mgTermCaller != null) {
                h.modifyRemote(entry.mgTermCaller, entry.mgAddrCaller, entry.mgCtxCaller,
                        server.publicIp(), entry.session.getAgentA().getLocalPort());
            }
            return;
        }
        if ((code == 180 || code == 183) && entry.mgTermCaller != null && h != null) {
            // UA 自己振铃，给 MG 主叫回铃音信号
            h.sendRingbackSignal(entry.mgTermCaller, entry.mgAddrCaller, entry.mgCtxCaller);
            return;
        }
        if (code >= 400 && entry.session.getState() == CallSession.State.RINGING) {
            if (h != null && entry.mgTermCaller != null) {
                h.sendBusySignal(entry.mgTermCaller, entry.mgAddrCaller, entry.mgCtxCaller);
            }
            teardown(entry, "被叫返回 " + code);
        }
    }

    /** ACK 请求（A 确认接通，媒体已启动，仅记录） */
    public void onAck(SipMessage ack, InetSocketAddress remote) {
        CallEntry entry = byCallA.get(ack.callId());
        if (entry != null) {
            Logs.debug(Logs.Module.SIP, "收到 ACK", "主叫", entry.session.getCallerExt());
        }
    }

    /** BYE：挂断两侧 */
    public void onBye(SipMessage bye, InetSocketAddress remote) {
        CallEntry entry = byCallA.get(bye.callId());
        boolean fromA = entry != null;
        if (entry == null) {
            entry = byCallUa.get(bye.callId());
            if (entry != null) {
                // MG 主叫呼叫里的 UA 被叫挂断
                respondToA(bye, remote, null, 200, "OK", null);
                H248Controller h = h248c();
                if (h != null && entry.mgTermCaller != null) {
                    h.subtract(entry.mgTermCaller, entry.mgAddrCaller, entry.mgCtxCaller);
                }
                teardown(entry, "被叫挂断");
                return;
            }
        }
        if (entry == null) {
            entry = byCallB.get(bye.callId());
        }
        if (entry == null) {
            server.send(notFound(bye), remote);
            return;
        }
        if (fromA) {
            respondToA(bye, remote, entry.aServerTag, 200, "OK", null);
            if (entry.mgCallee) {
                H248Controller h = h248c();
                if (h != null && entry.mgTermCallee != null) {
                    h.subtract(entry.mgTermCallee, entry.mgAddr, entry.mgContext);
                }
            } else {
                sendByeToB(entry);
            }
        } else {
            // 被叫挂断：BYE 必须发往主叫 A 的地址并携带 A 腿 Call-ID（原先误发 remote=B 的地址）
            sendByeToA(entry, null, null, entry.session.getCallIdA(), entry.aAddr);
        }
        teardown(entry, fromA ? "主叫挂断" : "被叫挂断");
    }

    /** CANCEL：振铃期取消 */
    public void onCancel(SipMessage cancel, InetSocketAddress remote) {
        CallEntry entry = byCallA.get(cancel.callId());
        if (entry == null || entry.session.getState() != CallSession.State.RINGING) {
            return;
        }
        respondToA(cancel, remote, entry.aServerTag, 200, "OK", null);
        respondToA(entry.inviteA, entry.aAddr, entry.aServerTag, 487, "Request Terminated", null);
        SipMessage cancelB = SipMessage.request("CANCEL", "sip:" + entry.session.getCalleeExt() + "@" + server.publicIp());
        cancelB.addHeader("Via", "SIP/2.0/UDP " + server.publicIp() + ":" + server.port());
        cancelB.addHeader("From", "<sip:" + entry.session.getCallerExt() + "@" + server.publicIp() + ">;tag=" + entry.bServerTag);
        cancelB.addHeader("To", "<sip:" + entry.session.getCalleeExt() + "@" + server.publicIp() + ">");
        cancelB.setHeader("Call-ID", entry.session.getCallIdB());
        cancelB.setHeader("CSeq", "101 CANCEL");
        server.send(cancelB, entry.bAddr);
        teardown(entry, "主叫取消");
    }

    // ------------------------------------------------ H.248 呼叫（H248Controller 调）

    /** SIP→MG：网关侧媒体就绪（Add 回复带 Local SDP），接到 B 腿 RTP */
    public void onMgLocalSdp(String callIdB, String ip, int port, InetSocketAddress mgAddr, String contextId) {
        CallEntry e = byCallB.get(callIdB);
        if (e == null) {
            return;
        }
        try {
            e.session.getAgentB().setTarget(new InetSocketAddress(java.net.InetAddress.getByName(ip), port));
        } catch (Exception ex) {
            Logs.error(Logs.Module.SIP, "MG SDP 地址非法", "错误", ex.toString());
            return;
        }
        e.mgAddr = mgAddr;
        e.mgContext = contextId;
    }

    /** 摘机事件：SIP→MG / MG→MG 的被叫摘机 = 接通 */
    public void onMgOffhookByTerm(String termId) {
        H248Controller h = h248c();
        if (h == null) {
            return;
        }
        for (CallEntry e : byCallA.values()) {
            if (!termId.equals(e.mgTermCallee) || e.session.getState() != CallSession.State.RINGING) {
                continue;
            }
            e.session.answer();
            h.stopSignal(termId, e.mgAddr, e.mgContext);
            answerSipLeg(e);
            if (e.mgTermCaller != null) {
                // MG→MG：主叫侧远端指向本端 A 腿 RTP
                h.modifyRemote(e.mgTermCaller, e.mgAddrCaller, e.mgCtxCaller,
                        server.publicIp(), e.session.getAgentA().getLocalPort());
            }
            Logs.info(Logs.Module.SIP, "H.248 被叫摘机接通", "主叫", e.session.getCallerExt(), "被叫", e.session.getCalleeExt());
            return;
        }
    }

    /** 挂机事件：拨号取消、拒接或挂断 */
    public void onMgOnhookByTerm(String termId) {
        H248Controller h = h248c();
        if (h == null) {
            return;
        }
        for (CallEntry e : new ArrayList<>(byCallA.values())) {
            boolean isCallee = termId.equals(e.mgTermCallee);
            boolean isCaller = termId.equals(e.mgTermCaller);
            if (!isCallee && !isCaller) {
                continue;
            }
            if (e.session.getState() == CallSession.State.RINGING) {
                if (e.inviteA != null) {
                    respondToA(e.inviteA, e.aAddr, e.aServerTag, 487, "Request Terminated", null);
                }
                if (e.uaCallId != null) {
                    sendCancelToUa(e);
                }
                if (isCallee) {
                    h.subtract(termId, e.mgAddr, e.mgContext);
                }
                if (e.mgTermCaller != null) {
                    h.sendBusySignal(e.mgTermCaller, e.mgAddrCaller, e.mgCtxCaller);
                }
                teardown(e, "被叫挂机");
                return;
            }
            if (e.session.getState() == CallSession.State.ACTIVE) {
                if (isCaller) {
                    if (e.uaCallId != null) {
                        sendByeToUa(e);
                    }
                    if (e.mgTermCallee != null) {
                        h.subtract(e.mgTermCallee, e.mgAddr, e.mgContext);
                    }
                    h.subtract(e.mgTermCaller, e.mgAddrCaller, e.mgCtxCaller);
                } else {
                    if (e.inviteA != null) {
                        sendByeToA(e, null, null, e.session.getCallIdA(), e.aAddr);
                    }
                    if (e.uaCallId != null) {
                        sendByeToUa(e);
                    }
                    h.subtract(e.mgTermCallee, e.mgAddr, e.mgContext);
                }
                teardown(e, "挂机");
                return;
            }
        }
    }

    /** MG 主叫拨号完成：按号码路由（SIP 分机或 H.248 终结点） */
    public boolean callFromMg(String number, H248Controller.Dialing dial) {
        String callerNumber = numberForTerm(dial.termId);
        if (isBusy(number) || (callerNumber != null && isBusy(callerNumber))) {
            return false;
        }
        H248Controller h = h248c();
        if (h == null) {
            return false;
        }
        if (registry.isConfigured(number) && registry.findAlive(number) != null) {
            return startMgToSip(callerNumber, number, dial, h);
        }
        if (props.getH248Terminals().containsKey(number)) {
            return startMgToMg(callerNumber, number, dial, h);
        }
        Logs.info(Logs.Module.SIP, "H.248 呼叫号码不存在", "号码", number);
        return false;
    }

    /** MG 主叫 → SIP 分机 */
    private boolean startMgToSip(String callerNumber, String number, H248Controller.Dialing dial, H248Controller h) {
        RtpAgent agentA = createAgent(true, callerNumber + ">" + number + "-a");
        RtpAgent agentB = createAgent(true, callerNumber + ">" + number + "-b");
        if (agentA == null || agentB == null) {
            return false;
        }
        agentA.start();
        agentB.start();
        try {
            agentA.setTarget(new InetSocketAddress(java.net.InetAddress.getByName(dial.mgLocalIp), dial.mgLocalPort));
        } catch (Exception e) {
            return false;
        }
        CallEntry entry = new CallEntry(new CallSession("UA-" + UUID.randomUUID(), callerNumber, number,
                agentA, true, new RingbackSource(props.getRingbackWav()), props.isRecordEnabled(), false));
        entry.session.setAgentB(agentB);
        entry.session.setCallIdB("MG-" + UUID.randomUUID());
        entry.uaCallId = "UA-" + UUID.randomUUID();
        entry.uaAddr = registry.findAlive(number).getAddress();
        entry.uaFromTag = randTag();
        entry.mgTermCaller = dial.termId;
        entry.mgAddrCaller = dial.mgAddr;
        entry.mgCtxCaller = dial.contextId;
        byCallA.put(entry.session.getCallIdA(), entry);
        byCallB.put(entry.session.getCallIdB(), entry);
        byCallUa.put(entry.uaCallId, entry);

        SipMessage invite = SipMessage.request("INVITE", "sip:" + number + "@" + server.publicIp());
        invite.addHeader("Via", "SIP/2.0/UDP " + server.publicIp() + ":" + server.port());
        invite.addHeader("From", "<sip:" + callerNumber + "@" + server.publicIp() + ">;tag=" + entry.uaFromTag);
        invite.addHeader("To", "<sip:" + number + "@" + server.publicIp() + ">");
        invite.setHeader("Call-ID", entry.uaCallId);
        invite.setHeader("CSeq", "1 INVITE");
        invite.setHeader("Contact", "<sip:acs@" + server.publicIp() + ":" + server.port() + ">");
        invite.setHeader("Max-Forwards", "70");
        invite.setHeader("User-Agent", "PilotACS-SIP");
        invite.setBody(Sdp.build(server.publicIp(), agentB.getLocalPort(), 8, "PCMA"));
        server.send(invite, entry.uaAddr);
        h.sendRingbackSignal(dial.termId, dial.mgAddr, dial.contextId);
        Logs.info(Logs.Module.SIP, "新呼叫（H.248→SIP）", "主叫", callerNumber, "被叫", number,
                "MG腿口", agentA.getLocalPort(), "UA腿口", agentB.getLocalPort());
        return true;
    }

    /** MG 主叫 → H.248 终结点 */
    private boolean startMgToMg(String callerNumber, String number, H248Controller.Dialing dial, H248Controller h) {
        String termCfg = props.getH248Terminals().get(number);
        GatewayRegistry.Gateway mg = resolveGateway(termCfg);
        if (mg == null) {
            Logs.info(Logs.Module.SIP, "H.248 被叫网关未注册", "号码", number);
            return false;
        }
        RtpAgent agentA = createAgent(true, callerNumber + ">" + number + "-a");
        RtpAgent agentB = createAgent(true, callerNumber + ">" + number + "-b");
        if (agentA == null || agentB == null) {
            return false;
        }
        agentA.start();
        agentB.start();
        try {
            agentA.setTarget(new InetSocketAddress(java.net.InetAddress.getByName(dial.mgLocalIp), dial.mgLocalPort));
        } catch (Exception e) {
            return false;
        }
        CallEntry entry = new CallEntry(new CallSession("MG-" + UUID.randomUUID(), callerNumber, number,
                agentA, true, new RingbackSource(props.getRingbackWav()), props.isRecordEnabled(), false));
        entry.session.setAgentB(agentB);
        entry.session.setCallIdB("MGC-" + UUID.randomUUID());
        entry.mgTermCaller = dial.termId;
        entry.mgAddrCaller = dial.mgAddr;
        entry.mgCtxCaller = dial.contextId;
        entry.mgCallee = true;
        entry.mgTermCallee = stripGatewayOf(termCfg);
        entry.mgAddr = mg.address;
        byCallA.put(entry.session.getCallIdA(), entry);
        byCallB.put(entry.session.getCallIdB(), entry);
        h.addForIncomingCall(entry.session.getCallIdB(), entry.mgTermCallee, mg.address,
                agentB.getLocalPort(), server.publicIp());
        h.sendRingbackSignal(dial.termId, dial.mgAddr, dial.contextId);
        Logs.info(Logs.Module.SIP, "新呼叫（H.248→H.248）", "主叫", callerNumber, "被叫", number,
                "主叫腿口", agentA.getLocalPort(), "被叫腿口", agentB.getLocalPort());
        return true;
    }

    /** 队列目标择路：按队列顺序 × 目标顺序，取第一个已注册且不忙的分机 */
    private String pickQueueTarget(List<SipQueue> queues) {
        for (SipQueue q : queues) {
            for (String t : q.getTargets().split(",")) {
                String target = t.trim();
                if (target.isEmpty() || !registry.isConfigured(target) || registry.findAlive(target) == null) {
                    continue;
                }
                if (isBusy(target)) {
                    continue;
                }
                return target;
            }
        }
        return null;
    }

    /** 终结点配置 "AALN/1@1.2.3.4" → 终结点 ID 与网关 */
    private static String stripGatewayOf(String termCfg) {
        int at = termCfg.indexOf('@');
        return at >= 0 ? termCfg.substring(0, at) : termCfg;
    }

    private GatewayRegistry.Gateway resolveGateway(String termCfg) {
        if (termCfg == null) {
            return null;
        }
        int at = termCfg.indexOf('@');
        if (at >= 0) {
            return h248Gateways.findByIp(termCfg.substring(at + 1).trim());
        }
        return h248Gateways.firstAlive();
    }

    /** 终结点 ID 反查分机号（取"主叫"号码展示用） */
    private String numberForTerm(String termId) {
        for (Map.Entry<String, String> e : props.getH248Terminals().entrySet()) {
            if (stripGatewayOf(e.getValue()).equals(termId)) {
                return e.getKey();
            }
        }
        return termId;
    }

    // ------------------------------------------------ 内部

    private void tickMedia() {
        for (CallEntry e : byCallA.values()) {
            try {
                e.session.tick();
            } catch (Exception ex) {
                Logs.error(Logs.Module.SIP, "媒体节拍异常", "错误", ex.toString());
            }
        }
    }

    private void housekeep() {
        registry.sweepExpired();
        H248Controller h = h248c();
        long now = System.currentTimeMillis();
        for (CallEntry e : new ArrayList<>(byCallA.values())) {
            CallSession s = e.session;
            if (s.getState() == CallSession.State.RINGING
                    && now - s.getCreatedAt() > props.getRingTimeoutSeconds() * 1000L) {
                if (e.inviteA != null) {
                    respondToA(e.inviteA, e.aAddr, e.aServerTag, 487, "Request Terminated", null);
                }
                if (e.uaCallId != null) {
                    sendCancelToUa(e);
                }
                if (h != null && e.mgTermCallee != null && e.mgAddr != null) {
                    h.subtract(e.mgTermCallee, e.mgAddr, e.mgContext);
                }
                if (h != null && e.mgTermCaller != null) {
                    h.sendBusySignal(e.mgTermCaller, e.mgAddrCaller, e.mgCtxCaller);
                }
                if (e.inviteA != null && !e.mgCallee) {
                    sendCancelToB(e);
                }
                teardown(e, "振铃超时");
            } else if (s.getState() == CallSession.State.ACTIVE && props.getMaxCallSeconds() > 0
                    && now - s.getCreatedAt() > props.getMaxCallSeconds() * 1000L) {
                endActive(e, h, "通话超时");
            } else if (s.getState() == CallSession.State.ACTIVE && props.getRtpIdleSeconds() > 0
                    && now - rtpIdleBaseline(e) > props.getRtpIdleSeconds() * 1000L) {
                // 客户端异常退出（断电/断网/被杀）不发 BYE → RTP 中断，超时即拆线，防僵尸会话
                endActive(e, h, "RTP 超时");
            }
        }
    }

    /** 通话双方最近一次 RTP 时间（取较新者；B 腿未挂时只看 A） */
    private long rtpIdleBaseline(CallEntry e) {
        long a = e.session.getAgentA() != null ? e.session.getAgentA().getLastRxAt() : 0L;
        Long b = e.session.getAgentB() != null ? e.session.getAgentB().getLastRxAt() : null;
        return Math.max(a, b == null ? a : b);
    }

    /** 结束一路 ACTIVE 通话：向两侧发 BYE / 释放 H.248 终结点 / 拆会话并落通话记录 */
    private void endActive(CallEntry e, H248Controller h, String reason) {
        CallSession s = e.session;
        if (e.inviteA != null) {
            sendByeToA(e, null, null, s.getCallIdA(), e.aAddr);
        }
        if (e.uaCallId != null) {
            sendByeToUa(e);
        }
        if (h != null && e.mgTermCallee != null && e.mgAddr != null) {
            h.subtract(e.mgTermCallee, e.mgAddr, e.mgContext);
        }
        if (h != null && e.mgTermCaller != null) {
            h.subtract(e.mgTermCaller, e.mgAddrCaller, e.mgCtxCaller);
        }
        if (e.inviteA != null && !e.mgCallee) {
            sendByeToB(e);
        }
        teardown(e, reason);
        Logs.info(Logs.Module.SIP, "会话强制结束", "主叫", s.getCallerExt(),
                "被叫", s.getCalleeExt(), "原因", reason);
    }

    private void sendByeToB(CallEntry entry) {
        SipMessage bye = SipMessage.request("BYE", "sip:" + entry.session.getCalleeExt() + "@" + server.publicIp());
        bye.addHeader("Via", "SIP/2.0/UDP " + server.publicIp() + ":" + server.port());
        bye.addHeader("From", "<sip:" + entry.session.getCallerExt() + "@" + server.publicIp() + ">;tag=" + entry.bServerTag);
        bye.addHeader("To", "<sip:" + entry.session.getCalleeExt() + "@" + server.publicIp() + ">");
        bye.setHeader("Call-ID", entry.session.getCallIdB());
        bye.setHeader("CSeq", "102 BYE");
        server.send(bye, entry.bAddr);
    }

    private void sendByeToA(CallEntry entry, String toHeader, String fromHeader, String callId, InetSocketAddress remote) {
        SipMessage bye = SipMessage.request("BYE", "sip:" + entry.session.getCallerExt() + "@" + server.publicIp());
        bye.addHeader("Via", "SIP/2.0/UDP " + server.publicIp() + ":" + server.port());
        if (fromHeader != null) {
            bye.addHeader("From", fromHeader);
        } else {
            bye.addHeader("From", "<sip:" + entry.session.getCalleeExt() + "@" + server.publicIp() + ">;tag=" + entry.aServerTag);
        }
        if (toHeader != null) {
            bye.addHeader("To", toHeader);
        } else {
            bye.addHeader("To", "<sip:" + entry.session.getCallerExt() + "@" + server.publicIp() + ">");
        }
        bye.setHeader("Call-ID", callId);
        bye.setHeader("CSeq", "102 BYE");
        bye.setHeader("Contact", "<sip:acs@" + server.publicIp() + ":" + server.port() + ">");
        server.send(bye, remote != null ? remote : entry.aAddr);
    }

    private void sendCancelToB(CallEntry entry) {
        SipMessage cancel = SipMessage.request("CANCEL", "sip:" + entry.session.getCalleeExt() + "@" + server.publicIp());
        cancel.addHeader("Via", "SIP/2.0/UDP " + server.publicIp() + ":" + server.port());
        cancel.addHeader("From", "<sip:" + entry.session.getCallerExt() + "@" + server.publicIp() + ">;tag=" + entry.bServerTag);
        cancel.addHeader("To", "<sip:" + entry.session.getCalleeExt() + "@" + server.publicIp() + ">");
        cancel.setHeader("Call-ID", entry.session.getCallIdB());
        cancel.setHeader("CSeq", "101 CANCEL");
        server.send(cancel, entry.bAddr);
    }

    /** CANCEL 给 UA 被叫（MG 主叫振铃超时） */
    private void sendCancelToUa(CallEntry entry) {
        SipMessage cancel = SipMessage.request("CANCEL", "sip:" + entry.session.getCalleeExt() + "@" + server.publicIp());
        cancel.addHeader("Via", "SIP/2.0/UDP " + server.publicIp() + ":" + server.port());
        cancel.addHeader("From", "<sip:" + entry.session.getCallerExt() + "@" + server.publicIp() + ">;tag=" + entry.uaFromTag);
        cancel.addHeader("To", entry.uaToHeader != null ? entry.uaToHeader
                : "<sip:" + entry.session.getCalleeExt() + "@" + server.publicIp() + ">");
        cancel.setHeader("Call-ID", entry.uaCallId);
        cancel.setHeader("CSeq", "1 CANCEL");
        server.send(cancel, entry.uaAddr);
    }

    /** BYE 给 UA 被叫（MG 主叫挂断） */
    private void sendByeToUa(CallEntry entry) {
        SipMessage bye = SipMessage.request("BYE", "sip:" + entry.session.getCalleeExt() + "@" + server.publicIp());
        bye.addHeader("Via", "SIP/2.0/UDP " + server.publicIp() + ":" + server.port());
        bye.addHeader("From", "<sip:" + entry.session.getCallerExt() + "@" + server.publicIp() + ">;tag=" + entry.uaFromTag);
        bye.addHeader("To", entry.uaToHeader != null ? entry.uaToHeader
                : "<sip:" + entry.session.getCalleeExt() + "@" + server.publicIp() + ">");
        bye.setHeader("Call-ID", entry.uaCallId);
        bye.setHeader("CSeq", (entry.uaCseq + 1) + " BYE");
        bye.setHeader("Contact", "<sip:acs@" + server.publicIp() + ":" + server.port() + ">");
        server.send(bye, entry.uaAddr);
    }

    /** 回复 A 腿：复制 Via/From/Call-ID/CSeq，To 加服务器 tag */
    private void respondToA(SipMessage original, InetSocketAddress remote, String serverTag,
                            int code, String reason, String sdpBody) {
        SipMessage resp = SipMessage.response(code, reason);
        for (String via : original.getHeaders().getOrDefault("Via", List.of())) {
            resp.addHeader("Via", via);
        }
        String from = original.header("From");
        resp.addHeader("From", from != null ? from : "<sip:unknown@localhost>");
        String to = original.header("To");
        if (serverTag != null && SipMessage.tagOf(to) == null) {
            to = to + ";tag=" + serverTag;
        }
        resp.addHeader("To", to != null ? to : "<sip:unknown@localhost>");
        resp.setHeader("Call-ID", original.callId());
        resp.setHeader("CSeq", original.header("CSeq") == null ? "1 " + (original.getMethod().isEmpty() ? "INVITE" : original.getMethod())
                : original.header("CSeq"));
        resp.setHeader("User-Agent", "PilotACS-SIP");
        if (code != 100) {
            resp.setHeader("Contact", "<sip:acs@" + server.publicIp() + ":" + server.port() + ">");
        }
        if (sdpBody != null) {
            resp.setHeader("Content-Type", "application/sdp");
            resp.setBody(sdpBody);
        }
        server.send(resp, remote);
    }

    private void teardown(CallEntry entry, String reason) {
        boolean wasEnded = entry.session.getState() == CallSession.State.ENDED;
        entry.session.teardown(reason);
        if (!wasEnded) {
            recordService.add(entry.session, reason);
        }
        byCallA.remove(entry.session.getCallIdA());
        byCallB.remove(entry.session.getCallIdB());
        if (entry.uaCallId != null) {
            byCallUa.remove(entry.uaCallId);
        }
    }

    private RtpAgent createAgent(boolean alaw, String tag) {
        try {
            RtpAgent agent = new RtpAgent(alaw,
                    Math.max(1, props.getJitterMs() / 20), props.getJitterMs() + 500);
            agent.setTag(tag);
            return agent;
        } catch (Exception e) {
            Logs.error(Logs.Module.SIP, "RTP 端口分配失败", "错误", e.toString());
            return null;
        }
    }

    private String reasonOf(int code, SipMessage resp) {
        return resp.getReasonPhrase() == null || resp.getReasonPhrase().isBlank()
                ? defaultReason(code) : resp.getReasonPhrase();
    }

    private String defaultReason(int code) {
        return switch (code) {
            case 404 -> "Not Found";
            case 480 -> "Temporarily Unavailable";
            case 486 -> "Busy Here";
            case 408 -> "Request Timeout";
            default -> "Call Failed";
        };
    }

    private String cseqMethod(SipMessage msg) {
        String cseq = msg.header("CSeq");
        return cseq != null && cseq.contains(" ") ? cseq.substring(cseq.lastIndexOf(' ') + 1) : "";
    }

    private SipMessage notFound(SipMessage req) {
        SipMessage resp = SipMessage.response(481, "Call/Transaction Does Not Exist");
        for (String via : req.getHeaders().getOrDefault("Via", List.of())) {
            resp.addHeader("Via", via);
        }
        resp.addHeader("From", req.header("From") != null ? req.header("From") : "<sip:unknown@localhost>");
        resp.addHeader("To", req.header("To") != null ? req.header("To") : "<sip:unknown@localhost>");
        resp.setHeader("Call-ID", req.callId());
        resp.setHeader("CSeq", req.header("CSeq") == null ? "1 BYE" : req.header("CSeq"));
        return resp;
    }

    private String randTag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 一通呼叫的信令上下文（媒体在 CallSession 里） */
    private static class CallEntry {
        final CallSession session;
        /** SIP 主叫腿（SIP 发起的呼叫），MG 主叫时为 null */
        SipMessage inviteA;
        InetSocketAddress aAddr;
        String aTag;
        String aServerTag;
        String bServerTag;
        InetSocketAddress bAddr;
        /** 被叫是否 H.248 终结点 */
        boolean mgCallee;
        String mgTermCallee;
        InetSocketAddress mgAddr;
        String mgContext = "-";
        /** MG 主叫腿（MG 发起的呼叫） */
        String mgTermCaller;
        InetSocketAddress mgAddrCaller;
        String mgCtxCaller = "-";
        /** MG→SIP 呼叫的 UA 被叫腿 */
        String uaCallId;
        InetSocketAddress uaAddr;
        String uaFromTag;
        String uaToHeader;
        int uaCseq = 1;

        CallEntry(CallSession session) {
            this.session = session;
        }
    }
}
