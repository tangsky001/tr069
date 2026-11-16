package com.PilotACS.cpe.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 品牌名转换：厂商英文名/缩写 → 中文品牌名，未登记的原样返回。
 */
public final class BrandName {

    private static final Map<String, String> NAMES = build();

    private BrandName() {
    }

    /** 英文品牌名/缩写 → 中文名（如 SKYW→创维）；不认识原样返回 */
    public static String text(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return NAMES.getOrDefault(key, raw.trim());
    }

    /**
     * 厂商全称 → 中文标签（台账/详情展示用）：
     */
    public static String label(String manufacturer) {
        if (manufacturer == null || manufacturer.isBlank()) {
            return null;
        }
        String key = manufacturer.trim().toUpperCase(Locale.ROOT);
        String hit = NAMES.get(key);
        if (hit != null) {
            return hit;
        }
        for (Map.Entry<String, String> e : NAMES.entrySet()) {
            if (wordContains(key, e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 缩写（HW/ZX/FH/TW/RSC）按词边界匹配，避免 NETWORK 里撞出 TW 这类误配 */
    private static boolean wordContains(String text, String key) {
        if (key.length() <= 2) {
            return Pattern.compile("\\b" + Pattern.quote(key) + "\\b").matcher(text).find();
        }
        return text.contains(key);
    }

    private static Map<String, String> build() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("HUAWEI", "华为");
        m.put("HW", "华为");
        m.put("ZTE", "中兴");
        m.put("ZX", "中兴");
        m.put("ZXDSL", "中兴");
        m.put("SKYW", "创维");
        m.put("SKYWORTH", "创维");
        m.put("FIBERHOME", "烽火");
        m.put("FH", "烽火");
        m.put("TP-LINK", "普联");
        m.put("TPLINK", "普联");
        m.put("MERCURY", "水星");
        m.put("RUIJIE", "锐捷");
        m.put("H3C", "新华三");
        m.put("HISENSE", "海信");
        m.put("SAMSUNG", "三星");
        m.put("T&W", "同维");
        m.put("TW", "同维");
        m.put("GYT", "国盈通");
        m.put("V-SOL", "光为");
        m.put("VSOL", "光为");
        m.put("C-DATA", "酷迪");
        m.put("RAISECOM", "瑞斯康达");
        m.put("RSC", "瑞斯康达");
        m.put("CIOT", "中移");
        return m;
    }
}
