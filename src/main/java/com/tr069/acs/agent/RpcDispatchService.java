package com.tr069.acs.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.cwmp.RpcMethod;
import com.tr069.acs.cwmp.model.FaultStruct;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.domain.entity.RpcRecord;
import com.tr069.acs.mapper.RpcRecordMapper;
import com.tr069.acs.queue.RpcTaskQueue;
import com.tr069.acs.queue.RpcTaskRegistry;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import com.tr069.acs.common.log.AcsCoreLog;

/**
 * RPC 下发服务。
 *
 * <p>下发流程：</p>
 * <ul>
 *   <li>{@link #submitAsync} 把指令放进 Redis 队列，立即返回任务号与结果凭据，不占用线程</li>
 *   <li>CPE 空 POST 时由 {@code CwmpController} 从队列取出捎带</li>
 *   <li>响应到达后由 {@link #completeTask} 回填结果与审计</li>
 * </ul>
 *
 * <p>相比原来的 {@code future.get(510s)} 阻塞，调用方线程不再被挂起；
 * 设备离线时指令留在队列里，不会随会话关闭被丢弃。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RpcDispatchService {

    private final RpcRecordMapper rpcRecordMapper;
    private final RpcTaskQueue rpcTaskQueue;
    private final RpcTaskRegistry rpcTaskRegistry;
    private final AcsCoreLog coreLog;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * RPC 审计流水号，入库失败时兜底。
     */
    private final AtomicLong rpcSequence = new AtomicLong(1);

    /**
     * 方法名非法时的故障码：不是设备返回的 CWMP 故障，属 ACS 内部错误。
     */
    private static final String FAULT_BAD_METHOD = "8005";

    @Value("${acs.agent.rpc-timeout:510}")
    private long rpcTimeout;

    /**
     * 指令在队列中等待被设备取走的最长时间，超时判失败。
     */
    @Value("${acs.agent.pending-timeout-seconds:600}")
    private long pendingTimeoutSeconds;

    /**
     * 提交一条指令，立即返回。
     *
     * <p>不要求设备当前在线：指令先进队列，等 CPE 空 POST 时捎带，
     * 设备离线或处理慢都不会占用线程。</p>
     *
     * @param sn         设备序列号
     * @param rpcName    CWMP 方法名
     * @param args       方法参数
     * @param worklistId 关联工单号，可为空
     * @return 提交句柄，含任务号与结果凭据
     */
    public DispatchHandle submitAsync(String sn, String rpcName,
                                      Map<String, Object> args, String worklistId) {
        return submitAsync(sn, rpcName, args, worklistId, null);
    }

    /**
     * 丢弃上一轮采集压在队列里的旧指令（见 {@link RpcTaskQueue#dropStale}）。
     *
     * @param sn           设备序列号
     * @param currentBatch 本次采集批次号
     * @return 丢弃的指令条数
     */
    public int dropStaleCollectTasks(String sn, String currentBatch) {
        return rpcTaskQueue.dropStale(sn, currentBatch);
    }

    /**
     * 同 {@link #submitAsync(String, String, Map, String)}，多带一个采集批次号。
     *
     * @param batch 所属采集批次，用于新采集开始时丢弃上一轮压在队列里的旧指令；非采集指令传空
     */
    public DispatchHandle submitAsync(String sn, String rpcName,
                                      Map<String, Object> args, String worklistId, String batch) {
        long taskId = insertRecord(sn, rpcName, args, worklistId);
        CompletableFuture<SoapMessage> future = rpcTaskRegistry.create(taskId);

        // 方法名非法必须在入队前拦掉：一旦进了队列，空 POST 取到它时构造不出 SOAP，
        // 只能回 500，还会把这条坏指令一直卡在 running 状态
        if (RpcMethod.of(rpcName) == null) {
            String reason = rpcName == null || rpcName.isBlank()
                    ? "CWMP 方法名为空" : "未知 CWMP 方法: " + rpcName;
            log.error("指令方法名非法，拒绝入队: sn={}, rpc={}, taskId={}", sn, rpcName, taskId);
            rpcTaskRegistry.fail(taskId, FAULT_BAD_METHOD, reason);
            finishRecord(taskId, null);
            return new DispatchHandle(taskId, future, false);
        }

        RpcTaskQueue.RpcTask task = new RpcTaskQueue.RpcTask();
        task.setTaskId(taskId);
        task.setSn(sn);
        task.setRpcName(rpcName);
        task.setArgsJson(rpcTaskQueue.writeArgs(args));
        task.setWorklistId(worklistId);
        task.setBatch(batch);

        boolean queued = rpcTaskQueue.enqueue(task);
        if (!queued) {
            rpcTaskRegistry.fail(taskId, "8003", "指令入队失败：队列不可用或该设备队列已满");
            finishRecord(taskId, null);
            return new DispatchHandle(taskId, future, false);
        }
        rpcTaskQueue.saveQueued(task);
        log.debug("指令已入队: taskId={}, sn={}, rpc={}", taskId, sn, rpcName);
        return new DispatchHandle(taskId, future, true);
    }

    /**
     * 同步等待版本，保留给需要立即拿到结果的调用方。
     *
     * <p>内部仍然走队列，只是调用方选择阻塞等待，超时后任务标记失败。</p>
     */
    public SoapMessage dispatch(String sn, String rpcName,
                                Map<String, Object> args, String worklistId) {
        DispatchHandle handle = submitAsync(sn, rpcName, args, worklistId);
        if (!handle.isQueued()) {
            return faultSoap("8003", "指令入队失败：队列不可用或该设备队列已满");
        }
        try {
            return handle.getFuture().get(rpcTimeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("RPC 执行超时: sn={}, rpc={}, taskId={}", sn, rpcName, handle.getTaskId());
            failTask(handle.getTaskId(), "8002", "RPC 执行超时");
            return faultSoap("8002", "RPC 执行超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failTask(handle.getTaskId(), "8002", "RPC 执行被中断");
            return faultSoap("8002", "RPC 执行被中断");
        } catch (ExecutionException e) {
            log.error("RPC 执行异常: sn={}, rpc={}", sn, rpcName, e);
            failTask(handle.getTaskId(), "8002", String.valueOf(e.getMessage()));
            return faultSoap("8002", String.valueOf(e.getMessage()));
        }
    }

    /**
     * 设备响应到达：回填 Redis 结果、完成结果凭据、回写审计记录。
     */
    public void completeTask(long taskId, SoapMessage result) {
        RpcTaskQueue.RpcTaskResult stored = rpcTaskQueue.getResult(taskId);
        RpcTaskQueue.RpcTaskResult result2 = new RpcTaskQueue.RpcTaskResult();
        result2.setTaskId(taskId);
        if (stored != null) {
            result2.setSn(stored.getSn());
            result2.setRpcName(stored.getRpcName());
            result2.setSubmitTime(stored.getSubmitTime());
        }
        boolean failed = result == null || result.isFault();
        result2.setStatus(failed ? "fail" : "success");
        result2.setFinishTime(System.currentTimeMillis());
        if (result != null && result.getFault() != null) {
            result2.setFaultCode(result.getFault().getFaultCode());
            result2.setFaultString(result.getFault().getFaultString());
        }
        if (result != null) {
            result2.setResultContent(result.getStrResult());
        }
        rpcTaskQueue.saveResult(result2);

        // 核心日志：下发成功/失败单独记一行到 log/ACS_LOG.LOG
        coreLog.rpcResult(result2.getSn(), result2.getRpcName(), taskId, !failed,
                failed && result != null && result.getFault() != null
                        ? result.getFault().getFaultCode() : "-",
                failed && result != null && result.getFault() != null
                        ? result.getFault().getFaultString() : "-");

        // 凭据完成后，挂在上面的工单下一步会被回调驱动继续执行
        rpcTaskRegistry.complete(taskId, result);
        finishRecord(taskId, result);
    }

    /**
     * 指令以失败结束：下发超时、会话关闭、设备无响应等。
     */
    public void failTask(long taskId, String faultCode, String faultString) {
        RpcTaskQueue.RpcTaskResult stored = rpcTaskQueue.getResult(taskId);
        RpcTaskQueue.RpcTaskResult result = new RpcTaskQueue.RpcTaskResult();
        result.setTaskId(taskId);
        if (stored != null) {
            result.setSn(stored.getSn());
            result.setRpcName(stored.getRpcName());
            result.setSubmitTime(stored.getSubmitTime());
        }
        result.setStatus("fail");
        result.setFaultCode(faultCode);
        result.setFaultString(faultString);
        result.setFinishTime(System.currentTimeMillis());
        rpcTaskQueue.saveResult(result);

        // 核心日志：超时、会话关闭等失败同样记一行
        coreLog.rpcResult(result.getSn(), result.getRpcName(), taskId, false, faultCode, faultString);

        rpcTaskRegistry.fail(taskId, faultCode, faultString);
        finishRecord(taskId, null);
    }

    /**
     * 查询指令结果，供管理端按任务号轮询。
     */
    public RpcTaskQueue.RpcTaskResult queryResult(long taskId) {
        return rpcTaskQueue.getResult(taskId);
    }

    /**
     * 定期把排队过久仍未被设备取走的指令判失败。
     *
     * <p>设备离线时指令会一直堆在队列里，挂在结果凭据上的工单步骤会永远等下去，
     * 工单既不前进也不结束。这里统一收尾，让工单带着失败继续推进或结束。</p>
     */
    @Scheduled(fixedDelayString = "${acs.agent.pending-sweep-interval:30000}")
    public void sweepPendingTasks() {
        List<Long> stale = rpcTaskRegistry.drainStale(pendingTimeoutSeconds * 1000L);
        if (stale.isEmpty()) {
            return;
        }
        log.warn("清理超时未执行的指令: 数量={}, 阈值={}s", stale.size(), pendingTimeoutSeconds);
        for (Long taskId : stale) {
            failTask(taskId, "8002", "指令超时未执行：设备未取走");
        }
    }

    /**
     * 工单号可能为非数字形态，解析失败时不阻塞下发流程。
     */
    private Long parseWorklistId(String worklistId) {
        if (worklistId == null || worklistId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(worklistId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long insertRecord(String sn, String rpcName,
                              Map<String, Object> args, String worklistId) {
        RpcRecord record = new RpcRecord();
        record.setRpcName(rpcName);
        record.setSn(sn);
        record.setWorklistId(parseWorklistId(worklistId));
        record.setStatus("init");
        record.setStartTime(LocalDateTime.now());
        try {
            record.setRequestArgs(objectMapper.writeValueAsString(args));
        } catch (Exception e) {
            record.setRequestArgs(String.valueOf(args));
        }
        try {
            rpcRecordMapper.insert(record);
            return record.getId();
        } catch (Exception e) {
            log.warn("RPC 记录入库失败，使用本地流水号: {}", e.getMessage());
            return rpcSequence.getAndIncrement();
        }
    }

    private void finishRecord(long rpcId, SoapMessage result) {
        try {
            RpcRecord update = new RpcRecord();
            update.setId(rpcId);
            update.setStatus(result == null || result.isFault() ? "fail" : "success");
            update.setFinishTime(LocalDateTime.now());
            if (result != null) {
                update.setResultContent(result.getStrResult());
            }
            rpcRecordMapper.updateById(update);
        } catch (Exception e) {
            log.warn("RPC 记录回写失败: {}", e.getMessage());
        }
    }

    private SoapMessage faultSoap(String faultCode, String faultString) {
        Map<String, Object> body = new HashMap<>();
        body.put("FaultCode", faultCode);
        body.put("FaultString", faultString);
        return SoapMessage.builder()
                .fault(FaultStruct.builder()
                        .faultCode(faultCode)
                        .faultString(faultString)
                        .build())
                .body(body)
                .strResult(faultString)
                .build();
    }

    /**
     * 提交句柄。
     */
    @Data
    public static class DispatchHandle {

        /** 任务号，同时是 rpc_record 主键。 */
        private final long taskId;

        /** 结果凭据，工单引擎通过回调串联步骤，不阻塞线程。 */
        private final CompletableFuture<SoapMessage> future;

        /** 是否成功入队。 */
        private final boolean queued;
    }
}
