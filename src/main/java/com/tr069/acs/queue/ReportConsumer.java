package com.tr069.acs.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.acs.service.InformService;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.SessionManager;
import com.tr069.acs.cwmp.model.InformRequest;
import com.tr069.acs.queue.config.QueueProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 上报消费者。
 *
 * <p>每个分片队列固定一个线程，保证同一台设备的上报按先后顺序处理，
 * 不会出现旧报文覆盖新状态的情况；不同分片之间并行，起到削峰作用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportConsumer {

    private final ReportQueueService queue;
    private final SessionManager sessionManager;
    private final InformService informService;
    private final QueueProperties properties;
    private final ObjectMapper objectMapper;

    private ExecutorService pool;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            log.info("队列未启用，上报消费者不启动，上报将就地同步处理");
            return;
        }
        String[] keys = queue.allReportKeys();
        running.set(true);
        pool = Executors.newFixedThreadPool(keys.length, r -> {
            Thread t = new Thread(r, "acs-report-consumer");
            t.setDaemon(true);
            return t;
        });
        for (String key : keys) {
            pool.submit(() -> consumeLoop(key));
        }
        log.info("上报消费者已启动: 队列数={}", keys.length);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
    }

    private void consumeLoop(String queueKey) {
        long idleMillis = Math.max(50, properties.getReport().getPollIntervalMillis());
        int consecutiveFailures = 0;
        while (running.get()) {
            try {
                ReportQueueService.ReportMessage msg = queue.poll(queueKey);
                if (consecutiveFailures > 0) {
                    log.warn("上报队列已恢复: key={}，此前连续失败 {} 次", queueKey, consecutiveFailures);
                }
                consecutiveFailures = 0;
                if (msg == null) {
                    // 队列空，按配置的间隔再问一次，不用阻塞式取数
                    sleepQuietly(idleMillis);
                } else {
                    handle(msg);
                }
            } catch (Throwable e) {
                // Redis 不可用等异常不能让线程空转打满日志，按失败次数退避
                consecutiveFailures++;
                long backoff = Math.min(30_000L, 500L * (1L << Math.min(consecutiveFailures, 6)));
                if (consecutiveFailures == 1 || consecutiveFailures % 20 == 0) {
                    // 带上根因，否则只看到包装文案，看不出是连接断了还是命令超时
                    Throwable root = e.getCause() != null ? e.getCause() : e;
                    log.error("上报消费异常({} 次连续失败)，退避 {}ms: {} | 根因 {}: {}",
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

    private void handle(ReportQueueService.ReportMessage msg) {
        CpeSession session = sessionManager.find(msg.getSn());
        if (session == null) {
            // 会话可能在排队期间因超时被关闭，这类上报只能丢弃，
            // 设备下次 Inform 会重新带上完整状态
            log.warn("上报处理时会话已不存在，跳过: sn={}", msg.getSn());
            return;
        }
        InformRequest inform;
        try {
            inform = objectMapper.readValue(msg.getInformJson(), InformRequest.class);
        } catch (Exception e) {
            log.error("Inform 还原失败: sn={}, err={}", msg.getSn(), e.getMessage());
            return;
        }
        informService.handle(session, inform, msg.getRawXml());
    }
}
