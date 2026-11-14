package com.tr069.acs.worklist.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** WiFi 编辑入参：业务字段，不是 CWMP 参数 */
@Data
public class WifiEdit {

    /** 目标 SSID 实例号 */
    private String instance;

    private String ssid;

    /** 加密方式，CWMP 取值如 {@code AESEncryption} */
    private String encryption;

    private String password;

    private boolean enabled;

    /** 频段：2.4G / 5.8G */
    private String band;

    /** 频段 → 工单业务类型 */
    public String toCategory() {
        return "5.8G".equals(band) ? "WIFI_58G" : "WIFI_24G";
    }

    /** 业务字段 → 下发意图键 */
    public Map<String, String> toValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("instance", text(instance));
        values.put("ssid", text(ssid));
        values.put("wpa_mode", text(encryption));
        values.put("wpa_key", text(password));
        values.put("enable", enabled ? "true" : "false");
        return values;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
