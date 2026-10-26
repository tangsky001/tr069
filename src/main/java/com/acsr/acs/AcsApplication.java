package com.acsr.acs;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan("com.acsr.acs.mapper")
@EnableConfigurationProperties
@SpringBootApplication
public class AcsApplication {

  public static void main(String[] args) {
    SpringApplication.run(AcsApplication.class, args);
  }
}
