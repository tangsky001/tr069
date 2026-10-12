package com.tr069.acs.worklist.model;

import java.util.Locale;

/**
 * 配置模板（工单模板）的业务类型。
 *
 * <p>对应管理端「配置模板」页面的分类按钮：新增模板时按类型创建，列表按类型筛选。
 * 存量模板没有显式类型，由 {@link #inferFromName(String)} 按模板名推断，
 * 因此在文件里补一个 {@code category} 属性即可覆盖推断结果。</p>
 */
public enum TemplateCategory {

    /** 局域网：LAN 口 IP / DHCP Server 地址池等。 */
    LAN("LAN", "LAN局域网"),
    /** WAN 连接（路由模式）。 */
    WAN_ROUTE("WAN_ROUTE", "WAN路由"),
    /** WAN 静态地址。 */
    WAN_STATIC("WAN_STATIC", "WAN固定IP"),
    /** WAN 动态地址。 */
    WAN_DHCP("WAN_DHCP", "WAN DHCP"),
    /** WAN 拨号。 */
    WAN_PPPOE("WAN_PPPOE", "WAN PPPoE"),
    /** 语音。 */
    SIP("SIP", "SIP语音"),
    /** 2.4G 无线。 */
    WIFI_24G("WIFI_24G", "2.4G WiFi"),
    /** 5.8G 无线。 */
    WIFI_58G("WIFI_58G", "5.8G WiFi"),
    /** 固件升级。 */
    FIRMWARE("FIRMWARE", "固件升级"),
    /** 其他（重启、恢复出厂等）。 */
    OTHER("OTHER", "其他"),
    /** 自定义。 */
    CUSTOM("CUSTOM", "自定义");

    private final String code;
    private final String label;

    TemplateCategory(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 按 code 解析，未识别时返回 {@link #OTHER}。
     */
    public static TemplateCategory of(String code) {
        if (code == null || code.isBlank()) {
            return OTHER;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (TemplateCategory category : values()) {
            if (category.code.equals(normalized) || category.name().equals(normalized)) {
                return category;
            }
        }
        return OTHER;
    }

    /**
     * 按模板名推断类型。
     *
     * <p>存量模板名形如 {@code WAN_DHCP}、{@code WLAN_ADD}、{@code SIP_ADD}，
     * 关键字匹配即可覆盖绝大多数；匹配不上归为 {@link #OTHER}，现场可在文件里显式补 category。</p>
     */
    public static TemplateCategory inferFromName(String name) {
        String n = name == null ? "" : name.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
        if (n.isBlank()) {
            return OTHER;
        }
        // 无线要在 LAN 之前判断，否则 WLAN 会被 LAN 命中
        if (n.contains("WLAN") || n.contains("WIFI") || n.contains("SSID") || n.contains("WIRELESS")) {
            return is5g(n) ? WIFI_58G : WIFI_24G;
        }
        if (n.contains("SIP") || n.contains("VOICE") || n.contains("VOIP") || n.contains("IMS")) {
            return SIP;
        }
        if (n.contains("PPPOE") || n.contains("PPPOE_") || n.contains("DIAL")) {
            return WAN_PPPOE;
        }
        if (n.contains("DHCP") && n.contains("WAN")) {
            return WAN_DHCP;
        }
        if (n.contains("STATIC") || (n.contains("WAN") && n.contains("IP"))) {
            return WAN_STATIC;
        }
        if (n.contains("FIRMWARE") || n.contains("UPGRADE") || n.contains("DOWNLOAD")) {
            return FIRMWARE;
        }
        if (n.contains("LAN")) {
            return LAN;
        }
        if (n.contains("WAN")) {
            return WAN_ROUTE;
        }
        return OTHER;
    }

    private static boolean is5g(String n) {
        return n.contains("5G") || n.contains("5_8") || n.contains("58G") || n.contains("_5") || n.endsWith("5");
    }
}
