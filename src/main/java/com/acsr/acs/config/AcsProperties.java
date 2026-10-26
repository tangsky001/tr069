package com.acsr.acs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** ACS 协议与服务端配置（对应 application.yml 的 acs 节点）。 */
@Data
@Component
@ConfigurationProperties(prefix = "acs")
public class AcsProperties {

  /** CPE 上报路径，多个入口映射到同一个协议处理器。 */
  private List<String> cwmpPaths = new ArrayList<>(List.of("/cwmp", "/ACS-server/ACS", "/tr069"));

  /** 对外可达地址，用于拼接 Download URL。 */
  private String publicUrl = "http://127.0.0.1:8888";

  /** CPE 下载固件/配置文件时是否要求账号密码。 */
  private boolean fileAuthUsed = false;

  /** 未适配型号自动采集能力树并写入 SQLite。 */
  private boolean discoveryMode = true;

  /** 并发下载上限。 */
  private int concurrentDownloadLimit = 100;

  /** 周期上报超时宽限（秒）。 */
  private int periodicTimeoutMargin = 120;

  private boolean cloudSync = false;

  /** 会话超时（秒）。 */
  private int sessionTimeoutSeconds = 180;

  /** 下发任务超时（秒）。 */
  private int taskTimeoutSeconds = 600;

  private String defaultAdminUsername = "admin";
  private String defaultAdminPassword = "admin123";

  private Auth auth = new Auth();
  private Jwt jwt = new Jwt();

  @Data
  public static class Auth {
    /** none | basic */
    private String method = "basic";
    /** auto 或留空表示免认证 */
    private String cpeToAcsUsername = "auto";
    private String cpeToAcsPassword = "auto";
    private String acsToCpeUsername = "itms";
    private String acsToCpePassword = "itms";
  }

  @Data
  public static class Jwt {
    private String secret = "acs-tr069-secret-please-change-me-0123456789";
    private long expireSeconds = 86400L;
  }

  /** 判断 CPE->ACS 是否免认证。 */
  public boolean isCpeAuthFree() {
    String u = auth.getCpeToAcsUsername();
    String p = auth.getCpeToAcsPassword();
    boolean freeUser = u == null || u.isBlank() || "auto".equalsIgnoreCase(u);
    boolean freePass = p == null || p.isBlank() || "auto".equalsIgnoreCase(p);
    return "none".equalsIgnoreCase(auth.getMethod()) || (freeUser && freePass);
  }
}
