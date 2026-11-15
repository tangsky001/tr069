/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.config;

import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.Set;

/**
 * CPE 独立多端口监听：在主 server 端口之外，为 CWMP 上报额外挂一组 Tomcat Connector
 * （9090/8080/8012/6060/5481/9092/6890/9095/7013，不同厂商设备配置的 ACS 端口不同）。
 * <p>端口分流由 {@link CpePortFilter} 控制：
 * CPE 端口上的请求全部交给 CWMP 端点（不带路径的 ACS URL 自动转发到 {@code acs.cpe.path}）；
 * 主 server 端口只服务管理端 REST API（拒绝 CPE 路径）。
 */
@Configuration
@RequiredArgsConstructor
public class CpeServerConfig {

    private final CpeProperties props;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> cpeAdditionalPortCustomizer() {
        return factory -> {
            if (!props.isEnabled()) {
                return;
            }
            for (int port : props.getPorts()) {
                if (port == factory.getPort()) {
                    // CPE 不使用主 server 端口，跳过防冲突
                    continue;
                }
                Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
                connector.setPort(port);
                factory.addAdditionalTomcatConnectors(connector);
            }
            Logs.info(Logs.Module.CPE, "CPE 监听端口就绪", "端口", props.getPorts());
        };
    }

    @Bean
    public FilterRegistrationBean<CpePortFilter> cpePortFilter() {
        FilterRegistrationBean<CpePortFilter> reg = new FilterRegistrationBean<>(
                new CpePortFilter(Set.copyOf(props.getPorts()), props.getPath()));
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setName("cpePortFilter");
        return reg;
    }

    /** 按 request.getLocalPort() 分流（见类注释） */
    @RequiredArgsConstructor
    public static class CpePortFilter implements jakarta.servlet.Filter {

        private final Set<Integer> cpePorts;
        private final String cpePath;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response,
                             jakarta.servlet.FilterChain chain)
                throws java.io.IOException, jakarta.servlet.ServletException {
            int localPort = request.getLocalPort();
            String uri = ((jakarta.servlet.http.HttpServletRequest) request).getRequestURI();
            boolean onCpePort = cpePorts.contains(localPort);
            boolean isCpePath = uri.equals(cpePath) || uri.startsWith(cpePath + "/");

            if (onCpePort) {
                if (isCpePath) {
                    chain.doFilter(request, response);
                    return;
                }
                // 设备侧 ACS URL 常不带路径（如 http://host:9090/?sn=xxx），
                // CPE 端口上非 /acs 的请求一律转发到 CWMP 端点，保证任意厂商配置都能建会话
                request.getRequestDispatcher(cpePath).forward(request, response);
                return;
            }
            // 主 server 端口：只服务管理端，CPE 路径拒绝（设备不配置主端口为 ACS URL）
            if (isCpePath) {
                ((jakarta.servlet.http.HttpServletResponse) response).sendError(404);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
