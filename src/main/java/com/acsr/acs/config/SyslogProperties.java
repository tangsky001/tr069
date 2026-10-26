package com.acsr.acs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 设备 syslog 接收配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "syslog")
public class SyslogProperties {
  private boolean enabled = true;
  private int port = 5141;
  private String bind = "0.0.0.0";
  private int keepDays = 7;
}
