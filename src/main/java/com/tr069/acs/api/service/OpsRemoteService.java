package com.tr069.acs.api.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tr069.acs.api.entity.OpsRemoteConfig;
import com.tr069.acs.api.mapper.OpsRemoteConfigMapper;
import com.tr069.acs.api.model.OpsRemoteView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 装维 APP 远程运维配置服务。
 *
 * <p>当前是占位实现：只负责生成 Token、拼二维码内容、存下权限项和可见范围，
 * 不做真正的登录鉴权。后续接真实数据（运维人员、登录会话、工单）时在这里扩展。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsRemoteService {

    /** 操作权限项：与前端复选框一一对应 */
    public static final List<String> PERMISSIONS = List.of("ticket", "config", "reboot");

    /** 可见设备范围：与前端下拉框一一对应 */
    public static final List<Map<String, String>> DEVICE_SCOPES = List.of(
            scope("all", "显示所有设备列表"),
            scope("online", "仅在线设备"),
            scope("5m", "5分钟内上线"),
            scope("10m", "10分钟内上线"),
            scope("15m", "15分钟内上线"),
            scope("1h", "1小时内上线"),
            scope("2h", "2小时内上线"),
            scope("24h", "24小时内上线"));

    private final OpsRemoteConfigMapper mapper;

    /** 内网登录地址：沿用 acs.public-host，跟下发给设备的连接请求地址保持一致 */
    @Value("${acs.public-host:127.0.0.1}")
    private String publicHost;

    /** 管理端端口，二维码内网登录链接里带它 */
    @Value("${server.port:8080}")
    private int serverPort;

    /** 内网穿透域名后缀（写死，不可配置） */
    private static final String DOMAIN_SUFFIX = "i-ddns.com";

    /** 内网穿透公网端口（写死，不可配置） */
    private static final int PUBLIC_PORT = 9443;

    private final SecureRandom random = new SecureRandom();

    private static Map<String, String> scope(String value, String label) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("value", value);
        map.put("label", label);
        return map;
    }

    /** 读配置，没有就建一条默认的 */
    public OpsRemoteConfig current() {
        OpsRemoteConfig config = mapper.selectOne(Wrappers.<OpsRemoteConfig>lambdaQuery()
                .orderByAsc(OpsRemoteConfig::getId)
                .last("LIMIT 1"));
        if (config == null) {
            config = new OpsRemoteConfig();
            config.setToken(newToken());
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

    /** 前端渲染用的完整视图：配置 + 可选项 + 三个二维码的内容 */
    public OpsRemoteView view() {
        OpsRemoteConfig config = current();
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
        // 内网：public-host + 管理端端口
        view.setLanQrContent(loginUrl(lanBaseUrl(), config.getToken()));
        // 外网云登录：有子域名就用真实穿透地址，没有就占位
        view.setCloudQrContent(loginUrl(cloudBaseUrl(config.getSubdomain()), config.getToken()));
        // APK 下载：占位，后续接真实下载地址再改
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

    /** 重新生成 Token：旧 Token 立刻失效 */
    public OpsRemoteView regenerateToken() {
        OpsRemoteConfig config = current();
        config.setToken(newToken());
        config.setTokenCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(config);
        log.info("远程运维 Token 已重新生成");
        return view();
    }

    /** 内网登录地址：public-host + 管理端端口，public-host 已带协议时直接用 */
    private String lanBaseUrl() {
        String host = publicHost == null || publicHost.isBlank() ? "127.0.0.1" : publicHost.trim();
        if (host.contains("://")) {
            return host;
        }
        return "http://" + host + ":" + serverPort;
    }

    /** 外网穿透地址：有子域名拼 http://{subdomain}.{DOMAIN_SUFFIX}:{PUBLIC_PORT}，没有返回占位（服务器未启用 HTTPS，用明文 HTTP） */
    private String cloudBaseUrl(String subdomain) {
        if (subdomain == null || subdomain.isBlank()) {
            return "https://acs.example.com";
        }
        return "http://" + subdomain.trim() + "." + DOMAIN_SUFFIX + ":" + PUBLIC_PORT;
    }

    /** 生成 10 位随机子域名 [a-z0-9] */
    public String newSubdomain() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /** 分配子域名：有就用，没有就生成一个并存库 */
    public String ensureSubdomain() {
        OpsRemoteConfig config = current();
        if (config.getSubdomain() != null && !config.getSubdomain().isBlank()) {
            return config.getSubdomain();
        }
        config.setSubdomain(newSubdomain());
        config.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(config);
        log.info("远程运维子域名已分配: {}", config.getSubdomain());
        return config.getSubdomain();
    }

    /** 重新生成子域名（冲突时调） */
    public String regenerateSubdomain() {
        OpsRemoteConfig config = current();
        config.setSubdomain(newSubdomain());
        config.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(config);
        log.info("远程运维子域名已重新生成: {}", config.getSubdomain());
        return config.getSubdomain();
    }

    /** 确保有实例 ID：有就用，没有就生成 UUID 存库 */
    public String ensureInstanceId() {
        OpsRemoteConfig config = current();
        if (config.getInstanceId() != null && !config.getInstanceId().isBlank()) {
            return config.getInstanceId();
        }
        config.setInstanceId(UUID.randomUUID().toString());
        config.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(config);
        log.info("ACS 实例 ID 已生成: {}", config.getInstanceId());
        return config.getInstanceId();
    }

    /** 扫码登录链接：地址 + token 查询参数 */
    private String loginUrl(String base, String token) {
        String root = base == null || base.isBlank() ? "" : base.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root + "/app/login?token=" + (token == null ? "" : token);
    }

    /** 生成随机 Token（URL 安全的 base64） */
    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 已勾选的权限项 */
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

    /** 可选权限项 */
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
