/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.config;

import com.PilotACS.cpe.server.CwmpEndpoint;
import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CPE 独立多端口监听：在主 server 端口之外，为 CWMP 上报额外挂一组 Tomcat Connector
 * （9090/8080/8012/6060/5481/9092/6890/9095/7013，不同厂商设备配置的 ACS 端口不同）。
 * <p>CWMP 端点直接启动监听，无开关；端点路径由 acs.cpe.paths 列表决定（设备 ACS URL
 * 多地址判定，每个路径都挂端点）。
 * <p>端口分流由 {@link CpePortFilter} 控制：
 * CPE 端口上的请求全部交给 CWMP 端点（不带路径或用任意别名路径的 ACS URL 统一转发到
 * paths 第一个路径）；主 server 端口只服务管理端 REST API（拒绝 CWMP 路径）。
 */
@Configuration
@RequiredArgsConstructor
public class CpeServerConfig {

    private final CpeProperties props;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> cpeAdditionalPortCustomizer() {
        return factory -> {
            for (int port : props.getPorts()) {
                if (port == factory.getPort()) {
                    // CPE 不使用主 server 端口，跳过防冲突
                    continue;
                }
                Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
                connector.setPort(port);
                factory.addAdditionalTomcatConnectors(connector);
            }
            Logs.info(Logs.Module.CPE, "CPE 监听端口就绪", "端口", props.getPorts(),
                    "端点路径", props.getPaths());
        };
    }

    /** CWMP 端点挂到配置的每个路径上（设备 ACS URL 多地址判定） */
    @Bean
    public SimpleUrlHandlerMapping cwmpHandlerMapping(CwmpEndpoint endpoint) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String p : props.getPaths()) {
            map.put(normalize(p), endpoint);
        }
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping(map, Ordered.HIGHEST_PRECEDENCE + 1);
        mapping.setAlwaysUseFullPath(true);
        return mapping;
    }

    @Bean
    public FilterRegistrationBean<CpePortFilter> cpePortFilter() {
        List<String> paths = props.getPaths().stream().map(CpeServerConfig::normalize).toList();
        FilterRegistrationBean<CpePortFilter> reg = new FilterRegistrationBean<>(
                new CpePortFilter(Set.copyOf(props.getPorts()), paths));
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setName("cpePortFilter");
        return reg;
    }

    /** 路径规范化：保证以 / 开头、不以 / 结尾，空值回落 /acs */
    private static String normalize(String p) {
        String path = (p == null || p.isBlank()) ? "/acs" : p.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** 按 request.getLocalPort() 分流（见类注释） */
    @RequiredArgsConstructor
    public static class CpePortFilter implements jakarta.servlet.Filter {

        private final Set<Integer> cpePorts;
        private final List<String> cpePaths;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response,
                             jakarta.servlet.FilterChain chain)
                throws java.io.IOException, jakarta.servlet.ServletException {
            int localPort = request.getLocalPort();
            String uri = ((jakarta.servlet.http.HttpServletRequest) request).getRequestURI();
            boolean onCpePort = cpePorts.contains(localPort);

            if (onCpePort) {
                // CPE 端口上的所有请求都归 CWMP：命中配置路径直接放行到端点；
                // 设备 ACS URL 常不带路径（如 http://host:9090/?sn=xxx）或用任意别名路径，
                // 统一转发到 paths 第一个路径，保证任意厂商配置都能建会话
                if (cpePaths.contains(uri)) {
                    chain.doFilter(request, response);
                    return;
                }
                request.getRequestDispatcher(cpePaths.get(0)).forward(request, response);
                return;
            }
            // 主 server 端口：只服务管理端，CWMP 路径拒绝（设备不配置主端口为 ACS URL）
            boolean isCpePath = cpePaths.stream().anyMatch(p -> uri.equals(p) || uri.startsWith(p + "/"));
            if (isCpePath) {
                ((jakarta.servlet.http.HttpServletResponse) response).sendError(404);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
