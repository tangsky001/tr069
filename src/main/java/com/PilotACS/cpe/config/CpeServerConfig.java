/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.config;

import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;

/** CPE 接入配置与主端口 CWMP 路径守卫 */
@Configuration
@RequiredArgsConstructor
public class CpeServerConfig {

    private final CpeProperties props;

    /** 注册 CPE 路径守卫过滤器 */
    @Bean
    public FilterRegistrationBean<CwmpPathGuard> cwmpPathGuard() {
        List<String> paths = props.getPaths().stream().map(CpeServerConfig::normalize).toList();
        FilterRegistrationBean<CwmpPathGuard> reg = new FilterRegistrationBean<>(
                new CwmpPathGuard(paths));
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setName("cwmpPathGuard");
        return reg;
    }

    /** 路径规范化 */
    public static String normalize(String p) {
        String path = (p == null || p.isBlank()) ? "/acs" : p.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** 主端口 CWMP 路径守卫 */
    @RequiredArgsConstructor
    public static class CwmpPathGuard implements jakarta.servlet.Filter {

        private final List<String> cpePaths;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response,
                             jakarta.servlet.FilterChain chain)
                throws java.io.IOException, jakarta.servlet.ServletException {
            String uri = ((jakarta.servlet.http.HttpServletRequest) request).getRequestURI();
            boolean isCpePath = cpePaths.stream().anyMatch(p -> uri.equals(p) || uri.startsWith(p + "/"));
            if (isCpePath) {
                Logs.warn(Logs.Module.CPE, "主端口拒绝 CWMP 路径（设备 ACS URL 不应指向管理端端口）",
                        "URI", uri);
                ((jakarta.servlet.http.HttpServletResponse) response).sendError(404);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
