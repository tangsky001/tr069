package com.tr069.acs.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.queue.config.QueueProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 设备上报队列。
 *
 * <p>CWMP 的 Inform 必须在同一个 HTTP 事务里回响应，所以这里不是「异步回响应」，
 * 而是把响应之后的重活（参数入库、报文留痕、事件调度）挪到队列里，
 * 让 Tomcat 线程尽快释放，高峰时队列起到缓冲作用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportQueueService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final QueueProperties properties;

    /**
     * 入队一条上报。同步路径只保留解析、认证与建会话。
     *
     * @return 入队成功与否；Redis 不可用时返回 false，调用方需就地同步处理
     */
    public boolean offer(ReportMessage message) {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            String key = reportKey(message.getSn());
            redis.opsForList().rightPush(key, toJson(message));
            return true;
        } catch (Exception e) {
            log.warn("上报入队失败，转为就地处理: sn={}, err={}", message.getSn(), e.getMessage());
            return false;
        }
    }

    /**
     * 取出一条上报，队列为空立即返回 null，供消费者线程轮询调用。
     *
     * <p>刻意<b>不用</b> {@code BRPOP} 这类阻塞取数：阻塞命令会把 Lettuce 的共享连接
     * 占住不放，8 个分片消费者一起阻塞时，入队、下发、查询全部被堵在这条连接上，
     * 一旦卡住往往再也恢复不了（表现为所有 Redis 操作一起超时）。
     * 改成非阻塞 RPOP + 短间隔轮询，连接随用随还，也不会把故障放大。</p>
     */
    public ReportMessage poll(String queueKey) {
        String json;
        try {
            json = redis.opsForList().rightPop(queueKey);
        } catch (Exception e) {
            // Redis 不可用时抛给消费者线程，由它做退避，避免空转打满日志
            throw new IllegalStateException("读取上报队列失败，Redis 可能不可用", e);
        }
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ReportMessage.class);
        } catch (Exception e) {
            // 单条消息损坏只影响这一条，不能让消费者线程退出
            log.error("上报消息反序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 队列积压长度，用于日志与告警。
     */
    public long size(String queueKey) {
        try {
            Long size = redis.opsForList().size(queueKey);
            return size == null ? 0 : size;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 上报队列按 SN 分片，保证同一设备的上报串行处理。
     */
    public String reportKey(String sn) {
        int shard = Math.abs(sn.hashCode() % Math.max(1, properties.getReport().getShards()));
        return properties.getKeyPrefix() + ":q:report:" + shard;
    }

    /**
     * 全部上报队列的 key，供消费者启动与积压统计。
     */
    public String[] allReportKeys() {
        int shards = Math.max(1, properties.getReport().getShards());
        String[] keys = new String[shards];
        for (int i = 0; i < shards; i++) {
            keys[i] = properties.getKeyPrefix() + ":q:report:" + i;
        }
        return keys;
    }

    private String toJson(ReportMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("上报消息序列化失败", e);
        }
    }

    /**
     * 队列中的一条上报消息。
     */
    @Data
    public static class ReportMessage {

        /** 设备序列号。 */
        private String sn;

        /** OUI。 */
        private String oui;

        /** TCP 连接标识，用于双会话检测等场景。 */
        private String connId;

        /** 原始 SOAP 报文，用于留痕。 */
        private String rawXml;

        /** 已解析的 Inform（JSON），避免消费者重复解析。 */
        private String informJson;

        /** 入队时间。 */
        private long receivedAt = System.currentTimeMillis();
    }
}
