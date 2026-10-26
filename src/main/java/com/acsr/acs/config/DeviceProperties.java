package com.acsr.acs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 设备纳管与在线判定配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "device")
public class DeviceProperties {

  /** 超过该秒数没有通信则判定离线。 */
  private int onlineCheckInterval = 120;

  private Provision provision = new Provision();

  @Data
  public static class Provision {
    private boolean enabled = true;
    /** 下发给设备的周期上报间隔（秒），0 表示不改。 */
    private int informIntervalSeconds = 300;
    /** 下发给设备的 ACS 上报地址，留空则按 public-url + cpe 路径拼。 */
    private String acsUrl = "";
    private String connectionRequestUsername = "itms";
    private String connectionRequestPassword = "itms";
  }
}
