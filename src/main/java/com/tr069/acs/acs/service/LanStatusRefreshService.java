package com.tr069.acs.acs.service;

import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.SessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LAN 网口状态定时自动刷新。
 *
 * <p>网口插线状态只有设备自己知道，ACS 拿到的永远是"上一次读到的值"：拔掉网线后
 * 设备不会主动上报，页面上的网口图标就会一直停在旧状态，只能人工点「重新采集」。
 * 这里按固定间隔对在线设备重读一遍网口状态叶子（{@link DeviceBusinessService#lanStatusPaths}），
 * 库里的值被刷成最新，前端照常读业务快照就能看到变化。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LanStatusRefreshService {

    private final SessionManager sessionManager;
    private final DeviceBusinessService businessService;
    private final DeviceCapabilityService capabilityService;

    @Value("${acs.device.lan.auto-refresh.enabled:true}")
    private boolean enabled;

    /** 单轮等待设备回值的上限（秒）。超时不判失败，下一轮继续。 */
    @Value("${acs.device.lan.auto-refresh.wait-seconds:120}")
    private long waitSeconds;

    /** 单轮最多刷多少台，设备规模上来后防止一轮下发过多。 */
    @Value("${acs.device.lan.auto-refresh.max-devices-per-round:200}")
    private int maxDevicesPerRound;

    /**
     * 一轮刷新：在线设备各下发一次网口状态读取，然后统一等回值。
     */
    @Scheduled(fixedDelayString = "${acs.device.lan.auto-refresh.interval-millis:300000}")
    public void refreshOnlineDevices() {
        if (!enabled) {
            return;
        }
        List<String> sns = onlineSns();
        if (sns.isEmpty()) {
            return;
        }
        Map<String, CompletableFuture<Map<String, String>>> pending = new LinkedHashMap<>();
        int skipped = 0;
        int failed = 0;
        for (String sn : sns) {
            if (pending.size() >= maxDevicesPerRound) {
                log.warn("LAN 网口状态刷新超出单轮上限，剩余设备下一轮处理: 在线={}, 上限={}",
                        sns.size(), maxDevicesPerRound);
                break;
            }
            List<String> paths;
            try {
                paths = businessService.lanStatusPaths(sn);
            } catch (Exception e) {
                log.warn("LAN 网口状态路径解析失败，跳过: sn={}, err={}", sn, e.getMessage());
                failed++;
                continue;
            }
            if (paths.isEmpty()) {
                // 没采过 LAN 参数：不知道网口实例，也不能凭空拼路径下发
                skipped++;
                continue;
            }
            try {
                pending.put(sn, capabilityService.readPathValues(sn, paths));
            } catch (Exception e) {
                log.warn("LAN 网口状态读取下发失败: sn={}, err={}", sn, e.getMessage());
                failed++;
            }
        }
        if (pending.isEmpty()) {
            log.debug("LAN 网口状态刷新：本轮没有可刷新的设备: 在线={}, 跳过={}", sns.size(), skipped);
            return;
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(
                pending.values().toArray(new CompletableFuture[0]));
        try {
            all.get(waitSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 设备还没轮到空 POST，指令在队列里；回应到达时照常回库，下一轮就能看到
            log.info("LAN 网口状态刷新等待回值超时，未回值的设备下一轮继续: 下发台数={}", pending.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("LAN 网口状态刷新等待异常: err={}", e.getMessage());
        }
        long replied = pending.values().stream()
                .filter(f -> f.isDone() && !f.isCompletedExceptionally())
                .count();
        log.info("LAN 网口状态刷新完成: 在线={}, 下发={}, 本轮回值={}, 无LAN参数={}, 失败={}",
                sns.size(), pending.size(), replied, skipped, failed);
    }

    /** 当前在线（有活动会话且未标离线）的设备序列号。 */
    private List<String> onlineSns() {
        List<String> sns = new ArrayList<>();
        for (CpeSession session : sessionManager.allSessions()) {
            if (session == null || !session.isOnline()) {
                continue;
            }
            String sn = session.getSn();
            if (sn != null && !sn.isBlank()) {
                sns.add(sn);
            }
        }
        return sns;
    }
}
