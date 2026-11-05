package com.tr069.acs.worklist.tpl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 下发模板的变量清单 */
public final class TemplateVariables {

    // ==================== WiFi ====================
    /** 房间名，用作 SSID */
    public static final String ROOM = "%room%";
    /** WiFi 密码 */
    public static final String WIFI_PWD = "%wifi_pwd%";

    /** 房间号没登记时的兜底值 */
    public static final String DEFAULT_ROOM = "0000";
    /** WiFi 密码没填写时的兜底值 */
    public static final String DEFAULT_WIFI_PWD = "88888888";

    /** 调用方没填时的兜底值，只对「有行业惯例默认值」的变量登记 */
    private static final Map<String, String> DEFAULTS = Map.of(
            ROOM, DEFAULT_ROOM,
            WIFI_PWD, DEFAULT_WIFI_PWD);

    // ==================== SIP 语音 ====================
    /** SIP 号码/账号 */
    public static final String SIP_NUM = "%sip_num%";
    /** SIP 密码 */
    public static final String SIP_PWD = "%sip_pwd%";
    /** SIP 代理/平台域名 */
    public static final String SIP_PROXY = "%sip_proxy%";
    /** SIP 端口 */
    public static final String SIP_PORT = "%sip_port%";
    /** SIP 注册服务器域名 */
    public static final String SIP_REGISTRAR = "%sip_registrar%";
    /** SIP 注册服务器端口 */
    public static final String SIP_REGISTRAR_PORT = "%sip_registrar_port%";

    // ==================== WAN ====================
    /** PPPoE 宽带账号 */
    public static final String PPPOE_USER = "%pppoe_user%";
    /** PPPoE 宽带密码 */
    public static final String PPPOE_PWD = "%pppoe_pwd%";
    /** 静态 WAN IP */
    public static final String WAN_IP = "%wan_ip%";
    /** 静态 WAN 掩码 */
    public static final String WAN_MASK = "%wan_mask%";
    /** 静态 WAN 网关 */
    public static final String WAN_GW = "%wan_gw%";
    /** WAN DNS，多个用逗号分隔 */
    public static final String WAN_DNS = "%wan_dns%";

    // ==================== VLAN ====================
    /** 业务 VLAN ID */
    public static final String VLAN_ID = "%vlan_id%";

    /** 口令类变量。前端渲染时要掩码、审计日志要脱敏 */
    public static final Set<String> SECRETS = Set.of(WIFI_PWD, SIP_PWD, PPPOE_PWD);

    private TemplateVariables() {
    }

    /** 是否为口令类变量 */
    public static boolean isSecret(String variable) {
        return variable != null && SECRETS.contains(variable);
    }

    /** 某个业务类型需要填写的变量 */
    public static List<Var> forCategory(String category) {
        if (category == null) {
            return List.of();
        }
        return switch (category.toUpperCase(java.util.Locale.ROOT)) {
            case "WIFI_24G", "WIFI_58G" -> List.of(
                    var(ROOM, "房间名", false, "8301", DEFAULT_ROOM),
                    var(WIFI_PWD, "WiFi 密码", true, "", DEFAULT_WIFI_PWD));
            case "SIP" -> List.of(
                    var(SIP_NUM, "SIP 号码", false, "02112345678", null),
                    var(SIP_PWD, "SIP 密码", true, "", null),
                    var(SIP_PROXY, "SIP 代理域名", false, "sip.example.com", null),
                    var(SIP_PORT, "SIP 端口", false, "5060", null),
                    var(SIP_REGISTRAR, "SIP 注册服务器", false, "sip.example.com", null),
                    var(SIP_REGISTRAR_PORT, "SIP 注册服务器端口", false, "5060", null));
            case "WAN_PPPOE" -> List.of(
                    var(PPPOE_USER, "宽带账号", false, "user123", null),
                    var(PPPOE_PWD, "宽带密码", true, "", null));
            case "WAN_STATIC" -> List.of(
                    var(WAN_IP, "IP 地址", false, "10.0.0.2", null),
                    var(WAN_MASK, "子网掩码", false, "255.255.255.0", null),
                    var(WAN_GW, "网关", false, "10.0.0.1", null),
                    var(WAN_DNS, "DNS（逗号分隔）", false, "223.5.5.5,114.114.114.114", null));
            case "WAN_DHCP", "WAN_BRIDGE" -> List.of(
                    var(VLAN_ID, "VLAN ID", false, "41", null));
            default -> List.of();
        };
    }

    /** 把调用方没填的变量补上兜底值，单台与批量下发都走这里，规则一致 */
    public static Map<String, Object> applyDefaults(String category, Map<String, Object> values,
                                                    String roomNo) {
        Map<String, Object> filled = values == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
        // 房间号是通用变量，不只 WiFi 用：SIP 账号常按房间号开，配置模板上就写 %room%，
        // 而 SIP 的变量清单里并没有它。按业务类型补的话，SIP 会把字面量 %room% 发给设备。
        // 所以房间号不按业务区分，一律先兜住：优先设备登记的 roomNo，没有才是 0000。
        if (!hasValue(filled, ROOM)) {
            String room = roomNo != null && !roomNo.isBlank() ? roomNo.trim() : DEFAULT_ROOM;
            filled.put(ROOM, room);
        }
        for (Var v : forCategory(category)) {
            if (hasValue(filled, v.name())) {
                continue;
            }
            String fallback = DEFAULTS.get(v.name());
            if (fallback != null && !fallback.isBlank()) {
                filled.put(v.name(), fallback);
            }
        }
        return filled;
    }

    /** 变量是否已有值：键带不带百分号都认，空串视为没填 */
    private static boolean hasValue(Map<String, Object> values, String variable) {
        Object raw = values.get(variable);
        if (raw == null) {
            raw = values.get(variable.replace("%", ""));
        }
        return raw != null && !String.valueOf(raw).isBlank();
    }

    /** 把变量值替换进模板串。没有对应变量时保留原样（不去也可以，便于排查漏填） */
    public static String resolve(String template, Map<String, String> values) {
        if (template == null || template.indexOf('%') < 0 || values == null) {
            return template;
        }
        String out = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private static Var var(String name, String label, boolean secret, String example,
                           String defaultValue) {
        return new Var(name, label, secret, example, defaultValue);
    }

    /** 变量描述，供前端表单与文档使用 */
    public record Var(String name, String label, boolean secret, String example, String defaultValue) {
    }
}
