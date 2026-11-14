package com.tr069.acs.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.cwmp.CwmpFaultCode;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import com.tr069.acs.common.log.AcsCoreLog;
import com.tr069.acs.queue.config.QueueProperties;
import jakarta.annotation.PostConstruct;

/** RPC 下发服务 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RpcDispatchService {

    private final RpcRecordMapper rpcRecordMapper;
    private final RpcTaskQueue rpcTaskQueue;
    private final RpcTaskRegistry rpcTaskRegistry;
    private final AcsCoreLog coreLog;
    private final QueueProperties queueProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 唤醒服务的延迟引用：DeviceCapabilityService 依赖本类，直接注入会成环 */
    private final org.springframework.beans.factory.ObjectProvider<
            com.tr069.acs.acs.service.DeviceCapabilityService> capabilityProvider;

    /** rpc_record 保留天数，0 表示永久保留。审计只留成功/失败摘要，过期行夜间分批清掉 */
    @Value("${acs.rpc.record-retention-days:7}")
    private int recordRetentionDays;

    /** 每晚分批清理过期 rpc_record：一次性 DELETE 上万行会长时间锁表，每批 5000 行 */
    @Scheduled(cron = "${acs.rpc.record-purge-cron:0 30 3 * * ?}")
    public void purgeExpiredRpcRecords() {
        if (recordRetentionDays <= 0) {
            return;
        }
        java.time.LocalDateTime before = LocalDateTime.now().minusDays(recordRetentionDays);
        int total = 0;
        try {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RpcRecord> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RpcRecord>()
                            .lt(RpcRecord::getCreatedAt, before)
                            .last("LIMIT 5000");
            while (true) {
                int deleted = rpcRecordMapper.delete(wrapper);
                total += deleted;
                if (deleted < 5000) {
                    break;
                }
            }
            if (total > 0) {
                log.info("rpc_record 过期清理完成: 删除 {} 行, 保留 {} 天内记录", total, recordRetentionDays);
            }
        } catch (Exception e) {
            log.warn("rpc_record 过期清理失败: {}", e.getMessage());
        }
    }

    /** RPC 审计流水号，入库失败时兜底 */
    private final AtomicLong rpcSequence = new AtomicLong(1);

    /** 方法名非法时的故障码：不是设备返回的 CWMP 故障，属 ACS 内部错误 */
    private static final String FAULT_BAD_METHOD = CwmpFaultCode.BAD_METHOD.getCode();

    /** ACS 内部错误故障码 */
    private static final String FAULT_INTERNAL = CwmpFaultCode.INTERNAL_ERROR.getCode();

    @Value("${acs.agent.rpc-timeout:510}")
    private long rpcTimeout;

    /** 指令在队列中等待被设备取走的最长时间，超时判失败 */
    @Value("${acs.agent.pending-timeout-seconds:600}")
    private long pendingTimeoutSeconds;

    /**
     * 进程启动时扫描 Redis 队列，把仍在排队的指令重新登记到 RpcTaskRegistry。
     * 进程重启后 RpcTaskRegistry 的内存 Map 清空，但 Redis 队列里的指令还在——
     * 如果不重建，这些指令的 CompletableFuture 永远不会完成，sweepPendingTasks 也扫不到。
     * 这里只登记 submitTime，让 sweepPendingTasks 在超时后自动判失败。
     */
    @PostConstruct
    public void rebuildPendingOnStartup() {
        if (!queueProperties.isEnabled()) {
            return;
        }
        try {
            List<String> devices = rpcTaskQueue.scanDevicesWithPending();
            if (devices.isEmpty()) {
                return;
            }
            int rebuilt = 0;
            int dead = 0;
            long staleMillis = pendingTimeoutSeconds * 1000L;
            long now = System.currentTimeMillis();
            for (String sn : devices) {
                for (RpcTaskQueue.RpcTask task : rpcTaskQueue.scanPendingTasks(sn)) {
                    // 排队时长按队列里的真实提交时间算：超过阈值的不进 pending 表干等一轮，
                    // 当场判死并摘出队列——否则每次重启都重建一遍、10 分钟后再集体失败一遍，永远循环
                    if (now - task.getCreatedAt() >= staleMillis) {
                        rpcTaskQueue.remove(sn, task.getTaskId());
                        failTask(task.getTaskId(), FAULT_INTERNAL, "排队超过阈值仍未被设备取走（重启清扫）");
                        dead++;
                        continue;
                    }
                    if (rpcTaskRegistry.createIfAbsent(task.getTaskId(), task.getCreatedAt()) != null) {
                        rebuilt++;
                    }
                }
            }
            if (rebuilt > 0) {
                log.info("进程重启后从 Redis 队列重建 pending 指令: 数量={}", rebuilt);
            }
            if (dead > 0) {
                log.warn("进程重启后清理排队超过阈值的残留指令: 数量={}, 阈值={}s", dead, pendingTimeoutSeconds);
            }
        } catch (Exception e) {
            log.warn("重建 pending 指令失败，等待 sweepPendingTasks 兜底: {}", e.getMessage());
        }
    }

    /** 提交一条指令，立即返回 */
    public DispatchHandle submitAsync(String sn, String rpcName,
                                      Map<String, Object> args, String worklistId) {
        return submitAsync(sn, rpcName, args, worklistId, null);
    }

    /** 丢弃上一轮采集压在队列里的旧指令（见 {@link RpcTaskQueue#dropStale}） */
    public int dropStaleCollectTasks(String sn, String currentBatch) {
        return rpcTaskQueue.dropStale(sn, currentBatch);
    }

    /** 指令重排退避后刷新排队时间戳，避免清扫按首次提交时间把它误判为超时（重试次数未用完就判死） */
    public void touchPending(long taskId) {
        rpcTaskRegistry.touch(taskId);
    }

    /** 同 {@link #submitAsync(String, String, Map, String)}，多带一个采集批次号 */
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
            rpcTaskRegistry.fail(taskId, CwmpFaultCode.QUEUE_FAILURE.getCode(), "指令入队失败：队列不可用或该设备队列已满");
            finishRecord(taskId, null);
            return new DispatchHandle(taskId, future, false);
        }
        rpcTaskQueue.saveQueued(task);
        // 设备不在下发窗口时发一次连接请求（内部带节流，不阻塞调用方）：设备建链后的下一跳空 POST 会把指令取走
        capabilityProvider.ifAvailable(capability -> capability.wakeUp(sn));
        log.debug("指令已入队: taskId={}, sn={}, rpc={}", taskId, sn, rpcName);
        return new DispatchHandle(taskId, future, true);
    }

    /** 同步等待版本，保留给需要立即拿到结果的调用方 */
    public SoapMessage dispatch(String sn, String rpcName,
                                Map<String, Object> args, String worklistId) {
        DispatchHandle handle = submitAsync(sn, rpcName, args, worklistId);
        if (!handle.isQueued()) {
            return faultSoap(CwmpFaultCode.QUEUE_FAILURE.getCode(), "指令入队失败：队列不可用或该设备队列已满");
        }
        try {
            return handle.getFuture().get(rpcTimeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("RPC 执行超时: sn={}, rpc={}, taskId={}", sn, rpcName, handle.getTaskId());
            failTask(handle.getTaskId(), FAULT_INTERNAL, "RPC 执行超时");
            return faultSoap(FAULT_INTERNAL, "RPC 执行超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failTask(handle.getTaskId(), FAULT_INTERNAL, "RPC 执行被中断");
            return faultSoap(FAULT_INTERNAL, "RPC 执行被中断");
        } catch (ExecutionException e) {
            log.error("RPC 执行异常: sn={}, rpc={}", sn, rpcName, e);
            failTask(handle.getTaskId(), FAULT_INTERNAL, String.valueOf(e.getMessage()));
            return faultSoap(FAULT_INTERNAL, String.valueOf(e.getMessage()));
        }
    }

    /** 设备响应到达：回填 Redis 结果、完成结果凭据、回写审计记录 */
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
            result2.setResultContent("fault=" + result.getFault().getFaultCode()
                    + " " + result.getFault().getFaultString());
        } else {
            result2.setResultContent("成功");
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

    /** 指令以失败结束：下发超时、会话关闭、设备无响应等 */
    public void failTask(long taskId, String faultCode, String faultString) {
        RpcTaskQueue.RpcTaskResult stored = rpcTaskQueue.getResult(taskId);
        RpcTaskQueue.RpcTaskResult result = new RpcTaskQueue.RpcTaskResult();
        result.setTaskId(taskId);
        if (stored != null) {
            result.setSn(stored.getSn());
            result.setRpcName(stored.getRpcName());
            result.setSubmitTime(stored.getSubmitTime());
        }
        // Redis 结果哈希过期时（老任务）从审计记录兜底，核心日志不再出现 sn=null
        if (result.getSn() == null || result.getRpcName() == null) {
            try {
                RpcRecord record = rpcRecordMapper.selectById(taskId);
                if (record != null) {
                    result.setSn(record.getSn());
                    result.setRpcName(record.getRpcName());
                }
            } catch (Exception ignored) {
            }
        }
        result.setStatus("fail");
        result.setFaultCode(faultCode);
        result.setFaultString(faultString);
        result.setFinishTime(System.currentTimeMillis());
        rpcTaskQueue.saveResult(result);

        // 判死的同时把任务从设备队列摘掉，否则它一直留在队列里，重启后被重建、再被清扫，永远循环
        rpcTaskQueue.remove(result.getSn(), taskId);

        // 核心日志：超时、会话关闭等失败同样记一行
        coreLog.rpcResult(result.getSn(), result.getRpcName(), taskId, false, faultCode, faultString);

        rpcTaskRegistry.fail(taskId, faultCode, faultString);
        finishRecord(taskId, null);
    }

    /** 查询指令结果，供管理端按任务号轮询 */
    public RpcTaskQueue.RpcTaskResult queryResult(long taskId) {
        return rpcTaskQueue.getResult(taskId);
    }

    /**
     * 定期清理两类指令：
     * 1. Registry 中排队过久仍未被设备取走的——超时判失败
     * 2. Redis 结果中处于 running 但 Registry 已无记录的——poll 取出后进程崩溃/重启的孤儿指令
     */
    @Scheduled(fixedDelayString = "${acs.agent.pending-sweep-interval:30000}")
    public void sweepPendingTasks() {
        // 第一类：Registry 内存表中超时的排队指令
        List<Long> stale = rpcTaskRegistry.drainStale(pendingTimeoutSeconds * 1000L);
        if (!stale.isEmpty()) {
            log.warn("清理超时未执行的指令: 数量={}, 阈值={}s", stale.size(), pendingTimeoutSeconds);
            for (Long taskId : stale) {
                failTask(taskId, FAULT_INTERNAL, "指令超时未执行：设备未取走");
            }
        }
    }

    /** 工单号可能为非数字形态，解析失败时不阻塞下发流程 */
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
        record.setRequestArgs(summarizeArgs(args));
        try {
            rpcRecordMapper.insert(record);
            return record.getId();
        } catch (Exception e) {
            log.warn("RPC 记录入库失败，使用本地流水号: {}", e.getMessage());
            return rpcSequence.getAndIncrement();
        }
    }

    /**
     * 请求参数摘要：只记结构不记值——GPV 的路径清单、SPV 的参数键值动辄几十 KB，
     * 原样入库把 rpc_record 撑到几百 MB（万台规模直接爆盘）。审计要的是
     * 「哪台设备、什么指令、成功没有」，完整报文在 ACS_CORE 日志里已经有一份
     */
    private String summarizeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            Object v = e.getValue();
            if (v instanceof Collection<?> c) {
                sb.append(e.getKey()).append("[n=").append(c.size()).append(']');
            } else if (v instanceof Map<?, ?> m) {
                sb.append(e.getKey()).append("[m=").append(m.size()).append(']');
            } else {
                String s = String.valueOf(v);
                sb.append(e.getKey()).append('=')
                        .append(s.length() <= 80 ? s : "len=" + s.length());
            }
            if (sb.length() >= 200) {
                sb.append("...");
                break;
            }
        }
        return sb.toString();
    }

    private void finishRecord(long rpcId, SoapMessage result) {
        try {
            RpcRecord update = new RpcRecord();
            update.setId(rpcId);
            update.setStatus(result == null || result.isFault() ? "fail" : "success");
            update.setFinishTime(LocalDateTime.now());
            if (result != null && result.isFault()) {
                update.setResultContent("fault=" + result.getFault().getFaultCode()
                        + " " + result.getFault().getFaultString());
            } else {
                update.setResultContent("成功");
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

    /** 提交句柄 */
    @Data
    public static class DispatchHandle {

        /** 任务号，同时是 rpc_record 主键 */
        private final long taskId;

        /** 结果凭据，工单引擎通过回调串联步骤，不阻塞线程 */
        private final CompletableFuture<SoapMessage> future;

        /** 是否成功入队 */
        private final boolean queued;
    }
}
