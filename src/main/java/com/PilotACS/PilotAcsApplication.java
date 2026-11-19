/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.385
 *
 */

package com.PilotACS;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// PilotACS TR-069 ACS 启动入口
@SpringBootApplication(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
@EnableScheduling
@MapperScan({"com.PilotACS.mapper", "com.PilotACS.olt.mapper", "com.PilotACS.group.mapper", "com.PilotACS.acl",
        "com.PilotACS.template.mapper", "com.PilotACS.biz.mapper", "com.PilotACS.worklist.mapper"})
public class PilotAcsApplication {
    public static void main(String[] args) {
        SpringApplication.run(PilotAcsApplication.class, args);
    }
}
