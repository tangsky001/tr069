package com.tr069.acs.worklist.tpl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 下发模板的变量清单。
 *
 * <p>存在意义：模板库拷到别的环境里也要能用，凡是「按房间/局点会变的」或「口令类」的
 * 值都不能写死，一律占位成 {@code %xxx%}，下发时用工单里的实际值替换。
 * 这里集中声明所有变量，避免逐个业务去记导致遗漏——之前就漏过 SIP 的号码与密码。</p>
 *
 * <h3>两类变量</h3>
 * <ul>
 *   <li><b>口令类</b>（在 {@link #SECRETS} 里）：前端要掩码显示，日志不得打印，
 *       库里存的一定是变量名而不是真值</li>
 *   <li><b>普通业务值</b>：房间号、VLAN ID、静态地址等，可以明文传输与展示</li>
 * </ul>
 */
public final class TemplateVariables {

    // ==================== WiFi ====================
    /** 房间名，用作 SSID */
    public static final String ROOM = "%room%";
    /** WiFi 密码 */
    public static final String WIFI_PWD = "%wifi_pwd%";

    /** 房间号没登记时的兜底值。 */
    public static final String DEFAULT_ROOM = "0000";
    /** WiFi 密码没填写时的兜底值。 */
    public static final String DEFAULT_WIFI_PWD = "88888888";

    /**
     * 调用方没填时的兜底值，只对「有行业惯例默认值」的变量登记。
     * SIP 号码、宽带账号这类填不出来就是填不出来，猜一个反而开错业务。
     */
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

    /**
     * 口令类变量。前端渲染时要掩码、审计日志要脱敏。
     */
    public static final Set<String> SECRETS = Set.of(WIFI_PWD, SIP_PWD, PPPOE_PWD);

    private TemplateVariables() {
    }

    /** 是否为口令类变量。 */
    public static boolean isSecret(String variable) {
        return variable != null && SECRETS.contains(variable);
    }

    /**
     * 某个业务类型需要填写的变量。
     *
     * <p>前端下发表单据此渲染输入框，避免每加一个业务就改一遍页面。</p>
     *
     * @param category 业务类型，见 {@code IntentDeliveryService} 的 keys：
     *                 {@code WIFI_24G / WIFI_58G / SIP / WAN_DHCP / WAN_PPPOE /
     *                 WAN_STATIC / WAN_BRIDGE}
     */
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

    /**
     * 把调用方没填的变量补上兜底值，单台与批量下发都走这里，规则一致。
     *
     * <p>房间号优先取设备登记的 {@code roomNo}（运维在设备备注里维护的那个），
     * 设备没登记才用 {@link #DEFAULT_ROOM}；WiFi 密码没填用 {@link #DEFAULT_WIFI_PWD}。
     * 其余变量没有兜底，缺了仍然按错误处理——号码、账号猜出来就是开错业务。</p>
     *
     * <p><b>空串也算没填</b>：前端表单初始化时把每个变量都置成空串提交上来，
     * 只判 null 会把「用户没输」当成「用户要下发空值」，SSID 与密码就被清成空。</p>
     *
     * @param category 业务类型
     * @param values   调用方传的变量值，键可以是 {@code %room%} 或 {@code room}
     * @param roomNo   设备登记的房间号，可为空
     * @return 补过兜底值的新 map，不改动入参
     */
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

    /** 变量是否已有值：键带不带百分号都认，空串视为没填。 */
    private static boolean hasValue(Map<String, Object> values, String variable) {
        Object raw = values.get(variable);
        if (raw == null) {
            raw = values.get(variable.replace("%", ""));
        }
        return raw != null && !String.valueOf(raw).isBlank();
    }

    /**
     * 把变量值替换进模板串。没有对应变量时保留原样（不去也可以，便于排查漏填）。
     */
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

    /**
     * 变量描述，供前端表单与文档使用。
     *
     * @param name         占位符，如 {@code %room%}
     * @param label        中文标签
     * @param secret       是否口令类
     * @param example      示例值，仅用于输入框 placeholder，不参与下发
     * @param defaultValue 没填时的兜底值，null 表示必须人工填写
     */
    public record Var(String name, String label, boolean secret, String example, String defaultValue) {
    }
}
