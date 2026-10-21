package com.tr069.acs.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.api.service.JwtTokenService;
import com.tr069.acs.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 管理端接口鉴权。
 *
 * <p>只拦 {@code /api/**}。设备侧的 CWMP 接口（{@code /ACS-server/ACS}、{@code /cpe}）
 * 不走这里——光猫不会有令牌，拦了设备就注册不上来。</p>
 *
 * <p>令牌取自 {@code X-Token} 头，与管理端 request 拦截器一致；同时兼容
 * {@code Authorization: Bearer} 与 {@code ?token=} 传参，方便接口调试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    /**
     * 解析出的用户名，请求期间可直接取用，不必再解一遍令牌。
     */
    public static final String USERNAME_ATTR = AdminAuthInterceptor.class.getName() + ".username";

    private static final String TOKEN_HEADER = "X-Token";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        // 跨域预检不带自定义头，必须放行，否则前端连 OPTIONS 都过不去
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String token = resolveToken(request);
        String username = token == null ? null : jwtTokenService.parse(token);
        if (username == null) {
            writeUnauthorized(response);
            return false;
        }
        request.setAttribute(USERNAME_ATTR, username);
        return true;
    }

    /**
     * 取令牌：优先 X-Token 头，其次 Authorization: Bearer，最后是 token 参数。
     */
    private String resolveToken(HttpServletRequest request) {
        String token = request.getHeader(TOKEN_HEADER);
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null
                && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String cut = authorization.substring(BEARER_PREFIX.length()).trim();
            if (!cut.isEmpty()) {
                return cut;
            }
        }
        String param = request.getParameter("token");
        return param == null || param.isBlank() ? null : param.trim();
    }

    /**
     * 返回 401，业务码用 50008，管理端响应拦截器据此弹出重新登录。
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.failure(50008, "登录状态已失效，请重新登录")));
    }
}
