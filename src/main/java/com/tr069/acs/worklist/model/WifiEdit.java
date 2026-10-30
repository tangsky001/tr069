package com.tr069.acs.worklist.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WiFi 编辑入参：业务字段，不是 CWMP 参数。
 *
 * <p>页面提交的是「改哪个 SSID、改成什么名、用什么加密、口令多少、开不开」，
 * 而下发引擎认的是意图键（{@code wpa_mode} / {@code wpa_key} / {@code enable}）
 * 加业务类型（{@code WIFI_24G} / {@code WIFI_58G}）。这层翻译原先写在页面上，
 * 两个后果都很难查：键名写错时下发静默跳过、设备上什么都没变；
 * 频段选错业务类型则整单开到另一个 SSID 上，2.4G 的改动被写到 5.8G 那条。</p>
 */
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

    /**
     * 频段 → 工单业务类型。
     *
     * <p>只有 2.4G 与 5.8G 两种：5.8G 是叠加在 2.4G 之上的，不存在「只有 5.8G」
     * 的设备。认不出来的按 2.4G 处理，宁可写到 2.4G 那条，也不往可能不存在的
     * 5.8G 实例上写。</p>
     */
    public String toCategory() {
        return "5.8G".equals(band) ? "WIFI_58G" : "WIFI_24G";
    }

    /** 业务字段 → 下发意图键。 */
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
