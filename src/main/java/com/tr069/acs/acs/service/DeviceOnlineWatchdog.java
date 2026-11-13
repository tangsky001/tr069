package com.tr069.acs.acs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.mapper.CpeDeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** 设备在线看门狗：台账上还是"在线"、但超过上报窗口没再上报的设备，*/
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceOnlineWatchdog {

    /** 每台设备的唤醒追踪状态 */
    private static class WatchState {
        long attempts;
        long lastContactMark;
    }

    private final CpeDeviceMapper cpeDeviceMapper;
    private final DeviceCapabilityService deviceCapabilityService;
    private final DeviceRegistryService deviceRegistryService;

    /** 看门狗开关 */
    @Value("${acs.device.watchdog.enabled:true}")
    private boolean enabled;

    /** 判定"超时未上报"的门槛（秒）：0 = 自动按在线窗口（acs.session.online-window-seconds）判定 */
    @Value("${acs.device.watchdog.stale-after-seconds:0}")
    private long staleAfterSeconds;

    /** 唤醒多少轮仍无上报才置离线 */
    @Value("${acs.device.watchdog.max-retries:3}")
    private int maxRetries;

    /** 在线判定窗口（秒）：与台账清扫共用同一口径 */
    @Value("${acs.session.online-window-seconds:900}")
    private long onlineWindowSeconds;

    /** sn → 唤醒状态 */
    private final Map<String, WatchState> states = new ConcurrentHashMap<>();

    /** 周期巡检：给超时未上报的"在线"设备发连接请求 */
    @Scheduled(fixedDelayString = "${acs.device.watchdog.interval-millis:600000}")
    public void check() {
        if (!enabled) {
            return;
        }
        try {
            long threshold = staleAfterSeconds > 0 ? staleAfterSeconds : onlineWindowSeconds;
            LocalDateTime deadline = LocalDateTime.now().minusSeconds(threshold);
            List<CpeDevice> stale = cpeDeviceMapper.selectList(new LambdaQueryWrapper<CpeDevice>()
                    .eq(CpeDevice::getStatus, "online")
                    .lt(CpeDevice::getLastContactTime, deadline)
                    .isNotNull(CpeDevice::getConnectionRequestUrl)
                    .ne(CpeDevice::getConnectionRequestUrl, ""));
            if (stale.isEmpty()) {
                return;
            }
            // 上报间隔被改大过的设备按自身周期放宽，与台账清扫同一口径（窗口 ×3），避免刚改完间隔就被唤醒
            Map<String, Long> intervals = deviceRegistryService.loadInformIntervals(
                    stale.stream().map(CpeDevice::getSn).toList());
            LocalDateTime now = LocalDateTime.now();
            int wake = 0;
            for (CpeDevice device : stale) {
                long effThreshold = effectiveThreshold(threshold, intervals.get(device.getSn()));
                if (device.getLastContactTime() != null
                        && device.getLastContactTime().isAfter(now.minusSeconds(effThreshold))) {
                    continue;
                }
                wake++;
                handle(device);
            }
            log.info("看门狗巡检: {} 台设备超过全局窗口未上报，本轮唤醒 {} 台", stale.size(), wake);
            pruneStates();
        } catch (Exception e) {
            log.warn("看门狗巡检失败，本轮忽略: {}", e.getMessage());
        }
    }

    /** 判定门槛：默认窗口与设备自身周期 3 倍取大 */
    static long effectiveThreshold(long windowSeconds, Long interval) {
        return interval == null || interval <= 0
                ? windowSeconds : Math.max(windowSeconds, interval * 3);
    }

    /** 清掉已不在线设备的唤醒计数，避免长期累积 */
    private void pruneStates() {
        try {
            Set<String> alive = cpeDeviceMapper.selectList(new LambdaQueryWrapper<CpeDevice>()
                            .select(CpeDevice::getSn)
                            .eq(CpeDevice::getStatus, "online"))
                    .stream().map(CpeDevice::getSn).collect(Collectors.toSet());
            states.keySet().retainAll(alive);
        } catch (Exception e) {
            log.debug("清理看门狗唤醒计数失败: {}", e.getMessage());
        }
    }

    /** 单台设备：本轮先发唤醒，累计到上限仍无上报就置离线 */
    private void handle(CpeDevice device) {
        String sn = device.getSn();
        long lastContact = device.getLastContactTime() == null
                ? 0L : java.sql.Timestamp.valueOf(device.getLastContactTime()).getTime();

        WatchState state = states.get(sn);
        if (state != null && lastContact > state.lastContactMark) {
            // 上次唤醒后设备自己上来了：恢复，清掉计数
            states.remove(sn);
            return;
        }
        if (state == null) {
            state = new WatchState();
            state.lastContactMark = lastContact;
            states.put(sn, state);
        }
        state.attempts++;

        DeviceCapabilityService.WakeUpResult result = deviceCapabilityService.wakeUp(sn);
        switch (result) {
            // 发出去了：等设备下一轮 Inform；没发出去的（开关关/退避期/没地址）不累计次数，
            // 交给台账清扫按窗口置离线，避免"醒不了还倒计时"
            case SENT -> log.info("看门狗唤醒设备: sn={}, 第 {}/{} 轮", sn, state.attempts, maxRetries);
            case FAILED -> log.warn("看门狗唤醒失败: sn={}, 第 {}/{} 轮", sn, state.attempts, maxRetries);
            default -> {
                return;
            }
        }

        if (state.attempts >= maxRetries) {
            log.warn("看门狗连续 {} 轮唤醒仍无上报，置离线: sn={}", state.attempts, sn);
            deviceRegistryService.markOffline(sn);
            states.remove(sn);
        }
    }
}
