package com.tr069.acs.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.biz.entity.BizTemplate;
import com.tr069.acs.biz.entity.BizTemplateExecution;
import com.tr069.acs.biz.mapper.BizTemplateExecutionMapper;
import com.tr069.acs.biz.mapper.BizTemplateMapper;
import com.tr069.acs.biz.model.BizTemplateItemView;
import com.tr069.acs.common.exception.BizException;
import com.tr069.acs.exec.IntentDeliveryService;
import com.tr069.acs.exec.TaskFinishedEvent;
import com.tr069.acs.exec.entity.DeviceTask;
import com.tr069.acs.exec.model.DeliveryRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务模板整包下发：按成员顺序逐个下发，前一个结束才发下一个。
 *
 * <h3>为什么要串行</h3>
 * <p>成员之间是有依赖的：先建 WAN 连接，才能在它上面挂 VLAN、绑业务，
 * 最后才是 WiFi 与语音。一股脑全排进队列，设备按 FIFO 取是「按顺序取」，
 * 但前一个失败了后面的照样往下写，开出来的就是半套业务。所以这里等前一个成员
 * 结束再决定下一个，{@code continueOnError=false} 时失败即停。</p>
 *
 * <h3>进度</h3>
 * <p>接口立即返回执行记录 id，页面靠它轮询；每结束一个成员就把计数与明细写回
 * {@code biz_template_execution}，不用等整包跑完才看得到进展。</p>
 *
 * <h3>续跑</h3>
 * <p>推进状态挂在内存里，进程重启后未完成的执行无法续跑，会一直停在
 * {@code running}。这是已知取舍：整包下发本来就要盯着，重启后重新点一次即可。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizTemplateExecutionService {

    /** 整包下发在下发任务上的来源标记，回写进度时靠它认领自己的任务。 */
    public static final String SOURCE_BIZ = "biz";

    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_PARTIAL = "partial";
    private static final String STATUS_FAIL = "fail";

    private final BizTemplateExecutionMapper executionMapper;
    private final BizTemplateMapper templateMapper;
    private final BizTemplateService bizTemplateService;
    private final IntentDeliveryService intentDeliveryService;
    private final ObjectMapper objectMapper;

    /** 正在跑的整包，按执行记录 id 索引。 */
    private final Map<Long, Progress> running = new ConcurrentHashMap<>();

    /** taskNo -> 所属整包，下发任务结束事件回来时按它定位。 */
    private final Map<String, Progress> awaiting = new ConcurrentHashMap<>();

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

    /**
     * 对一台设备整包下发业务模板。
     *
     * @param templateId 业务模板 id
     * @param sn         设备序列号
     * @param params     整包级的入参，成员上配的同名参数优先
     * @param username   操作人
     * @return 执行记录，含 id 供页面轮询
     */
    public BizTemplateExecution execute(Long templateId, String sn,
                                        Map<String, String> params, String username) {
        if (templateId == null) {
            throw new BizException("业务模板 id 不能为空");
        }
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
        List<BizTemplateItemView> items = bizTemplateService.viewsOf(templateId);
        if (items.isEmpty()) {
            throw new BizException("业务模板未配置成员: " + template.getName());
        }

        BizTemplateExecution execution = new BizTemplateExecution();
        execution.setTemplateId(templateId);
        execution.setTemplateName(template.getName());
        execution.setSn(sn.trim());
        execution.setStatus(STATUS_RUNNING);
        execution.setUsername(username);
        execution.setTotalCount(items.size());
        execution.setFinishedCount(0);
        execution.setSuccessCount(0);
        execution.setFailCount(0);
        execution.setResult("[]");
        execution.setCreatedAt(LocalDateTime.now());
        executionMapper.insert(execution);

        Progress progress = new Progress(execution, template, items, sn.trim(), params, username);
        running.put(execution.getId(), progress);
        log.info("业务模板整包下发开始: executionId={}, 模板={}, sn={}, 成员={}",
                execution.getId(), template.getName(), sn, items.size());
        startNext(progress);
        return execution;
    }

    // ==================== 串行推进 ====================

    /**
     * 下发下一个成员。
     *
     * <p>任务号带执行记录 id 与成员序号，既是幂等键，也让事件回来时能直接定位
     * 到是哪一个成员结束了。</p>
     */
    private synchronized void startNext(Progress progress) {
        if (progress.index() >= progress.items().size()) {
            finish(progress, null);
            return;
        }
        BizTemplateItemView item = progress.items().get(progress.index());
        String seq = "成员 " + (progress.index() + 1) + "/" + progress.items().size()
                + "（" + item.getTemplateKey() + "）";
        if (!item.isAvailable() || !item.isExecutable()) {
            String reason = item.getIssue() == null ? "配置模板不可用" : item.getIssue();
            progress.record(item, false, reason);
            advance(progress, false, seq + "不可用: " + reason);
            return;
        }
        String category = item.getCategory() == null
                ? "" : item.getCategory().trim().toUpperCase(Locale.ROOT);
        if (category.isEmpty()) {
            progress.record(item, false, "成员未绑定业务类型");
            advance(progress, false, seq + "未绑定业务类型，无法下发");
            return;
        }

        // 整包入参打底，成员上配的覆盖同名项；没填的业务变量由渲染侧按房间号与默认口令兜底
        Map<String, Object> values = new LinkedHashMap<>();
        if (progress.params() != null) {
            values.putAll(progress.params());
        }
        if (item.getParams() != null) {
            values.putAll(item.getParams());
        }

        String taskNo = "BIZ-" + progress.execution().getId() + "-" + (progress.index() + 1);
        progress.setCurrentKey(item.getTemplateKey());
        awaiting.put(taskNo, progress);
        try {
            intentDeliveryService.deliver(new DeliveryRequest(
                    taskNo, progress.sn(), category, parseTemplateId(item.getTemplateKey()),
                    null, values, true, SOURCE_BIZ, progress.username()));
            // 受理阶段就被拒或异常结束的，deliver 内部已经发过结束事件，推进在监听器里做完了
        } catch (Exception e) {
            awaiting.remove(taskNo);
            log.warn("整包成员受理失败: executionId={}, {}, err={}",
                    progress.execution().getId(), seq, e.getMessage());
            progress.record(item, false, e.getMessage());
            advance(progress, false, seq + "受理失败: " + e.getMessage());
        }
    }

    /**
     * 下发任务结束：认领自己的成员，记结果，决定继续还是停下。
     */
    @EventListener
    public synchronized void onTaskFinished(TaskFinishedEvent event) {
        DeviceTask task = event.getTask();
        if (task == null || !SOURCE_BIZ.equals(task.getSource()) || task.getTaskNo() == null) {
            return;
        }
        Progress progress = awaiting.remove(task.getTaskNo());
        if (progress == null) {
            return;
        }
        BizTemplateItemView item = progress.items().get(progress.index());
        boolean ok = DeviceTask.STATUS_SUCCESS.equals(task.getStatus());
        progress.record(item, ok, ok ? null : task.getErrorMsg());
        String seq = "成员 " + (progress.index() + 1) + "/" + progress.items().size()
                + "（" + item.getTemplateKey() + "）";
        log.info("整包成员结束: executionId={}, {}, 结果={}",
                progress.execution().getId(), seq, ok ? "成功" : task.getErrorMsg());
        advance(progress, ok, ok ? null : seq + "下发失败: " + task.getErrorMsg());
    }

    private void advance(Progress progress, boolean ok, String error) {
        if (!ok && !progress.continueOnError()) {
            finish(progress, error);
            return;
        }
        progress.next();
        persist(progress);
        startNext(progress);
    }

    /** 每结束一个成员就落一次库，页面不用等整包跑完才看得到进度。 */
    private void persist(Progress progress) {
        BizTemplateExecution execution = progress.execution();
        execution.setFinishedCount(progress.results().size());
        execution.setSuccessCount(progress.successCount());
        execution.setFailCount(progress.failCount());
        execution.setCurrentKey(progress.currentKey());
        execution.setResult(writeJson(progress.results()));
        executionMapper.updateById(execution);
    }

    private void finish(Progress progress, String error) {
        BizTemplateExecution execution = progress.execution();
        progress.setCurrentKey(null);
        persist(progress);
        execution.setFinishedAt(LocalDateTime.now());
        if (error != null) {
            execution.setStatus(STATUS_FAIL);
            execution.setErrorMessage(error);
        } else if (progress.failCount() > 0) {
            execution.setStatus(STATUS_PARTIAL);
            execution.setErrorMessage(progress.failCount() + " 个成员下发失败");
        } else {
            execution.setStatus(STATUS_SUCCESS);
        }
        executionMapper.updateById(execution);
        running.remove(execution.getId());
        log.info("业务模板整包下发结束: executionId={}, status={}, 成功={}, 失败={}, err={}",
                execution.getId(), execution.getStatus(),
                progress.successCount(), progress.failCount(), error);
    }

    // ==================== 内部实现 ====================

    /** 成员引用的配置模板 id；存量数据存的是旧的文件 key，解析不出数字即为失效。 */
    private static Long parseTemplateId(String templateKey) {
        if (templateKey == null || templateKey.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(templateKey.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("整包执行明细序列化失败，已忽略: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 一次整包下发的推进状态。
     */
    private static final class Progress {

        private final BizTemplateExecution execution;
        private final boolean continueOnError;
        private final List<BizTemplateItemView> items;
        private final String sn;
        private final Map<String, String> params;
        private final String username;
        private final List<Map<String, Object>> results = new ArrayList<>();

        private int index;
        private int successCount;
        private int failCount;
        private String currentKey;

        Progress(BizTemplateExecution execution, BizTemplate template,
                 List<BizTemplateItemView> items, String sn,
                 Map<String, String> params, String username) {
            this.execution = execution;
            this.continueOnError = Boolean.TRUE.equals(template.getContinueOnError());
            this.items = items;
            this.sn = sn;
            this.params = params;
            this.username = username;
        }

        /** 记录一个成员的结局。 */
        void record(BizTemplateItemView item, boolean ok, String error) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("order", index + 1);
            row.put("templateKey", item.getTemplateKey());
            row.put("templateName", item.getTemplateName());
            row.put("category", item.getCategory());
            row.put("status", ok ? "success" : "fail");
            row.put("error", error);
            row.put("finishTime", LocalDateTime.now().toString());
            results.add(row);
            if (ok) {
                successCount++;
            } else {
                failCount++;
            }
        }

        BizTemplateExecution execution() {
            return execution;
        }

        List<BizTemplateItemView> items() {
            return items;
        }

        List<Map<String, Object>> results() {
            return results;
        }

        String sn() {
            return sn;
        }

        Map<String, String> params() {
            return params;
        }

        String username() {
            return username;
        }

        boolean continueOnError() {
            return continueOnError;
        }

        int index() {
            return index;
        }

        void next() {
            index++;
        }

        int successCount() {
            return successCount;
        }

        int failCount() {
            return failCount;
        }

        String currentKey() {
            return currentKey;
        }

        void setCurrentKey(String currentKey) {
            this.currentKey = currentKey;
        }
    }
}
