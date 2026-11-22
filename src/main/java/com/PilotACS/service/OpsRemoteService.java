/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.296
 *
 */

package com.PilotACS.service;

import com.PilotACS.common.constant.AuthConstant;
import com.PilotACS.common.enums.ClientType;
import com.PilotACS.common.exception.BusinessException;
import com.PilotACS.common.log.Logs;
import com.PilotACS.common.security.JwtUtil;
import com.PilotACS.common.security.LoginUser;
import com.PilotACS.dto.OpsRemoteView;
import com.PilotACS.entity.OpsRemoteConfig;
import com.PilotACS.entity.SysUser;
import com.PilotACS.mapper.OpsRemoteConfigMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 装维 APP 远程运维配置服务 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsRemoteService {

    /** 可勾选的操作权限项 */
    public static final List<String> PERMISSIONS = List.of("ticket", "config", "reboot");

    /** 可选的设备可见范围 */
    public static final List<Map<String, String>> DEVICE_SCOPES = List.of(
            scope("all", "显示所有设备列表"),
            scope("online", "仅在线设备"),
            scope("5m", "5分钟内上线"),
            scope("10m", "10分钟内上线"),
            scope("15m", "15分钟内上线"),
            scope("1h", "1小时内上线"),
            scope("2h", "2小时内上线"),
            scope("24h", "24小时内上线"));

    private static final String DOMAIN_SUFFIX = "i-ddns.com";
    private static final int PUBLIC_PORT = 9443;

    private final OpsRemoteConfigMapper mapper;
    private final AuthService authService;
    private final SysUserService userService;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    /** 隧道客户端（延迟获取避免循环依赖） */
    private final org.springframework.beans.factory.ObjectProvider<TunnelClient> tunnelClient;

    /** 内网登录地址 */
    @Value("${acs.public-host:127.0.0.1}")
    private String publicHost;

    /** 管理端端口 */
    @Value("${server.port:8888}")
    private int serverPort;

    /** 二维码 Token 有效期（天） */
    @Value("${acs.itms-token-days:30}")
    private int itmsTokenDays;

    private final SecureRandom random = new SecureRandom();

    private static Map<String, String> scope(String value, String label) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("value", value);
        map.put("label", label);
        return map;
    }

    /** 读取配置，没有则初始化默认配置 */
    public OpsRemoteConfig current() {
        OpsRemoteConfig config = mapper.selectOne(Wrappers.<OpsRemoteConfig>lambdaQuery()
                .orderByAsc(OpsRemoteConfig::getId)
                .last("LIMIT 1"));
        if (config == null) {
            config = new OpsRemoteConfig();
            config.setToken("");
            config.setPermissions(String.join(",", PERMISSIONS));
            config.setDeviceScope("all");
            config.setInstanceId(UUID.randomUUID().toString());
            config.setTokenCreatedAt(LocalDateTime.now());
            config.setCreatedAt(LocalDateTime.now());
            config.setUpdatedAt(LocalDateTime.now());
            mapper.insert(config);
        }
        return config;
    }

    /** 组装前端渲染用的完整视图 */
    public OpsRemoteView view() {
        OpsRemoteConfig config = current();
        if (!tokenValid(config.getToken())) {
            config.setToken(createItmsToken(config.getToken()));
            config.setTokenCreatedAt(LocalDateTime.now());
            config.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(config);
        }
        OpsRemoteView view = new OpsRemoteView();
        view.setToken(config.getToken());
        view.setTokenCreatedAt(config.getTokenCreatedAt() == null ? null
                : config.getTokenCreatedAt().withNano(0).toString().replace('T', ' '));
        view.setPermissions(splitPermissions(config.getPermissions()));
        view.setDeviceScope(config.getDeviceScope() == null ? "all" : config.getDeviceScope());
        view.setSubdomain(config.getSubdomain());
        view.setRemoteEnabled(config.getRemoteEnabled() != null && config.getRemoteEnabled() == 1);
        view.setPermissionOptions(permissionOptions());
        view.setDeviceScopeOptions(DEVICE_SCOPES);
        view.setLanQrContent(loginUrl(lanBaseUrl(), config.getToken()));
        view.setCloudQrContent(loginUrl(cloudBaseUrl(config.getSubdomain()), config.getToken()));
        view.setApkQrContent("https://acs.example.com/download/app.apk");
        return view;
    }

    /** 保存设置 */
    public OpsRemoteView save(List<String> permissions, String deviceScope) {
        OpsRemoteConfig config = current();
        if (permissions != null) {
            List<String> valid = new ArrayList<>();
            for (String p : permissions) {
                if (p != null && PERMISSIONS.contains(p.trim()) && !valid.contains(p.trim())) {
                    valid.add(p.trim());
                }
            }
            config.setPermissions(String.join(",", valid));
        }
        if (deviceScope != null) {
            boolean known = DEVICE_SCOPES.stream().anyMatch(s -> deviceScope.equals(s.get("value")));
            if (known) {
                config.setDeviceScope(deviceScope);
            }
        }
        config.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(config);
        return view();
    }

    /** 重新生成 Token，旧 Token 立即失效 */
    public OpsRemoteView regenerateToken() {
        OpsRemoteConfig config = current();
        config.setToken(createItmsToken(config.getToken()));
        config.setTokenCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(config);
        Logs.info(Logs.Module.OPS, "Token 已重新生成（绑定 itms 账号）");
        return view();
    }

    /** 判断 Token 是否有效 */
    private boolean tokenValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            String jti = jwtUtil.parse(token).getId();
            return redisTemplate.opsForValue().get(AuthConstant.TOKEN_KEY_PREFIX + jti) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 以 itms 账号签发长有效期令牌 */
    private String createItmsToken(String oldToken) {
        SysUser user = userService.lambdaQuery().eq(SysUser::getUsername, AuthConstant.ITMS_USERNAME).one();
        if (user == null) {
            throw new BusinessException("ITMS 账号不存在，请先执行初始化 SQL");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException("ITMS 账号已禁用，无法生成二维码令牌");
        }
        LoginUser lu = authService.buildLoginUser(user, ClientType.MOBILE);
        String token = jwtUtil.create(user.getId(), user.getUsername(), Map.of(), itmsTokenDays * 24L);
        try {
            String jti = jwtUtil.parse(token).getId();
            if (oldToken != null && !oldToken.isBlank()) {
                try {
                    redisTemplate.delete(AuthConstant.TOKEN_KEY_PREFIX + jwtUtil.parse(oldToken).getId());
                } catch (Exception ignored) {
                }
            }
            redisTemplate.opsForValue().set(AuthConstant.TOKEN_KEY_PREFIX + jti,
                    objectMapper.writeValueAsString(lu), Duration.ofHours(itmsTokenDays * 24L));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("二维码令牌生成失败：" + e.getMessage());
        }
        return token;
    }

    /** 生成随机子域名 */
    public String newSubdomain() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /** 分配子域名，没有则生成并落库 */
    public String ensureSubdomain() {
        OpsRemoteConfig config = current();
        if (config.getSubdomain() != null && !config.getSubdomain().isBlank()) {
            return config.getSubdomain();
        }
        config.setSubdomain(newSubdomain());
        config.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(config);
        Logs.info(Logs.Module.OPS, "子域名已分配", "域名", config.getSubdomain() + ".i-ddns.com");
        return config.getSubdomain();
    }

    /** 重新生成子域名 */
    public String regenerateSubdomain() {
        OpsRemoteConfig config = current();
        config.setSubdomain(newSubdomain());
        config.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(config);
        Logs.info(Logs.Module.OPS, "子域名已重新生成", "域名", config.getSubdomain() + ".i-ddns.com");
        return config.getSubdomain();
    }

    /** 确保有实例 ID，没有则生成 */
    public String ensureInstanceId() {
        OpsRemoteConfig config = current();
        if (config.getInstanceId() != null && !config.getInstanceId().isBlank()) {
            return config.getInstanceId();
        }
        config.setInstanceId(UUID.randomUUID().toString());
        config.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(config);
        Logs.info(Logs.Module.OPS, "实例 ID 已生成", "实例", config.getInstanceId());
        return config.getInstanceId();
    }

    /** 组装内网登录地址 */
    private String lanBaseUrl() {
        String host = publicHost == null || publicHost.isBlank() ? "127.0.0.1" : publicHost.trim();
        if (host.contains("://")) {
            return host;
        }
        return "http://" + host + ":" + serverPort;
    }

    /** 组装外网穿透地址 */
    private String cloudBaseUrl(String subdomain) {
        if (subdomain == null || subdomain.isBlank()) {
            return "https://acs.example.com";
        }
        TunnelClient tunnel = tunnelClient.getIfAvailable();
        String suffix = tunnel != null ? tunnel.currentDomainSuffix() : DOMAIN_SUFFIX;
        return "http://" + subdomain.trim() + "." + suffix + ":" + PUBLIC_PORT;
    }

    /** 组装扫码登录链接 */
    private String loginUrl(String base, String token) {
        String root = base == null || base.isBlank() ? "" : base.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root + "/app/login?token=" + (token == null ? "" : token);
    }

    /** 拆分已勾选的权限项 */
    private static List<String> splitPermissions(String permissions) {
        List<String> list = new ArrayList<>();
        if (permissions == null || permissions.isBlank()) {
            return list;
        }
        for (String p : permissions.split(",")) {
            String t = p.trim();
            if (!t.isEmpty() && PERMISSIONS.contains(t) && !list.contains(t)) {
                list.add(t);
            }
        }
        return list;
    }

    /** 组装可选权限项 */
    private static List<Map<String, String>> permissionOptions() {
        List<Map<String, String>> list = new ArrayList<>();
        list.add(option("ticket", "录入工单"));
        list.add(option("config", "修改/下发配置"));
        list.add(option("reboot", "重启/复位"));
        return list;
    }

    private static Map<String, String> option(String value, String label) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("value", value);
        map.put("label", label);
        return map;
    }
}
