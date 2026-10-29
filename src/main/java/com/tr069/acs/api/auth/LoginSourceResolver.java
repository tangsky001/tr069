package com.tr069.acs.api.auth;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 识别登录来源：网页端 / 客户端 / 移动端。
 *
 * <p>先看请求头 {@code X-Client-Type}——自己的客户端与 App 会带它，比猜 UA 准；
 * 没有就按 User-Agent 判：带移动设备字样的是移动端，
 * 非浏览器的（curl、okhttp 这类）是客户端，剩下的是网页端。</p>
 *
 * <p>它只是个标签，用来知道账号是从哪儿登进来的，不做限制——
 * 同一个运维今天用网页、明天用 App，来源不同但干的活一样。</p>
 */
@Component
public class LoginSourceResolver {

    /** 网页端。 */
    public static final String WEB = "WEB";

    /** 客户端：脚本、接口调用这类非浏览器来源。 */
    public static final String CLIENT = "CLIENT";

    /** 移动端：手机、平板的浏览器或 App。 */
    public static final String MOBILE = "MOBILE";

    private static final String HEADER = "X-Client-Type";

    private static final String[] MOBILE_HINTS = {
            "android", "iphone", "ipad", "ipod", "mobile", "harmony"};

    /** 不是浏览器却会发 HTTP 的常见工具与库。 */
    private static final String[] CLIENT_HINTS = {
            "curl", "wget", "okhttp", "python-requests", "java/", "postman",
            "apache-httpclient", "go-http-client", "axios"};

    /**
     * 判定来源，认不出就按网页端——管理端本来就是网页，这是绝大多数情况。
     */
    public String resolve(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null) {
            return WEB;
        }
        String declared = request.getHeader(HEADER);
        String byHeader = normalize(declared);
        if (byHeader != null) {
            return byHeader;
        }
        String ua = request.getHeader("User-Agent");
        if (ua == null || ua.isBlank()) {
            return CLIENT;
        }
        String lower = ua.toLowerCase(Locale.ROOT);
        for (String hint : MOBILE_HINTS) {
            if (lower.contains(hint)) {
                return MOBILE;
            }
        }
        for (String hint : CLIENT_HINTS) {
            if (lower.contains(hint)) {
                return CLIENT;
            }
        }
        return WEB;
    }

    /** 头里写的值认 web / client / mobile，其它当没写。 */
    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "web" -> WEB;
            case "client" -> CLIENT;
            case "mobile" -> MOBILE;
            default -> null;
        };
    }
}
