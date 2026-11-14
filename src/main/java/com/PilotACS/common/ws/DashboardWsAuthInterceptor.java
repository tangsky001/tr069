/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.723
 *
 */

package com.PilotACS.common.ws;

import com.PilotACS.common.constant.AuthConstant;
import com.PilotACS.common.log.Logs;
import com.PilotACS.common.security.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * WebSocket 握手鉴权：token 走 X-Token 头或 ?token= 查询参数（与 AuthInterceptor 同一套口径），
 * 校验 JWT + Redis 会话都在才放行，用户名放进握手 attributes
 */
@Component
@RequiredArgsConstructor
public class DashboardWsAuthInterceptor implements HandshakeInterceptor {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = request.getHeaders().getFirst(AuthConstant.TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            token = queryParam(request.getURI(), "token");
        }
        if (token != null && token.startsWith(AuthConstant.TOKEN_PREFIX)) {
            token = token.substring(AuthConstant.TOKEN_PREFIX.length());
        }
        if (token == null || token.isBlank()) {
            reject(response);
            return false;
        }
        try {
            Claims claims = jwtUtil.parse(token);
            if (redisTemplate.opsForValue().get(AuthConstant.TOKEN_KEY_PREFIX + claims.getId()) == null) {
                reject(response);
                return false;
            }
            attributes.put("username", claims.getSubject());
            return true;
        } catch (Exception e) {
            Logs.warn(Logs.Module.EXCEPTION, "WebSocket 握手鉴权失败", "原因", e.getMessage());
            reject(response);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private void reject(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    private String queryParam(URI uri, String name) {
        String query = uri.getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }
}
