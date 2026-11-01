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

/**
 * ONT 自动注册：后台按每台 OLT 自己的扫描间隔去设备上扫待注册光猫，命中模板就注册掉。
 *
 * <p>为什么要有这个：光猫是装机师傅现场接的，接上就报 SN 上来，等人在页面上点已经晚了，
 * 更别说晚上装机、节假日装机。开了自动注册，光猫接上不用管，扫到就按模板授权。</p>
 *
 * <p>几个刻意的取舍：</p>
 * <ul>
 *   <li><strong>每台设备自己的间隔</strong>：这里只是个「到点了没有」的检查器（每 10 秒一次），
 *       真去登设备是按 {@code scan_seconds} 来的，页面改完下次就按新的来。</li>
 *   <li><strong>串行</strong>：OLT 的 Telnet 会话数就那么几个，一台一台来；
 *       {@code running} 还保证上一轮没跑完时本轮跳过。</li>
 *   <li><strong>失败不抛</strong>：单台连不上只是跳过，记日志，不影响其它 OLT，
 *       更不能影响 ACS 本身。</li>
 * </ul>
 */
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
