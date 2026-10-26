package com.acsr.acs.cwmp;

import com.acsr.acs.config.AcsProperties;
import com.acsr.acs.service.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** TR-069 协议唯一入口：接收 CPE 的 Inform / RPC 响应，并在 HTTP 响应里夹带 ACS 的 RPC。 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CwmpController {

  private final CwmpEngine engine;
  private final AcsProperties properties;
  private final DeviceService deviceService;

  @PostMapping(value = "/cwmp", consumes = MediaType.ALL_VALUE)
  public ResponseEntity<String> handle(HttpServletRequest request) {
    if (!authOk(request)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"ACS\"")
          .body("");
    }
    String remoteIp = remoteIp(request);
    HttpSession httpSession = request.getSession(true);
    byte[] body;
    try {
      body = request.getInputStream().readAllBytes();
    } catch (Exception e) {
      log.warn("读取 CPE 请求体失败: {}", e.getMessage());
      body = new byte[0];
    }
    CwmpEngine.CwmpResult result = engine.handle(body, remoteIp, httpSession.getId());
    if (result.empty() || result.xml() == null) {
      return ResponseEntity.status(HttpStatus.NO_CONTENT).body("");
    }
    return ResponseEntity.status(HttpStatus.OK)
        .contentType(new MediaType(MediaType.TEXT_XML, StandardCharsets.UTF_8))
        .header("SOAPAction", "")
        .body(result.xml());
  }

  /** CPE -> ACS 认证：auto 免认证；否则校验全局账号或设备级账号密码。 */
  private boolean authOk(HttpServletRequest request) {
    if (properties.isCpeAuthFree()) {
      return true;
    }
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith("Basic ")) {
      return false;
    }
    String decoded;
    try {
      decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
    } catch (Exception e) {
      return false;
    }
    int idx = decoded.indexOf(':');
    String user = idx < 0 ? decoded : decoded.substring(0, idx);
    String pass = idx < 0 ? "" : decoded.substring(idx + 1);

    String cfgUser = properties.getAuth().getCpeToAcsUsername();
    String cfgPass = properties.getAuth().getCpeToAcsPassword();
    if (user.equals(cfgUser) && pass.equals(cfgPass)) {
      return true;
    }
    // 设备级账号：用户名等于 unitId 或台账登记的 account
    var device = deviceService.findByUnitId(user);
    if (device == null) {
      device = deviceService.findByAccount(user);
    }
    if (device == null) {
      return false;
    }
    if (device.getSecret() == null || device.getSecret().isBlank()) {
      return true;
    }
    return device.getSecret().equals(pass);
  }

  private String remoteIp(HttpServletRequest request) {
    String xReal = request.getHeader("X-Real-IP");
    if (xReal != null && !xReal.isBlank()) {
      return xReal;
    }
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
