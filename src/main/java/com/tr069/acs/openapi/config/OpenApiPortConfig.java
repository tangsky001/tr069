package com.tr069.acs.openapi.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * 北向接口独立端口。
 *
 * <p>北向是运营商/计费侧的系统对接，与管理端得分开：防火墙只放行这一个端口给合作方，
 * 不必让它们摸到管理接口。端口由 {@code acs.openapi.port} 指定，配置 0 或不配则
 * 北向接口挂在管理端口上（此时仍走 {@code /openapi/v1/**} 前缀，只是不隔离端口）。</p>
 */
@Slf4j
@Configuration
public class OpenApiPortConfig {

    @Value("${acs.openapi.port:8899}")
    private int openApiPort;

    @Value("${acs.openapi.path-prefix:/openapi/v1}")
    private String pathPrefix;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${server.tomcat.uri-encoding:UTF-8}")
    private String uriEncoding;

    /**
     * 北向端口的连接器；与管理端口同号时不重复绑定。
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> openApiConnector() {
        int port = normalizePort(openApiPort);
        return factory -> {
            if (port <= 0) {
                log.info("北向接口未启用独立端口，与管理端共用 {}", serverPort);
                return;
            }
            if (port == serverPort) {
                log.info("北向端口与管理端口相同（{}），不额外绑定连接器", port);
                return;
            }
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(port);
            connector.setURIEncoding(uriEncoding);
            factory.addAdditionalTomcatConnectors(connector);
            log.info("北向接口监听端口: {}（路径前缀 {}）", port, normalizedPrefix());
        };
    }

    /**
     * 端口隔离过滤器，顺序排在设备上报路径分发之前，避免北向端口上的请求被当成 CPE 上报处理。
     */
    @Bean
    public FilterRegistrationBean<OpenApiIsolationFilter> openApiIsolationFilter() {
        int port = normalizePort(openApiPort);
        String prefix = normalizedPrefix();
        FilterRegistrationBean<OpenApiIsolationFilter> registration =
                new FilterRegistrationBean<>();
        registration.setFilter(new OpenApiIsolationFilter(port, prefix));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    private int normalizePort(int port) {
        return port <= 0 || port > 65535 ? 0 : port;
    }

    private String normalizedPrefix() {
        String prefix = pathPrefix == null || pathPrefix.isBlank() ? "/openapi/v1" : pathPrefix.trim();
        prefix = prefix.startsWith("/") ? prefix : "/" + prefix;
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }
}
