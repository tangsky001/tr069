/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.common.ws;

import com.PilotACS.common.constant.AuthConstant;
import com.PilotACS.common.security.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/** 实时日志 WebSocket 握手鉴权（独立类，不复用概览 WebSocket 的拦截器）： */
@Component
public class RealtimeLogWsAuthInterceptor implements HandshakeInterceptor {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    public RealtimeLogWsAuthInterceptor(JwtUtil jwtUtil, StringRedisTemplate redisTemplate) {
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
    }

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
