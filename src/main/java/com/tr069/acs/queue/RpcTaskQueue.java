package com.tr069.acs.queue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.queue.config.QueueProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 下发指令队列。ACS 不主动连接设备，指令先在 Redis 里排队，
 * CPE 每次空 POST 时按设备取一条捎带，设备慢或离线都不占线程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RpcTaskQueue {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final QueueProperties properties;

    /** Redis 失败日志节流时间戳。 */
    private final AtomicLong lastFailureLog = new AtomicLong(0);

    /**
     * 入队唤醒器：把等着取指令的那一跳 HTTP 请求叫起来。缺席时指令等设备下次上报取走，
     * 不影响下发正确性。
     */
    @Autowired(required = false)
    private RpcDeliveryWaiter waiter;

    /** 内存兜底的结果缓存上限，超出即清理，防止 Redis 长时间不可用把内存吃满。 */
    private static final int MEMORY_RESULT_LIMIT = 10_000;

    /** Redis 不可用时的内存队列兜底，按设备分流。 */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<RpcTask>> memoryQueues =
            new ConcurrentHashMap<>();

    /** 内存兜底的结果缓存。 */
    private final ConcurrentHashMap<Long, MemoryResult> memoryResults = new ConcurrentHashMap<>();

    /**
     * 提交一条指令到设备队列。
     *
     * @return 是否入队成功；设备队列已满或 Redis 不可用时返回 false
     */
    public boolean enqueue(RpcTask task) {
        try {
            String key = deviceKey(task.getSn());
            Long size = redis.opsForList().size(key);
            int max = properties.getRpc().getMaxPendingPerDevice();
            if (size != null && size >= max) {
                log.warn("设备指令队列已满，拒绝提交: sn={}, size={}, max={}", task.getSn(), size, max);
                return false;
            }
            redis.opsForList().rightPush(key, toJson(task));
            redis.expire(key, properties.getRpc().getQueueTtlSeconds(), TimeUnit.SECONDS);
            signal(task.getSn());
            return true;
        } catch (Exception e) {
            // Redis 不可用时退回内存，保证下发功能不断，只是进程重启后丢失
            logFailure("指令入队", e);
            boolean ok = enqueueMemory(task);
            if (ok) {
                signal(task.getSn());
            }
            return ok;
        }
    }

    /**
     * 通知等着取指令的那一跳：本实例直接叫醒，同时广播给其他实例——多实例部署时
     * 入队可能发生在别的节点上。
     */
    private void signal(String sn) {
        if (waiter == null) {
            return;
        }
        waiter.wakeup(sn);
        try {
            redis.convertAndSend(waiter.signalChannel(sn), Long.toString(System.currentTimeMillis()));
        } catch (Exception e) {
            logFailure("下发唤醒广播", e);
        }
    }

    /**
     * 取出该设备下一条待下发指令，供空 POST 捎带。
     */
    public RpcTask poll(String sn) {
        try {
            String json = redis.opsForList().leftPop(deviceKey(sn));
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, RpcTask.class);
            }
        } catch (Exception e) {
            logFailure("读取指令队列", e);
        }
        // Redis 为空或不可用时，取内存兜底里排着的指令
        ConcurrentLinkedQueue<RpcTask> queue = memoryQueues.get(sn);
        return queue == null ? null : queue.poll();
    }

    /**
     * 把指令放回队首，用于下发失败需要重试的场景。
     */
    public void pushBack(RpcTask task) {
        try {
            redis.opsForList().leftPush(deviceKey(task.getSn()), toJson(task));
            signal(task.getSn());
            return;
        } catch (Exception e) {
            log.error("指令回退失败，改放内存队首: sn={}, err={}", task.getSn(), e.getMessage());
        }
        memoryQueues.computeIfAbsent(task.getSn(), k -> new ConcurrentLinkedQueue<>()).offer(task);
        signal(task.getSn());
    }

    /**
     * 丢弃上一轮采集压在队列里的旧指令：只丢带批次号且不是当前批次的。
     * 工单指令没有批次号，一律保留。
     *
     * @param sn           设备序列号
     * @param currentBatch 本次采集批次号，为空表示不做清理
     * @return 丢弃的指令条数
     */
    public int dropStale(String sn, String currentBatch) {
        if (currentBatch == null || currentBatch.isBlank()) {
            return 0;
        }
        int dropped = 0;
        ConcurrentLinkedQueue<RpcTask> memory = memoryQueues.get(sn);
        if (memory != null) {
            int before = memory.size();
            memory.removeIf(task -> isStale(task, currentBatch));
            dropped += before - memory.size();
        }
        try {
            String key = deviceKey(sn);
            java.util.List<String> all = redis.opsForList().range(key, 0, -1);
            if (all == null || all.isEmpty()) {
                return dropped;
            }
            // 逐条 LREM 而不是「读全部 -> DEL -> 重写保留的」：后者不是原子操作，
            // 清理期间入队的新指令会被重写抹掉
            int staleHere = 0;
            for (Object raw : all) {
                String json = String.valueOf(raw);
                if (!isStale(parseTask(json), currentBatch)) {
                    continue;
                }
                Long removed = redis.opsForList().remove(key, 0, json);
                if (removed != null && removed > 0) {
                    staleHere++;
                }
            }
            dropped += staleHere;
        } catch (Exception e) {
            logFailure("清理过期采集指令", e);
        }
        return dropped;
    }

    private static boolean isStale(RpcTask task, String currentBatch) {
        return task != null && task.getBatch() != null && !currentBatch.equals(task.getBatch());
    }

    private RpcTask parseTask(String json) {
        try {
            return objectMapper.readValue(json, RpcTask.class);
        } catch (Exception e) {
            return null;
        }
    }

    public long pendingCount(String sn) {
        long memory = 0;
        ConcurrentLinkedQueue<RpcTask> queue = memoryQueues.get(sn);
        if (queue != null) {
            memory = queue.size();
        }
        try {
            Long size = redis.opsForList().size(deviceKey(sn));
            return (size == null ? 0 : size) + memory;
        } catch (Exception e) {
            return memory;
        }
    }

    // ==================== 结果 ====================

    /**
     * 写入指令执行结果，供调用方按 taskId 查询。
     */
    public void saveResult(RpcTaskResult result) {
        try {
            redis.opsForValue().set(resultKey(result.getTaskId()),
                    toJson(result),
                    properties.getRpc().getResultTtlSeconds(), TimeUnit.SECONDS);
            // 已写进 Redis，内存里的兜底副本可以丢掉
            memoryResults.remove(result.getTaskId());
            return;
        } catch (Exception e) {
            logFailure("写入指令结果", e);
        }
        long ttlMillis = Math.max(1, properties.getRpc().getResultTtlSeconds()) * 1000L;
        memoryResults.put(result.getTaskId(),
                new MemoryResult(result, System.currentTimeMillis() + ttlMillis));
        evictMemoryResults();
    }

    public RpcTaskResult getResult(long taskId) {
        try {
            String json = redis.opsForValue().get(resultKey(taskId));
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, RpcTaskResult.class);
            }
        } catch (Exception e) {
            logFailure("读取指令结果", e);
        }
        MemoryResult cached = memoryResults.get(taskId);
        if (cached == null) {
            return null;
        }
        if (cached.isExpired(System.currentTimeMillis())) {
            memoryResults.remove(taskId);
            return null;
        }
        return cached.result;
    }

    /**
     * 提交后立即写入排队状态，避免调用方查询时查不到任务。
     */
    public void saveQueued(RpcTask task) {
        RpcTaskResult result = new RpcTaskResult();
        result.setTaskId(task.getTaskId());
        result.setSn(task.getSn());
        result.setRpcName(task.getRpcName());
        result.setStatus("queued");
        result.setSubmitTime(task.getCreatedAt());
        saveResult(result);
    }

    public String deviceKey(String sn) {
        return properties.getKeyPrefix() + ":q:rpc:" + sn;
    }

    public String resultKey(long taskId) {
        return properties.getKeyPrefix() + ":rpc:result:" + taskId;
    }

    /**
     * 还原指令参数。JSON 往返后容器类型会退化为 LinkedHashMap/ArrayList，
     * 与 SOAP 构建时的遍历方式兼容。
     */
    public Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argsJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.error("指令参数解析失败: {}", e.getMessage());
            return Map.of();
        }
    }

    public String writeArgs(Map<String, Object> args) {
        try {
            return objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            log.error("指令参数序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("对象序列化失败", e);
        }
    }

    /**
     * Redis 异常日志节流。
     *
     * <p>空 POST 每次都会查队列，Redis 不可用时会高频出错，
     * 这里限制成最多 30 秒一条，避免把日志打满。</p>
     */
    private void logFailure(String scene, Exception e) {
        long now = System.currentTimeMillis();
        long last = lastFailureLog.get();
        if (now - last > 30_000L && lastFailureLog.compareAndSet(last, now)) {
            log.error("{}失败，Redis 可能不可用: {}", scene, e.getMessage());
        }
    }

    /**
     * 内存兜底入队，容量与 Redis 侧一致，避免无限堆积把内存吃满。
     */
    private boolean enqueueMemory(RpcTask task) {
        ConcurrentLinkedQueue<RpcTask> queue =
                memoryQueues.computeIfAbsent(task.getSn(), k -> new ConcurrentLinkedQueue<>());
        int max = properties.getRpc().getMaxPendingPerDevice();
        if (queue.size() >= max) {
            log.warn("内存兜底队列已满，拒绝提交: sn={}, size={}, max={}", task.getSn(), queue.size(), max);
            return false;
        }
        queue.offer(task);
        return true;
    }

    /**
     * 内存结果缓存的清理：先丢过期的，仍超限就再丢一批最早的。
     */
    private void evictMemoryResults() {
        if (memoryResults.size() <= MEMORY_RESULT_LIMIT) {
            return;
        }
        long now = System.currentTimeMillis();
        memoryResults.entrySet().removeIf(e -> e.getValue().isExpired(now));
        int excess = memoryResults.size() - MEMORY_RESULT_LIMIT;
        if (excess <= 0) {
            return;
        }
        memoryResults.keySet().stream().limit(excess).forEach(memoryResults::remove);
    }

    /**
     * 队列中的一条下发指令。
     */
    @Data
    public static class RpcTask {

        /** 任务号，同时是 rpc_record 主键，便于审计与查询。 */
        private long taskId;

        private String sn;

        private String rpcName;

        /** 方法参数的 JSON 形式。 */
        private String argsJson;

        private String worklistId;

        /**
         * 所属采集批次，只有能力采集产生的指令才有，用于识别上一轮采集没发完的残留指令：
         * 不清掉它们会被下一轮采集反复消费，还会占满设备队列。
         */
        private String batch;

        /** 已尝试下发的次数。只在没收到回应时累加；设备回了 fault 不再重试。 */
        private int attempts;

        /** 到期才可以再下发的时间戳（毫秒），0 表示随到随发。重试按 delay * 2^n 递增。 */
        private long notBefore;

        private long createdAt = System.currentTimeMillis();
    }

    /**
     * 指令执行结果，提供调用方轮询。
     */
    @Data
    public static class RpcTaskResult {

        private long taskId;

        private String sn;

        private String rpcName;

        /** queued / running / success / fail。 */
        private String status;

        private String faultCode;

        private String faultString;

        private String resultContent;

        private long submitTime;

        private long finishTime;
    }

    /**
     * 内存兜底的结果项，带过期时间，Redis 恢复后不再依赖它。
     */
    private static class MemoryResult {

        private final RpcTaskResult result;

        private final long expireAt;

        MemoryResult(RpcTaskResult result, long expireAt) {
            this.result = result;
            this.expireAt = expireAt;
        }

        boolean isExpired(long now) {
            return now >= expireAt;
        }
    }
}
