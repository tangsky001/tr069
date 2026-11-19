/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.149
 *
 */

package com.PilotACS.service;

import com.PilotACS.common.constant.AuthConstant;
import com.PilotACS.common.enums.ClientType;
import com.PilotACS.common.exception.BusinessException;
import com.PilotACS.common.log.Logs;
import com.PilotACS.common.security.JwtUtil;
import com.PilotACS.common.security.LoginUser;
import com.PilotACS.common.security.PasswordUtil;
import com.PilotACS.common.utils.IpUtil;
import io.jsonwebtoken.Claims;
import com.PilotACS.dto.LoginDTO;
import com.PilotACS.entity.SysLoginLog;
import com.PilotACS.entity.SysRolePermission;
import com.PilotACS.entity.SysUser;
import com.PilotACS.mapper.SysRolePermissionMapper;
import com.PilotACS.mapper.SysLoginLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// 登录认证服务
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 登录爆破防护参数：同一用户名窗口期内失败达上限后锁定 */
    private static final int MAX_LOGIN_FAILS = 5;
    private static final Duration LOGIN_FAIL_WINDOW = Duration.ofMinutes(15);
    private static final String LOGIN_FAIL_KEY = "acs:auth:fail:";

    private final SysUserService userService;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Map<String, Object> login(LoginDTO dto, HttpServletRequest request) {
        String ip = IpUtil.getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        ClientType source = ClientType.of(request.getHeader(AuthConstant.CLIENT_HEADER));
        // 爆破防护：锁定期内直接拒绝（放最前面，用户名枚举的爆破节奏也被拦住）
        String failKey = LOGIN_FAIL_KEY + dto.getUsername();
        String fails = null;
        try {
            fails = redisTemplate.opsForValue().get(failKey);
        } catch (Exception e) {
            Logs.warn(Logs.Module.AUTH, "登录失败计数读取失败（放行本次登录）", "原因", e.getMessage());
        }
        if (fails != null && parseFails(fails) >= MAX_LOGIN_FAILS) {
            log(dto.getUsername(), ip, source, false, userAgent);
            throw new BusinessException("失败次数过多，账号已临时锁定，请 " + LOGIN_FAIL_WINDOW.toMinutes() + " 分钟后再试");
        }
        SysUser user = userService.lambdaQuery().eq(SysUser::getUsername, dto.getUsername()).one();
        if (user == null) {
            countFail(failKey);
            log(dto.getUsername(), ip, source, false, userAgent);
            throw new BusinessException("用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            log(user.getUsername(), ip, source, false, userAgent);
            throw new BusinessException("账号已禁用");
        }
        boolean legacyHash = PasswordUtil.isLegacy(user.getPassword());
        if (!PasswordUtil.verify(dto.getPassword(), user.getSalt(), user.getPassword())) {
            countFail(failKey);
            log(user.getUsername(), ip, source, false, userAgent);
            throw new BusinessException("用户名或密码错误");
        }
        if (legacyHash) {
            // 存量单轮 SHA-256 在登录成功时透明升级为 PBKDF2 慢哈希。
            try {
                SysUser rehash = new SysUser();
                rehash.setId(user.getId());
                rehash.setPassword(PasswordUtil.hash(dto.getPassword(), user.getSalt()));
                userService.updateById(rehash);
            } catch (Exception e) {
                Logs.warn(Logs.Module.AUTH, "密码哈希透明升级失败（不影响本次登录）", "用户", user.getUsername(),
                        "原因", e.getMessage());
            }
        }
        LoginUser lu = buildLoginUser(user, source);
        String token = jwtUtil.create(user.getId(), user.getUsername(), Map.of());
        try {
            lu.setJti(jwtUtil.parse(token).getId());
        } catch (Exception e) {
            throw new BusinessException("令牌生成失败");
        }
        try {
            redisTemplate.opsForValue().set(AuthConstant.TOKEN_KEY_PREFIX + lu.getJti(),
                    objectMapper.writeValueAsString(lu), Duration.ofHours(jwtUtil.getExpireHours()));
        } catch (Exception e) {
            Logs.error(Logs.Module.AUTH, "Redis 会话写入失败，请检查 Redis 连接配置", e);
            throw new BusinessException("会话写入失败：" + e.getMessage());
        }
        SysUser upd = new SysUser();
        upd.setId(user.getId());
        upd.setLastLoginIp(ip);
        upd.setLastLoginTime(LocalDateTime.now());
        upd.setLastLoginSource(source.name());
        userService.updateById(upd);
        log(user.getUsername(), ip, source, true, userAgent);
        // 登录成功清空失败计数
        try {
            redisTemplate.delete(failKey);
        } catch (Exception ignore) {
            // 计数清理失败不影响登录
        }

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        return result;
    }

    public void logout(HttpServletRequest request) {
        String token = resolveToken(request);
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            Claims claims = jwtUtil.parse(token);
            redisTemplate.delete(AuthConstant.TOKEN_KEY_PREFIX + claims.getId());
            Logs.info(Logs.Module.AUTH, "退出登录",
                    "用户", claims.getSubject(),
                    "IP", IpUtil.getClientIp(request),
                    "标识", ClientType.of(request.getHeader(AuthConstant.CLIENT_HEADER)).getLabel());
        } catch (Exception ignored) {
            // token 已失效的退出不记日志
        }
    }

    // 当前登录用户信息，供 /user/info
    public LoginUser currentUser(HttpServletRequest request) {
        String token = resolveToken(request);
        if (token == null || token.isBlank()) {
            throw new BusinessException(AuthConstant.UNAUTHORIZED, "未登录");
        }
        try {
            String jti = jwtUtil.parse(token).getId();
            String json = redisTemplate.opsForValue().get(AuthConstant.TOKEN_KEY_PREFIX + jti);
            if (json == null) {
                throw new BusinessException(AuthConstant.UNAUTHORIZED, "登录已失效");
            }
            return objectMapper.readValue(json, LoginUser.class);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(AuthConstant.UNAUTHORIZED, "登录已失效");
        }
    }

    private void countFail(String failKey) {
        try {
            redisTemplate.opsForValue().increment(failKey);
            redisTemplate.expire(failKey, LOGIN_FAIL_WINDOW);
        } catch (Exception e) {
            Logs.warn(Logs.Module.AUTH, "登录失败计数写入失败", "原因", e.getMessage());
        }
    }

    private int parseFails(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String resolveToken(HttpServletRequest request) {
        // 优先 X-Token 头，兼容 query 参数 token
        String token = request.getHeader(AuthConstant.TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            token = request.getParameter("token");
        }
        if (token != null && token.startsWith(AuthConstant.TOKEN_PREFIX)) {
            token = token.substring(AuthConstant.TOKEN_PREFIX.length());
        }
        return token;
    }

    public LoginUser buildLoginUser(SysUser user, ClientType source) {
        LoginUser lu = new LoginUser();
        lu.setUserId(user.getId());
        lu.setUsername(user.getUsername());
        lu.setNickname(user.getNickname());
        lu.setRole(user.getRoleCode());
        lu.setSuperAdmin(AuthConstant.SUPER_ADMIN_CODE.equals(user.getRoleCode()) || user.getId() == AuthConstant.SUPER_ADMIN_ID);
        lu.setSource(source.name());
        if (lu.isSuperAdmin()) {
            // 超级管理员拥有全部权限
            lu.setPermissions(com.PilotACS.common.constant.PermissionGroups.allCodes().stream().collect(Collectors.toSet()));
        } else {
            List<SysRolePermission> list = rolePermissionMapper.selectList(
                    new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleCode, user.getRoleCode()));
            lu.setPermissions(list.stream().map(SysRolePermission::getPermCode).collect(Collectors.toSet()));
        }
        return lu;
    }

    private void log(String username, String ip, ClientType source, boolean success, String userAgent) {
        SysLoginLog entry = new SysLoginLog();
        entry.setUsername(username);
        entry.setIp(ip);
        entry.setSource(source.name());
        entry.setSuccess(success ? 1 : 0);
        entry.setUserAgent(userAgent);
        entry.setCreatedAt(LocalDateTime.now());
        loginLogMapper.insert(entry);
        // 控制台同步输出登录日志
        Logs.info(Logs.Module.AUTH, "登录" + (success ? "成功" : "失败"),
                "用户", username, "IP", ip, "标识", source.getLabel());
    }
}
