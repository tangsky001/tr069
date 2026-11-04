package com.tr069.acs.queue.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 上报与下发队列的配置 */
@Data
@ConfigurationProperties(prefix = "acs.queue")
public class QueueProperties {

    /** 是否启用队列。关闭则退回进程内同步处理，便于排障与降级 */
    private boolean enabled = true;

    /** Redis key 前缀，多套环境共用同一个 Redis 时用来隔离 */
    private String keyPrefix = "acs";

    /** 上报队列相关配置 */
    private Report report = new Report();

    /** 下发队列相关配置 */
    private Rpc rpc = new Rpc();

    @Data
    public static class Report {

        /** 消费者线程数 */
        private int consumerThreads = 4;

        /** 队列分片数，同一 SN 固定落在同一分片 */
        private int shards = 8;

        /** 队列为空时消费者线程的下一次轮询间隔(毫秒) */
        private long pollIntervalMillis = 200;

        /** 单条上报的处理超时(秒)，超时仅告警不阻断后续 */
        private long handleTimeoutSeconds = 30;
    }

    @Data
    public static class Rpc {

        /** 指令在队列中的最长存活时间(秒)，超时由清理任务标记失败 */
        private long queueTtlSeconds = 3600;

        /** 执行结果在 Redis 中的保留时间(秒)，供调用方查询 */
        private long resultTtlSeconds = 3600;

        /** 单个设备队列中允许堆积的指令上限，超过则拒绝提交，防止慢设备无限堆积 */
        private int maxPendingPerDevice = 200;

        /** 等待设备响应的最长时间(秒)，与会话超时配合 */
        private long timeoutSeconds = 510;
    }
}
