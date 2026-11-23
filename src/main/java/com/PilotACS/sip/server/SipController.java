/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.server;

import com.PilotACS.common.log.Logs;
import com.PilotACS.sip.SipProperties;
import com.PilotACS.sip.call.CallManager;
import com.PilotACS.sip.core.SipMessage;
import com.PilotACS.sip.reg.DigestAuth;
import com.PilotACS.sip.reg.ExtensionRegistry;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

/** SIP 报文入口：请求分发（REGISTER/INVITE/ACK/BYE/CANCEL/OPTIONS）+ 响应转呼叫管理器 */
@Component
public class SipController {

    private final SipProperties props;
    private final SipUdpServer server;
    private final ExtensionRegistry registry;
    private final DigestAuth digest = new DigestAuth();
    private final CallManager callManager;

    public SipController(SipProperties props, SipUdpServer server,
                         ExtensionRegistry registry, CallManager callManager) {
        this.props = props;
        this.server = server;
        this.registry = registry;
        this.callManager = callManager;
    }

    public void handle(SipMessage msg, InetSocketAddress remote) {
        if (msg.getKind() == SipMessage.Kind.RESPONSE) {
            callManager.onResponse(msg, remote);
            return;
        }
        switch (msg.getMethod()) {
            case "REGISTER" -> handleRegister(msg, remote);
            case "INVITE" -> callManager.onInvite(msg, remote);
            case "ACK" -> callManager.onAck(msg, remote);
            case "BYE" -> callManager.onBye(msg, remote);
            case "CANCEL" -> callManager.onCancel(msg, remote);
            case "OPTIONS" -> respondSimple(msg, remote, 200, "OK");
            default -> respondSimple(msg, remote, 405, "Method Not Allowed");
        }
    }

    /** REGISTER：Digest 401 鉴权 → 登记注册/注销 → 200 OK */
    private void handleRegister(SipMessage msg, InetSocketAddress remote) {
        String ext = SipMessage.userOf(SipMessage.uriOf(msg.header("To")));
        String authUser = usernameOfAuthorization(msg);
        if (authUser != null && (ext == null || ext.isBlank())) {
            ext = authUser;
        }
        if (ext == null || !registry.isConfigured(ext)) {
            respondRegister(msg, remote, 403, "Forbidden", null, 0);
            Logs.info(Logs.Module.SIP, "注册拒绝（分机未配置）", "分机", ext, "远端", remote);
            return;
        }
        int expires = parseExpires(msg);
        if (expires == 0) {
            registry.unregister(ext);
            respondRegister(msg, remote, 200, "OK", null, 0);
            return;
        }
        if (props.isAuthEnabled()) {
            String password = registry.passwordOf(ext);
            if (password != null && !password.isBlank()
                    && (authUser == null || !authUser.equals(ext) || !digest.verify(msg, "REGISTER", props.getRealm(), password))) {
                String nonce = digest.newNonce();
                respondRegister(msg, remote, 401, "Unauthorized", digest.challenge(props.getRealm(), nonce), expires);
                return;
            }
        }
        String contact = msg.header("Contact") != null ? msg.header("Contact")
                : "<sip:" + ext + "@" + remote.getAddress().getHostAddress() + ":" + remote.getPort() + ">";
        String userAgent = msg.header("User-Agent") == null ? "" : msg.header("User-Agent");
        registry.register(ext, remote, contact, userAgent, expires);
        respondRegister(msg, remote, 200, "OK", null, expires);
    }

    private String usernameOfAuthorization(SipMessage msg) {
        String auth = msg.header("Authorization");
        if (auth == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("username\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))").matcher(auth);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
    }

    /** Expires 头或 Contact 头的 expires 参数 */
    private int parseExpires(SipMessage msg) {
        String expires = msg.header("Expires");
        if (expires != null) {
            try {
                return Integer.parseInt(expires.trim());
            } catch (NumberFormatException ignored) {
                // 落到 Contact 参数
            }
        }
        String contact = msg.header("Contact");
        if (contact != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("expires\\s*=\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(contact);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return 3600;
    }

    private void respondRegister(SipMessage req, InetSocketAddress remote, int code, String reason,
                                 String wwwAuth, int expires) {
        SipMessage resp = SipMessage.response(code, reason);
        for (String via : req.getHeaders().getOrDefault("Via", List.of())) {
            resp.addHeader("Via", via);
        }
        resp.addHeader("From", req.header("From") != null ? req.header("From") : "<sip:unknown@localhost>");
        String to = req.header("To") != null ? req.header("To") : "<sip:unknown@localhost>";
        if (SipMessage.tagOf(to) == null) {
            to = to + ";tag=" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }
        resp.addHeader("To", to);
        resp.setHeader("Call-ID", req.callId());
        resp.setHeader("CSeq", req.header("CSeq") == null ? "1 REGISTER" : req.header("CSeq"));
        resp.setHeader("User-Agent", "PilotACS-SIP");
        resp.setHeader("Contact", "<sip:acs@" + server.publicIp() + ":" + server.port() + ">");
        resp.setHeader("Expires", String.valueOf(expires));
        if (wwwAuth != null) {
            resp.setHeader("WWW-Authenticate", wwwAuth);
        }
        server.send(resp, remote);
    }

    private void respondSimple(SipMessage req, InetSocketAddress remote, int code, String reason) {
        SipMessage resp = SipMessage.response(code, reason);
        for (String via : req.getHeaders().getOrDefault("Via", List.of())) {
            resp.addHeader("Via", via);
        }
        resp.addHeader("From", req.header("From") != null ? req.header("From") : "<sip:unknown@localhost>");
        resp.addHeader("To", req.header("To") != null ? req.header("To") : "<sip:unknown@localhost>");
        resp.setHeader("Call-ID", req.callId());
        resp.setHeader("CSeq", req.header("CSeq") == null ? "1 " + req.getMethod() : req.header("CSeq"));
        resp.setHeader("User-Agent", "PilotACS-SIP");
        server.send(resp, remote);
    }
}
