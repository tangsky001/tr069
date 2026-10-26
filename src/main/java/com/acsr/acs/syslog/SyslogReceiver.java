package com.acsr.acs.syslog;

import com.acsr.acs.config.SyslogProperties;
import com.acsr.acs.mapper.DeviceSyslogMapper;
import com.acsr.acs.model.entity.DeviceSyslog;
import com.acsr.acs.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/** UDP syslog 接收：解析后按 IP 关联台账写入 MySQL。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyslogReceiver {

  private final SyslogProperties properties;
  private final DeviceSyslogMapper syslogMapper;
  private final DeviceService deviceService;

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (!properties.isEnabled()) {
      return;
    }
    Thread t = new Thread(this::loop, "acs-syslog-receiver");
    t.setDaemon(true);
    t.start();
  }

  private void loop() {
    try (DatagramSocket socket = new DatagramSocket(properties.getPort(), InetAddress.getByName(properties.getBind()))) {
      log.info("syslog 接收已启动: udp://{}:{}", properties.getBind(), properties.getPort());
      byte[] buf = new byte[4096];
      while (true) {
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);
        String line = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
        String ip = packet.getAddress().getHostAddress();
        handle(line, ip);
      }
    } catch (Exception e) {
      log.error("syslog 接收线程退出: {}", e.getMessage());
    }
  }

  private void handle(String line, String ip) {
    try {
      SyslogParser.Entry entry = SyslogParser.parse(line);
      DeviceSyslog row = new DeviceSyslog();
      row.setIpAddress(ip);
      row.setFacility(entry.getFacility());
      row.setSeverity(entry.getSeverity());
      row.setDeviceTime(entry.getTimestamp());
      row.setHostname(entry.getHostname());
      row.setTag(entry.getTag());
      row.setContent(entry.getContent().length() > 1000 ? entry.getContent().substring(0, 1000) : entry.getContent());
      row.setCreateTime(LocalDateTime.now());
      // 按 IP 关联设备台账
      try {
        var device = deviceService.findByIp(ip);
        if (device != null) {
          row.setUnitId(device.getUnitId());
        }
      } catch (Exception ignore) {
        // 关联失败不影响日志入库
      }
      syslogMapper.insert(row);
    } catch (Exception e) {
      log.warn("写入 syslog 失败: {}", e.getMessage());
    }
  }
}
