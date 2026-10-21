package com.tr069.acs.common.web;

import com.tr069.acs.acs.controller.CwmpController;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CPE 上报路径兼容分发。
 *
 * <p>各厂商光猫出厂固化的 ACS URL 路径并不统一：中国电信常见 {@code /ACS-server/ACS}，
 * 华为/中兴部分批次直接用根路径 {@code /}，还有 {@code /acs}、{@code /cwmp}、{@code /tr069} 等写法。
 * 控制器只映射了其中一两个，设备一旦写了别的路径，Spring 会返回
 * {@code 405 Request method 'POST' is not supported}——因为路径命中了只接受 GET 的静态资源处理器。
 * 设备侧看不懂 405，只会按周期重试，现场表现为「设备一直在发，ACS 一条 Inform 都收不到」。</p>
 *
 * <p>这里在请求进入 MVC 之前把已知的上报路径直接交给 {@link CwmpController} 处理，
 * 并额外做两件事：</p>
 * <ul>
 *   <li>路径清单可通过 {@code acs.cwmp.extra-cpe-paths} 扩展，现场不必改代码；</li>
 *   <li>遇到清单外的 POST 请求打印 WARN（含端口、UA、Content-Type），
 *       一眼就能看出光猫实际用的是哪个路径，把它加进配置即可。</li>
 * </ul>
 *
 * <p>这里不用 {@code forward} 转发：CPE 的 digest 认证把 {@code request.getRequestURI()}
 * 算进了响应摘要，转发会改写 URI，设备侧校验必然失败退回 401。直接调用控制器可保留原始 URI。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class CpePathDispatchFilter implements Filter {

    /** 同一路径的告警间隔，避免设备周期重试把日志刷满。 */
    private static final long WARN_INTERVAL_MS = 60_000L;

    private final CwmpController cwmpController;

    @Value("${acs.cwmp.cpe-path:/ACS-server/ACS}")
    private String cpePath;

    /**
     * 额外兼容的上报路径，逗号 / 分号 / 空格分隔。
     * 默认值覆盖现场见过的几种写法，含根路径 {@code /}。
     */
    @Value("${acs.cwmp.extra-cpe-paths:/acs,/ACS,/cwmp,/CWMP,/tr069,/TR069,/CPE,/cpe,/}")
    private String extraCpePaths;

    private final Set<String> cpePaths = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, Long> lastWarnAt = new ConcurrentHashMap<>();

    @PostConstruct
    public void initPaths() {
        cpePaths.add(normalize(cpePath));
        for (String part : extraCpePaths.split("[,;\\s]+")) {
            if (!part.isBlank()) {
                cpePaths.add(normalize(part));
            }
        }
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String path = normalize(pathWithoutContext(request));
        if (cpePaths.contains(path)) {
            cwmpController.handleCpe(request, response);
            return;
        }

        if (!path.startsWith("/api")) {
            warnUnknownPath(request, path);
        }
        chain.doFilter(request, response);
    }

    /**
     * 清单外的 POST 大多是光猫写错了上报路径，打印出来便于现场直接加进配置。
     */
    private void warnUnknownPath(HttpServletRequest request, String path) {
        String key = request.getLocalPort() + path;
        long now = System.currentTimeMillis();
        Long last = lastWarnAt.get(key);
        if (last != null && now - last < WARN_INTERVAL_MS) {
            return;
        }
        lastWarnAt.put(key, now);
        log.warn("收到未匹配的上报请求：端口 {} 路径 {} UA=[{}] Content-Type=[{}]。"
                        + "若这是光猫上报，请把该路径加入 acs.cwmp.cpe-path 或 acs.cwmp.extra-cpe-paths，"
                        + "否则设备会一直重试且 ACS 收不到 Inform",
                request.getLocalPort(), request.getRequestURI(),
                request.getHeader("User-Agent"), request.getContentType());
    }

    /**
     * 去掉 context-path 后的请求路径。
     */
    private String pathWithoutContext(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            return "/";
        }
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri.isEmpty() ? "/" : uri;
    }

    /**
     * 归一化为小写、无末尾斜杠的形式，路径匹配不区分大小写。
     */
    private String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String value = path.trim();
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.toLowerCase();
    }
}
