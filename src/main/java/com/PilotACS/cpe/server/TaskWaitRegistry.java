/**
 * @author: tangsky001
 * @date:   2026年11月27日
 *
 */

package com.PilotACS.cpe.server;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 任务完成事件注册表：awaitTask 等待方挂一个 future，任务到达终态瞬间
 * signalDone 唤醒——替代 150ms 轮询，下发链路每步 createTask→awaitTask 的
 * 轮询间隔不再计入端到端延迟。
 *
 * 与 {@link DeliveryWaiter} 的分工：DeliveryWaiter 唤醒设备的"挂起跳"
 * （指令怎么送到设备），TaskWaitRegistry 唤醒 API 等待方（执行结果怎么
 * 回到调用方）——两级唤醒互补，互不干扰。
 */
@Component
public class TaskWaitRegistry {

    private final Map<Long, CompletableFuture<Void>> waiters = new ConcurrentHashMap<>();

    /** 等待方登记；同一任务多个等待方共享同一 future */
    public CompletableFuture<Void> register(long taskId) {
        return waiters.computeIfAbsent(taskId, k -> new CompletableFuture<>());
    }

    /** 任务到达终态（success/fail/取消/组装失败）时唤醒等待方 */
    public void signalDone(long taskId) {
        if (taskId <= 0) {
            return;
        }
        CompletableFuture<Void> f = waiters.remove(taskId);
        if (f != null) {
            f.complete(null);
        }
    }

    /** 等信号或超时返回（超时由调用方兜底轮询，不会漏终态） */
    public void awaitSignal(CompletableFuture<Void> future, long waitMs) {
        if (future == null || waitMs <= 0) {
            return;
        }
        try {
            future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignore) {
            // 超时/异常都走等待方的下一轮状态检查
        }
    }

    /** 等待结束注销（防泄漏） */
    public void unregister(long taskId, CompletableFuture<Void> future) {
        if (future != null) {
            waiters.remove(taskId, future);
        }
    }
}
