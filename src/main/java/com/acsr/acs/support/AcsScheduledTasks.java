package com.acsr.acs.support;

import com.acsr.acs.config.AcsProperties;
import com.acsr.acs.config.DeviceProperties;
import com.acsr.acs.config.SyslogProperties;
import com.acsr.acs.cwmp.session.SessionStore;
import com.acsr.acs.mapper.DeviceSyslogMapper;
import com.acsr.acs.service.DeviceService;
import com.acsr.acs.service.DispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 后台任务：离线判定、任务超时、会话清理、日志清理。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcsScheduledTasks {

  private final DeviceService deviceService;
  private final DispatchService dispatchService;
  private final SessionStore sessionStore;
  private final DeviceProperties deviceProperties;
  private final AcsProperties acsProperties;
  private final SyslogProperties syslogProperties;
  private final DeviceSyslogMapper syslogMapper;

  /** 离线判定：超过配置时长没有通信。 */
  @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
  public void detectOffline() {
    try {
      int n = deviceService.markOffline(deviceProperties.getOnlineCheckInterval());
      if (n > 0) {
        log.info("标记离线设备 {} 台", n);
      }
    } catch (Exception e) {
      log.warn("离线判定失败: {}", e.getMessage());
    }
  }

  /** 下发任务超时。 */
  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  public void taskTimeout() {
    try {
      int n = dispatchService.timeout(acsProperties.getTaskTimeoutSeconds());
      if (n > 0) {
        log.info("下发任务超时置为 TIMEOUT: {} 个", n);
      }
    } catch (Exception e) {
      log.warn("任务超时处理失败: {}", e.getMessage());
    }
  }

  /** 会话清理。 */
  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  public void cleanSessions() {
    try {
      sessionStore.cleanup();
    } catch (Exception e) {
      log.warn("会话清理失败: {}", e.getMessage());
    }
  }

  /** syslog 清理。 */
  @Scheduled(cron = "0 0 3 * * *")
  public void purgeSyslog() {
    try {
      int days = syslogProperties.getKeepDays();
      if (days > 0) {
        int n = syslogMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
            com.acsr.acs.model.entity.DeviceSyslog>()
            .lt(com.acsr.acs.model.entity.DeviceSyslog::getCreateTime,
                java.time.LocalDateTime.now().minusDays(days)));
        log.info("清理 {} 天前的 syslog {} 条", days, n);
      }
    } catch (Exception e) {
      log.warn("syslog 清理失败: {}", e.getMessage());
    }
  }
}
