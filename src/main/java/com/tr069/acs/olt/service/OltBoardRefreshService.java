package com.tr069.acs.olt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.mapper.OltDeviceMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/** 板卡状态刷新：每 2 分钟一轮，只发 display board 0，设备忙则跳过（人工优先） */
@Slf4j
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

    /** 上一轮还没跑完就跳过本轮，避免叠加 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 轮次计数：离线设备按轮次抽检，不每轮都连 */
    private final java.util.concurrent.atomic.AtomicLong rounds = new java.util.concurrent.atomic.AtomicLong();

    /** 整轮调度，单线程 */
    private ExecutorService roundRunner;

    /** 设备刷新并发池，线程按需创建、空闲 60 秒回收 */
    private ThreadPoolExecutor devicePool;

    @PostConstruct
    public void start() {
        roundRunner = Executors.newSingleThreadExecutor(daemon("olt-board-round"));
        devicePool = new ThreadPoolExecutor(MAX_CONCURRENCY, MAX_CONCURRENCY,
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(MAX_CONCURRENCY * 2), daemon("olt-board-refresh"));
        devicePool.allowCoreThreadTimeOut(true);
        log.info("OLT 板卡状态刷新已就绪：{} 分钟一轮，上限 {} 路并行，人工操作优先",
                INTERVAL_MILLIS / 60000, MAX_CONCURRENCY);
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
            log.warn("OLT 板卡状态刷新：线程已关闭，本轮放弃");
        } catch (Exception e) {
            running.set(false);
            log.warn("OLT 板卡状态刷新：提交异常: {}", e.getMessage());
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
                // 离线设备每 30 分钟探一次就够了：每轮都连会让 connect 超时把整轮拖长
                if (!probeOffline && "OFFLINE".equals(device.getStatus())) {
                    continue;
                }
                queue.add(device);
            }
            int updated = 0;
            int skipped = 0;
            // 分批提交：每批 MAX_CONCURRENCY 台，等这批完成再提交下一批
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
            // 2 分钟一轮，成功不打 info，免得日志被刷满；只有状态变化时才值得看
            log.debug("OLT 板卡状态刷新：{} 台更新，{} 台跳过（人工操作中或采集失败）", updated, skipped);
        } catch (Exception e) {
            log.warn("OLT 板卡状态刷新异常: {}", e.getMessage());
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
