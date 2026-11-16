package com.PilotACS.cpe.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备版本归属：运营商定制版 + 厂商通用版，用短码区分，并识别各自私有节点路径前缀。
 */
public enum IspVariant {

    /** 移动定制 */
    CM("CM", "移动", "X_CM-", "X_CMCC_"),
    /** 电信定制 */
    CT("CT", "电信", "X_CT-COM_"),
    /** 联通定制 */
    CU("CU", "联通", "X_CU_", "X_CU-COM_"),
    /** 广电定制 */
    CNB("CNB", "广电", "X_CBN_", "X_CN_", "X_GD-"),
    /** 华为通用版 */
    HW("HW", "华为通用", "X_HW_", "Huawei."),
    /** 中兴通用版 */
    ZX("ZX", "中兴通用", "X_ZTE-COM_", "X_ZTE_", "ZTE."),
    /** 标准/通用版，无私有节点 */
    COMMON("COMMON", "通用/标准"),
    /** ONU ISP 版 */
    ONUISP("ONUISP", "ONU ISP");

    /** 旧编码/别名 → 归属，用于兼容历史数据和前端旧取值 */
    private static final Map<String, IspVariant> LOOKUP = build();

    private final String code;
    private final String label;
    private final List<String> prefixes;

    IspVariant(String code, String label, String... prefixes) {
        this.code = code;
        this.label = label;
        this.prefixes = List.of(prefixes);
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 私有节点路径前缀（TR-069 扩展节点，如 InternetGatewayDevice.X_CT-COM_*） */
    public List<String> prefixes() {
        return prefixes;
    }

    /** 路径是否属于本归属的私有节点 */
    public boolean isPrivateNode(String path) {
        if (path == null || prefixes.isEmpty()) {
            return false;
        }
        String upper = path.toUpperCase();
        return prefixes.stream().anyMatch(p -> upper.contains(p.toUpperCase()));
    }

    /** 短码/别名 → 归属；不认识返回 null */
    public static IspVariant of(String raw) {
        if (raw == null || raw.isBlank()) {
            return COMMON;
        }
        return LOOKUP.get(raw.trim().toUpperCase());
    }

    /** 短码/别名 → 归属短码；不认识原样返回（新入库统一走这里） */
    public static String canonical(String raw) {
        IspVariant v = of(raw);
        return v == null ? (raw == null ? null : raw.trim()) : v.code;
    }

    /** 数据库匹配用候选码：原始值在前，短码和别名跟后（兼容历史模板行） */
    public static List<String> candidateCodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String trim = raw.trim();
        List<String> out = new ArrayList<>();
        out.add(trim);
        IspVariant v = of(trim);
        if (v != null && !out.contains(v.code)) {
            out.add(v.code);
        }
        for (Map.Entry<String, IspVariant> e : LOOKUP.entrySet()) {
            if (e.getValue() == v && !out.contains(e.getKey())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** 按参数路径识别归属；无私有前缀返回 null（标准节点） */
    public static IspVariant ofPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        IspVariant best = null;
        int bestLen = 0;
        for (IspVariant v : values()) {
            for (String p : v.prefixes) {
                String upper = p.toUpperCase();
                if (path.toUpperCase().contains(upper) && upper.length() > bestLen) {
                    best = v;
                    bestLen = upper.length();
                }
            }
        }
        return best;
    }

    private static Map<String, IspVariant> build() {
        Map<String, IspVariant> m = new LinkedHashMap<>();
        put(m, CM, "CM", "CMCC", "MOBILE", "YD");
        put(m, CT, "CT", "TELECOM", "DX");
        put(m, CU, "CU", "UNICOM", "LT");
        put(m, CNB, "CNB", "CBN", "GD", "BROADCAST");
        put(m, HW, "HW", "HUAWEI");
        put(m, ZX, "ZX", "ZTE");
        put(m, COMMON, "COMMON", "STANDARD", "GENERIC", "GENERAL");
        put(m, ONUISP, "ONUISP", "ONU_ISP", "ONU-ISP");
        return m;
    }

    private static void put(Map<String, IspVariant> m, IspVariant v, String... keys) {
        for (String k : keys) {
            m.put(k.toUpperCase(), v);
        }
    }
}
