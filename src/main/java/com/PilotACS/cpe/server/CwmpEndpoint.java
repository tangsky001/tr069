/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.server;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.common.log.Logs;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpRequestHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * CPE 上报端点（TR-069 CWMP）。
 * <p>注意：这里故意不用 @RestController——项目给所有 RestController 统一加 /api
 * 前缀，而设备侧配置的 ACS URL 不能带 /api。端点由 {@link CpeServerConfig} 用
 * SimpleUrlHandlerMapping 挂到 acs.cpe.paths 配置的每个路径上（多地址判定），
 * 鉴权由设备侧 Basic 账号控制。
 */
@Component
@RequiredArgsConstructor
public class CwmpEndpoint implements HttpRequestHandler {

    private final CwmpService cwmpService;
    private final CpeProperties props;

    @Override
    public void handleRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            // GET 一律拒绝（CWMP 只收 POST）
            response.sendError(405);
            return;
        }
        // 可选 Basic 认证（acs.cpe.auth-username 配置后才启用）
        if (!basicAuthOk(request)) {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Basic realm=\"acs\"");
            return;
        }
        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String sessionId = cookieSession(request);
        CwmpService.Reply reply = cwmpService.processRequest(body, sessionId, request.getRemoteAddr());
        if (reply.newSessionId() != null) {
            response.addHeader("Set-Cookie", "session=" + reply.newSessionId()
                    + "; Path=/; HttpOnly; Max-Age=" + props.getSessionTimeoutSeconds());
        }
        response.setStatus(reply.httpStatus());
        if (reply.body() != null && !reply.body().isEmpty()) {
            response.setContentType("text/xml; charset=\"utf-8\"");
            response.getOutputStream().write(reply.body().getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean basicAuthOk(HttpServletRequest request) {
        if (props.getAuthUsername() == null || props.getAuthUsername().isBlank()) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Basic ")) {
            return false;
        }
        String expected = Base64.getEncoder().encodeToString(
                (props.getAuthUsername() + ":" + props.getAuthPassword()).getBytes(StandardCharsets.UTF_8));
        boolean ok = expected.equals(auth.substring(6).trim());
        if (!ok) {
            Logs.warn(Logs.Module.CPE, "CPE Basic 认证失败", "地址", request.getRemoteAddr());
        }
        return ok;
    }

    private String cookieSession(HttpServletRequest request) {
        String cookie = request.getHeader("Cookie");
        if (cookie == null) {
            return null;
        }
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("session=")) {
                return p.substring(8);
            }
        }
        return null;
    }
}
