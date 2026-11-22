/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.333
 *
 */

package com.PilotACS.conf;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在线接口文档配置 */
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
                .version("1.0.2"));
    }
}
