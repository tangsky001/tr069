/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.cpe.redis;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.common.log.Logs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务派发可靠队列 tr069:queue:rpc。
 * 消费时先原子转到 processing 队列（rightPopAndLeftPush），派发成功后 ack 移除；
 * 进程崩溃时 processing 里的未确认消息在下次启动时回收重派——消息不丢。
 */
@Component
public class RedisTaskQueue {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单轮最多派发条数 */
    private static final int MAX_BATCH = 50;

    /** 派发失败重试上限（超过后放弃，等设备下次 Inform 时由任务表拾取兜底） */
    private static final int MAX_RETRY = 3;

    /** 启动恢复 processing 队列的最大条数（防死循环） */
    private static final int MAX_RECOVER = 10_000;

    /** 派发中（未 ack）队列：消费时消息先落到这里，ack 后移除 */
    public static final String PROCESSING_QUEUE = "tr069:queue:rpc:processing";

    private final StringRedisTemplate template;
    private final CpeProperties props;
    private final DispatchHandler handler;
    /** 启动后首次 drain 前先回收 processing 里的遗留消息 */
    private volatile boolean recovered = false;

    public RedisTaskQueue(StringRedisTemplate template,
                          CpeProperties props, DispatchHandler handler) {
        this.template = template;
        this.props = props;
        this.handler = handler;
    }

    /** 消费处理器：拿到 {sn, taskId} 后做反向唤醒 */
    public interface DispatchHandler {
        void dispatch(String sn, long taskId);
    }

    /** 任务入队；Redis 故障/队列满返回 false（调用方降级为直接唤醒） */
    public boolean enqueue(String sn, long taskId) {
        try {
            int cap = Math.max(100, props.getTaskQueueMaxSize());
            Long len = template.opsForList().size(CpeRedisKeys.TASK_QUEUE);
            if (len != null && len >= cap) {
                // 队列满拒绝入队：原先 trim 掉最老的会把别的设备任务悄悄挤丢
                Logs.warn(Logs.Module.CPE_PUSH, "任务派发队列已满（降级直接唤醒）", "sn", sn,
                        "任务", taskId, "队列长度", len, "上限", cap);
                return false;
            }
            template.opsForList().leftPush(CpeRedisKeys.TASK_QUEUE, toMessage(sn, taskId, 0));
            return true;
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE_PUSH, "任务派发入队失败（降级直接唤醒）", "sn", sn, "任务", taskId,
                    "原因", e.getMessage());
            return false;
        }
    }

    /** 派发线程：每 2s 批量消费；消息先原子进 processing，成功后 ack（崩溃可恢复） */
    @Scheduled(fixedDelay = 2000)
    public void drain() {
        if (!recovered) {
            recoverProcessing();
            if (!recovered) {
                return;
            }
        }
        List<String> retries = new ArrayList<>();
        try {
            for (int i = 0; i < MAX_BATCH; i++) {
                String msg;
                try {
                    msg = template.opsForList().rightPopAndLeftPush(
                            CpeRedisKeys.TASK_QUEUE, PROCESSING_QUEUE);
                } catch (Exception e) {
                    Logs.warn(Logs.Module.CPE_PUSH, "任务派发队列消费失败", "原因", e.getMessage());
                    pushRetries(retries);
                    return;
                }
                if (msg == null) {
                    return;
                }
                handleOne(msg, retries);
            }
        } finally {
            pushRetries(retries);
        }
    }

    /** 处理一条消息：解析→派发→ack；失败按 retry 计数回队重试 */
    private void handleOne(String msg, List<String> retries) {
        String sn;
        long taskId;
        int retry;
        try {
            Map<?, ?> m = JSON.readValue(msg, Map.class);
            Object rawSn = m.get("sn");
            sn = rawSn == null ? null : String.valueOf(rawSn);
            taskId = m.get("taskId") instanceof Number n ? n.longValue() : 0;
            retry = m.get("retry") instanceof Number r ? r.intValue() : 0;
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE_PUSH, "派发消息格式错误，已丢弃", "消息", msg, "原因", e.getMessage());
            ack(msg);
            return;
        }
        if (sn == null || sn.isBlank() || "null".equals(sn) || taskId <= 0) {
            Logs.warn(Logs.Module.CPE_PUSH, "派发消息缺少 sn/taskId，已丢弃", "消息", msg);
            ack(msg);
            return;
        }
        try {
            handler.dispatch(sn, taskId);
            ack(msg);
        } catch (Exception e) {
            ack(msg);
            if (retry + 1 > MAX_RETRY) {
                Logs.warn(Logs.Module.CPE_PUSH,
                        "派发重试次数用尽，任务保留在库中等设备下次 Inform 装载",
                        "sn", sn, "任务", taskId, "重试", retry, "原因", e.getMessage());
                return;
            }
            try {
                retries.add(toMessage(sn, taskId, retry + 1));
                Logs.warn(Logs.Module.CPE_PUSH, "派发失败，稍后重试", "sn", sn, "任务", taskId,
                        "第几次重试", retry + 1, "原因", e.getMessage());
            } catch (Exception se) {
                Logs.warn(Logs.Module.CPE_PUSH, "派发重试消息序列化失败", "sn", sn, "任务", taskId,
                        "原因", se.getMessage());
            }
        }
    }

    /** 重试消息回主队列（leftPush 保持在队头优先消费） */
    private void pushRetries(List<String> retries) {
        for (String r : retries) {
            try {
                template.opsForList().leftPush(CpeRedisKeys.TASK_QUEUE, r);
            } catch (Exception e) {
                Logs.warn(Logs.Module.CPE_PUSH, "派发重试回队失败", "消息", r, "原因", e.getMessage());
            }
        }
    }

    /** 确认完成：从 processing 队列移除 */
    private void ack(String msg) {
        try {
            template.opsForList().remove(PROCESSING_QUEUE, 1, msg);
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE_PUSH, "派发消息确认失败", "消息", msg, "原因", e.getMessage());
        }
    }

    /** 启动恢复：把上次进程崩溃时遗留在 processing 的消息全部搬回主队列重派 */
    private void recoverProcessing() {
        try {
            int moved = 0;
            for (; moved < MAX_RECOVER
                    && template.opsForList().rightPopAndLeftPush(PROCESSING_QUEUE, CpeRedisKeys.TASK_QUEUE) != null;
                    moved++) {
            }
            if (moved > 0) {
                Logs.info(Logs.Module.CPE_PUSH, "已回收未确认的派发消息", "条数", moved);
            }
            recovered = true;
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE_PUSH, "派发消息回收失败，下轮重试", "原因", e.getMessage());
        }
    }

    private String toMessage(String sn, long taskId, int retry) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sn", sn);
        m.put("taskId", taskId);
        m.put("retry", retry);
        m.put("ts", System.currentTimeMillis());
        return JSON.writeValueAsString(m);
    }
}
