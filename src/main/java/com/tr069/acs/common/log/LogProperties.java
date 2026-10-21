package com.tr069.acs.common.log;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分类日志的配置，前缀 {@code acs.log}。
 */
@Data
@ConfigurationProperties(prefix = "acs.log")
public class LogProperties {

    /**
     * 是否启用 Redis 队列中转。
     *
     * <p>关闭后所有日志在调用线程内<b>同步</b>写 logback，不进队列。
     * 排障时可以这样开，日志与打印语句同线程同序，绝不会丢；
     * 代价是写盘会占用业务线程。</p>
     */
    private boolean enabled = true;

    /**
     * Redis 队列 key。
     */
    private String queueKey = "acs:q:log";

    /**
     * 输出级别下限，低于该级别的日志直接丢弃，不进队列。
     *
     * <p>默认 INFO：DEBUG 是逐条跟踪量，几百台设备每分钟一次 Inform 就够把
     * 日志文件冲垮，而排障时临时改成 DEBUG 即可，不必改代码。</p>
     */
    private LogLevel level = LogLevel.INFO;

    /**
     * 队列在 Redis 中的堆积上限，超出后丢弃最旧的一条。
     *
     * <p>日志不允许反压业务：Redis 不可用或消费跟不上时，宁可丢日志也不能
     * 让 CWMP 处理变慢，所以队列必须有硬上限而不是无限增长。</p>
     */
    private int maxQueueLength = 200_000;

    /**
     * Redis 不可用时的进程内降级缓冲容量。
     */
    private int fallbackCapacity = 20_000;

    /**
     * 消费端单次批量取出的条数。
     */
    private int batchSize = 200;

    /**
     * 队列为空时的轮询间隔(毫秒)。
     */
    private long pollIntervalMillis = 200;

    /**
     * 停机时等待队列排空的最长时间(毫秒)，超时直接丢弃剩余。
     */
    private long flushOnShutdownMillis = 3_000L;
}
