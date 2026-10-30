package com.tr069.acs;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TR069 ACS 服务端入口。 */
@EnableScheduling
@MapperScan({"com.tr069.acs.mapper", "com.tr069.acs.biz.mapper", "com.tr069.acs.template.mapper",
        "com.tr069.acs.exec.mapper", "com.tr069.acs.openapi.mapper", "com.tr069.acs.olt.mapper"})
@ConfigurationPropertiesScan("com.tr069.acs")
@SpringBootApplication
public class PilotAcsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PilotAcsApplication.class, args);
    }
}
