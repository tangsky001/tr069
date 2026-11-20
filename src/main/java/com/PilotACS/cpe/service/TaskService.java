/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.cpe.model.TaskRun;
import com.PilotACS.cpe.redis.RedisTaskQueue;
import com.PilotACS.cpe.rpc.TaskPlanner;
import com.PilotACS.cpe.server.CwmpService;
import com.PilotACS.cpe.session.CwmpSession;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** 任务编排入口：前端下发 → cpe_task 落库 → 设备在线即执行（离线经 Redis 派发队列唤醒） */
@Service
@RequiredArgsConstructor
public class TaskService {

    /** 同步等待类接口的并发等待名额上限：等待的 REST 线程超过此数立即返回当前状态， */
    private static final int MAX_WAITERS = 16;

    private final CpeStore store;
    private final CwmpService cwmpService;
    private final TaskPlanner planner;
    private final RedisTaskQueue redisTaskQueue;
    private final TaskDispatcher taskDispatcher;
    private final CpeProperties props;
    private final Semaphore waitPermits = new Semaphore(MAX_WAITERS);

    /** 重发等于真实二次执行的类型：同类活跃任务存在时不重复建单。 */
    private static final java.util.Set<String> IDEMPOTENT_TYPES =
            java.util.Set.of(TaskPlanner.TYPE_REBOOT, TaskPlanner.TYPE_FACTORY_RESET);

    /** 建任务。设备在会话中直接插队执行；离线经 Redis 派发队列触发反向唤醒（故障域与会话域隔离） */
    public long createTask(String sn, String type, Map<String, Object> params, String createdBy) {
        // 幂等去重必须在 per-SN 锁内做：锁外检查与插入之间并发请求可各插一单
        var lock = cwmpService.lockFor(sn);
        lock.lock();
        try {
            if (IDEMPOTENT_TYPES.contains(type)) {
                long existing = store.findActiveTaskId(sn, type);
                if (existing > 0) {
                    Logs.info(Logs.Module.CPE_PUSH, "同类任务执行中，复用不重复建单", "sn", sn, "类型", type, "任务", existing);
                    return existing;
                }
            }
            long id = store.insertTask(sn, type, params == null ? Map.of() : params, createdBy);
            enqueueOrWake(sn, id, type);
            return id;
        } finally {
            lock.unlock();
        }
    }

    /** 会话活着则锁内入队插队执行；否则走 Redis 派发队列反踢唤醒（Redis 故障降级为直接唤醒） */
    private void enqueueOrWake(String sn, long id, String type) {
        CwmpSession session = cwmpService.findSessionBySn(sn);
        if (session != null && !session.isEnded()) {
            // 与 CWMP 报文线程同一把 per-SN 锁：入队瞬间会话可能正在收尾/被顶替，
            var lock = cwmpService.lockFor(sn);
            lock.lock();
            try {
                session = cwmpService.findSessionBySn(sn);
                if (session != null && !session.isEnded()) {
                    // 会话活着：组装成步骤直接入队（state 1 空 POST / state 2 收尾时被取走）
                    Map<String, Object> row = store.getTask(id);
                    String root = BusinessPaths.detectRoot(session.getData(), props.getDefaultRootNode());
                    TaskRun run = planner.build(row, session.getData(), root, session.getModelParams());
                    session.getQueue().add(run);
                    Logs.info(Logs.Module.CPE_PUSH, "任务入会话队列", "sn", sn, "任务", id, "类型", type);
                    return;
                }
            } finally {
                lock.unlock();
            }
        }
        // 离线/无会话：等设备下次 Inform 时装载；入 Redis 派发队列触发反向连接唤醒
        boolean queued = redisTaskQueue.enqueue(sn, id);
        if (!queued) {
            // Redis 故障降级：不经派发队列直接反踢（此前只打日志，降级路径缺失，
            taskDispatcher.wakeNow(sn, id);
        }
        Logs.info(Logs.Module.CPE_PUSH, "任务排队等待设备上线", "sn", sn, "任务", id, "类型", type);
    }

    /** 轮询等任务完成（同步采集类接口用）；等待名额用尽时立即返回当前状态，不占死线程池 */
    public Map<String, Object> awaitTask(long taskId, int timeoutSeconds) {
        if (!waitPermits.tryAcquire()) {
            Logs.warn(Logs.Module.CPE_PUSH, "任务同步等待名额已满，直接返回当前状态", "任务", taskId);
            return store.getTask(taskId);
        }
        try {
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                Map<String, Object> task = store.getTask(taskId);
                if (task == null) {
                    return null;
                }
                String status = str(task.get("status"));
                if ("success".equals(status) || "fail".equals(status)) {
                    return task;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return store.getTask(taskId);
        } finally {
            waitPermits.release();
        }
    }

    /** 任务结果 JSON → Map */
    public Map<String, Object> resultOf(Map<String, Object> task) {
        Object rc = task.get("result_content");
        if (rc instanceof String s && !s.isBlank()) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, Map.class);
            } catch (Exception ignore) {
                // 结果不是 JSON
            }
        }
        return new LinkedHashMap<>();
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
