package com.tr069.acs.exec;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.acs.model.DataModel;
import com.tr069.acs.acs.service.CwmpRpcService;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeModel;
import com.tr069.acs.domain.entity.ModelObjectMapping;
import com.tr069.acs.domain.entity.ModelParamMapping;
import com.tr069.acs.exec.entity.DeviceTask;
import com.tr069.acs.exec.mapper.DeviceTaskMapper;
import com.tr069.acs.exec.model.DeliveryRequest;
import com.tr069.acs.exec.model.IntentPlan;
import com.tr069.acs.exec.model.PlanItem;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeModelMapper;
import com.tr069.acs.mapper.ModelObjectMappingMapper;
import com.tr069.acs.mapper.ModelParamMappingMapper;
import com.tr069.acs.template.service.ConfigTemplateService;
import com.tr069.acs.worklist.service.ModelMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.tr069.acs.common.log.AcsCoreLog;

/**
 * 意图模板下发执行器。
 *
 * <p>业务只说要「开通」，剩下的三步这一步做完：</p>
 * <ol>
 *   <li><strong>对齐型号</strong>：按设备的 ProductClass + 固件版本找到 {@code cpe_model}，
 *       型号没做语义映射就拒绝，避免拿着猜测的路径往设备上写；</li>
 *   <li><strong>渲染计划</strong>：模板的意图字段 + 本次请求的业务值，经
 *       {@link IntentPlanRenderer} 翻译成该型号真实的 CWMP 路径与值；</li>
 *   <li><strong>执行并留痕</strong>：先按需 AddObject 建实例，再分批 SetParameterValues，
 *       全程状态写进 {@code device_task}，结束时发布 {@link TaskFinishedEvent}。</li>
 * </ol>
 *
 * <p>下发必须等设备回应：ACS 只能在设备主动上报后的那次空 POST 里捎带指令，
 * 所以这里吃掉的是「等设备窗口」的时间，不是 CPU。调用方拿到的是已受理的任务，
 * 结果另poll task 接口或等 Webhook 回调。</p>
 *
 * <p>同一 {@code source + taskNo} 只会被受理一次：重复提交返回既有任务，
 * 不会因为网络重试把同一条业务下发两遍。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentDeliveryService {

    /**
     * 模板业务类型 → 型号语义映射里的业务对象。
     *
     * <p>模板面向的是「开通什么业务」（WAN DHCP、2.4G WiFi），
     * 映射表面向的是「设备上的哪一块」（Internet_WAN、WiFi_2G），两者在这里对齐。
     * 调用方也可以直接用 {@code bizObject} 指定，绕开这张表。</p>
     */
    private static final Map<String, String> BIZ_OBJECTS = Map.of(
            "WAN_DHCP", "Internet_WAN",
            "WAN_PPPOE", "Internet_WAN",
            "WAN_STATIC", "Internet_WAN",
            "WAN_BRIDGE", "Bridge_WAN",
            "WIFI_24G", "WiFi_2G",
            "WIFI_58G", "WiFi_5G",
            // SIP 模板配置的是 VoiceService 本身，VOIP_WAN 只承载它的 WAN 连接
            "SIP", "Voice_Service",
            "OTHER", "Device_Mgmt");

    /** 单批 SetParameterValues 携带的参数个数，避免报文过大被设备拒收。 */
    private static final int VALUE_BATCH = 32;

    /** 下发等待设备回应的时间不定，统一丢到这个后台线程池。 */
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "acs-exec");
        thread.setDaemon(true);
        return thread;
    });

    private final DeviceTaskMapper taskMapper;
    private final CpeDeviceMapper deviceMapper;
    private final CpeModelMapper modelMapper;
    private final ModelParamMappingMapper paramMappingMapper;
    private final ModelObjectMappingMapper objectMappingMapper;
    private final ConfigTemplateService configTemplateService;
    private final IntentPlanRenderer renderer;
    private final CwmpRpcService rpcService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AcsCoreLog coreLog;

    /** 下发等待设备回应的超时（秒）。 */
    @Value("${acs.exec.request-timeout-seconds:90}")
    private long requestTimeoutSeconds;

    // ==================== 受理 ====================

    /**
     * 受理一次下发：同步完成校验与计划渲染，执行过程异步。
     *
     * @return 任务记录，重复提交返回已有任务
     */
    public DeviceTask deliver(DeliveryRequest request) {
        DeviceTask existing = findByTaskNo(request.source(), request.taskNo());
        if (existing != null) {
            log.info("重复下发请求，返回既有任务: taskNo={}, id={}", request.taskNo(), existing.getId());
            return existing;
        }
        DeviceTask task = newTask(request);
        try {
            prepare(task, request);
        } catch (Exception e) {
            log.warn("下发受理失败: sn={}, category={}, err={}", request.sn(), request.category(),
                    e.getMessage());
            task.setStatus(DeviceTask.STATUS_REJECTED);
            task.setErrorMsg(e.getMessage());
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            eventPublisher.publishEvent(new TaskFinishedEvent(this, task));
            return task;
        }
        // 计划已定稿，剩下的就是等设备取走指令——那可能要等它下一次周期上报，
        // 不能堵在调用方的 HTTP 线程上，丢给后台线程推进状态
        if (DeviceTask.STATUS_RUNNING.equals(task.getStatus())) {
            EXECUTOR.execute(() -> execute(task));
        }
        return task;
    }

    /**
     * 按 id 查任务，不存在返回 null。
     */
    public DeviceTask get(Long id) {
        return id == null ? null : taskMapper.selectById(id);
    }

    /**
     * 按外部单号查任务。
     */
    public DeviceTask findByTaskNo(String source, String taskNo) {
        if (source == null || taskNo == null) {
            return null;
        }
        return taskMapper.selectOne(new LambdaQueryWrapper<DeviceTask>()
                .eq(DeviceTask::getSource, source)
                .eq(DeviceTask::getTaskNo, taskNo)
                .last("LIMIT 1"));
    }

    /**
     * 任务视图：把 JSON 字段还原成对象，方便接口直接返回。
     */
    public Map<String, Object> view(DeviceTask task) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", task.getId());
        data.put("taskNo", task.getTaskNo());
        data.put("sn", task.getSn());
        data.put("category", task.getCategory());
        data.put("templateId", task.getTemplateId());
        data.put("source", task.getSource());
        data.put("createdBy", task.getCreatedBy());
        data.put("status", task.getStatus());
        data.put("errorMsg", task.getErrorMsg());
        data.put("intentFields", readJson(task.getIntentFields()));
        data.put("plan", readJson(task.getPlan()));
        data.put("createdAt", task.getCreatedAt());
        data.put("updatedAt", task.getUpdatedAt());
        data.put("finishedAt", task.getFinishedAt());
        return data;
    }

    /**
     * 模板类型支持的业务对象清单，供接口做参数提示。
     */
    public Map<String, String> bizObjects() {
        return BIZ_OBJECTS;
    }

    // ==================== 校验与计划 ====================

    private DeviceTask newTask(DeliveryRequest request) {
        DeviceTask task = new DeviceTask();
        task.setTaskNo(request.taskNo());
        task.setSn(request.sn());
        task.setCategory(request.category());
        task.setTemplateId(request.templateId());
        task.setSource(request.source() == null ? DeviceTask.SOURCE_ADMIN : request.source());
        task.setCreatedBy(request.createdBy());
        task.setStatus(DeviceTask.STATUS_PENDING);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
        return task;
    }

    private void prepare(DeviceTask task, DeliveryRequest request) {
        if (request.sn() == null || request.sn().isBlank()) {
            throw new IllegalArgumentException("设备序列号不能为空");
        }
        if (request.taskNo() == null || request.taskNo().isBlank()) {
            throw new IllegalArgumentException("taskNo 不能为空，它是幂等与结果查询的依据");
        }
        String bizObject = request.bizObject() != null && !request.bizObject().isBlank()
                ? request.bizObject().trim()
                : BIZ_OBJECTS.get(request.category());
        if (bizObject == null) {
            throw new IllegalArgumentException("未知的业务类型: " + request.category());
        }
        CpeDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                .eq(CpeDevice::getSn, request.sn())
                .last("LIMIT 1"));
        if (device == null) {
            throw new IllegalArgumentException("设备不存在，请确认已注册: " + request.sn());
        }

        Map<String, Object> intentFields = new LinkedHashMap<>();
        if (request.templateId() != null) {
            intentFields.putAll(configTemplateService.fieldsOf(request.templateId()));
        }
        Map<String, Object> values = request.values() == null ? Map.of() : request.values();

        CpeModel model = findModel(device);
        if (model == null) {
            throw new IllegalArgumentException("该型号尚未登记，请先做语义映射: "
                    + ModelMappingService.buildModelKey(device.getProductClass(),
                    device.getSoftwareVersion()));
        }
        if (!CpeModel.STATUS_READY.equals(model.getStatus())) {
            throw new IllegalArgumentException("型号映射未完成，当前状态 " + model.getStatus()
                    + "，补齐必填字段后再下发: " + model.getModelKey());
        }
        // 数据模型对不上时宁可不发：把 IGD 的路径写进 TR-181 设备，设备只会回 9005
        checkDataModel(model, device);

        List<ModelParamMapping> paramMappings = paramMappingMapper.selectList(
                new LambdaQueryWrapper<ModelParamMapping>()
                        .eq(ModelParamMapping::getModelId, model.getId()));
        List<ModelObjectMapping> objectMappings = objectMappingMapper.selectList(
                new LambdaQueryWrapper<ModelObjectMapping>()
                        .eq(ModelObjectMapping::getModelId, model.getId()));

        IntentPlan plan = renderer.render(bizObject, paramMappings, objectMappings,
                intentFields, values, request.createInstance());
        task.setIntentFields(writeJson(merge(intentFields, values)));
        task.setPlan(writeJson(planView(plan)));
        if (!plan.deliverable()) {
            task.setStatus(DeviceTask.STATUS_REJECTED);
            task.setErrorMsg(String.join("; ", plan.errors()));
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            eventPublisher.publishEvent(new TaskFinishedEvent(this, task));
            return;
        }
        task.setStatus(DeviceTask.STATUS_RUNNING);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    /**
     * 按设备侧型号标识找登记过的型号档案：型号不是设备台账里的 SN，而是
     * ProductClass + 固件版本算出来的 key，同型号同固件共用一份映射。
     */
    private CpeModel findModel(CpeDevice device) {
        String modelKey = ModelMappingService.buildModelKey(
                device.getProductClass(), device.getSoftwareVersion());
        return modelMapper.selectOne(new LambdaQueryWrapper<CpeModel>()
                .eq(CpeModel::getModelKey, modelKey)
                .last("LIMIT 1"));
    }

    private void checkDataModel(CpeModel model, CpeDevice device) {
        String modelRoot = model.getRootNode();
        String deviceRoot = device.getRootNode();
        if (modelRoot == null || modelRoot.isBlank()
                || deviceRoot == null || deviceRoot.isBlank()) {
            return;
        }
        if (!DataModel.ofRoot(modelRoot).equals(DataModel.ofRoot(deviceRoot))) {
            throw new IllegalArgumentException("数据模型不一致：型号档案按 "
                    + DataModel.ofRoot(modelRoot).getLabel() + " 映射，而设备上报的是 "
                    + DataModel.ofRoot(deviceRoot).getLabel() + "，请为该型号建另一份适配");
        }
    }

    // ==================== 执行 ====================

    private void execute(DeviceTask task) {
        IntentPlan plan = readPlan(task.getPlan());
        if (plan == null || !plan.deliverable()) {
            return;
        }
        execute(task, plan);
    }

    private void execute(DeviceTask task, IntentPlan plan) {
        try {
            for (String objectName : plan.objectsToAdd()) {
                SoapMessage resp = await(rpcService.addObject(task.getSn(), objectName, task.getTaskNo()));
                String error = failureOf(resp, "AddObject " + objectName);
                if (error != null) {
                    finish(task, DeviceTask.STATUS_FAILED, error);
                    return;
                }
                log.info("已创建业务对象: sn={}, object={}, instance={}",
                        task.getSn(), objectName, CwmpRpcService.instanceNumberOf(resp));
            }
            List<List<PlanItem>> chunks = chunks(plan.parameters());
            for (int i = 0; i < chunks.size(); i++) {
                List<ParameterValueStruct> values = new ArrayList<>();
                for (PlanItem item : chunks.get(i)) {
                    values.add(ParameterValueStruct.builder()
                            .name(item.path())
                            .value(item.value())
                            .valueType(item.valueType())
                            .build());
                }
                SoapMessage resp = await(rpcService.setParameterValues(
                        task.getSn(), values, task.getTaskNo()));
                String error = failureOf(resp, "SetParameterValues 第 " + (i + 1) + "/" + chunks.size() + " 批");
                if (error != null) {
                    finish(task, DeviceTask.STATUS_FAILED, error);
                    return;
                }
            }
            log.info("下发完成: sn={}, taskNo={}, 参数={}", task.getSn(), task.getTaskNo(),
                    plan.parameters().size());
            finish(task, DeviceTask.STATUS_SUCCESS, null);
        } catch (Exception e) {
            log.warn("下发异常: sn={}, taskNo={}, err={}", task.getSn(), task.getTaskNo(), e.getMessage());
            finish(task, DeviceTask.STATUS_FAILED, e.getMessage());
        }
    }

    private void finish(DeviceTask task, String status, String error) {
        task.setStatus(status);
        task.setErrorMsg(error);
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        // 核心日志：业务下发的最终成败单独记一行到 log/ACS_LOG.LOG
        boolean ok = DeviceTask.STATUS_SUCCESS.equals(status);
        coreLog.business(task.getSn(),
                task.getCategory() + "/" + task.getTaskNo(), ok,
                ok ? null : String.valueOf(error));
        try {
            taskMapper.updateById(task);
        } catch (Exception e) {
            log.error("回写下发结果失败: id={}, err={}", task.getId(), e.getMessage());
        }
        eventPublisher.publishEvent(new TaskFinishedEvent(this, task));
    }

    /**
     * 判定响应是否算失败，成功返回 null。
     *
     * <p>SetParameterValues 即便返回成功，Status 为 1 也意味着设备没全收，
     * 这时必须当失败报出去，否则上层会以为开通成功。</p>
     */
    private String failureOf(SoapMessage resp, String action) {
        if (resp == null) {
            return action + " 失败: 设备无响应";
        }
        if (resp.isFault()) {
            return action + " 失败: " + CwmpRpcService.faultText(resp);
        }
        String status = CwmpRpcService.statusOf(resp);
        if (status != null && !"0".equals(status)) {
            return action + " 失败: 设备回 Status=" + status;
        }
        return null;
    }

    private SoapMessage await(CompletableFuture<SoapMessage> future) throws Exception {
        return future.get(requestTimeoutSeconds, TimeUnit.SECONDS);
    }

    private List<List<PlanItem>> chunks(List<PlanItem> items) {
        List<List<PlanItem>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += VALUE_BATCH) {
            chunks.add(items.subList(i, Math.min(items.size(), i + VALUE_BATCH)));
        }
        return chunks;
    }

    // ==================== 序列化 ====================

    private Map<String, Object> planView(IntentPlan plan) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bizObject", plan.bizObject());
        data.put("instance", plan.instance());
        data.put("objectsToAdd", plan.objectsToAdd());
        List<Map<String, Object>> items = new ArrayList<>();
        for (PlanItem item : plan.parameters()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bizField", item.bizField());
            row.put("path", item.path());
            row.put("value", item.value());
            row.put("valueType", item.valueType());
            items.add(row);
        }
        data.put("parameters", items);
        data.put("errors", plan.errors());
        return data;
    }

    @SuppressWarnings("unchecked")
    private IntentPlan readPlan(String json) {
        Map<String, Object> data = readJson(json);
        if (data.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("parameters");
        List<PlanItem> items = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                items.add(new PlanItem(str(data.get("bizObject")), str(row.get("bizField")),
                        str(row.get("path")), str(row.get("value")), str(row.get("valueType"))));
            }
        }
        List<String> objects = (List<String>) data.get("objectsToAdd");
        return new IntentPlan(str(data.get("bizObject")),
                Integer.parseInt(String.valueOf(data.getOrDefault("instance", 1))),
                objects == null ? List.of() : objects, items, List.of());
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<?, ?> parsed = objectMapper.readValue(json, Map.class);
            Map<String, Object> result = new LinkedHashMap<>();
            parsed.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        } catch (Exception e) {
            log.warn("下发计划解析失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 模板默认值与本次请求值合并：请求优先，合并结果随任务落库，便于事后复查「当时要下发什么」。
     */
    private Map<String, Object> merge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        if (override != null) {
            result.putAll(override);
        }
        return result;
    }

    private static String str(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
