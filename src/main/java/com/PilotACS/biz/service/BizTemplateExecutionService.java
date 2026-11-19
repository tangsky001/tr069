package com.PilotACS.biz.service;

import com.PilotACS.biz.entity.BizTemplate;
import com.PilotACS.biz.entity.BizTemplateExecution;
import com.PilotACS.biz.entity.BizTemplateItem;
import com.PilotACS.biz.mapper.BizTemplateExecutionMapper;
import com.PilotACS.biz.mapper.BizTemplateMapper;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.exception.BusinessException;
import com.PilotACS.template.exec.IntentDeliveryService;
import com.PilotACS.worklist.entity.Worklist;
import com.PilotACS.worklist.entity.WorklistStepRecord;
import com.PilotACS.worklist.mapper.WorklistMapper;
import com.PilotACS.worklist.mapper.WorklistStepMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 业务模板整包下发执行器：串行逐成员下发（意图下发服务按机型翻译）， */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizTemplateExecutionService {

    private final BizTemplateMapper templateMapper;
    private final BizTemplateExecutionMapper executionMapper;
    private final BizTemplateService bizTemplateService;
    private final ConfigTemplateResolver templateResolver;
    private final IntentDeliveryService intentDeliveryService;
    private final CpeStore store;
    private final ObjectMapper objectMapper;
    /** 工单留痕：页面直接触发的整包下发也要建工单（下发与工单必须可互相追溯） */
    private final WorklistMapper worklistMapper;
    private final WorklistStepMapper stepMapper;

    /** 执行线程池：同一时间只跑一个整包下发（逐成员串行足够） */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "biz-template-executor");
        t.setDaemon(true);
        return t;
    });

    // ==================== 入口 ====================

    /** 提交整包下发：建执行记录后交给后台线程，返回记录 id 供轮询。
     *  linkedWorklistId：经工单触发的传工单 id（工单由调用方回写，不重复建单）；
     *  页面直接触发传 null，这里自动建一条 sending 工单留痕并在收尾回写。 */
    public Long execute(Long templateId, String sn, Map<String, Map<String, String>> itemParams,
                        String roomNo, String username) {
        return execute(templateId, sn, itemParams, roomNo, username, null);
    }

    public Long execute(Long templateId, String sn, Map<String, Map<String, String>> itemParams,
                        String roomNo, String username, Long linkedWorklistId) {
        BizTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new BusinessException("业务模板不存在: " + templateId);
        }
        if (template.getEnabled() == null || !template.getEnabled()) {
            throw new BusinessException("业务模板已停用: " + template.getName());
        }
        List<BizTemplateItem> items = bizTemplateService.itemsOf(templateId);
        if (items.isEmpty()) {
            throw new BusinessException("业务模板没有任何成员，无法下发");
        }
        List<String> problems = bizTemplateService.validate(templateId);
        if (!problems.isEmpty()) {
            throw new BusinessException("模板校验未通过：" + String.join("；", problems));
        }

        BizTemplateExecution exec = new BizTemplateExecution();
        exec.setTemplateId(templateId);
        exec.setTemplateName(template.getName());
        exec.setSn(sn);
        exec.setStatus("running");
        exec.setUsername(username);
        exec.setTotalCount(items.size());
        exec.setFinishedCount(0);
        exec.setSuccessCount(0);
        exec.setFailCount(0);
        exec.setResult("[]");
        executionMapper.insert(exec);
        Long execId = exec.getId();

        // 页面直接触发的整包下发：自动建工单留痕（status=sending，调度器只扫 standby/reserve，
        // 不会重复执行；经工单触发的由 WorklistDeliveryService 回写状态，这里不碰）
        Long createdWorklistId = null;
        if (linkedWorklistId == null) {
            try {
                createdWorklistId = createTraceWorklist(template, sn, execId, username);
            } catch (Exception e) {
                log.warn("整包下发工单留痕失败 execId={}: {}", execId, e.getMessage());
            }
        }

        boolean continueOnError = template.getContinueOnError() != null && template.getContinueOnError();
        Map<String, Map<String, String>> overrides = itemParams == null ? new HashMap<>() : itemParams;
        final Long traceId = createdWorklistId;
        executor.submit(() -> run(execId, sn, items, overrides, roomNo, username, continueOnError,
                traceId));
        return execId;
    }

    /** 直接触发的整包下发建留痕工单 + 首条步骤记录 */
    private Long createTraceWorklist(BizTemplate template, String sn, Long execId, String username) {
        Worklist w = new Worklist();
        w.setWorklistDesc(String.format(Locale.ROOT, "ADMIN-BIZ-%s-%d",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")), execId));
        w.setWorklistName("整包下发 · " + template.getName());
        w.setWorklistType("logic");
        w.setSn(sn);
        w.setStatus("sending");
        w.setBizType("biz");
        w.setActive(1);
        w.setParameters("{\"bizTemplateId\":\"" + template.getId() + "\",\"execId\":\"" + execId + "\"}");
        w.setUsername(username);
        w.setRollbackFlag(0);
        LocalDateTime now = LocalDateTime.now();
        w.setTimeInit(now);
        w.setTimeExecStart(now);
        w.setCreatedAt(now);
        w.setUpdatedAt(now);
        worklistMapper.insert(w);
        WorklistStepRecord step = new WorklistStepRecord();
        step.setWorklistId(w.getId());
        step.setStepIndex(0);
        step.setComment("整包下发 · " + template.getName());
        step.setRpc("BizTemplate");
        step.setRequestArgs("{\"templateId\":" + template.getId() + ",\"execId\":" + execId + "}");
        step.setStatus("init");
        step.setCreatedAt(now);
        stepMapper.insert(step);
        log.info("整包下发已留痕工单: worklistId={}, execId={}, sn={}", w.getId(), execId, sn);
        return w.getId();
    }

    // ==================== 执行 ====================

    /** 后台串行执行整包，逐成员写入执行记录；traceWorklistId 非空时收尾回写留痕工单 */
    private void run(Long execId, String sn, List<BizTemplateItem> items,
                     Map<String, Map<String, String>> overrides, String roomNo,
                     String username, boolean continueOnError, Long traceWorklistId) {
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0;
        int fail = 0;
        boolean aborted = false;
        try {
            for (BizTemplateItem item : items) {
                Long key = parseKey(item.getTemplateKey());
                Map<String, Object> cfg = key == null ? null : templateResolver.findView(key);
                String category = cfg == null ? "" : String.valueOf(cfg.get("category"));

                BizTemplateExecution exec = executionMapper.selectById(execId);
                if (exec != null) {
                    exec.setCurrentKey(item.getTemplateKey());
                    executionMapper.updateById(exec);
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("templateKey", item.getTemplateKey());
                row.put("name", cfg == null ? "(模板已删除)" : cfg.get("name"));
                row.put("category", category);
                row.put("categoryLabel", cfg == null ? "" : cfg.get("categoryLabel"));

                if (aborted || cfg == null) {
                    row.put("status", aborted ? "skipped" : "fail");
                    row.put("message", aborted ? "前序成员失败，已跳过" : "配置模板不存在: " + item.getTemplateKey());
                    results.add(row);
                    if (!aborted) {
                        fail++;
                        aborted = true;
                    }
                    persist(execId, results, success, fail, null);
                    continue;
                }

                // 合并入参：配置模板字段打底，成员入参覆盖
                Map<String, Object> fields = new LinkedHashMap<>(templateResolver.fieldsOf(key));
                Map<String, String> override = overrides.get(item.getTemplateKey());
                if (override != null) {
                    fields.putAll(override);
                }
                boolean incremental = templateResolver.incrementalOf(key);

                Map<String, Object> result = intentDeliveryService.deliver(sn, category, fields,
                        incremental, username);
                boolean ok = Boolean.TRUE.equals(result.get("success"));
                // 每个成员的独立下发日志（意图下发内部还会打每一步 RPC 的明细）
                log.info("[下发] 整包成员 execId={} sn={} 模板={}({}) 结果={}{}",
                        execId, sn, row.get("name"), category, ok ? "成功" : "失败",
                        ok ? "" : "，原因=" + result.get("error"));
                row.put("status", ok ? "success" : "fail");
                row.put("message", ok ? "下发成功" : String.valueOf(result.get("error")));
                row.put("detail", result.get("detail"));
                results.add(row);
                if (ok) {
                    success++;
                } else {
                    fail++;
                    if (!continueOnError) {
                        aborted = true;
                    }
                }
                persist(execId, results, success, fail, item.getTemplateKey());

                // 房间号同步到设备资料（首个成员成功且要求同步时）
                if (ok && roomNo != null && !roomNo.isBlank() && success == 1) {
                    try {
                        store.updateDeviceColumns(sn, Map.of("room_no", roomNo));
                    } catch (Exception e) {
                        log.warn("房间号同步失败 sn={}: {}", sn, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("业务模板整包下发异常 execId={}", execId, e);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", "fail");
            row.put("message", "执行异常: " + e.getMessage());
            results.add(row);
            fail++;
        }
        finish(execId, results, success, fail, aborted, traceWorklistId);
    }

    /** 增量保存执行进度 */
    private void persist(Long execId, List<Map<String, Object>> results, int success, int fail, String currentKey) {
        BizTemplateExecution exec = executionMapper.selectById(execId);
        if (exec == null) {
            return;
        }
        exec.setFinishedCount(success + fail);
        exec.setSuccessCount(success);
        exec.setFailCount(fail);
        if (currentKey != null) {
            exec.setCurrentKey(currentKey);
        }
        exec.setResult(writeJson(results));
        executionMapper.updateById(exec);
    }

    /** 收尾：更新状态与结束时间；留痕工单（页面直接触发时创建的）同步回写终态 */
    private void finish(Long execId, List<Map<String, Object>> results, int success, int fail,
                        boolean aborted, Long traceWorklistId) {
        BizTemplateExecution exec = executionMapper.selectById(execId);
        if (exec == null) {
            return;
        }
        exec.setFinishedCount(success + fail);
        exec.setSuccessCount(success);
        exec.setFailCount(fail);
        exec.setResult(writeJson(results));
        exec.setCurrentKey("");
        exec.setFinishedAt(LocalDateTime.now());
        String status = fail == 0 ? "success" : (aborted && success == 0 ? "fail" : "partial");
        exec.setStatus(status);
        if (aborted && fail > 0) {
            exec.setErrorMessage("存在失败成员，后续已跳过");
        }
        executionMapper.updateById(exec);
        log.info("业务模板整包下发完成 execId={} status={} success={} fail={}", execId, status, success, fail);
        finishTraceWorklist(execId, traceWorklistId, status, success, fail, writeJson(results));
    }

    /** 留痕工单回写终态 + 步骤记录收尾（仅页面直接触发路径；工单触发的由 WorklistDeliveryService 回写） */
    private void finishTraceWorklist(Long execId, Long worklistId, String status,
                                     int success, int fail, String resultJson) {
        if (worklistId == null) {
            return;
        }
        try {
            Worklist w = worklistMapper.selectById(worklistId);
            if (w == null) {
                return;
            }
            boolean ok = "success".equals(status);
            w.setStatus(ok ? "success" : "failed");
            w.setResult("成员 成功" + success + "/失败" + fail + "，执行记录 " + execId
                    + ("success".equals(status) ? "" : "，状态=" + status));
            w.setTimeExecFinish(LocalDateTime.now());
            w.setUpdatedAt(LocalDateTime.now());
            worklistMapper.updateById(w);
            WorklistStepRecord patch = new WorklistStepRecord();
            patch.setId(lastStepId(worklistId));
            if (patch.getId() != null) {
                patch.setStatus(ok ? "success" : "fail");
                patch.setResponseContent(resultJson);
                stepMapper.updateById(patch);
            }
        } catch (Exception e) {
            log.warn("留痕工单回写失败 execId={} worklistId={}: {}", execId, worklistId, e.getMessage());
        }
    }

    private Long lastStepId(Long worklistId) {
        List<WorklistStepRecord> steps = stepMapper.selectList(new LambdaQueryWrapper<WorklistStepRecord>()
                .eq(WorklistStepRecord::getWorklistId, worklistId)
                .orderByDesc(WorklistStepRecord::getId)
                .last("LIMIT 1"));
        return steps.isEmpty() ? null : steps.get(0).getId();
    }

    // ==================== 查询 ====================

    /** 执行记录列表（可按 sn 过滤，条数上限 100） */
    public List<Map<String, Object>> executions(String sn, int limit) {
        LambdaQueryWrapper<BizTemplateExecution> query = new LambdaQueryWrapper<>();
        if (sn != null && !sn.isBlank()) {
            query.eq(BizTemplateExecution::getSn, sn.trim());
        }
        query.orderByDesc(BizTemplateExecution::getId);
        query.last("LIMIT " + Math.max(1, Math.min(limit, 100)));
        List<Map<String, Object>> views = new ArrayList<>();
        for (BizTemplateExecution exec : executionMapper.selectList(query)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", exec.getId());
            view.put("templateId", exec.getTemplateId());
            view.put("templateName", exec.getTemplateName());
            view.put("sn", exec.getSn());
            view.put("status", exec.getStatus());
            view.put("username", exec.getUsername());
            view.put("totalCount", exec.getTotalCount());
            view.put("finishedCount", exec.getFinishedCount());
            view.put("successCount", exec.getSuccessCount());
            view.put("failCount", exec.getFailCount());
            view.put("currentKey", exec.getCurrentKey());
            view.put("errorMessage", exec.getErrorMessage());
            view.put("createdAt", exec.getCreatedAt());
            view.put("finishedAt", exec.getFinishedAt());
            views.add(view);
        }
        return views;
    }

    /** 单次执行详情：记录 + 每成员结果（含每条步骤的 detail） */
    public Map<String, Object> execution(Long id) {
        BizTemplateExecution exec = executionMapper.selectById(id);
        if (exec == null) {
            throw new BusinessException("执行记录不存在: " + id);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", exec.getId());
        view.put("templateId", exec.getTemplateId());
        view.put("templateName", exec.getTemplateName());
        view.put("sn", exec.getSn());
        view.put("status", exec.getStatus());
        view.put("username", exec.getUsername());
        view.put("totalCount", exec.getTotalCount());
        view.put("finishedCount", exec.getFinishedCount());
        view.put("successCount", exec.getSuccessCount());
        view.put("failCount", exec.getFailCount());
        view.put("currentKey", exec.getCurrentKey());
        view.put("errorMessage", exec.getErrorMessage());
        view.put("createdAt", exec.getCreatedAt());
        view.put("finishedAt", exec.getFinishedAt());
        view.put("results", readJson(exec.getResult()));
        return view;
    }

    // ==================== 工具 ====================

    private Long parseKey(String templateKey) {
        if (templateKey == null || templateKey.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(templateKey.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String writeJson(List<Map<String, Object>> results) {
        try {
            return objectMapper.writeValueAsString(results);
        } catch (Exception e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
