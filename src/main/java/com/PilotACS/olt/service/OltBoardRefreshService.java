/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.418
 *
 */

package com.PilotACS.olt.service;
import com.PilotACS.common.log.Logs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.PilotACS.olt.entity.OltDevice;
import com.PilotACS.olt.mapper.OltDeviceMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** OLT 板卡状态定时刷新 */
@Service
@RequiredArgsConstructor
public class OltBoardRefreshService {

    private static final String OPERATOR = "板卡刷新";

    /** 板卡状态刷新周期：2 分钟 */
    private static final long INTERVAL_MILLIS = 2 * 60 * 1000L;

    /** 启动后先等建表、连接池就绪 */
    private static final long INITIAL_DELAY_MILLIS = 30 * 1000L;

    /** 并行设备数 */
    private static final int MAX_CONCURRENCY = 4;

    /** 离线设备每 15 轮（30 分钟）探一次 */
    private static final long OFFLINE_PROBE_ROUNDS = 15;

    private final OltDeviceMapper deviceMapper;

    private final OltService oltService;

    /** 上一轮未完成时跳过本轮 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 轮次计数 */
    private final java.util.concurrent.atomic.AtomicLong rounds = new java.util.concurrent.atomic.AtomicLong();

    /** 整轮调度，单线程 */
    private ExecutorService roundRunner;

    /** 设备刷新并发池 */
    private ThreadPoolExecutor devicePool;

    @PostConstruct
    public void start() {
        roundRunner = Executors.newSingleThreadExecutor(daemon("olt-board-round"));
        devicePool = new ThreadPoolExecutor(MAX_CONCURRENCY, MAX_CONCURRENCY,
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(MAX_CONCURRENCY * 2), daemon("olt-board-refresh"));
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

    @Scheduled(fixedDelay = INTERVAL_MILLIS, initialDelay = INITIAL_DELAY_MILLIS)
    public void refreshBoards() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            roundRunner.execute(this::runRound);
        } catch (RejectedExecutionException e) {
            running.set(false);
            Logs.warn(Logs.Module.OLT_COLLECT, "OLT 板卡状态刷新：线程已关闭，本轮放弃");
        } catch (Exception e) {
            running.set(false);
            Logs.warn(Logs.Module.OLT_COLLECT, "OLT 板卡状态刷新：提交异常: {}", e.getMessage());
        }
    }

    private void runRound() {
        try {
            List<OltDevice> devices = deviceMapper.selectList(
                    new LambdaQueryWrapper<OltDevice>().orderByAsc(OltDevice::getId));
            if (devices.isEmpty()) {
                return;
            }
            boolean probeOffline = rounds.incrementAndGet() % OFFLINE_PROBE_ROUNDS == 0;
            List<OltDevice> queue = new ArrayList<>();
            for (OltDevice device : devices) {
                if (!probeOffline && "OFFLINE".equals(device.getStatus())) {
                    continue;
                }
                queue.add(device);
            }
            int updated = 0;
            int skipped = 0;
            for (int from = 0; from < queue.size(); from += MAX_CONCURRENCY) {
                List<OltDevice> chunk = queue.subList(from, Math.min(from + MAX_CONCURRENCY, queue.size()));
                List<Future<Boolean>> futures = new ArrayList<>(chunk.size());
                for (OltDevice device : chunk) {
                    try {
                        futures.add(devicePool.submit(() -> oltService.refreshBoardsAuto(device.getId(), OPERATOR)));
                    } catch (RejectedExecutionException e) {
                        skipped++;
                    }
                }
                for (Future<Boolean> future : futures) {
                    try {
                        if (Boolean.TRUE.equals(future.get())) {
                            updated++;
                        } else {
                            skipped++;
                        }
                    } catch (Exception e) {
                        skipped++;
                    }
                }
            }
            Logs.debug(Logs.Module.OLT_COLLECT, "OLT 板卡状态刷新：{} 台更新，{} 台跳过（人工操作中或采集失败）", updated, skipped);
        } catch (Exception e) {
            Logs.warn(Logs.Module.OLT_COLLECT, "OLT 板卡状态刷新异常: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
