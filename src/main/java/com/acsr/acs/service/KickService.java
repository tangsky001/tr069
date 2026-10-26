package com.acsr.acs.service;

import com.acsr.acs.config.AcsProperties;
import com.acsr.acs.model.entity.CpeDevice;
import com.acsr.acs.model.entity.UnitParam;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.acsr.acs.mapper.UnitParamMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 连接请求（kick）：让设备立即发起 Inform。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KickService {

  private final UnitParamMapper unitParamMapper;
  private final AcsProperties properties;

  /** 向设备的 ConnectionRequestURL 发起一次 HTTP 请求。 */
  public boolean kick(CpeDevice device) {
    String url = connectionRequestUrl(device);
    if (url == null || url.isBlank()) {
      log.warn("设备 {} 没有 ConnectionRequestURL，无法唤醒", device.getUnitId());
      return false;
    }
    try {
      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setConnectTimeout(5000);
      conn.setReadTimeout(5000);
      conn.setRequestMethod("GET");
      String user = properties.getAuth().getAcsToCpeUsername();
      String pass = properties.getAuth().getAcsToCpePassword();
      if (user != null && !user.isBlank() && !"auto".equalsIgnoreCase(user)) {
        String basic = Base64.getEncoder().encodeToString(
            (user + ":" + (pass == null ? "" : pass)).getBytes(StandardCharsets.UTF_8));
        conn.setRequestProperty("Authorization", "Basic " + basic);
      }
      int code = conn.getResponseCode();
      log.info("唤醒设备 {} -> {} 返回 {}", device.getUnitId(), url, code);
      return code == 200 || code == 204;
    } catch (Exception e) {
      log.warn("唤醒设备 {} 失败: {}", device.getUnitId(), e.getMessage());
      return false;
    }
  }

  /** 从台账参数里找 ConnectionRequestURL。 */
  public String connectionRequestUrl(CpeDevice device) {
    var params = unitParamMapper.selectList(new LambdaQueryWrapper<UnitParam>()
        .eq(UnitParam::getUnitId, device.getUnitId()));
    for (UnitParam p : params) {
      if (p.getName() != null && p.getName().endsWith("ManagementServer.ConnectionRequestURL")) {
        return p.getValue();
      }
    }
    return null;
  }
}
