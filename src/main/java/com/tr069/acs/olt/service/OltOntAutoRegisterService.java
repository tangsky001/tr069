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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** 并行处理多台 OLT 的上限 */
    private static final int MAX_CONCURRENCY = 8;

    private final OltDeviceMapper deviceMapper;

    private final OltOntRegService ontRegService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 已经提示过「没开自动注册」的设备：只在状态变化时打一条日志，不刷屏 */
    private final Set<Long> reportedOff = ConcurrentHashMap.newKeySet();

    /** 并行处理池 */
    private ThreadPoolExecutor devicePool;

    @jakarta.annotation.PostConstruct
    public void start() {
        AtomicInteger seq = new AtomicInteger();
        devicePool = new ThreadPoolExecutor(MAX_CONCURRENCY, MAX_CONCURRENCY,
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(MAX_CONCURRENCY * 2), r -> {
            Thread thread = new Thread(r, "ont-auto-reg-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        devicePool.allowCoreThreadTimeOut(true);
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        if (devicePool != null) {
            devicePool.shutdownNow();
        }
    }

    @Scheduled(fixedDelay = TICK_MILLIS, initialDelay = INITIAL_DELAY_MILLIS)
    public void scanAndRegister() {
        if (!running.compareAndSet(false, true)) {
            log.info("ONT 自动注册：上一轮还没跑完，本轮跳过");
            return;
        }
        try {
            List<OltDevice> devices = deviceMapper.selectList(
                    new LambdaQueryWrapper<OltDevice>().orderByAsc(OltDevice::getId));
            cleanupReportedOff(devices);
            long deadline = System.currentTimeMillis() + MAX_ROUND_MILLIS;
            // 先筛出到点的设备，减少线程池提交量
            List<OltDevice> queue = new ArrayList<>();
            for (OltDevice device : devices) {
                if (System.currentTimeMillis() > deadline) {
                    break;
                }
                OltOntRegConfig config = ontRegService.config(device.getId());
                if (config.getEnabled() == null || config.getEnabled() != 1) {
                    if (reportedOff.add(device.getId())) {
                        log.info("ONT 自动注册未开启，跳过 id={}（页面上打开开关并点「保存设置」后才会跑）",
                                device.getId());
                    }
                    continue;
                }
                reportedOff.remove(device.getId());
                if (due(config)) {
                    queue.add(device);
                }
            }
            if (queue.isEmpty()) {
                return;
            }
            int scanned = queue.size();
            int registered = 0;
            // 并行处理多台 OLT
            for (int from = 0; from < queue.size(); from += MAX_CONCURRENCY) {
                if (System.currentTimeMillis() > deadline) {
                    log.warn("ONT 自动注册超出单轮上限，剩余 {} 台下一轮处理", queue.size() - from);
                    break;
                }
                List<OltDevice> chunk = queue.subList(from, Math.min(from + MAX_CONCURRENCY, queue.size()));
                List<Future<Integer>> futures = new ArrayList<>(chunk.size());
                for (OltDevice device : chunk) {
                    try {
                        futures.add(devicePool.submit(() -> ontRegService.autoRegister(device.getId())));
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        log.info("ONT 自动注册提交被拒 id={}", device.getId());
                    }
                }
                for (Future<Integer> future : futures) {
                    try {
                        registered += future.get();
                    } catch (Exception e) {
                        log.info("ONT 自动注册失败: {}", e.getMessage());
                    }
                }
            }
            if (scanned > 0) {
                log.info("ONT 自动注册本轮: 扫描={}, 注册成功={}", scanned, registered);
            }
        } finally {
            running.set(false);
        }
    }

    /** 清理已删除设备的 reportedOff 记录 */
    private void cleanupReportedOff(List<OltDevice> devices) {
        Set<Long> alive = devices.stream().map(OltDevice::getId).collect(java.util.stream.Collectors.toSet());
        reportedOff.removeIf(id -> id == null || !alive.contains(id));
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
