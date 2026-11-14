package com.tr069.acs.queue;

import com.tr069.acs.cwmp.model.FaultStruct;
import com.tr069.acs.cwmp.model.SoapMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** 已提交指令的结果凭据表 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RpcTaskRegistry {

    private final ConcurrentHashMap<Long, CompletableFuture<SoapMessage>> futures = new ConcurrentHashMap<>();

    /** 指令提交时间，用于判定「排了太久还没被执行」 */
    private final ConcurrentHashMap<Long, Long> submitTimes = new ConcurrentHashMap<>();

    /** 登记一条指令并返回结果凭据 */
    public CompletableFuture<SoapMessage> create(long taskId) {
        CompletableFuture<SoapMessage> future = new CompletableFuture<>();
        futures.put(taskId, future);
        submitTimes.put(taskId, System.currentTimeMillis());
        return future;
    }

    /**
     * 仅在 taskId 尚未登记时创建结果凭据，返回新建的 Future；
     * 已存在则返回原 Future。用于进程重启后从 Redis 队列重建 pending 表。
     */
    public CompletableFuture<SoapMessage> createIfAbsent(long taskId) {
        return createIfAbsent(taskId, System.currentTimeMillis());
    }

    /** 同上，但用调用方给的真实提交时间：重建时带上队列里的 createdAt，清扫按真实排队时长判定 */
    public CompletableFuture<SoapMessage> createIfAbsent(long taskId, long submitTimeMillis) {
        CompletableFuture<SoapMessage> existing = futures.get(taskId);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<SoapMessage> future = new CompletableFuture<>();
        CompletableFuture<SoapMessage> prev = futures.putIfAbsent(taskId, future);
        if (prev != null) {
            return prev;
        }
        submitTimes.putIfAbsent(taskId, submitTimeMillis);
        return future;
    }

    /** 用设备响应完成指令 */
    public void complete(long taskId, SoapMessage message) {
        submitTimes.remove(taskId);
        CompletableFuture<SoapMessage> future = futures.remove(taskId);
        if (future != null) {
            future.complete(message);
        }
    }

    /** 以失败完成指令，如设备离线、下发超时 */
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

    /** 取出排队时间超过阈值的任务号，交给调用方判失败 */
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

    /**
     * 重排退避后刷新排队时间戳。
     * 不刷的话清扫按「首次提交至今超阈值」判定：重试次数还没用完的指令
     * 会被 sweepPendingTasks 误判为超时、从队列摘除并判死——重试形同虚设
     */
    public void touch(long taskId) {
        submitTimes.computeIfPresent(taskId, (k, v) -> System.currentTimeMillis());
    }

    public int pending() {
        return futures.size();
    }
}
