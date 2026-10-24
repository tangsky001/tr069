package com.tr069.acs.olt.telnet;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * OLT 命令行字符集。
 *
 * <p>国产 OLT 的中文回显多数是 GBK，部分新版本是 UTF-8。页面上的「自动」表示
 * 先用 UTF-8 连，回显里出现替换符（说明解码失败）时再用 GBK 重连一次。</p>
 */
public final class OltCharset {

    /** 自动识别 */
    public static final String AUTO = "AUTO";

    private OltCharset() {
    }

    /**
     * 解析配置值，非法值回落 UTF-8。
     */
    public static Charset resolve(String value) {
        if (value == null || value.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        String key = value.trim().toUpperCase(Locale.ROOT);
        switch (key) {
            case "GBK":
            case "GB2312":
            case "CP936":
                return Charset.forName("GBK");
            case "GB18030":
                return Charset.forName("GB18030");
            case "UTF8":
            case "UTF-8":
                return StandardCharsets.UTF_8;
            case AUTO:
            default:
                return StandardCharsets.UTF_8;
        }
    }

    /**
     * 是否为「自动」。
     */
    public static boolean isAuto(String value) {
        return value == null || value.isBlank() || AUTO.equalsIgnoreCase(value.trim());
    }

    /**
     * 自动识别失败时的兜底字符集。
     */
    public static Charset fallback() {
        return Charset.forName("GBK");
    }
}
