package com.PilotACS.conf;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 在线接口文档
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("PilotACS TR-069 平台接口文档")
                .description("权限框架 / ACS 设备管理")
                .version("1.0.0"));
    }
}
