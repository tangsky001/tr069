/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.536
 *
 */

package com.PilotACS.common.utils;

import jakarta.servlet.http.HttpServletRequest;

// 客户端 IP 提取
public final class IpUtil {

    private IpUtil() {
    }

    public static String getClientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String h : headers) {
            String ip = request.getHeader(h);
            if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                return ip.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
