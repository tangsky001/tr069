package com.tr069.acs.openapi.watch;

import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.SessionManager;
import com.tr069.acs.openapi.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 设备上下线侦测 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceStatusWatcher {

    private final SessionManager sessionManager;

    private final WebhookService webhookService;

    /** 上一轮快照：sn -> 是否在线 */
    private final Map<String, Boolean> previous = new HashMap<>();

    private volatile boolean baselineReady = false;

    @Value("${acs.openapi.watch.enabled:true}")
    private boolean enabled;

    /** 扫描会话并推送状态变化 */
    @Scheduled(fixedDelayString = "${acs.openapi.watch.interval-millis:15000}")
    public void watch() {
        if (!enabled) {
            return;
        }
        try {
            Map<String, Boolean> current = new HashMap<>();
            for (CpeSession session : sessionManager.allSessions()) {
                if (session.getSn() == null) {
                    continue;
                }
                current.put(session.getSn(), session.isOnline());
            }
            if (!baselineReady) {
                previous.putAll(current);
                baselineReady = true;
                return;
            }
            Set<String> gone = new HashSet<>(previous.keySet());
            for (Map.Entry<String, Boolean> entry : current.entrySet()) {
                Boolean before = previous.get(entry.getKey());
                gone.remove(entry.getKey());
                if (before == null) {
                    // 首次出现在会话表里：在线即上线事件，离线说明只是注册了会话还没上报
                    if (Boolean.TRUE.equals(entry.getValue())) {
                        webhookService.publishDeviceState(entry.getKey(), true);
                    }
                } else if (before != entry.getValue()) {
                    webhookService.publishDeviceState(entry.getKey(), entry.getValue());
                }
            }
            for (String sn : gone) {
                webhookService.publishDeviceState(sn, false);
            }
            previous.clear();
            previous.putAll(current);
        } catch (Exception e) {
            log.warn("设备上下线侦测失败: {}", e.getMessage());
        }
    }
}
