package com.tr069.acs.worklist.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.Worklist;
import com.tr069.acs.domain.entity.WorklistStepRecord;
import com.tr069.acs.exec.IntentDeliveryService;
import com.tr069.acs.exec.TaskFinishedEvent;
import com.tr069.acs.exec.entity.DeviceTask;
import com.tr069.acs.exec.model.DeliveryRequest;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.WorklistMapper;
import com.tr069.acs.mapper.WorklistStepMapper;
import com.tr069.acs.template.service.ConfigTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 管理端下发工单：建单、转下发、记录步骤、推进状态。
 *
 * <h3>幂等</h3>
 * <p>以 {@code worklistDesc} 作为调用方流水号：同一编号重复提交只受理一次，
 * 返回既有工单。网络重试或用户手抖点两下都不会给设备重复开业务。</p>
 *
 * <h3>状态流转</h3>
 * <p>建单即 {@code build}，落库后立刻转成下发任务（{@link IntentDeliveryService}，
 * 来源标记 {@link #SOURCE_WORKLIST}）：受理成功置 {@code sending}，设备执行由
 * 下发引擎异步推进；任务结束按 {@link TaskFinishedEvent} 回写终态
 * {@code success} / {@code failed}，失败原因写进 remark。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorklistDeliveryService {

    /** 工单转下发任务时在 DeviceTask.source 上打的标记，回写结果时按它识别。 */
    public static final String SOURCE_WORKLIST = "worklist";

    private final WorklistMapper worklistMapper;
    private final WorklistStepMapper stepMapper;
    private final CpeDeviceMapper deviceMapper;
    private final ObjectMapper objectMapper;
    private final IntentDeliveryService intentDeliveryService;
    private final ConfigTemplateService configTemplateService;

    /**
     * 创建下发工单。
     *
     * @param sn            设备序列号
     * @param category      业务类型，如 {@code WIFI_24G / SIP / WAN_PPPOE}
     * @param worklistDesc  调用方流水号，幂等键
     * @param values        变量值，键为 {@code %room%} 这样的占位符
     * @param username      操作人
     */
    public Worklist create(String sn, String category, String worklistDesc,
                           Map<String, String> values, String username, Long templateId) {
        if (sn == null || sn.isBlank()) {
            throw new IllegalArgumentException("缺少设备序列号");
        }
        if (worklistDesc == null || worklistDesc.isBlank()) {
            throw new IllegalArgumentException("缺少工单流水号");
        }

        CpeDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                .eq(CpeDevice::getSn, sn)
                .last("LIMIT 1"));
        if (device == null) {
            throw new IllegalArgumentException("设备不存在: " + sn);
        }

        Worklist exist = worklistMapper.selectOne(new LambdaQueryWrapper<Worklist>()
                .eq(Worklist::getWorklistDesc, worklistDesc)
                .last("LIMIT 1"));
        if (exist != null) {
            log.info("工单已存在，本次提交忽略: desc={}, id={}", worklistDesc, exist.getId());
            return exist;
        }

        LocalDateTime now = LocalDateTime.now();
        Worklist w = new Worklist();
        w.setWorklistDesc(worklistDesc);
        w.setWorklistName(category);
        w.setWorklistType("logic");
        w.setSn(sn);
        w.setIsp(device.getIsp());
        w.setDomain(device.getDeviceType());
        w.setStatus("build");
        // 口令值不落日志，只记录填了哪些变量
        w.setParameters(toJson(values));
        w.setUsername(username);
        w.setRollbackFlag(0);
        w.setTimeInit(now);
        w.setCreatedAt(now);
        w.setUpdatedAt(now);
        worklistMapper.insert(w);
        log.info("创建下发工单: id={}, sn={}, category={}, 变量={}", w.getId(), sn, category,
                values == null ? Collections.emptyList() : List.copyOf(values.keySet()));
        triggerDelivery(w, values, templateId);
        return w;
    }

    // ==================== 工单 → 下发 ====================

    /**
     * 工单落库后立刻转成下发任务。
     *
     * <p>此前建单只写库：受理、渲染、执行全在 {@link IntentDeliveryService} 里，
     * 却没有任何调用方把两者接起来，工单会一直停在 {@code build} 上不动。
     * 受理被拒（型号未适配、现场不符合预期等）时直接把工单置为 {@code failed}，
     * 原因写进 remark；受理通过则置 {@code sending}，等任务结束事件回写终态。</p>
     */
    private void triggerDelivery(Worklist w, Map<String, String> values, Long templateId) {
        Long tpl = templateId != null ? templateId : defaultTemplateId(w.getWorklistName());
        Map<String, Object> vars = values == null ? new HashMap<>() : new HashMap<>(values);
        // 模板固化的字段（VLAN ID、DNS 等）并入变量，前端填的优先：
        // 下发变量表里的 example 只是输入框 placeholder，不参与下发
        if (tpl != null) {
            configTemplateService.fieldsOf(tpl).forEach(vars::putIfAbsent);
        }
        try {
            DeviceTask task = intentDeliveryService.deliver(new DeliveryRequest(
                    "WL-" + w.getId(), w.getSn(), w.getWorklistName(), tpl, null,
                    vars, true, SOURCE_WORKLIST, w.getUsername()));
            if (DeviceTask.STATUS_REJECTED.equals(task.getStatus())) {
                fail(w, task.getErrorMsg());
            } else {
                w.setStatus("sending");
                w.setUpdatedAt(LocalDateTime.now());
                worklistMapper.updateById(w);
                log.info("工单已转下发任务: worklistId={}, taskId={}, status={}",
                        w.getId(), task.getId(), task.getStatus());
            }
        } catch (Exception e) {
            fail(w, e.getMessage());
        }
    }

    /**
     * 未指定模板时按业务类型取第一条，让前端不开模板选择也能下发。
     *
     * <p>同一类业务有多条模板时取 id 最小的那条；想用别的，前端传 templateId 即可。</p>
     */
    private Long defaultTemplateId(String category) {
        try {
            List<Map<String, Object>> templates = configTemplateService.list(category, null);
            if (templates.isEmpty()) {
                return null;
            }
            Object id = templates.get(0).get("id");
            return id == null ? null : Long.valueOf(String.valueOf(id));
        } catch (Exception e) {
            log.warn("按业务类型查找模板失败: category={}, err={}", category, e.getMessage());
            return null;
        }
    }

    /** 下发任务结束回写工单终态：只认 {@link #SOURCE_WORKLIST} 来源，OpenAPI 的单不归工单管。 */
    @EventListener
    public void onTaskFinished(TaskFinishedEvent event) {
        DeviceTask task = event.getTask();
        if (task == null || !SOURCE_WORKLIST.equals(task.getSource())
                || task.getTaskNo() == null || !task.getTaskNo().startsWith("WL-")) {
            return;
        }
        try {
            Worklist w = worklistMapper.selectById(Long.valueOf(task.getTaskNo().substring(3)));
            if (w == null || "success".equals(w.getStatus()) || "failed".equals(w.getStatus())) {
                return;
            }
            if (DeviceTask.STATUS_SUCCESS.equals(task.getStatus())) {
                w.setStatus("success");
                w.setResult(task.getErrorMsg());
            } else {
                w.setStatus("failed");
                w.setResult(task.getErrorMsg());
            }
            w.setTimeExecFinish(LocalDateTime.now());
            w.setUpdatedAt(LocalDateTime.now());
            worklistMapper.updateById(w);
            log.info("工单下发结束: id={}, status={}, err={}", w.getId(), w.getStatus(), task.getErrorMsg());
        } catch (Exception e) {
            log.warn("工单状态回写失败: taskNo={}, err={}", task.getTaskNo(), e.getMessage());
        }
    }

    private void fail(Worklist w, String reason) {
        w.setStatus("failed");
        w.setResult(reason);
        w.setTimeExecFinish(LocalDateTime.now());
        worklistMapper.updateById(w);
        log.warn("工单转下发失败: id={}, err={}", w.getId(), reason);
    }

    /**
     * 记录工单的一条执行步骤。已有同序号步骤则覆盖，避免重试灌入重复步骤。
     */
    public WorklistStepRecord addStep(Long worklistId, int stepIndex, String comment,
                                      String rpc, Object requestArgs) {
        stepMapper.delete(new LambdaQueryWrapper<WorklistStepRecord>()
                .eq(WorklistStepRecord::getWorklistId, worklistId)
                .eq(WorklistStepRecord::getStepIndex, stepIndex));

        WorklistStepRecord step = new WorklistStepRecord();
        step.setWorklistId(worklistId);
        step.setStepIndex(stepIndex);
        step.setComment(comment);
        step.setRpc(rpc);
        step.setRequestArgs(toJson(requestArgs));
        step.setStatus("init");
        step.setCreatedAt(LocalDateTime.now());
        stepMapper.insert(step);
        return step;
    }

    /** 推进工单状态。 */
    public void moveTo(Long worklistId, String status) {
        Worklist patch = new Worklist();
        patch.setId(worklistId);
        patch.setStatus(status);
        patch.setUpdatedAt(LocalDateTime.now());
        worklistMapper.updateById(patch);
    }

    /** 读回工单里存的参数值。 */
    public Map<String, String> readValues(Worklist worklist) {
        if (worklist == null || worklist.getParameters() == null || worklist.getParameters().isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(worklist.getParameters(),
                    new TypeReference<Map<String, String>>() {
                    });
        } catch (Exception e) {
            log.warn("工单参数解析失败，按空处理: id={}", worklist.getId(), e);
            return new HashMap<>();
        }
    }

    /** 供前端生成表单：某业务类型要填哪些变量。 */
    public static String normalize(String category) {
        return category == null ? "" : category.toUpperCase(Locale.ROOT);
    }

    private String toJson(Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("参数序列化失败，存空对象", e);
            return "{}";
        }
    }
}
