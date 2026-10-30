package com.tr069.acs.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.api.auth.ClientIpResolver;
import com.tr069.acs.api.auth.LoginSourceResolver;
import com.tr069.acs.api.auth.PermissionCodes;
import com.tr069.acs.api.service.JwtTokenService;
import com.tr069.acs.api.service.LoginLogService;
import com.tr069.acs.api.service.PasswordHasher;
import com.tr069.acs.api.service.RoleService;
import com.tr069.acs.common.Result;
import com.tr069.acs.config.AdminAuthInterceptor;
import com.tr069.acs.domain.entity.SysUser;
import jakarta.servlet.http.HttpServletRequest;
import com.tr069.acs.mapper.SysUserMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 管理端登录认证。
 *
 * <p>接口路径同时支持管理端实际使用的 {@code /api/user/*} 与
 * vue-admin-template 模板自带的 {@code /vue-admin-template/user/*}，
 * 便于在未调整前端前直接联调。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final SysUserMapper sysUserMapper;
    private final JwtTokenService jwtTokenService;
    private final PasswordHasher passwordHasher;
    private final LoginSourceResolver loginSourceResolver;
    private final ClientIpResolver clientIpResolver;
    private final LoginLogService loginLog;
    private final RoleService roleService;

    @PostMapping({"/api/user/login", "/vue-admin-template/user/login"})
    public Result<Map<String, Object>> login(@RequestBody LoginRequest request,
                                             HttpServletRequest http) {
        // IP 与来源先取下来：失败的也要记一笔，不能等成功才取
        String ip = clientIpResolver.resolve(http);
        String source = loginSourceResolver.resolve(http);
        String userAgent = http.getHeader("User-Agent");

        if (request == null || request.getUsername() == null || request.getPassword() == null) {
            loginLog.fail(null, null, ip, source, userAgent, "参数不全");
            return Result.failure("用户名或密码不能为空");
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername()));
        if (user == null) {
            // 连用户名都不存在，没有 id 可记，但这次尝试要留下
            loginLog.fail(null, request.getUsername(), ip, source, userAgent, "用户不存在");
            return Result.failure("用户名或密码错误");
        }
        if (!passwordHasher.matches(request.getPassword(), user.getPassword())) {
            loginLog.fail(user.getId(), request.getUsername(), ip, source, userAgent, "密码错误");
            return Result.failure("用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            loginLog.fail(user.getId(), request.getUsername(), ip, source, userAgent, "账号已禁用");
            return Result.failure("账号已禁用");
        }

        String token = jwtTokenService.generate(user.getUsername());
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setLastLoginAt(LocalDateTime.now());
        update.setLastLoginSource(source);
        update.setLastLoginIp(ip);
        // 历史明文口令在本次登录成功后自动升级为散列
        if (!passwordHasher.isHashed(user.getPassword())) {
            update.setPassword(passwordHasher.hash(request.getPassword()));
        }
        sysUserMapper.updateById(update);
        loginLog.succeed(user.getId(), user.getUsername(), ip, source, userAgent);
        log.info("管理端登录: username={}, source={}, ip={}", user.getUsername(), source, ip);

        // vue-admin-template 要求 data 中携带 token
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("role", roleOf(user));
        data.put("source", source);
        return Result.success(data);
    }

    @GetMapping({"/api/user/info", "/vue-admin-template/user/info"})
    public Result<Map<String, Object>> info(@RequestParam(value = "token", required = false) String token,
                                            jakarta.servlet.http.HttpServletRequest request) {
        // 优先用拦截器已解析出的用户名，其次兼容 ?token= 传参
        Object parsed = request.getAttribute(AdminAuthInterceptor.USERNAME_ATTR);
        String username = parsed != null ? String.valueOf(parsed) : jwtTokenService.parse(token);
        if (username == null) {
            return Result.unauthorized("登录状态已失效，请重新登录");
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        if (user == null) {
            return Result.unauthorized("用户不存在");
        }

        // vue-admin-template 用 name / avatar / roles 渲染，权限清单与登录来源另给
        Map<String, Object> data = new HashMap<>();
        data.put("name", user.getNickname() == null ? user.getUsername() : user.getNickname());
        data.put("avatar", "https://wpimg.wallstcn.com/f778738c-e4f8-4870-b634-56703b4acafe.gif");
        String role = roleOf(user);
        data.put("roles", java.util.List.of(role));
        data.put("role", role);
        // 权限跟着角色走，页面用它决定哪些菜单与按钮露出来
        data.put("permissions", roleService.permissionsOf(role));
        data.put("source", user.getLastLoginSource());
        data.put("introduction", "TR069 ACS 管理端");
        return Result.success(data);
    }

    @PostMapping({"/api/user/logout", "/vue-admin-template/user/logout"})
    public Result<Void> logout() {
        return Result.success();
    }

    /** 老账号没有角色就按系统管理员：升级前它是唯一的角色，不给权限反而把自己锁在门外。 */
    private static String roleOf(SysUser user) {
        String role = user.getRole();
        return role == null || role.isBlank() ? PermissionCodes.ROLE_SUPER_ADMIN : role;
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }
}
