package com.PilotACS;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// TR-069 ACS 启动入口
@SpringBootApplication
@EnableScheduling
@MapperScan({"com.PilotACS.mapper", "com.PilotACS.olt.mapper", "com.PilotACS.group.mapper"})
public class PilotAcsApplication {
    public static void main(String[] args) {
        SpringApplication.run(PilotAcsApplication.class, args);
    }
}
