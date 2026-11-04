package com.tr069.acs.olt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.entity.OltOntRegConfig;
import com.tr069.acs.olt.mapper.OltDeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** ONT 自动注册：后台按每台 OLT 自己的扫描间隔去设备上扫待注册光猫，命中模板就注册掉 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OltOntAutoRegisterService {

    /** 检查间隔(毫秒)：只判断「这台设备到点了吗」，不登设备 */
    private static final long TICK_MILLIS = 10 * 1000L;

    /** 启动后先让建表、连接池初始化跑完 */
    private static final long INITIAL_DELAY_MILLIS = 20 * 1000L;

    /** 一轮里最多花多久，超了剩下的留到下一轮 */
    private static final long MAX_ROUND_MILLIS = 5 * 60 * 1000L;

    private final OltDeviceMapper deviceMapper;

    private final OltOntRegService ontRegService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelay = TICK_MILLIS, initialDelay = INITIAL_DELAY_MILLIS)
    public void scanAndRegister() {
        if (!running.compareAndSet(false, true)) {
            log.info("ONT 自动注册：上一轮还没跑完，本轮跳过");
            return;
        }
        try {
            List<OltDevice> devices = deviceMapper.selectList(
                    new LambdaQueryWrapper<OltDevice>().orderByAsc(OltDevice::getId));
            long deadline = System.currentTimeMillis() + MAX_ROUND_MILLIS;
            int scanned = 0;
            int registered = 0;
            for (OltDevice device : devices) {
                if (System.currentTimeMillis() > deadline) {
                    log.warn("ONT 自动注册超出单轮上限，剩余设备下一轮处理");
                    break;
                }
                OltOntRegConfig config = ontRegService.config(device.getId());
                if (config.getEnabled() == null || config.getEnabled() != 1) {
                    continue; // 这台没开自动注册
                }
                if (!due(config)) {
                    continue;
                }
                scanned++;
                try {
                    registered += ontRegService.autoRegister(device.getId());
                } catch (Exception e) {
                    // 单台出问题不能让整轮停住：剩下的设备照扫
                    log.info("ONT 自动注册失败 id={}: {}", device.getId(), e.getMessage());
                }
            }
            if (scanned > 0) {
                log.info("ONT 自动注册本轮: 扫描={}, 注册成功={}", scanned, registered);
            }
        } finally {
            running.set(false);
        }
    }

    /** 距上次扫描够不够一个间隔；没扫过就现在扫 */
    private static boolean due(OltOntRegConfig config) {
        LocalDateTime last = config.getLastScanAt();
        if (last == null) {
            return true;
        }
        int seconds = config.getScanSeconds() == null ? 30 : Math.max(5, config.getScanSeconds());
        return Duration.between(last, LocalDateTime.now()).getSeconds() >= seconds;
    }
}
