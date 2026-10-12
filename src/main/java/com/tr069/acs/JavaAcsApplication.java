package com.tr069.acs;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TR069 ACS 服务端入口。
 *
 * <p>Java 版在单一进程内对齐 Python 版的全部能力，按职责划分为：</p>
 * <ul>
 *   <li>{@code cwmp}  - CWMP/SOAP 协议层，对应 Python 版 lib/acss/xml</li>
 *   <li>{@code acs}   - ACS 会话与设备管理，对应 lib/acss/cpes、lib/acss/acs</li>
 *   <li>{@code agent} - 按 SN 串行的下发队列，对应 lib/agent</li>
 *   <li>{@code worklist} - 工单引擎，对应 lib/worklists</li>
 *   <li>{@code api}   - 管理端 REST 接口，对应 web/tr069_site</li>
 * </ul>
 */
@EnableScheduling
@MapperScan({"com.tr069.acs.mapper", "com.tr069.acs.biz.mapper"})
@ConfigurationPropertiesScan("com.tr069.acs")
@SpringBootApplication
public class JavaAcsApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaAcsApplication.class, args);
    }
}
