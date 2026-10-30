package com.tr069.acs.api.auth;

import org.springframework.stereotype.Component;

/**
 * 取客户端 IP。
 *
 * <p>前面挂了反代时 {@code getRemoteAddr()} 拿到的是 nginx 的地址，
 * 记录成 IP 就全是同一个。所以先看反代给的 {@code X-Forwarded-For}，
 * 多级反代时它是一串，最前面那个才是最外层的客户端。</p>
 */
@Component
public class ClientIpResolver {

    /** 反代没配好的时候会拿到这个，直接当未知。 */
    private static final String UNKNOWN = "unknown";

    public String resolve(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (hasText(xff)) {
            // 多级反代：client, proxy1, proxy2
            String first = xff.split(",")[0].trim();
            if (hasText(first)) {
                return first;
            }
        }
        String real = request.getHeader("X-Real-IP");
        if (hasText(real)) {
            return real.trim();
        }
        String remote = request.getRemoteAddr();
        return hasText(remote) ? remote.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank()
                && !UNKNOWN.equalsIgnoreCase(value.trim());
    }
}
