package com.tr069.acs;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TR069 ACS 服务端入口。
 *
 * <p>按职责划分为：</p>
 * <ul>
 *   <li>{@code cwmp}  - CWMP/SOAP 协议层：报文构造与解析</li>
 *   <li>{@code acs}   - 会话、鉴权与设备管理</li>
 *   <li>{@code agent} - 按 SN 串行的下发队列</li>
 *   <li>{@code worklist} - 工单引擎</li>
 *   <li>{@code api}   - 管理端 REST 接口</li>
 * </ul>
 */
@EnableScheduling
@MapperScan({"com.tr069.acs.mapper", "com.tr069.acs.biz.mapper", "com.tr069.acs.template.mapper",
        "com.tr069.acs.exec.mapper", "com.tr069.acs.openapi.mapper"})
@ConfigurationPropertiesScan("com.tr069.acs")
@SpringBootApplication
public class JavaAcsApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaAcsApplication.class, args);
    }
}
