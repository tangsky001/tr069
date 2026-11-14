package com.PilotACS.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.PilotACS.common.constant.AuthConstant;
import com.PilotACS.common.result.Result;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Set;

// 认证与权限拦截
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        String token = resolveToken(request);
        if (token == null || token.isBlank()) {
            reject(response, Result.UNAUTHORIZED, "未登录");
            return false;
        }
        Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (Exception e) {
            reject(response, Result.UNAUTHORIZED, "登录已失效");
            return false;
        }
        String jti = claims.getId();
        String json = redisTemplate.opsForValue().get(AuthConstant.TOKEN_KEY_PREFIX + jti);
        if (json == null) {
            reject(response, Result.UNAUTHORIZED, "登录已失效");
            return false;
        }
        LoginUser user = objectMapper.readValue(json, LoginUser.class);
        // 滑动续期
        redisTemplate.expire(AuthConstant.TOKEN_KEY_PREFIX + jti, Duration.ofHours(jwtUtil.getExpireHours()));
        // 方法上有注解用方法的，否则回退类级注解（模块级权限）
        RequiresPermission rp = hm.getMethodAnnotation(RequiresPermission.class);
        if (rp == null) {
            rp = hm.getBeanType().getAnnotation(RequiresPermission.class);
        }
        if (rp != null && !user.isSuperAdmin() && !hasPerm(user.getPermissions(), rp.value())) {
            reject(response, Result.FORBIDDEN, "无操作权限");
            return false;
        }
        UserContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    // 优先 X-Token，兼容 Authorization 与 token 查询参数（img 标签等带不了请求头的场景）
    private String resolveToken(HttpServletRequest request) {
        String token = request.getHeader(AuthConstant.TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            token = request.getHeader("Authorization");
        }
        if (token == null || token.isBlank()) {
            token = request.getParameter("token");
        }
        if (token != null && token.startsWith(AuthConstant.TOKEN_PREFIX)) {
            token = token.substring(AuthConstant.TOKEN_PREFIX.length());
        }
        return token;
    }

    private boolean hasPerm(Set<String> perms, String code) {
        return perms != null && perms.contains(code);
    }

    private void reject(HttpServletResponse response, int code, String msg) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, msg)));
    }
}
