package com.tr069.acs.openapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 北向接口的端口与路径隔离。
 *
 * <p>追加 Tomcat 连接器后，新增端口与管理端口共用同一套 DispatcherServlet：
 * 8899 上也能访问 {@code /api/**}，8888 上也能访问 {@code /openapi/v1/**}。
 * 这里双向收口：</p>
 * <ul>
 *   <li>北向端口上只允许 {@code /openapi/v1/**} 出现；</li>
 *   <li>其余端口（管理端、设备上报口）一概不提供北向路径。</li>
 * </ul>
 *
 * <p>设备上报口由 {@code CpePathDispatchFilter} 单独处理，本过滤器顺序排在它之前，
 * 两者互不干扰。</p>
 */
@Slf4j
public class OpenApiIsolationFilter extends OncePerRequestFilter {

    private final int openApiPort;

    private final String pathPrefix;

    public OpenApiIsolationFilter(int openApiPort, String pathPrefix) {
        this.openApiPort = openApiPort;
        this.pathPrefix = pathPrefix;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (openApiPort <= 0) {
            chain.doFilter(request, response);
            return;
        }
        boolean northPort = request.getLocalPort() == openApiPort;
        boolean openApiPath = isOpenApiPath(request.getRequestURI());
        if (northPort != openApiPath) {
            // 北向端口上问别的路径，或者别的端口上问北向路径：都不存在，按 404 处理
            if (log.isDebugEnabled()) {
                log.debug("拒绝跨端口访问: port={}, uri={}", request.getLocalPort(),
                        request.getRequestURI());
            }
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":404,\"message\":\"Not Found\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isOpenApiPath(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.equals(pathPrefix) || uri.startsWith(pathPrefix + "/")
                || uri.startsWith(pathPrefix);
    }
}
