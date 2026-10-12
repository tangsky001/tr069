package com.tr069.acs.worklist.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.SessionManager;
import com.tr069.acs.agent.RpcDispatchService;
import com.tr069.acs.cwmp.RpcMethod;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.domain.entity.Worklist;
import com.tr069.acs.domain.entity.WorklistStepRecord;
import com.tr069.acs.mapper.WorklistMapper;
import com.tr069.acs.mapper.WorklistStepMapper;
import com.tr069.acs.worklist.loader.LoadedItem;
import com.tr069.acs.worklist.model.DeviceProfile;
import com.tr069.acs.worklist.model.StepOutcome;
import com.tr069.acs.worklist.model.WorklistArg;
import com.tr069.acs.worklist.model.WorklistOutcome;
import com.tr069.acs.worklist.model.WorklistStep;
import com.tr069.acs.worklist.model.WorklistTemplate;
import com.tr069.acs.worklist.registry.WorklistRegistry;
import com.tr069.acs.worklist.service.DeviceProfileMatcher;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 工单执行引擎。
 *
 * <p>负责把一张 {@link WorklistTemplate} 顺序展开为若干 CWMP RPC 下发到设备，
 * 全程记录到 {@code worklist} 与 {@code worklist_step}，失败可精确到具体步骤。</p>
 *
 * <p>与原版最大的差别：原版依靠每个工单目录下的 script.py 组织调用顺序，
 * 这里由模板的 steps 描述，配合设备适配档案的参数别名与能力开关，
 * 使得同一份业务工单可以跨厂商设备复用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorklistEngine {

    private final WorklistRegistry worklistRegistry;
    private final DeviceProfileMatcher profileMatcher;
    private final SessionManager sessionManager;
    private final RpcDispatchService rpcDispatchService;
    private final WorklistMapper worklistMapper;
    private final WorklistStepMapper worklistStepMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 工单执行线程数，即同时在执行的工单上限，避免大量工单把资源吃满。
     */
    @Value("${acs.agent.worklist-executor-threads:16}")
    private int executorThreads;

    /**
     * 工单执行线程池。用可调度的实现，步骤间的下发间隔靠它延时，不需要线程 sleep。
     */
    private volatile ScheduledThreadPoolExecutor executor;

    /**
     * 执行一张工单。
     *
     * @param sn          设备序列号
     * @param templateKey 工单模板 key，如 CT/v3.0/business/ADSL_4+1/WAN_ADD
     * @param params      工单入参，未提供的必填参数取模板默认值
     * @param username    操作人
     * @return 执行结果，含每一步明细
     */
    /**
     * 提交一张工单，立即返回工单号。
     *
     * <p>步骤执行放到自有线程池，不再占用 Tomcat 线程：一张 N 步工单要等 N 次
     * 设备空 POST 往返，同步执行会把请求线程挂住几分钟，设备一多就把线程池打满。</p>
     *
     * @return 提交结果，被拒绝时含原因；受理后可用工单号查询进度
     */
    public WorklistSubmission submit(String sn, String templateKey,
                                     Map<String, String> params, String username) {
        LoadedItem<WorklistTemplate> item = worklistRegistry.get(templateKey).orElse(null);
        if (item == null || !item.isValid()) {
            return WorklistSubmission.rejected("工单模板不存在或解析失败: " + templateKey);
        }
        WorklistTemplate template = item.getValue();
        // 存量工单只迁了参数定义，执行步骤还在原 script.py 里。
        // 这里必须拦住，否则空步骤会被当成「零步全部成功」，现场误以为业务已开通。
        if (!template.isStepsMigrated()) {
            String src = template.getSourceScript();
            return WorklistSubmission.rejected("该工单的执行步骤尚未迁移，暂不可下发"
                    + (src == null || src.isBlank() ? "" : "，请参照原脚本 " + src));
        }

        List<String> errors = new ArrayList<>();
        Map<String, String> usedParams = mergeParams(template, params, errors);
        if (!errors.isEmpty()) {
            return WorklistSubmission.rejected(String.join("; ", errors));
        }

        // 设备属性在这里取好，避免异步执行时设备状态已变化导致匹配结果不一致
        Map<String, String> deviceVars = collectDeviceVars(sn);
        DeviceProfile profile = profileMatcher.match(
                deviceVars.get("manufacturer"),
                deviceVars.get("oui"),
                deviceVars.get("productClass"),
                deviceVars.get("softwareVersion"),
                sn).orElse(null);

        Long wlId = createWorklist(template, sn, usedParams, username);
        markWorklistStatus(wlId, "running", "time_exec_start");

        long begin = System.currentTimeMillis();
        CompletableFuture<WorklistOutcome> future =
                runStepsAsync(sn, template, usedParams, deviceVars, profile, wlId, begin);
        return WorklistSubmission.accepted(wlId, future);
    }

    /**
     * 同步等待执行完成，保留给需要立即拿结果的场景，内部仍走队列。
     */
    public WorklistOutcome execute(String sn, String templateKey,
                                   Map<String, String> params, String username) {
        WorklistSubmission submission = submit(sn, templateKey, params, username);
        if (!submission.isAccepted()) {
            return WorklistOutcome.builder()
                    .sn(sn)
                    .templateKey(templateKey)
                    .success(false)
                    .error(submission.getError())
                    .build();
        }
        return submission.getFuture().join();
    }

    /**
     * 顺序执行各步骤并落库。
     *
     * <p>每一步都是「提交指令 + 回调处理」，等待设备响应的那段时间不占用任何线程：
     * 指令要等设备下一次空 POST 才被取走，短则几秒、长则几分钟，同步等待会把
     * 工单线程池迅速挂满。这里用 CompletableFuture 串行串联，线程只在提交与
     * 落成结果时短暂工作。</p>
     */
    private CompletableFuture<WorklistOutcome> runStepsAsync(String sn, WorklistTemplate template,
                                                             Map<String, String> usedParams,
                                                             Map<String, String> deviceVars,
                                                             DeviceProfile profile, Long wlId,
                                                             long begin) {
        ExecutionContext ctx = new ExecutionContext(usedParams,
                profile == null ? Map.of() : profile.getParameterAliases(),
                deviceVars);

        WorklistOutcome.WorklistOutcomeBuilder outcome = WorklistOutcome.builder()
                .sn(sn)
                .templateKey(template.getKey())
                .worklistId(wlId);
        if (profile != null) {
            outcome.profileKey(profile.getKey());
        }

        List<StepOutcome> steps = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> lastError = new AtomicReference<>();
        String worklistIdStr = String.valueOf(wlId);

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < template.getSteps().size(); i++) {
            WorklistStep step = template.getSteps().get(i);
            int index = i;
            chain = chain.thenComposeAsync(ignored ->
                    executeStepAsync(sn, worklistIdStr, index, step, ctx, profile)
                            .thenAccept(result -> {
                                steps.add(result);
                                if (!"success".equals(result.getStatus())) {
                                    lastError.set(result.getError());
                                    result.setContinued(step.isAbortOnError());
                                    if (step.isAbortOnError()) {
                                        // 抛出去中断后续步骤，最终由 exceptionally 收尾
                                        throw new StepAbortException(result.getError());
                                    }
                                }
                            }), executor());
        }

        return chain.thenApply(ignored -> {
            String error = lastError.get();
            finishWorklist(wlId, error == null, steps, error);
            return outcome.success(error == null)
                    .steps(steps)
                    .error(error)
                    .durationMs(System.currentTimeMillis() - begin)
                    .build();
        }).exceptionally(ex -> {
            Throwable cause = ex instanceof CompletionException && ex.getCause() != null
                    ? ex.getCause() : ex;
            String error = cause.getMessage() == null ? String.valueOf(cause) : cause.getMessage();
            log.error("工单执行异常终止: sn={}, wlId={}, err={}", sn, wlId, error);
            finishWorklist(wlId, false, steps, error);
            return outcome.success(false)
                    .steps(steps)
                    .error(error)
                    .durationMs(System.currentTimeMillis() - begin)
                    .build();
        });
    }

    /**
     * 工单执行线程池，并发数即同时在执行的工单上限，起到背压作用。
     */
    private ScheduledThreadPoolExecutor executor() {
        if (executor == null) {
            synchronized (this) {
                if (executor == null) {
                    ScheduledThreadPoolExecutor pool =
                            new ScheduledThreadPoolExecutor(executorThreads, r -> {
                                Thread t = new Thread(r, "acs-worklist-exec");
                                t.setDaemon(true);
                                return t;
                            });
                    pool.setRemoveOnCancelPolicy(true);
                    executor = pool;
                }
            }
        }
        return executor;
    }

    /**
     * 工单提交结果。
     */
    @Data
    public static class WorklistSubmission {

        /** 工单号，受理后有值。 */
        private final Long worklistId;

        /** 是否被受理。 */
        private final boolean accepted;

        /** 被拒绝的原因。 */
        private final String error;

        /** 执行结果凭据，受理后有值，可据此等待执行完成。 */
        private final CompletableFuture<WorklistOutcome> future;

        public static WorklistSubmission accepted(Long worklistId,
                                                  CompletableFuture<WorklistOutcome> future) {
            return new WorklistSubmission(worklistId, true, null, future);
        }

        public static WorklistSubmission rejected(String error) {
            return new WorklistSubmission(null, false, error, null);
        }
    }

    /**
     * 步骤要求中止时抛出，用于中断后续步骤的编排链。
     */
    private static class StepAbortException extends RuntimeException {

        StepAbortException(String message) {
            super(message);
        }
    }

    /**
     * 执行单个步骤：提交指令后立即返回，设备响应由回调处理。
     */
    private CompletableFuture<StepOutcome> executeStepAsync(String sn, String worklistId, int index,
                                                            WorklistStep step, ExecutionContext ctx,
                                                            DeviceProfile profile) {
        long begin = System.currentTimeMillis();
        StepOutcome.StepOutcomeBuilder outcome = StepOutcome.builder()
                .index(index)
                .comment(step.getComment())
                .rpc(step.getRpc())
                .status("init");

        Map<String, Object> args;
        try {
            args = StepArgsFactory.build(step, ctx);
        } catch (Exception e) {
            log.error("工单步骤参数构造失败: {}", e.getMessage());
            outcome.status("fail").error("参数构造失败: " + e.getMessage())
                    .durationMs(System.currentTimeMillis() - begin);
            saveStep(worklistId, index, step, null, null, outcome.build());
            return CompletableFuture.completedFuture(outcome.build());
        }
        outcome.request(args);

        return submitAfterDelay(sn, step.getRpc(), args, worklistId, delayMillis(profile))
                .thenApply(response -> {
                    outcome.durationMs(System.currentTimeMillis() - begin);
                    outcome.response(response == null ? null : response.getBody());
                    if (response == null || response.isFault()) {
                        outcome.status("fail").error(faultMessage(response));
                    } else {
                        outcome.status("success");
                        saveAs(ctx, step, response);
                    }
                    StepOutcome done = outcome.build();
                    saveStep(worklistId, index, step, args, response, done);
                    return done;
                })
                .exceptionally(ex -> {
                    Throwable cause = ex instanceof CompletionException && ex.getCause() != null
                            ? ex.getCause() : ex;
                    String error = cause.getMessage() == null ? String.valueOf(cause) : cause.getMessage();
                    log.error("下发 RPC 异常: sn={}, rpc={}, err={}", sn, step.getRpc(), error);
                    outcome.status("fail").error(error)
                            .durationMs(System.currentTimeMillis() - begin);
                    StepOutcome done = outcome.build();
                    saveStep(worklistId, index, step, args, null, done);
                    return done;
                });
    }

    /**
     * 提交指令，需要间隔时交给调度器延后，不在线程里 sleep。
     */
    private CompletableFuture<SoapMessage> submitAfterDelay(String sn, String rpcName,
                                                            Map<String, Object> args,
                                                            String worklistId, long delayMillis) {
        if (delayMillis <= 0) {
            return submitRpc(sn, rpcName, args, worklistId);
        }
        CompletableFuture<SoapMessage> result = new CompletableFuture<>();
        executor().schedule(() -> submitRpc(sn, rpcName, args, worklistId)
                .whenComplete((msg, ex) -> {
                    if (ex != null) {
                        result.completeExceptionally(ex);
                    } else {
                        result.complete(msg);
                    }
                }), delayMillis, TimeUnit.MILLISECONDS);
        return result;
    }

    /**
     * 入队即返回。入队失败时凭据已被填成失败结果，这里不会悬挂。
     */
    private CompletableFuture<SoapMessage> submitRpc(String sn, String rpcName,
                                                     Map<String, Object> args, String worklistId) {
        RpcDispatchService.DispatchHandle handle =
                rpcDispatchService.submitAsync(sn, rpcName, args, worklistId);
        if (!handle.isQueued()) {
            log.warn("指令未入队: sn={}, rpc={}, taskId={}", sn, rpcName, handle.getTaskId());
        }
        return handle.getFuture();
    }

    /**
     * 把本步结果存入上下文，供后续步骤 {@code ${ctx.xxx}} 引用。
     */
    private void saveAs(ExecutionContext ctx, WorklistStep step, SoapMessage response) {
        String name = step.getSaveAs();
        if (name == null || name.isBlank()) {
            return;
        }
        RpcMethod rpc = step.resolveRpc();
        Object value;
        if (rpc == RpcMethod.ADD_OBJECT) {
            value = response.stringValue("InstanceNumber");
        } else if (rpc == RpcMethod.GET_PARAMETER_VALUES
                || rpc == RpcMethod.GET_PARAMETER_NAMES
                || rpc == RpcMethod.GET_PARAMETER_ATTRIBUTES) {
            value = response.getBody().get("ParameterList");
        } else {
            value = response.getBody();
        }
        ctx.put(name, value);
    }

    /**
     * 部分设备连续下发需要间隔（由适配档案的 delayAfterSetMillis 控制），
     * 避免设备处理不过来导致后续步骤失败。
     */
    private long delayMillis(DeviceProfile profile) {
        if (profile == null || profile.getCapability() == null) {
            return 0;
        }
        long millis = profile.getCapability().getDelayAfterSetMillis();
        return millis <= 0 ? 0 : millis;
    }

    private String faultMessage(SoapMessage response) {
        if (response == null) {
            return "设备无响应";
        }
        if (response.isFault()) {
            return "FaultCode=" + response.getFault().getFaultCode()
                    + ", FaultString=" + response.getFault().getFaultString();
        }
        return "未知错误";
    }

    // ==================== 参数处理 ====================

    /**
     * 合并模板默认值与调用方入参，并做必填与枚举校验。
     */
    private Map<String, String> mergeParams(WorklistTemplate template,
                                            Map<String, String> input,
                                            List<String> errors) {
        Map<String, String> merged = new LinkedHashMap<>();
        Map<String, String> safeInput = input == null ? Map.of() : input;

        for (Map.Entry<String, WorklistArg> entry : template.getArgs().entrySet()) {
            String key = entry.getKey();
            WorklistArg arg = entry.getValue();
            String value = safeInput.get(key);
            if (value == null || value.isBlank()) {
                value = arg.getDefaultValue();
            }
            if ((value == null || value.isBlank()) && arg.isRequired()) {
                errors.add("缺少必填参数: " + key);
                continue;
            }
            if (arg.getEnumValues() != null && !arg.getEnumValues().isEmpty()
                    && value != null && !arg.getEnumValues().contains(value)) {
                errors.add("参数 " + key + " 取值非法: " + value
                        + "，可选值 " + arg.getEnumValues());
                continue;
            }
            merged.put(key, value);
        }
        // 模板未声明的参数一并透传，方便临时扩展而不用改模板
        for (Map.Entry<String, String> e : safeInput.entrySet()) {
            merged.putIfAbsent(e.getKey(), e.getValue());
        }
        return merged;
    }

    /**
     * 采集设备属性作为模板内置变量。
     */
    private Map<String, String> collectDeviceVars(String sn) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("sn", sn);
        CpeSession session = sessionManager.find(sn);
        if (session != null) {
            putIfNotEmpty(vars, "oui", session.getOui());
            putIfNotEmpty(vars, "serialNumber", session.getSerialNumber());
            putIfNotEmpty(vars, "manufacturer", session.getManufacturer());
            putIfNotEmpty(vars, "productClass", session.getProductClass());
            putIfNotEmpty(vars, "softwareVersion", session.getSoftwareVersion());
            putIfNotEmpty(vars, "hardwareVersion", session.getHardwareVersion());
            putIfNotEmpty(vars, "rootNode", session.getRootNode());
        }
        return vars;
    }

    private void putIfNotEmpty(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    // ==================== 落库 ====================

    private Long createWorklist(WorklistTemplate template, String sn,
                                Map<String, String> params, String username) {
        Worklist record = new Worklist();
        record.setWorklistDesc(template.getKey() + "@" + sn + "@" + System.currentTimeMillis());
        record.setWorklistName(template.getName());
        record.setWorklistType("physic");
        record.setSn(sn);
        record.setIsp(template.getIsp());
        record.setIspVersion(template.getVersion());
        record.setDomain(template.getDeviceType());
        record.setStatus("build");
        record.setRollbackFlag(0);
        record.setUsername(username);
        record.setTimeInit(LocalDateTime.now());
        record.setTimeBind(LocalDateTime.now());
        try {
            record.setParameters(objectMapper.writeValueAsString(params));
        } catch (Exception e) {
            record.setParameters(String.valueOf(params));
        }
        try {
            worklistMapper.insert(record);
            return record.getId();
        } catch (Exception e) {
            log.warn("工单记录入库失败，仅本地执行: {}", e.getMessage());
            return null;
        }
    }

    private void markWorklistStatus(Long wlId, String status, String timeField) {
        if (wlId == null) {
            return;
        }
        try {
            Worklist update = new Worklist();
            update.setId(wlId);
            update.setStatus(status);
            LocalDateTime now = LocalDateTime.now();
            if ("time_exec_start".equals(timeField)) {
                update.setTimeExecStart(now);
            } else if ("time_exec_finish".equals(timeField)) {
                update.setTimeExecFinish(now);
            }
            worklistMapper.updateById(update);
        } catch (Exception e) {
            log.warn("工单状态回写失败: {}", e.getMessage());
        }
    }

    private void finishWorklist(Long wlId, boolean success,
                                List<StepOutcome> steps, String error) {
        if (wlId == null) {
            return;
        }
        try {
            Worklist update = new Worklist();
            update.setId(wlId);
            update.setStatus(success ? "success" : "fail");
            update.setTimeExecFinish(LocalDateTime.now());
            StringBuilder sb = new StringBuilder();
            for (StepOutcome s : steps) {
                sb.append(s.getStatus()).append(":").append(s.getRpc()).append("\n");
            }
            if (error != null) {
                sb.append("error: ").append(error);
            }
            update.setResult(sb.toString());
            worklistMapper.updateById(update);
        } catch (Exception e) {
            log.warn("工单结果回写失败: {}", e.getMessage());
        }
    }

    private void saveStep(String worklistId, int index, WorklistStep step,
                          Map<String, Object> args, SoapMessage response,
                          StepOutcome outcome) {
        if (worklistId == null || "null".equals(worklistId)) {
            return;
        }
        try {
            WorklistStepRecord record = new WorklistStepRecord();
            record.setWorklistId(Long.parseLong(worklistId));
            record.setStepIndex(index);
            record.setComment(step.getComment());
            record.setRpc(step.getRpc());
            record.setRequestArgs(toJson(args));
            record.setResponseContent(response == null ? null : toJson(response.getBody()));
            record.setStatus(outcome.getStatus());
            record.setErrorMsg(outcome.getError());
            record.setDurationMs(outcome.getDurationMs());
            worklistStepMapper.insert(record);
        } catch (Exception e) {
            log.warn("工单步骤明细入库失败: {}", e.getMessage());
        }
    }

    private String toJson(Object obj) {
        try {
            return obj == null ? null : objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
