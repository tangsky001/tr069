package com.tr069.acs.queue;

import com.tr069.acs.cwmp.model.FaultStruct;
import com.tr069.acs.cwmp.model.SoapMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已提交指令的结果凭据表。
 *
 * <p>单实例部署下会话与凭据都在进程内，这里只做 taskId 到 Future 的映射，
 * 让工单引擎可以用回调串联各步骤，而不是阻塞线程等待。</p>
 *
 * <p>将来要多实例部署时，把本类替换为「Redis 订阅 + 回调」实现即可，
 * 调用方只依赖 {@link #create(long)} 返回的 Future。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RpcTaskRegistry {

    private final ConcurrentHashMap<Long, CompletableFuture<SoapMessage>> futures = new ConcurrentHashMap<>();

    /**
     * 指令提交时间，用于判定「排了太久还没被执行」。
     */
    private final ConcurrentHashMap<Long, Long> submitTimes = new ConcurrentHashMap<>();

    /**
     * 登记一条指令并返回结果凭据。
     */
    public CompletableFuture<SoapMessage> create(long taskId) {
        CompletableFuture<SoapMessage> future = new CompletableFuture<>();
        futures.put(taskId, future);
        submitTimes.put(taskId, System.currentTimeMillis());
        return future;
    }

    /**
     * 用设备响应完成指令。
     */
    public void complete(long taskId, SoapMessage message) {
        submitTimes.remove(taskId);
        CompletableFuture<SoapMessage> future = futures.remove(taskId);
        if (future != null) {
            future.complete(message);
        }
    }

    /**
     * 以失败完成指令，如设备离线、下发超时。
     */
    public void fail(long taskId, String faultCode, String faultString) {
        submitTimes.remove(taskId);
        CompletableFuture<SoapMessage> future = futures.remove(taskId);
        if (future == null) {
            return;
        }
        future.complete(SoapMessage.builder()
                .fault(FaultStruct.builder()
                        .faultCode(faultCode)
                        .faultString(faultString)
                        .build())
                .build());
    }

    /**
     * 取出排队时间超过阈值的任务号，交给调用方判失败。
     *
     * <p>设备离线时指令会一直留在队列里，不清理的话挂在凭据上的工单步骤
     * 永远不会往下走。这里只挑出任务号，完成动作由 {@code RpcDispatchService}
     * 统一处理，保证结果、审计一起落。</p>
     */
    public java.util.List<Long> drainStale(long olderThanMillis) {
        long now = System.currentTimeMillis();
        java.util.List<Long> stale = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Long, Long> e : submitTimes.entrySet()) {
            if (now - e.getValue() >= olderThanMillis) {
                stale.add(e.getKey());
            }
        }
        return stale;
    }

    public int pending() {
        return futures.size();
    }
}
