package com.tr069.acs.olt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.mapper.OltDeviceMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** OLT 定时自动采集：多台设备并行采集 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OltAutoRefreshService {

    private static final String OPERATOR = "自动刷新";

    /** 连续失败后间隔的最大倍数 */
    private static final int MAX_BACKOFF = 6;

    /** 退避最长分钟数上限：不管连续失败多少次，退避都不超过这个值 */
    private static final long MAX_BACKOFF_MINUTES = 120;

    /** 全量采集间隔（可配置，默认 30 分钟） */
    @Value("${acs.olt.auto-refresh.interval-millis:1800000}")
    private long intervalMillis;

    /** 启动后多久跑第一轮(毫秒) */
    private static final long INITIAL_DELAY_MILLIS = 5 * 1000L;

    /** 并行采集上限，实际并行度 = min(设备数, 该值) */
    private static final int MAX_CONCURRENCY = 20;

    /** 单轮总耗时上限(毫秒) */
    private static final long MAX_ROUND_MILLIS = 30 * 60 * 1000L;

    private final OltDeviceMapper deviceMapper;

    private final OltService oltService;

    /** 每台设备的连续失败次数 */
    private final Map<Long, Integer> failures = new ConcurrentHashMap<>();

    /** 每台设备最早可以再采一次的时间戳 */
    private final Map<Long, Long> retryAt = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 整轮调度，单线程 */
    private ExecutorService roundRunner;

    /** 设备采集并发池，线程按需创建、空闲 60 秒回收 */
    private ThreadPoolExecutor devicePool;

    @PostConstruct
    public void start() {
        roundRunner = Executors.newSingleThreadExecutor(daemon("olt-refresh-round"));
        devicePool = new ThreadPoolExecutor(MAX_CONCURRENCY, MAX_CONCURRENCY,
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), daemon("olt-refresh"));
        devicePool.allowCoreThreadTimeOut(true);
    }

    @PreDestroy
    public void stop() {
        if (roundRunner != null) {
            roundRunner.shutdownNow();
        }
        if (devicePool != null) {
            devicePool.shutdownNow();
        }
    }

    /** 触发一轮自动采集，本轮在独立线程上跑 */
    @Scheduled(fixedDelayString = "${acs.olt.auto-refresh.interval-millis:1800000}", initialDelay = INITIAL_DELAY_MILLIS)
    public void refreshAll() {
        if (!running.compareAndSet(false, true)) {
            log.info("OLT 自动刷新：上一轮还没跑完，本轮跳过");
            return;
        }
        try {
            roundRunner.execute(this::runRound);
        } catch (RejectedExecutionException e) {
            running.set(false);
            log.warn("OLT 自动刷新：采集线程已关闭，本轮放弃");
        } catch (Exception e) {
            running.set(false);
            log.warn("OLT 自动刷新：提交采集任务异常: {}", e.getMessage());
        }
    }

    /** 一轮采集：筛出到点的设备，按并行度分批采 */
    private void runRound() {
        try {
            List<OltDevice> devices = deviceMapper.selectList(
                    new LambdaQueryWrapper<OltDevice>().orderByAsc(OltDevice::getId));
            cleanupRemoved(devices);
            long deadline = System.currentTimeMillis() + MAX_ROUND_MILLIS;
            List<OltDevice> queue = new ArrayList<>();
            int skipped = 0;
            for (OltDevice device : devices) {
                Long waitUntil = retryAt.get(device.getId());
                if (waitUntil != null && waitUntil > System.currentTimeMillis()) {
                    skipped++;
                    continue;
                }
                queue.add(device);
            }
            int ok = 0;
            int failed = 0;
            int deferred = 0;
            for (int from = 0; from < queue.size(); from += MAX_CONCURRENCY) {
                if (System.currentTimeMillis() > deadline) {
                    deferred = queue.size() - from;
                    log.warn("OLT 自动刷新超出单轮上限，剩余 {} 台下一轮处理", deferred);
                    break;
                }
                List<OltDevice> chunk = queue.subList(from, Math.min(from + MAX_CONCURRENCY, queue.size()));
                List<Future<Boolean>> futures = new ArrayList<>(chunk.size());
                for (OltDevice device : chunk) {
                    futures.add(devicePool.submit(() -> syncOne(device)));
                }
                for (Future<Boolean> future : futures) {
                    if (awaitOk(future)) {
                        ok++;
                    } else {
                        failed++;
                    }
                }
            }
            log.info("OLT 自动刷新完成: 成功={}, 失败={}", ok, failed);
        } catch (Exception e) {
            log.warn("OLT 自动刷新本轮异常: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    /** 采一台，失败退避，成功清退避记录；定时采集不针对端口光模块发命令 */
    private boolean syncOne(OltDevice device) {
        long start = System.currentTimeMillis();
        try {
            oltService.autoSync(device.getId(), OPERATOR);
            failures.remove(device.getId());
            retryAt.remove(device.getId());
            log.info("OLT 自动刷新成功 id={} host={} 耗时={}s", device.getId(), device.getHost(),
                    (System.currentTimeMillis() - start) / 1000);
            return true;
        } catch (Exception e) {
            int streak = Math.min(MAX_BACKOFF, failures.merge(device.getId(), 1, Integer::sum));
            long backoffMs = Math.min(intervalMillis * streak, MAX_BACKOFF_MINUTES * 60_000L);
            retryAt.put(device.getId(), System.currentTimeMillis() + backoffMs);
            log.info("OLT 自动刷新失败 id={}, 下次退避 {} 分钟: {}", device.getId(),
                    backoffMs / 60000, e.getMessage());
            return false;
        }
    }

    /** 等一台采完 */
    private static boolean awaitOk(Future<Boolean> future) {
        try {
            return Boolean.TRUE.equals(future.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 清掉已删除设备的退避记录 */
    private void cleanupRemoved(List<OltDevice> devices) {
        Set<Long> alive = devices.stream().map(OltDevice::getId).collect(Collectors.toSet());
        failures.keySet().removeIf(id -> id == null || !alive.contains(id));
        retryAt.keySet().removeIf(id -> id == null || !alive.contains(id));
    }

    /** 守护线程工厂 */
    private static ThreadFactory daemon(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
