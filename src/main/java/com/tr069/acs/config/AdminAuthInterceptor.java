package com.tr069.acs.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.api.auth.PermissionCodes;
import com.tr069.acs.api.service.JwtTokenService;
import com.tr069.acs.api.service.RoleService;
import com.tr069.acs.common.Result;
import com.tr069.acs.domain.entity.SysUser;
import com.tr069.acs.mapper.SysUserMapper;
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

    private final RoleService roleService;

    private final SysUserMapper userMapper;

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
            log.debug("拒绝未授权访问: {} {}", request.getMethod(), request.getRequestURI());
            writeUnauthorized(response);
            return false;
        }
        request.setAttribute(USERNAME_ATTR, username);
        // 权限：按 URL 与方法算出这条请求需要哪个权限点
        String required = PermissionCodes.required(request.getMethod(), request.getRequestURI());
        // 漏登记的接口直接拦掉：新接口忘了登记模块时，宁可它打不开，也不能谁都能访问
        if (PermissionCodes.UNMAPPED.equals(required)) {
            log.warn("接口未登记权限，已拒绝: {} {} —— 新接口请在 PermissionCodes 里登记模块",
                    request.getMethod(), request.getRequestURI());
            writeForbidden(response, "接口未登记权限");
            return false;
        }
        if (required != null && !roleService.has(roleOf(username), required)) {
            log.debug("拒绝越权访问: username={}, {} {}, 需要 {}",
                    username, request.getMethod(), request.getRequestURI(), required);
            writeForbidden(response, required);
            return false;
        }
        return true;
    }

    /**
     * 取用户的角色。每次都查库：改了角色要立刻生效，缓存了就得等重启。
     *
     * <p>管理端的请求量撑得住这一下——真正高频的是设备上报，那条路不进这里。</p>
     */
    private String roleOf(String username) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .last("LIMIT 1"));
        return user == null ? null : user.getRole();
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
    /**
     * 登录了但没权限。业务码 50403，与管理端的响应拦截器对齐。
     */
    private void writeForbidden(HttpServletResponse response, String required) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.failure(50403, "没有权限：" + required)));
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.failure(50008, "登录状态已失效，请重新登录")));
    }
}
