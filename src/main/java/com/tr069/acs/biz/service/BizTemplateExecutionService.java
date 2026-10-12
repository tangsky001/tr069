package com.tr069.acs.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.biz.entity.BizTemplate;
import com.tr069.acs.biz.entity.BizTemplateExecution;
import com.tr069.acs.biz.entity.BizTemplateItem;
import com.tr069.acs.biz.mapper.BizTemplateExecutionMapper;
import com.tr069.acs.biz.mapper.BizTemplateMapper;
import com.tr069.acs.common.exception.BizException;
import com.tr069.acs.worklist.engine.WorklistEngine;
import com.tr069.acs.worklist.model.WorklistOutcome;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务模板整包下发。
 *
 * <p>按成员顺序串行下发：前一张工单没跑完不提交下一张。TR069 是反向轮询模型，
 * 一个成员要等若干次设备空 POST 往返，并行下发会让设备侧收到交错的配置指令。</p>
 *
 * <p>整个包在后台线程推进，接口立即返回执行记录 id，页面轮询进度即可。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizTemplateExecutionService {

    private final BizTemplateMapper templateMapper;
    private final BizTemplateService bizTemplateService;
    private final BizTemplateExecutionMapper executionMapper;
    private final WorklistEngine worklistEngine;
    private final ObjectMapper objectMapper;

    /** 同时下发的业务模板数上限。 */
    @Value("${acs.biz.execute-threads:4}")
    private int executeThreads;

    private ExecutorService executor;

    @PostConstruct
    public void init() {
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "acs-biz-executor-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        executor = Executors.newFixedThreadPool(Math.max(1, executeThreads), factory);
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * 提交一次整包下发。
     *
     * @return 执行记录 id
     */
    public Long execute(Long templateId, String sn, Map<String, String> params, String username) {
        if (sn == null || sn.isBlank()) {
            throw new BizException("设备序列号不能为空");
        }
        BizTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new BizException("业务模板不存在: " + templateId);
        }
        if (!Boolean.TRUE.equals(template.getEnabled())) {
            throw new BizException("业务模板已停用: " + template.getName());
        }
        List<BizTemplateItem> items = bizTemplateService.itemsOf(templateId);
        if (items.isEmpty()) {
            throw new BizException("业务模板未配置任何配置模板成员: " + template.getName());
        }

        BizTemplateExecution execution = new BizTemplateExecution();
        execution.setTemplateId(template.getId());
        execution.setTemplateName(template.getName());
        execution.setSn(sn.trim());
        execution.setUsername(username);
        execution.setStatus("running");
        execution.setTotalCount(items.size());
        execution.setFinishedCount(0);
        execution.setSuccessCount(0);
        execution.setFailCount(0);
        execution.setCreatedAt(LocalDateTime.now());
        executionMapper.insert(execution);

        Long executionId = execution.getId();
        boolean continueOnError = Boolean.TRUE.equals(template.getContinueOnError());
        executor.submit(() -> run(executionId, sn.trim(), items, params, username, continueOnError));
        return executionId;
    }

    /**
     * 单条执行记录。
     */
    public BizTemplateExecution get(Long id) {
        BizTemplateExecution execution = executionMapper.selectById(id);
        if (execution == null) {
            throw new BizException("下发记录不存在: " + id);
        }
        return execution;
    }

    /**
     * 下发记录列表，按时间倒序。
     */
    public List<BizTemplateExecution> list(String sn, Long templateId, int limit) {
        LambdaQueryWrapper<BizTemplateExecution> query = new LambdaQueryWrapper<>();
        if (sn != null && !sn.isBlank()) {
            query.eq(BizTemplateExecution::getSn, sn.trim());
        }
        if (templateId != null) {
            query.eq(BizTemplateExecution::getTemplateId, templateId);
        }
        query.orderByDesc(BizTemplateExecution::getId).last("limit " + Math.min(Math.max(limit, 1), 200));
        return executionMapper.selectList(query);
    }

    // ==================== 内部实现 ====================

    private void run(Long executionId, String sn, List<BizTemplateItem> items,
                     Map<String, String> params, String username, boolean continueOnError) {
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0;
        int fail = 0;
        String abortReason = null;

        for (BizTemplateItem item : items) {
            markCurrent(executionId, item.getTemplateKey());
            Map<String, String> merged = mergeParams(item.getParams(), params);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("templateKey", item.getTemplateKey());
            record.put("order", item.getItemOrder());

            WorklistOutcome outcome;
            try {
                outcome = worklistEngine.execute(sn, item.getTemplateKey(), merged, username);
            } catch (Exception e) {
                log.warn("业务模板成员下发异常: {} -> {}", item.getTemplateKey(), e.getMessage());
                outcome = WorklistOutcome.builder()
                        .success(false)
                        .templateKey(item.getTemplateKey())
                        .error("下发异常: " + e.getMessage())
                        .build();
            }
            record.put("success", outcome.isSuccess());
            record.put("worklistId", outcome.getWorklistId());
            record.put("error", outcome.getError());
            results.add(record);

            if (outcome.isSuccess()) {
                success++;
            } else {
                fail++;
                if (!continueOnError) {
                    abortReason = "成员 " + item.getTemplateKey() + " 执行失败，已中止后续成员";
                    break;
                }
            }
            updateProgress(executionId, results.size(), success, fail, writeJson(results));
        }

        updateProgress(executionId, results.size(), success, fail, writeJson(results));
        finish(executionId, success, fail, abortReason, results.size());
    }

    private void markCurrent(Long executionId, String templateKey) {
        BizTemplateExecution patch = new BizTemplateExecution();
        patch.setId(executionId);
        patch.setCurrentKey(templateKey);
        executionMapper.updateById(patch);
    }

    private void updateProgress(Long executionId, int finished, int success, int fail, String result) {
        BizTemplateExecution patch = new BizTemplateExecution();
        patch.setId(executionId);
        patch.setFinishedCount(finished);
        patch.setSuccessCount(success);
        patch.setFailCount(fail);
        patch.setResult(result);
        executionMapper.updateById(patch);
    }

    private void finish(Long executionId, int success, int fail, String abortReason, int finished) {
        BizTemplateExecution patch = new BizTemplateExecution();
        patch.setId(executionId);
        patch.setFinishedCount(finished);
        patch.setSuccessCount(success);
        patch.setFailCount(fail);
        patch.setFinishedAt(LocalDateTime.now());
        patch.setCurrentKey(null);
        patch.setErrorMessage(abortReason);
        if (fail == 0) {
            patch.setStatus("success");
        } else if (success == 0) {
            patch.setStatus("fail");
        } else {
            patch.setStatus("partial");
        }
        executionMapper.updateById(patch);
        log.info("业务模板下发完成: executionId={} status={} success={} fail={}",
                executionId, patch.getStatus(), success, fail);
    }

    /**
     * 入参合并：成员上配置的固定值打底，页面传入的覆盖同名参数。
     */
    private Map<String, String> mergeParams(String itemParamsJson, Map<String, String> requestParams) {
        Map<String, String> merged = bizTemplateService.parseParams(itemParamsJson);
        if (requestParams != null) {
            merged.putAll(requestParams);
        }
        return merged;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
