package com.tr069.acs.queue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.queue.config.QueueProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 下发指令队列。ACS 不主动连接设备，指令先在 Redis 里排队 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RpcTaskQueue {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final QueueProperties properties;

    /** Redis 失败日志节流时间戳 */
    private final AtomicLong lastFailureLog = new AtomicLong(0);

    /** 入队唤醒器：把等着取指令的那一跳 HTTP 请求叫起来。缺席时指令等设备下次上报取走 */
    @Autowired(required = false)
    private RpcDeliveryWaiter waiter;

    /**
     * 原子入队 + 容量检查的 Lua 脚本。
     * KEYS[1] = 队列 key
     * ARGV[1] = max pending, ARGV[2] = ttl seconds, ARGV[3] = task json
     * 返回: 1 成功, 0 队列已满
     */
    private static final DefaultRedisScript<Long> ENQUEUE_SCRIPT = new DefaultRedisScript<>(
            "local n = redis.call('LLEN', KEYS[1]) "
            + "if tonumber(n) >= tonumber(ARGV[1]) then return 0 end "
            + "redis.call('RPUSH', KEYS[1], ARGV[3]) "
            + "redis.call('EXPIRE', KEYS[1], ARGV[2]) "
            + "return 1",
            Long.class);

    /**
     * 原子清理过期采集指令的 Lua 脚本。
     * 遍历整个列表，删除所有 batch 与当前批次不匹配的元素，
     * 整个过程在 Redis 单线程内原子完成，不会误删并发入队的新指令。
     * KEYS[1] = 队列 key
     * ARGV[1] = currentBatch
     * 返回: 删除的条数
     */
    private static final DefaultRedisScript<Long> DROP_STALE_SCRIPT = new DefaultRedisScript<>(
            "local current = ARGV[1] "
            + "local all = redis.call('LRANGE', KEYS[1], 0, -1) "
            + "local dropped = 0 "
            + "for i, v in ipairs(all) do "
            + "  local stale = redis.call('LREM', KEYS[1], 0, v) "
            + "  -- 这里不能用 LREM：遍历期间索引会乱 "
            + "end "
            + "-- 改用 DEL + RPUSH 回写保留的 "
            + "local keep = {} "
            + "for i, v in ipairs(all) do "
            + "  if string.find(v, '\"batch\":\"' .. current .. '\"', 1, true) ~= nil "
            + "     or string.find(v, '\"batch\": \"' .. current .. '\"', 1, true) ~= nil then "
            + "    table.insert(keep, v) "
            + "  else "
            + "    dropped = dropped + 1 "
            + "  end "
            + "end "
            + "if #keep == 0 then "
            + "  redis.call('DEL', KEYS[1]) "
            + "else "
            + "  redis.call('DEL', KEYS[1]) "
            + "  for i, v in ipairs(keep) do "
            + "    redis.call('RPUSH', KEYS[1], v) "
            + "  end "
            + "  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2])) "
            + "end "
            + "return dropped",
            Long.class);

    /** 提交一条指令到设备队列 */
    public boolean enqueue(RpcTask task) {
        try {
            String key = deviceKey(task.getSn());
            String json = toJson(task);
            int max = properties.getRpc().getMaxPendingPerDevice();
            long ttl = properties.getRpc().getQueueTtlSeconds();
            Long ret = redis.execute(ENQUEUE_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(max), String.valueOf(ttl), json);
            if (ret == null || ret == 0L) {
                log.warn("设备指令队列已满，拒绝提交: sn={}, max={}", task.getSn(), max);
                return false;
            }
            signal(task.getSn());
            return true;
        } catch (Exception e) {
            logFailure("指令入队", e);
            return false;
        }
    }

    /** 通知等着取指令的那一跳：本实例直接叫醒，同时广播给其他实例——多实例部署时 */
    private void signal(String sn) {
        if (waiter == null) {
            return;
        }
        // 先唤醒本实例挂起的请求，即使跨实例广播失败，本实例仍能取到指令
        waiter.wakeup(sn);
        try {
            redis.convertAndSend(waiter.signalChannel(sn), Long.toString(System.currentTimeMillis()));
        } catch (Exception e) {
            logFailure("下发唤醒广播", e);
        }
    }

    /**
     * 取出该设备下一条待下发指令，供空 POST 捎带。
     * 跳过尚在退避期的指令（放回队尾），避免退避指令霸占队首阻塞后续指令。
     */
    public RpcTask poll(String sn) {
        String key = deviceKey(sn);
        List<RpcTask> skipped = new ArrayList<>();
        try {
            while (true) {
                String json = redis.opsForList().leftPop(key);
                if (json == null || json.isBlank()) {
                    break;
                }
                RpcTask task = objectMapper.readValue(json, RpcTask.class);
                if (task.getNotBefore() > System.currentTimeMillis()) {
                    // 退避期未结束：暂存，继续取下一条
                    skipped.add(task);
                    continue;
                }
                // 取到可下发的指令，先把跳过的放回队尾
                requeueSkipped(key, skipped);
                return task;
            }
        } catch (Exception e) {
            logFailure("读取指令队列", e);
        }
        // 队列空了或异常：把跳过的放回去
        requeueSkipped(key, skipped);
        return null;
    }

    /** 把跳过的退避指令放回队尾，保持原相对顺序 */
    private void requeueSkipped(String key, List<RpcTask> skipped) {
        if (skipped.isEmpty()) {
            return;
        }
        try {
            for (RpcTask task : skipped) {
                redis.opsForList().rightPush(key, toJson(task));
            }
            redis.expire(key, properties.getRpc().getQueueTtlSeconds(), TimeUnit.SECONDS);
            // 跳过的指令放回后，下一跳可能仍需被唤醒去尝试
            if (skipped.size() > 0) {
                signal(skipped.get(0).getSn());
            }
        } catch (Exception e) {
            logFailure("退避指令回队尾", e);
        }
    }

    /**
     * 把指令放回队尾（退避重试场景）。
     * 旧实现放回队首会导致退避指令反复霸占队首，阻塞后续指令。
     */
    public void pushBack(RpcTask task) {
        try {
            redis.opsForList().rightPush(deviceKey(task.getSn()), toJson(task));
            redis.expire(deviceKey(task.getSn()),
                    properties.getRpc().getQueueTtlSeconds(), TimeUnit.SECONDS);
            signal(task.getSn());
        } catch (Exception e) {
            logFailure("指令回退队尾", e);
        }
    }

    /**
     * 丢弃上一轮采集压在队列里的旧指令：只丢带批次号且不是当前批次的。
     * 用 Lua 脚本原子完成「读全部 -> 过滤 -> DEL + RPUSH 回写」，
     * 避免遍历期间并发入队的新指令被误删。
     */
    public int dropStale(String sn, String currentBatch) {
        if (currentBatch == null || currentBatch.isBlank()) {
            return 0;
        }
        try {
            String key = deviceKey(sn);
            Long dropped = redis.execute(DROP_STALE_SCRIPT,
                    Collections.singletonList(key),
                    currentBatch,
                    String.valueOf(properties.getRpc().getQueueTtlSeconds()));
            if (dropped != null && dropped > 0) {
                log.warn("清理过期采集指令: sn={}, 条数={}, 本轮批次={}", sn, dropped, currentBatch);
            }
            return dropped == null ? 0 : dropped.intValue();
        } catch (Exception e) {
            logFailure("清理过期采集指令", e);
            return 0;
        }
    }

    public long pendingCount(String sn) {
        try {
            Long size = redis.opsForList().size(deviceKey(sn));
            return size == null ? 0 : size;
        } catch (Exception e) {
            logFailure("查询队列长度", e);
            return 0;
        }
    }

    /**
     * 扫描 Redis 队列中所有指令，返回仍在排队（非 running/非终态）的 taskId 列表。
     * 进程重启后 RpcTaskRegistry 的内存 Map 清空，需要用此方法重建 pending 表，
     * 否则这些指令的 CompletableFuture 永远不会完成。
     */
    public List<Long> scanPendingTaskIds(String sn) {
        List<Long> ids = new ArrayList<>();
        try {
            List<String> all = redis.opsForList().range(deviceKey(sn), 0, -1);
            if (all == null || all.isEmpty()) {
                return ids;
            }
            for (String json : all) {
                RpcTask task = parseTask(json);
                if (task != null && task.getTaskId() > 0) {
                    ids.add(task.getTaskId());
                }
            }
        } catch (Exception e) {
            logFailure("扫描排队指令", e);
        }
        return ids;
    }

    /** 获取所有有指令排队的设备 SN */
    public List<String> scanDevicesWithPending() {
        List<String> devices = new ArrayList<>();
        try {
            String pattern = properties.getKeyPrefix() + ":q:rpc:*";
            try (var cursor = redis.scan(org.springframework.data.redis.core.ScanOptions
                    .scanOptions().match(pattern).count(100).build())) {
                String prefix = properties.getKeyPrefix() + ":q:rpc:";
                while (cursor.hasNext()) {
                    String key = cursor.next();
                    if (key.startsWith(prefix)) {
                        devices.add(key.substring(prefix.length()));
                    }
                }
            }
        } catch (Exception e) {
            logFailure("扫描设备队列", e);
        }
        return devices;
    }

    // ==================== 结果 ====================

    /** 写入指令执行结果，供调用方按 taskId 查询 */
    public void saveResult(RpcTaskResult result) {
        try {
            redis.opsForValue().set(resultKey(result.getTaskId()),
                    toJson(result),
                    properties.getRpc().getResultTtlSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            logFailure("写入指令结果", e);
        }
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
        return null;
    }

    /** 提交后立即写入排队状态，避免调用方查询时查不到任务 */
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

    /** 还原指令参数。JSON 往返后容器类型会退化为 LinkedHashMap/ArrayList */
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

    /** Redis 异常日志节流 */
    private void logFailure(String scene, Exception e) {
        long now = System.currentTimeMillis();
        long last = lastFailureLog.get();
        if (now - last > 30_000L && lastFailureLog.compareAndSet(last, now)) {
            log.error("{}失败，Redis 可能不可用: {}", scene, e.getMessage());
        }
    }

    private RpcTask parseTask(String json) {
        try {
            return objectMapper.readValue(json, RpcTask.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 队列中的一条下发指令 */
    @Data
    public static class RpcTask {

        /** 任务号，同时是 rpc_record 主键，便于审计与查询 */
        private long taskId;

        private String sn;

        private String rpcName;

        /** 方法参数的 JSON 形式 */
        private String argsJson;

        private String worklistId;

        /** 所属采集批次，只有能力采集产生的指令才有，用于识别上一轮采集没发完的残留指令 */
        private String batch;

        /** 已尝试下发的次数。只在没收到回应时累加；设备回了 fault 不再重试 */
        private int attempts;

        /** 到期才可以再下发的时间戳（毫秒），0 表示随到随发。重试按 delay * 2^n 递增 */
        private long notBefore;

        private long createdAt = System.currentTimeMillis();
    }

    /** 指令执行结果，提供调用方轮询 */
    @Data
    public static class RpcTaskResult {

        private long taskId;

        private String sn;

        private String rpcName;

        /** queued / running / success / fail */
        private String status;

        private String faultCode;

        private String faultString;

        private String resultContent;

        private long submitTime;

        private long finishTime;

        /** 状态中文：排队中 / 执行中 / 成功 / 失败 */
        public String getStatusText() {
            if (status == null) {
                return "未知";
            }
            return switch (status) {
                case "queued" -> "排队中";
                case "running" -> "执行中";
                case "success" -> "成功";
                case "fail" -> "失败";
                default -> status;
            };
        }

        /** 状态标签配色（Element UI 的 type）：语义在后端判，页面只上色 */
        public String getStatusLevel() {
            if (status == null) {
                return "info";
            }
            return switch (status) {
                case "running" -> "warning";
                case "success" -> "success";
                case "fail" -> "danger";
                default -> "info";
            };
        }
    }
}
