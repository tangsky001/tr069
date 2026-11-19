package com.PilotACS.biz.service;

import com.PilotACS.biz.entity.BizTemplate;
import com.PilotACS.biz.entity.BizTemplateExecution;
import com.PilotACS.biz.entity.BizTemplateItem;
import com.PilotACS.biz.mapper.BizTemplateExecutionMapper;
import com.PilotACS.biz.mapper.BizTemplateMapper;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.exception.BusinessException;
import com.PilotACS.template.exec.IntentDeliveryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** 执行线程池：同一时间只跑一个整包下发（逐成员串行足够） */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "biz-template-executor");
        t.setDaemon(true);
        return t;
    });

    // ==================== 入口 ====================

    /** 提交整包下发：建执行记录后交给后台线程，返回记录 id 供轮询 */
    public Long execute(Long templateId, String sn, Map<String, Map<String, String>> itemParams,
                        String roomNo, String username) {
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

        boolean continueOnError = template.getContinueOnError() != null && template.getContinueOnError();
        Map<String, Map<String, String>> overrides = itemParams == null ? new HashMap<>() : itemParams;
        executor.submit(() -> run(execId, sn, items, overrides, roomNo, username, continueOnError));
        return execId;
    }

    // ==================== 执行 ====================

    /** 后台串行执行整包，逐成员写入执行记录 */
    private void run(Long execId, String sn, List<BizTemplateItem> items,
                     Map<String, Map<String, String>> overrides, String roomNo,
                     String username, boolean continueOnError) {
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
        finish(execId, results, success, fail, aborted);
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

    /** 收尾：更新状态与结束时间 */
    private void finish(Long execId, List<Map<String, Object>> results, int success, int fail, boolean aborted) {
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
