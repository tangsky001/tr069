package com.tr069.acs.common.log;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 日志消费线程：把队列里的日志批量写盘。
 *
 * <p>全进程<b>只有一个</b>写日志的线程。这样做有两个理由：</p>
 * <ul>
 *   <li>多线程写同一个文件会互相抢占行缓冲，一行日志被拆成两半的概率随并发上升，
 *       而日志文件被拆行之后基本失去排查价值；</li>
 *   <li>单线程 + 批量取数能把 Redis 往返次数压到最低，写盘吞吐足够（每秒数万行级别）。</li>
 * </ul>
 *
 * <p>取数与轮询的取舍与上报队列一致：非阻塞批量取 + 固定间隔轮询，
 * 不用阻塞命令，避免占住 Lettuce 共享连接把上报与下发一起拖死。</p>
 */
@Service
@RequiredArgsConstructor
public class LogQueueConsumer {

    /**
     * 消费线程自身的故障日志专用 logger，同样不能走业务日志通道（见 {@link LogQueueService}）。
     */
    private static final Logger INFRA = LoggerFactory.getLogger("ACS_LOG_INFRA");

    /**
     * 单线程消费，不需要线程池，直接一条守护线程即可。
     */
    private static final String THREAD_NAME = "acs-log-consumer";

    /**
     * 连续失败告警的抽样间隔(次)，避免 Redis 长时间不可用时刷爆控制台。
     */
    private static final int FAILURE_REPORT_INTERVAL = 20;

    /**
     * 退避上限(毫秒)。
     */
    private static final long MAX_BACKOFF_MILLIS = 30_000L;

    private final LogQueueService logQueue;

    private final LogProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread worker;

    @PostConstruct
    public void start() {
        if (running.getAndSet(true)) {
            return;
        }
        worker = new Thread(this::consumeLoop, THREAD_NAME);
        worker.setDaemon(true);
        worker.start();
        INFRA.info("日志消费线程已启动: key={}, batch={}, 级别={}",
                properties.getQueueKey(), properties.getBatchSize(), properties.getLevel());
    }

    @PreDestroy
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(Math.max(500L, properties.getFlushOnShutdownMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        int rest = logQueue.flushFallback(properties.getFlushOnShutdownMillis());
        if (rest > 0) {
            INFRA.warn("停机时仍有 {} 条降级日志未能写出，已丢弃", rest);
        }
    }

    private void consumeLoop() {
        long idleMillis = Math.max(20L, properties.getPollIntervalMillis());
        int consecutiveFailures = 0;
        while (running.get()) {
            try {
                List<LogRecord> batch = logQueue.pollBatch(properties.getBatchSize());
                if (consecutiveFailures > 0) {
                    INFRA.warn("日志队列已恢复，此前连续失败 {} 次", consecutiveFailures);
                    consecutiveFailures = 0;
                }
                if (batch.isEmpty()) {
                    sleepQuietly(idleMillis);
                    continue;
                }
                for (LogRecord record : batch) {
                    LogPrinter.print(record);
                }
                // 队列里还有积压时立刻接着取，不空等一轮轮询间隔
                if (batch.size() >= properties.getBatchSize()) {
                    continue;
                }
                sleepQuietly(idleMillis);
            } catch (Throwable e) {
                // Redis 不可用等异常不能让线程空转打满日志，按失败次数指数退避
                consecutiveFailures++;
                long backoff = Math.min(MAX_BACKOFF_MILLIS,
                        500L * (1L << Math.min(consecutiveFailures, 6)));
                if (consecutiveFailures == 1 || consecutiveFailures % FAILURE_REPORT_INTERVAL == 0) {
                    // 带上根因，否则只看到包装文案，看不出是连接断了还是命令超时
                    Throwable root = e.getCause() != null ? e.getCause() : e;
                    INFRA.error("日志消费异常(连续失败 {} 次)，退避 {}ms: {} | 根因 {}: {}",
                            consecutiveFailures, backoff, e.getMessage(),
                            root.getClass().getSimpleName(), root.getMessage());
                }
                sleepQuietly(backoff);
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
