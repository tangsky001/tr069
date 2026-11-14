package com.PilotACS.conf;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 在线接口文档：分组显示名用中文，接口描述来自各 Controller 的 @Operation 与 DTO 的 @Schema
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("PilotACS TR-069 平台接口文档")
                .description("基于 TR-069 (CWMP) 协议实现 HGU 光网关远程管理，私有化部署；"
                        + "同时支持 SSH/Telnet 对接华为 MA5683T、MA5800 系列 OLT，"
                        + "把「OLT 上 ONT 注册开通」和「TR069 光猫业务下发」整合到一套 Web 平台，"
                        + "适合酒店、公寓全光 POL 组网场景。")
                .contact(new Contact().name("阿斌来了").email("38912748@qq.com"))
                .version("1.0.0"));
    }

    @Bean
    public GroupedOpenApi authGroup() {
        return GroupedOpenApi.builder()
                .group("auth")
                .displayName("认证与用户")
                .pathsToMatch("/user/**", "/system/**")
                .build();
    }

    @Bean
    public GroupedOpenApi oltGroup() {
        return GroupedOpenApi.builder()
                .group("olt")
                .displayName("OLT 管理")
                .pathsToMatch("/olt/**")
                .build();
    }

    @Bean
    public GroupedOpenApi opsGroup() {
        return GroupedOpenApi.builder()
                .group("ops")
                .displayName("运维与升级")
                .pathsToMatch("/ops/**", "/upgrade/**")
                .build();
    }
}
