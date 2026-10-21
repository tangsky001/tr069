package com.tr069.acs.common.web;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * CPE 上报多端口监听。
 *
 * <p>不同厂商、不同批次的光猫出厂固化的 ACS URL 端口并不一致，ACS 必须同时监听
 * 这些端口才能收到上报。端口清单由 {@code acs.cwmp.listen-ports} 配置，
 * 除主端口 {@code server.port} 外，其余端口通过追加 Tomcat 连接器实现，
 * 所有端口共用同一套处理逻辑（{@code /ACS-server/ACS} 与 {@code /cpe}）。</p>
 */
@Slf4j
@Configuration
public class CwmpMultiPortConfig {

    @Value("${acs.cwmp.listen-ports:9090,8082}")
    private String listenPorts;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${server.tomcat.uri-encoding:UTF-8}")
    private String uriEncoding;

    /**
     * 为主端口之外的 CPE 上报端口追加连接器。
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> cwmpAdditionalConnectors() {
        List<Integer> extraPorts = resolveExtraPorts();
        return factory -> {
            for (Integer port : extraPorts) {
                Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
                connector.setPort(port);
                connector.setURIEncoding(uriEncoding);
                factory.addAdditionalTomcatConnectors(connector);
            }
            if (extraPorts.isEmpty()) {
                log.info("CPE 上报监听端口：{}（未配置额外端口）", serverPort);
            } else {
                log.info("CPE 上报监听端口：{}（主端口 {}，额外 {}）",
                        new TreeSet<>(listenedPorts(extraPorts)), serverPort, extraPorts);
            }
        };
    }

    /**
     * 实际监听的端口集合：主端口 + 配置中的额外端口。
     */
    private Set<Integer> listenedPorts(List<Integer> extraPorts) {
        Set<Integer> ports = new LinkedHashSet<>();
        ports.add(serverPort);
        ports.addAll(extraPorts);
        return ports;
    }

    /**
     * 解析端口清单：支持逗号/分号/空格分隔，去重、去掉越界值，并跳过主端口避免重复绑定。
     */
    private List<Integer> resolveExtraPorts() {
        Set<Integer> parsed = new LinkedHashSet<>();
        if (listenPorts != null) {
            for (String part : listenPorts.split("[,;\\s]+")) {
                String text = part.trim();
                if (text.isEmpty()) {
                    continue;
                }
                try {
                    parsed.add(Integer.parseInt(text));
                } catch (NumberFormatException e) {
                    log.warn("acs.cwmp.listen-ports 中存在非法端口号，已忽略：{}", text);
                }
            }
        }
        List<Integer> extra = new ArrayList<>();
        for (Integer port : parsed) {
            if (port == null || port <= 0 || port > 65535) {
                log.warn("acs.cwmp.listen-ports 中存在越界端口号，已忽略：{}", port);
                continue;
            }
            if (port == serverPort) {
                continue;
            }
            extra.add(port);
        }
        return extra;
    }
}
