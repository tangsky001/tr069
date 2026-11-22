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

import java.util.LinkedHashMap;
import java.util.Map;

/** 任务派发通知队列 tr069:queue:rpc */
@Component
public class RedisTaskQueue {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单轮最多派发条数 */
    private static final int MAX_BATCH = 50;

    private final StringRedisTemplate template;
    private final CpeProperties props;
    private final DispatchHandler handler;

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

    /** 任务入队；Redis 故障返回 false（调用方降级为直接唤醒） */
    public boolean enqueue(String sn, long taskId) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sn", sn);
            m.put("taskId", taskId);
            m.put("ts", System.currentTimeMillis());
            template.opsForList().rightPush(CpeRedisKeys.TASK_QUEUE, JSON.writeValueAsString(m));
            Long len = template.opsForList().size(CpeRedisKeys.TASK_QUEUE);
            int cap = Math.max(100, props.getTaskQueueMaxSize());
            if (len != null && len > cap) {
                template.opsForList().trim(CpeRedisKeys.TASK_QUEUE, len - cap, -1);
            }
            return true;
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE_PUSH, "任务派发入队失败（降级直接唤醒）", "sn", sn, "任务", taskId,
                    "原因", e.getMessage());
            return false;
        }
    }

    /** 派发线程：每 2s 批量消费，非阻塞 leftPop，队列空即返回 */
    @Scheduled(fixedDelay = 2000)
    public void drain() {
        for (int i = 0; i < MAX_BATCH; i++) {
            String msg;
            try {
                msg = template.opsForList().leftPop(CpeRedisKeys.TASK_QUEUE);
            } catch (Exception e) {
                Logs.warn(Logs.Module.CPE_PUSH, "任务派发队列消费失败", "原因", e.getMessage());
                return;
            }
            if (msg == null) {
                return;
            }
            try {
                Map<?, ?> m = JSON.readValue(msg, Map.class);
                String sn = String.valueOf(m.get("sn"));
                long taskId = m.get("taskId") instanceof Number n ? n.longValue() : 0;
                if (sn.isBlank() || "null".equals(sn) || taskId <= 0) {
                    continue;
                }
                handler.dispatch(sn, taskId);
            } catch (Exception e) {
                Logs.warn(Logs.Module.CPE_PUSH, "派发消息处理失败", "原因", e.getMessage());
            }
        }
    }
}
