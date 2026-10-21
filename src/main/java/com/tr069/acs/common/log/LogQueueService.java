package com.tr069.acs.common.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分类日志队列（生产端 + 取数端）。
 *
 * <p>日志为什么要走队列：设备上线、离线、每条指令结果都发生在 CWMP 的处理线程上，
 * 直接写文件会让磁盘 IO 反压到 Tomcat 线程，高峰时表现为设备 Inform 响应变慢甚至超时——
 * 为了记日志把设备挡在门外是本末倒置。放进队列后，生产端只做一次 RPUSH（一次往返），
 * 落盘由 {@link LogQueueConsumer} 单线程批量完成。</p>
 *
 * <p>三条硬约束，改动时务必守住：</p>
 * <ol>
 *   <li><b>永不抛异常给调用方。</b>记日志失败绝不能影响业务，只降级不抛出。</li>
 *   <li><b>队列有硬上限。</b>积压时丢最旧的日志，绝不无限增长把 Redis 内存吃满。</li>
 *   <li><b>Redis 不可用自动降级到进程内缓冲</b>，恢复后补打，不静默吞掉。</li>
 * </ol>
 *
 * <p>取数刻意用非阻塞的批量 RPOP 而非 BRPOP 之类的阻塞命令：阻塞命令会长时间占住
 * Lettuce 的共享连接，日志队列与上报队列共用同一个连接模板时容易互相拖死。</p>
 */
@Service
@RequiredArgsConstructor
public class LogQueueService {

    /**
     * 日志子系统自身的故障日志专用 logger。
     *
     * <p>绝不能走 {@link AcsCoreLog} 或 {@code ACS_CORE}：本类出问题时若经由
     * 业务日志通道上报，会形成自递归，打日志把 CPU 和磁盘一起打满。</p>
     */
    private static final Logger INFRA = LoggerFactory.getLogger("ACS_LOG_INFRA");

    /**
     * 批量弹出脚本：一次往返取走末尾 N 条并裁掉，避免逐条 RPOP 的往返开销。
     */
    private static final DefaultRedisScript<List> POP_SCRIPT = new DefaultRedisScript<>(
            "local n = tonumber(ARGV[1])\n"
                    + "if n == nil or n <= 0 then return {} end\n"
                    + "local items = redis.call('LRANGE', KEYS[1], -n, -1)\n"
                    + "if #items > 0 then redis.call('LTRIM', KEYS[1], 0, -(n + 1)) end\n"
                    + "return items\n",
            List.class);

    /**
     * 裁剪节流窗口(毫秒)：生产端只在距上次裁剪超过该间隔时才多发一次查询，
     * 避免每条日志都往返两次。
     */
    private static final long TRIM_INTERVAL_MILLIS = 1_000L;

    private final StringRedisTemplate redis;

    private final ObjectMapper objectMapper;

    private final LogProperties properties;

    /**
     * Redis 不可用时的进程内降级缓冲。非阻塞队列 + 原子计数：满了就丢，
     * 绝不让写日志这件事阻塞 CWMP 处理线程。
     */
    private final Queue<LogRecord> fallback = new ConcurrentLinkedQueue<>();

    private final AtomicInteger fallbackSize = new AtomicInteger(0);

    private final AtomicLong lastTrimAt = new AtomicLong(0L);

    private final AtomicLong dropped = new AtomicLong(0L);

    /**
     * 入队一条日志。<b>任何情况下都不抛异常。</b>
     *
     * @param record 日志记录；{@code null} 直接忽略
     * @return 是否成功进入队列（进入进程内降级缓冲也算成功）
     */
    public boolean offer(LogRecord record) {
        if (record == null) {
            return false;
        }
        if (record.getThread() == null) {
            // 线程名必须在生产端取：消费线程只有一个，写出来全是它，顺序就丢了
            record.setThread(Thread.currentThread().getName());
        }
        if (!properties.isEnabled()) {
            // 未启用队列：同步直写，行为退化成「打一条普通日志」，排障时最直观
            LogPrinter.print(record);
            return true;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(record);
        } catch (Exception e) {
            INFRA.warn("日志序列化失败，本次丢弃: {}", e.getMessage());
            return false;
        }
        try {
            redis.opsForList().rightPush(properties.getQueueKey(), json);
            trimIfNeeded();
            return true;
        } catch (Exception e) {
            // Redis 抖动不影响业务，转进程内缓冲由消费线程稍后补打
            return offerFallback(record);
        }
    }

    /**
     * 取一批日志供消费线程写盘。
     *
     * <p>优先排空进程内降级缓冲——Redis 故障窗口内的那些日志更老，也更该先补上。</p>
     *
     * @param max 本次最多取出的条数
     * @return 日志记录，可能为空列表
     */
    public List<LogRecord> pollBatch(int max) {
        int limit = Math.max(1, Math.min(max, properties.getBatchSize()));
        List<LogRecord> result = new ArrayList<>(limit + fallbackSize.get());
        drainFallback(result, limit);
        if (result.size() >= limit) {
            return result;
        }
        try {
            List<?> raw = redis.execute(POP_SCRIPT,
                    Collections.singletonList(properties.getQueueKey()), String.valueOf(limit));
            if (raw != null) {
                for (Object item : raw) {
                    LogRecord record = deserialize(item);
                    if (record != null) {
                        result.add(record);
                    }
                }
            }
        } catch (Exception e) {
            // 交给消费线程按失败次数退避，避免空转打满日志
            throw new IllegalStateException("读取日志队列失败，Redis 可能不可用", e);
        }
        return result;
    }

    /**
     * 当前 Redis 队列积压长度，取不到返回 -1。
     */
    public long queueLength() {
        try {
            Long size = redis.opsForList().size(properties.getQueueKey());
            return size == null ? 0L : size;
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * 进程内降级缓冲里的待写条数。
     */
    public int fallbackSize() {
        return fallbackSize.get();
    }

    /**
     * 因降级缓冲已满而被丢弃的日志条数。
     */
    public long droppedCount() {
        return dropped.get();
    }

    /**
     * 停机时把降级缓冲尽量排空，避免重启丢掉 Redis 故障窗口内的日志。
     *
     * @param millis 最长等待时间
     * @return 未写出的剩余条数
     */
    public int flushFallback(long millis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, millis);
        for (Iterator<LogRecord> it = fallback.iterator(); it.hasNext(); ) {
            if (System.currentTimeMillis() > deadline) {
                return fallbackSize.get();
            }
            LogPrinter.print(it.next());
            it.remove();
            fallbackSize.decrementAndGet();
        }
        return 0;
    }

    /**
     * 降级缓冲入队：满了就丢，并按次数抽样告警，避免日志风暴反过来再打爆日志。
     */
    private boolean offerFallback(LogRecord record) {
        int capacity = Math.max(1, properties.getFallbackCapacity());
        if (fallbackSize.get() >= capacity) {
            long total = dropped.incrementAndGet();
            if (total % 1000 == 1) {
                INFRA.warn("Redis 不可用且进程内日志缓冲已满，已丢弃 {} 条日志，请尽快恢复 Redis", total);
            }
            return false;
        }
        fallback.add(record);
        fallbackSize.incrementAndGet();
        return true;
    }

    private void drainFallback(List<LogRecord> target, int limit) {
        for (int i = 0; i < limit; i++) {
            LogRecord record = fallback.poll();
            if (record == null) {
                return;
            }
            fallbackSize.decrementAndGet();
            target.add(record);
        }
    }

    /**
     * 积压保护：距上次裁剪超过节流窗口时才查一次队列长度，超上限就裁掉最旧的一段。
     * 生产端不做无条件的 LTRIM——那会让每条日志都变成两次 Redis 往返。
     */
    private void trimIfNeeded() {
        long now = System.currentTimeMillis();
        long last = lastTrimAt.get();
        if (now - last < TRIM_INTERVAL_MILLIS || !lastTrimAt.compareAndSet(last, now)) {
            return;
        }
        try {
            int max = Math.max(1, properties.getMaxQueueLength());
            Long size = redis.opsForList().size(properties.getQueueKey());
            if (size != null && size > max) {
                // 从左侧 RPUSH 入队，头部最旧，保留最近 max 条即裁掉头部
                redis.opsForList().trim(properties.getQueueKey(), 0, max - 1);
            }
        } catch (Exception e) {
            INFRA.warn("日志队列裁剪失败，等待下一轮重试: {}", e.getMessage());
        }
    }

    private LogRecord deserialize(Object item) {
        if (item == null) {
            return null;
        }
        String json = item instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(item);
        try {
            return objectMapper.readValue(json, LogRecord.class);
        } catch (Exception e) {
            // 单条损坏只丢这一条，不能让消费线程退出
            INFRA.error("日志记录反序列化失败，已丢弃: {}", e.getMessage());
            return null;
        }
    }
}
