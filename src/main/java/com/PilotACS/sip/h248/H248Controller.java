/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.h248;

import com.PilotACS.common.log.Logs;
import com.PilotACS.sip.SipProperties;
import com.PilotACS.sip.call.CallManager;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H.248 控制器（MGC 角色）。
 *
 * <p>负责：网关注册（ServiceChange）、命令下发（Add/Modify/Subtract）、
 * 事件接收（摘机 al/hd、挂机 al/od、DTMF dd/ce）、拨号采集与呼叫转接。</p>
 *
 * <p>信号与事件名按国内主流 IAD/ONT 惯例：al/rg 振铃、dt 拨号音、rt 回铃音、
 * bt 忙音；不同厂商若命名不同，只需改本类常量。</p>
 */
@Component
public class H248Controller {

    /** 事件描述符（摘机/挂机/DTMF） */
    private static final String EVENTS_DESC =
            "Events = 1001 { al/hd { strict=state }, al/od { strict=state }, dd/ce { strict=state } }";
    /** 振铃信号 */
    private static final String SIGNAL_RING = "Signals = 1002 { al/rg }";
    /** 拨号音 */
    private static final String SIGNAL_DIAL = "Signals = 1002 { dt }";
    /** 回铃音 */
    private static final String SIGNAL_RINGBACK = "Signals = 1002 { rt }";
    /** 忙音 */
    private static final String SIGNAL_BUSY = "Signals = 1002 { bt }";

    private final SipProperties props;
    private final H248Server server;
    private final GatewayRegistry gateways;
    private final com.PilotACS.sip.server.SipUdpServer sipServer;
    private final CallManager callManager;

    private final AtomicLong txnSeq = new AtomicLong(System.currentTimeMillis() % 100000);
    /** 事务号 → 在途事务 */
    private final Map<Long, PendingTxn> pending = new ConcurrentHashMap<>();
    /** 终结点 → 拨号中会话（MG 侧用户摘机拨号） */
    private final Map<String, Dialing> dialings = new ConcurrentHashMap<>();
    /** 终结点 → 已接通呼叫的 callIdB（SIP→MG 方向，事件回投用） */
    private final Map<String, String> termCall = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    public H248Controller(SipProperties props, H248Server server, GatewayRegistry gateways,
                          com.PilotACS.sip.server.SipUdpServer sipServer, CallManager callManager) {
        this.props = props;
        this.server = server;
        this.gateways = gateways;
        this.sipServer = sipServer;
        this.callManager = callManager;
    }

    /** 在途事务 */
    private static class PendingTxn {
        final String type;
        final String callIdB;
        final String termId;
        final InetSocketAddress mgAddr;
        final long createdAt = System.currentTimeMillis();

        PendingTxn(String type, String callIdB, String termId, InetSocketAddress mgAddr) {
            this.type = type;
            this.callIdB = callIdB;
            this.termId = termId;
            this.mgAddr = mgAddr;
        }
    }

    /** MG 侧拨号中会话 */
    public static class Dialing {
        public String termId;
        public InetSocketAddress mgAddr;
        public String mgId;
        public String contextId = "-";
        public String mgLocalIp;
        public int mgLocalPort;
        public StringBuilder digits = new StringBuilder();
        public long startAt = System.currentTimeMillis();
    }

    /** 网关注册（由 callManager 初始化完成后调度清理） */
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::sweepDialings, 10, 5, TimeUnit.SECONDS);
    }

    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // ------------------------------------------------ 报文入口

    public void handle(String raw, InetSocketAddress remote) {
        H248Message.Parsed msg = H248Message.parse(raw);
        if (msg == null || msg.root == null) {
            return;
        }
        if ("Transaction".equals(msg.msgType)) {
            handleRequest(msg, remote);
        } else if ("Reply".equals(msg.msgType)) {
            handleReply(msg, remote);
        } else {
            Logs.debug(Logs.Module.SIP, "H.248 报文忽略", "类型", msg.msgType);
        }
    }

    /** MG 发来的请求：ServiceChange（注册）/ Notify（事件） */
    private void handleRequest(H248Message.Parsed msg, InetSocketAddress remote) {
        NodeWrapper ctx = firstContext(msg.root);
        if (ctx == null) {
            return;
        }
        H248Message.Node sc = ctx.node.child("ServiceChange");
        H248Message.Node notify = ctx.node.child("Notify");
        if (sc != null) {
            handleServiceChange(msg.transactionId, ctx, sc, remote);
        } else if (notify != null) {
            handleNotify(msg.transactionId, ctx, notify, remote);
        } else {
            replyRequest(msg.transactionId, ctx, null, remote);
        }
    }

    /** ServiceChange：Restart/Handoff 即登记网关，用空描述符回 200 */
    private void handleServiceChange(long txn, NodeWrapper ctx, H248Message.Node sc, InetSocketAddress remote) {
        String method = sc.childText("ServiceChangeDescriptor") == null ? ""
                : String.valueOf(methodOf(sc.child("ServiceChangeDescriptor")));
        String version = "";
        H248Message.Node desc = sc.child("ServiceChangeDescriptor");
        if (desc != null && desc.child("ServiceChangeVersion") != null) {
            version = desc.child("ServiceChangeVersion").text;
        }
        gateways.register(remote, version);
        replyRequest(txn, ctx, "ServiceChange", remote);
        Logs.info(Logs.Module.SIP, "H.248 ServiceChange", "方法", method, "远端", remote);
    }

    private String methodOf(H248Message.Node desc) {
        H248Message.Node m = desc.child("Method");
        return m == null ? "" : m.text;
    }

    /** Notify：摘机/挂机/DTMF 事件 */
    private void handleNotify(long txn, NodeWrapper ctx, H248Message.Node notify, InetSocketAddress remote) {
        String termId = notify.text == null ? "" : notify.text;
        replyRequest(txn, ctx, "Notify", remote);
        H248Message.Node oe = notify.child("ObservedEvents");
        if (oe == null) {
            return;
        }
        for (H248Message.Node ev : oe.findAll("dd/ce")) {
            String digit = eventDigit(ev);
            if (digit != null && !digit.isEmpty()) {
                onDigit(termId, digit, remote);
                return;
            }
        }
        String evText = flattenEvents(oe);
        if (evText.contains("hd") || evText.contains("off")) {
            onOffhook(termId, remote);
        } else if (evText.contains("od") || evText.contains("on")) {
            onOnhook(termId, remote);
        }
    }

    /** DTMF 事件值（dd/ce { digit=1 }） */
    private String eventDigit(H248Message.Node ev) {
        for (H248Message.Node c : ev.findAll("digit")) {
            if (c.text != null) {
                return c.text;
            }
        }
        // 叶子文本兜底："... digit=1 ..."
        H248Message.Node ce = ev;
        return ce.text != null ? ce.text.replaceAll(".*digit=([0-9*#]).*", "$1") : null;
    }

    private String flattenEvents(H248Message.Node oe) {
        StringBuilder sb = new StringBuilder();
        for (H248Message.Node ev : oe.children) {
            sb.append(ev.name).append(' ');
            if (ev.text != null) {
                sb.append(ev.text).append(' ');
            }
            for (H248Message.Node c : ev.children) {
                sb.append(c.name).append(' ');
                if (c.text != null) {
                    sb.append(c.text).append(' ');
                }
            }
        }
        return sb.toString();
    }

    /** MG 对 MGC 命令的回复 */
    private void handleReply(H248Message.Parsed msg, InetSocketAddress remote) {
        PendingTxn txn = pending.remove(msg.transactionId);
        if (txn == null) {
            return;
        }
        NodeWrapper ctx = firstContext(msg.root);
        if (ctx == null) {
            return;
        }
        H248Message.Node media = ctx.node.findAll("Media").isEmpty() ? null : ctx.node.findAll("Media").get(0);
        if ("ADD".equals(txn.type) && media != null) {
            H248Message.Node local = media.findAll("Local").isEmpty() ? null : media.findAll("Local").get(0);
            if (local != null && local.text != null) {
                String sdp = H248Message.lineToSdp(local.text);
                com.PilotACS.sip.core.Sdp parsed = com.PilotACS.sip.core.Sdp.parse(sdp);
                if (parsed.getAudio() != null && parsed.getAudio().ip != null) {
                    if (txn.callIdB != null) {
                        // SIP→MG 呼叫：MG 侧媒体就绪
                        callManager.onMgLocalSdp(txn.callIdB, parsed.getAudio().ip, parsed.getAudio().port, txn.mgAddr, ctx.node.name);
                    } else {
                        onDialMediaReady(txn.termId, parsed.getAudio().ip, parsed.getAudio().port, ctx.node.name, remote);
                    }
                }
            }
        }
        Logs.debug(Logs.Module.SIP, "H.248 命令回复", "类型", txn.type, "事务", msg.transactionId);
    }

    // ------------------------------------------------ MGC 命令（CallManager 调用）

    /** SIP→MG：被叫终结点加入上下文，带远端 SDP（服务器 RTP）+ 振铃 + 事件 */
    public void addForIncomingCall(String callIdB, String termId, InetSocketAddress mgAddr, int rtpPort, String rtpIp) {
        long txn = txnSeq.incrementAndGet();
        pending.put(txn, new PendingTxn("ADD", callIdB, termId, mgAddr));
        termCall.put(termId, callIdB);
        String remoteSdp = H248Message.sdpToLine(
                com.PilotACS.sip.core.Sdp.build(rtpIp, rtpPort, 8, "PCMA"));
        StringBuilder sb = H248Message.header(mgcIp());
        sb.append("Transaction = ").append(txn).append(" {\n");
        sb.append("  Context = - {\n");
        sb.append("    Add = ").append(termId).append(" {\n");
        sb.append("      Media {\n");
        sb.append("        Remote {\n").append("          ").append(remoteSdp).append("\n        }\n");
        sb.append("      },\n");
        sb.append("      ").append(EVENTS_DESC).append(",\n");
        sb.append("      ").append(SIGNAL_RING).append("\n");
        sb.append("    }\n");
        sb.append("  }\n}");
        server.send(sb.toString(), mgAddr);
        Logs.info(Logs.Module.SIP, "H.248 Add（被叫振铃）", "终结点", termId, "呼叫", callIdB);
    }

    /** 摘机应答后：停振铃，回铃侧无需媒体变更 */
    public void stopSignal(String termId, InetSocketAddress mgAddr, String contextId) {
        long txn = txnSeq.incrementAndGet();
        pending.put(txn, new PendingTxn("MODIFY", null, termId, mgAddr));
        StringBuilder sb = H248Message.header(mgcIp());
        sb.append("Transaction = ").append(txn).append(" {\n");
        sb.append("  Context = ").append(contextId).append(" {\n");
        sb.append("    Modify = ").append(termId).append(" {\n");
        sb.append("      Signals = -\n");
        sb.append("    }\n");
        sb.append("  }\n}");
        server.send(sb.toString(), mgAddr);
    }

    /** 设置远端媒体（MG 主叫接通后把对端 RTP 指给 MG）并停信号 */
    public void modifyRemote(String termId, InetSocketAddress mgAddr, String contextId, String rtpIp, int rtpPort) {
        long txn = txnSeq.incrementAndGet();
        pending.put(txn, new PendingTxn("MODIFY", null, termId, mgAddr));
        String remoteSdp = H248Message.sdpToLine(
                com.PilotACS.sip.core.Sdp.build(rtpIp, rtpPort, 8, "PCMA"));
        StringBuilder sb = H248Message.header(mgcIp());
        sb.append("Transaction = ").append(txn).append(" {\n");
        sb.append("  Context = ").append(contextId).append(" {\n");
        sb.append("    Modify = ").append(termId).append(" {\n");
        sb.append("      Media {\n");
        sb.append("        Remote {\n").append("          ").append(remoteSdp).append("\n        }\n");
        sb.append("      },\n");
        sb.append("      Signals = -\n");
        sb.append("    }\n");
        sb.append("  }\n}");
        server.send(sb.toString(), mgAddr);
    }

    /** 给 MG 主叫侧发回铃音信号 */
    public void sendRingbackSignal(String termId, InetSocketAddress mgAddr, String contextId) {
        sendSignal(termId, mgAddr, contextId, SIGNAL_RINGBACK);
    }

    /** 给 MG 主叫侧发忙音（3 秒后 Subtract） */
    public void sendBusySignal(String termId, InetSocketAddress mgAddr, String contextId) {
        sendSignal(termId, mgAddr, contextId, SIGNAL_BUSY);
        scheduler.schedule(() -> subtract(termId, mgAddr, contextId), 3, TimeUnit.SECONDS);
    }

    private void sendSignal(String termId, InetSocketAddress mgAddr, String contextId, String signals) {
        long txn = txnSeq.incrementAndGet();
        pending.put(txn, new PendingTxn("MODIFY", null, termId, mgAddr));
        StringBuilder sb = H248Message.header(mgcIp());
        sb.append("Transaction = ").append(txn).append(" {\n");
        sb.append("  Context = ").append(contextId).append(" {\n");
        sb.append("    Modify = ").append(termId).append(" {\n");
        sb.append("      ").append(signals).append("\n");
        sb.append("    }\n");
        sb.append("  }\n}");
        server.send(sb.toString(), mgAddr);
    }

    /** Subtract：终结点退出上下文（挂断） */
    public void subtract(String termId, InetSocketAddress mgAddr, String contextId) {
        long txn = txnSeq.incrementAndGet();
        pending.put(txn, new PendingTxn("SUBTRACT", null, termId, mgAddr));
        StringBuilder sb = H248Message.header(mgcIp());
        sb.append("Transaction = ").append(txn).append(" {\n");
        sb.append("  Context = ").append(contextId).append(" {\n");
        sb.append("    Subtract = ").append(termId).append(" { }\n");
        sb.append("  }\n}");
        server.send(sb.toString(), mgAddr);
        termCall.remove(termId);
        Logs.info(Logs.Module.SIP, "H.248 Subtract", "终结点", termId);
    }

    // ------------------------------------------------ 拨号（MG 主叫）

    /** 摘机（空闲终结点）：建上下文收拨号音 + 事件 */
    private void onOffhook(String termId, InetSocketAddress remote) {
        if (dialings.containsKey(termId) || termCall.containsKey(termId)) {
            callManager.onMgOffhookByTerm(termId);
            return;
        }
        long txn = txnSeq.incrementAndGet();
        pending.put(txn, new PendingTxn("ADD", null, termId, remote));
        Dialing dial = new Dialing();
        dial.termId = termId;
        dial.mgAddr = remote;
        dial.mgId = remote.getAddress().getHostAddress() + ":" + remote.getPort();
        dialings.put(termId, dial);
        StringBuilder sb = H248Message.header(mgcIp());
        sb.append("Transaction = ").append(txn).append(" {\n");
        sb.append("  Context = - {\n");
        sb.append("    Add = ").append(termId).append(" {\n");
        sb.append("      ").append(EVENTS_DESC).append(",\n");
        sb.append("      ").append(SIGNAL_DIAL).append("\n");
        sb.append("    }\n");
        sb.append("  }\n}");
        server.send(sb.toString(), remote);
        Logs.info(Logs.Module.SIP, "H.248 摘机（拨号中）", "终结点", termId, "远端", remote);
    }

    /** 挂机：取消拨号或挂断通话 */
    private void onOnhook(String termId, InetSocketAddress remote) {
        Dialing dial = dialings.remove(termId);
        if (dial != null) {
            subtract(termId, remote, dial.contextId);
            Logs.info(Logs.Module.SIP, "H.248 拨号取消（挂机）", "终结点", termId);
            return;
        }
        callManager.onMgOnhookByTerm(termId);
    }

    /** DTMF：累积 4 位后路由呼叫 */
    private void onDigit(String termId, String digit, InetSocketAddress remote) {
        Dialing dial = dialings.get(termId);
        if (dial == null) {
            return;
        }
        if ("*".equals(digit) || "#".equals(digit)) {
            dial.digits.setLength(0);
            return;
        }
        dial.digits.append(digit);
        Logs.debug(Logs.Module.SIP, "H.248 收号", "终结点", termId, "已收", dial.digits);
        if (dial.digits.length() >= 4) {
            dialings.remove(termId);
            String number = dial.digits.toString();
            dial.digits.setLength(0);
            boolean routed = callManager.callFromMg(number, dial);
            if (!routed) {
                sendBusySignal(termId, remote, dial.contextId);
            }
        }
    }

    /** MG 主叫媒体就绪（Add 回复带 Local SDP）：记录，等收号后接续 */
    private void onDialMediaReady(String termId, String ip, int port, String contextId, InetSocketAddress remote) {
        Dialing dial = dialings.get(termId);
        if (dial == null) {
            return;
        }
        dial.mgLocalIp = ip;
        dial.mgLocalPort = port;
        dial.contextId = contextId;
        Logs.debug(Logs.Module.SIP, "H.248 主叫媒体就绪", "终结点", termId, "RTP", ip + ":" + port);
    }

    /** 拨号超时清理 */
    private void sweepDialings() {
        long now = System.currentTimeMillis();
        for (Dialing dial : dialings.values()) {
            if (now - dial.startAt > 15000) {
                dialings.remove(dial.termId);
                subtract(dial.termId, dial.mgAddr, dial.contextId);
                Logs.info(Logs.Module.SIP, "H.248 拨号超时", "终结点", dial.termId);
            }
        }
        pending.entrySet().removeIf(e -> now - e.getValue().createdAt > 60_000);
    }

    // ------------------------------------------------ 内部

    private String mgcIp() {
        return sipServer.publicIp();
    }

    private void replyRequest(long txn, NodeWrapper ctx, String cmd, InetSocketAddress remote) {
        StringBuilder sb = H248Message.header(mgcIp());
        sb.append("Reply = ").append(txn).append(" {\n");
        sb.append("  Context = ").append(ctx.node.name).append(" {\n");
        if (cmd != null) {
            sb.append("    ").append(cmd).append(" { }\n");
        }
        sb.append("  }\n}");
        server.send(sb.toString(), remote);
    }

    /** 第一个 Context 包装 */
    private NodeWrapper firstContext(H248Message.Node root) {
        H248Message.Node ctx = root.child("Context");
        if (ctx == null) {
            for (H248Message.Node c : root.children) {
                H248Message.Node inner = c.child("Context");
                if (inner != null) {
                    ctx = inner;
                    break;
                }
            }
        }
        return ctx == null ? null : new NodeWrapper(ctx);
    }

    private static class NodeWrapper {
        final H248Message.Node node;

        NodeWrapper(H248Message.Node node) {
            this.node = node;
        }
    }
}
