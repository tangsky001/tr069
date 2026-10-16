package com.tr069.acs.exec;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.acs.model.DataModel;
import com.tr069.acs.acs.service.CwmpRpcService;
import com.tr069.acs.acs.service.DeviceBusinessService;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeModel;

import com.tr069.acs.exec.entity.DeviceTask;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeParameterMapper;
import com.tr069.acs.exec.mapper.DeviceTaskMapper;
import com.tr069.acs.exec.model.DeliveryRequest;
import com.tr069.acs.exec.model.IntentPlan;
import com.tr069.acs.exec.model.PlanItem;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.template.service.ConfigTemplateService;
import com.tr069.acs.worklist.service.ModelMappingService;
import com.tr069.acs.worklist.tpl.dao.DeviceDao;
import com.tr069.acs.worklist.tpl.entity.Device;
import com.tr069.acs.worklist.tpl.service.ModelTemplateService;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final ConfigTemplateService configTemplateService;
    /** 下发渲染改读 SQLite 适配库，型号适配结果不再走 MySQL 的语义映射表 */
    private final SqlitePlanRenderer renderer;
    private final DeviceDao deviceDao;
    private final ModelTemplateService templateService;
    private final CwmpRpcService rpcService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AcsCoreLog coreLog;
    /** 读设备已上报的业务参数快照，WAN 下发前靠它清点现场有哪些连接 */
    private final DeviceBusinessService businessService;
    /** 读设备能力快照，下发前靠它确认 WAN 实际的挂载位置 */
    private final CpeParameterMapper parameterMapper;

    /** 路径里的 WAN 挂载位置，形如 {@code WANConnectionDevice.2.}。 */
    private static final Pattern WAN_DEVICE = Pattern.compile("WANConnectionDevice\\.\\d+\\.");

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

        Device model = findAdaptedModel(device, bizObject);
        // 数据模型对不上时宁可不发：把 IGD 的路径写进 TR-181 设备，设备只会回 9005
        checkDataModel(model, device);

        IntentPlan plan = renderer.render(bizObject, request.category(), model,
                values, resolveInstance(values));
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
     * 按设备侧型号标识去 SQLite 适配库里匹配已适配的型号。
     *
     * <p>型号不是设备台账里的 SN，而是 ProductClass + 固件版本算出来的 key，
     * 同型号同固件共用一份适配结果。归档时可能挂在实际运营商目录下，也可能在
     * {@code standard} 下，两处都找一遍。</p>
     *
     * <p>查不到就是没适配过——这不是下发能补的，必须回到适配流程重新采集
     * （采集导出后会由 {@link ModelTemplateService} 生成模板）。</p>
     */
    private Device findAdaptedModel(CpeDevice device, String bizObject) {
        String modelKey = ModelMappingService.buildModelKey(
                device.getProductClass(), device.getSoftwareVersion());
        String isp = device.getIsp() == null || device.getIsp().isBlank()
                ? "standard" : device.getIsp().trim();
        Device adapted = deviceDao.findByIspModelKey(isp, modelKey).orElse(null);
        // 命中了但这份没申报过（早期采集留下的空档）：同型号里挑有申报的那份。
        // 否则拿空申报去生成，模板条数为 0，表现成「明明适配过，却说没模板」
        if (adapted == null || !declared(adapted)) {
            Device withCap = deviceDao.findByModelKey(modelKey).stream()
                    .filter(IntentDeliveryService::declared)
                    .findFirst().orElse(null);
            if (withCap != null) {
                log.info("型号按运营商命中的归档无申报，改用已申报那份: model={}, {} -> {}",
                        modelKey, adapted == null ? "无" : adapted.getIsp(), withCap.getIsp());
                adapted = withCap;
            }
        }
        if (adapted == null && !"standard".equals(isp)) {
            adapted = deviceDao.findByIspModelKey("standard", modelKey).orElse(null);
        }
        if (adapted == null) {
            throw new IllegalArgumentException(
                    "该型号尚未适配，请先发起适配: " + modelKey + "（isp=" + isp + "）");
        }
        // 型号登记过但没生成过模板（比如能力申报是后补的）：当场补一次，
        // 不必让运维再跑一遍采集
        if (!renderer.hasTemplate(adapted, bizObject)) {
            log.info("型号缺少 {} 的模板，按能力申报补生成: model={}", bizObject, modelKey);
            templateService.generateAll(adapted);
        }
        return adapted;
    }

    /** 实例号：写第几个（第几个 SSID、第几路语音），缺省 1。 */
    private static int resolveInstance(Map<String, Object> values) {
        Object raw = values == null ? null : values.get("instance");
        if (raw == null) {
            return 1;
        }
        try {
            int n = Integer.parseInt(String.valueOf(raw).trim());
            return n > 0 ? n : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 这份归档有没有做过能力申报。
     *
     * <p>申报是模板生成的唯一输入：没有它，WiFi / SIP / LAN 的条数全是 0，
     * 下发只能报「模板里没有第 N 个实例」。所以匹配型号时它比运营商更关键。</p>
     */
    private static boolean declared(Device model) {
        return model != null && (notBlank(model.getCapWifiSpec())
                || notBlank(model.getCapSipCount())
                || notBlank(model.getCapLanPorts()));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private void checkDataModel(Device model, CpeDevice device) {
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
            // 动 WAN 的业务：先把旧连接清干净再按模板重建，否则残留连接会和新业务抢路由
            if (isWan(task.getCategory())) {
                plan = alignWanDevice(task, plan);
                if (!cleanupWan(task)) {
                    return;
                }
            }
            // AddObject 的实例号是设备分配的，可能和计划渲染时占的号不同，
            // 必须回填进参数路径，否则会写到错误的实例上去
            Map<String, Integer> allocated = new LinkedHashMap<>();
            for (String objectName : plan.objectsToAdd()) {
                SoapMessage resp = await(rpcService.addObject(task.getSn(), objectName, task.getTaskNo()));
                String error = failureOf(resp, "AddObject " + objectName);
                if (error != null) {
                    finish(task, DeviceTask.STATUS_FAILED, error);
                    return;
                }
                Integer instance = parseInstance(CwmpRpcService.instanceNumberOf(resp));
                if (instance != null) {
                    allocated.put(objectName, instance);
                }
                log.info("已创建业务对象: sn={}, object={}, instance={}",
                        task.getSn(), objectName, instance);
            }
            List<List<PlanItem>> chunks = chunks(remapInstances(plan, allocated));
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

    /**
     * AddObject 返回的实例号回填进参数路径。
     *
     * <p>渲染计划时参数路径里的实例号是占位值（plan.instance()），设备实际分配的
     * 可能是另一个号——不改写就会把参数写到错误的实例上。只动「以 AddObject 对象名
     * 开头、且实例号段等于占位值」的路径，其余路径一律不碰。</p>
     */
    static List<PlanItem> remapInstances(IntentPlan plan, Map<String, Integer> allocated) {
        if (allocated.isEmpty()) {
            return plan.parameters();
        }
        String planned = String.valueOf(plan.instance());
        List<PlanItem> out = new ArrayList<>(plan.parameters().size());
        for (PlanItem item : plan.parameters()) {
            String path = item.path();
            String replaced = path;
            for (Map.Entry<String, Integer> e : allocated.entrySet()) {
                String prefix = e.getKey();          // AddObject 对象名，以 "." 结尾
                if (!replaced.startsWith(prefix)) {
                    continue;
                }
                String rest = replaced.substring(prefix.length());   // 如 "1.Enable"
                int dot = rest.indexOf('.');
                if (dot > 0 && rest.substring(0, dot).equals(planned)) {
                    replaced = prefix + e.getValue() + rest.substring(dot);
                    break;
                }
            }
            out.add(replaced.equals(path) ? item
                    : new PlanItem(item.bizObject(), item.bizField(), replaced,
                           item.value(), item.valueType()));
        }
        return out;
    }

    /** 解析 AddObject 返回的实例号，取不到或非正数返回 null（维持占位值不动）。 */
    private static Integer parseInstance(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 该业务是否要动 WAN 连接。只有 WAN 类才需要清理，WiFi / SIP 下发不要碰。
     */
    private static boolean isWan(String category) {
        return category != null && category.toUpperCase().startsWith("WAN_");
    }

    /**
     * WAN 下发前清理旧业务连接：TR069 管理通道保留，其余逐个删除。
     *
     * <p>认不出管理通道时整体中止——宁可本次下发失败，也不冒设备脱管的险。
     * 失败原因会写进工单，运维看到就知道是「判读不出来」，而不是配置本身有问题。</p>
     *
     * @return true 表示可以继续下发
     */
    private boolean cleanupWan(DeviceTask task) {
        try {
            WanCleanup.Plan clean = WanCleanup.plan(fetchWanLinks(task.getSn()));
            if (clean.delete().isEmpty()) {
                return true;
            }
            log.info("下发前清理旧 WAN: sn={}, 保留={}条, 删除={}条, 明细={}",
                    task.getSn(), clean.keep().size(), clean.delete().size(), describe(clean));
            List<String> skipped = new ArrayList<>();
            for (WanCleanup.WanLink victim : clean.delete()) {
                SoapMessage resp = await(rpcService.deleteObject(
                        task.getSn(), victim.path(), task.getTaskNo()));
                String error = failureOf(resp, "DeleteObject " + victim.path());
                if (error != null) {
                    // 设备拒绝删除（实测回 9002 Internal error）就是最硬的「占用」证据：
                    // 这条连接要么正在承载业务，要么是内置不允许删的。清理是尽力而为——
                    // 删不掉就留着继续下发，总比整单判死、新业务一条都发不下去强
                    log.warn("清理旧 WAN 被设备拒绝，跳过该连接继续下发: sn={}, path={}, err={}",
                            task.getSn(), victim.path(), error);
                    skipped.add(victim.path());
                }
            }
            if (!skipped.isEmpty()) {
                log.warn("旧 WAN 未清理干净，继续下发新业务（可能残留冲突路由）: sn={}, 未清理={}",
                        task.getSn(), String.join(", ", skipped));
            }
            return true;
        } catch (WanCleanup.Tr069NotFound e) {
            log.warn("中止下发: sn={}, taskNo={}, 原因={}", task.getSn(), task.getTaskNo(), e.getMessage());
            finish(task, DeviceTask.STATUS_FAILED, e.getMessage());
            return false;
        } catch (Exception e) {
            // 现场都读不清就不知道该保住哪条，此时删任何一条都可能命中管理通道
            log.warn("读取现有 WAN 失败，中止下发: sn={}, taskNo={}, err={}",
                    task.getSn(), task.getTaskNo(), e.getMessage());
            finish(task, DeviceTask.STATUS_FAILED,
                    "读取现有 WAN 连接失败，中止下发以免误删管理通道: " + e.getMessage());
            return false;
        }
    }

    /**
     * 按设备实际的 WAN 挂载位置校正下发计划。
     *
     * <p>模板里的挂载位置是型号级的、当初照着另一台设备的报文写死的
     * （{@code WANConnectionDevice.2}），换一台结构不同的设备（比如只有 {@code .1}）
     * 照发就是 9002 Internal error。下发前拿这台设备采集到的真实实例号改一遍，
     * AddObject 的父对象和后续参数路径都要跟着改。</p>
     */
    private IntentPlan alignWanDevice(DeviceTask task, IntentPlan plan) {
        Integer actual = detectWanDevice(task.getSn());
        if (actual == null) {
            return plan;
        }
        List<String> objects = plan.objectsToAdd().stream()
                .map(name -> withWanDevice(name, actual))
                .toList();
        List<PlanItem> params = plan.parameters().stream()
                .map(item -> new PlanItem(item.bizObject(), item.bizField(),
                        withWanDevice(item.path(), actual), item.value(), item.valueType()))
                .toList();
        boolean changed = !objects.equals(plan.objectsToAdd());
        if (changed) {
            log.info("按设备实际结构校正 WAN 挂载位置: sn={}, WANConnectionDevice={}",
                    task.getSn(), actual);
        }
        return new IntentPlan(plan.bizObject(), plan.instance(), objects, params, plan.errors());
    }

    /**
     * 这台设备的 WAN 连接挂在 {@code WANConnectionDevice} 的第几个实例下。
     *
     * <p>取设备自己上报的能力快照，不发 RPC；采不到就返回 null，让计划保持原样
     * （宁可按模板发，也好过凭空猜一个实例号）。</p>
     */
    private Integer detectWanDevice(String sn) {
        try {
            List<CpeParameter> rows = parameterMapper.selectList(
                    new LambdaQueryWrapper<CpeParameter>()
                            .eq(CpeParameter::getSn, sn)
                            .like(CpeParameter::getParamPath, "WANConnectionDevice.")
                            .last("LIMIT 200"));
            for (CpeParameter row : rows) {
                Matcher m = WAN_DEVICE.matcher(row.getParamPath());
                if (m.find()) {
                    return Integer.valueOf(m.group(1));
                }
            }
        } catch (Exception e) {
            log.warn("读取 WAN 挂载位置失败，按模板原样下发: sn={}, err={}", sn, e.getMessage());
        }
        return null;
    }

    private static String withWanDevice(String path, int instance) {
        if (path == null) {
            return null;
        }
        return WAN_DEVICE.matcher(path)
                .replaceAll("WANConnectionDevice." + instance + ".");
    }

    /**
     * 清理明细：每条连接是什么业务、占不占用、留还是删，逐条列清。
     *
     * <p>下发失败时最需要的是「当时到底判成了什么」，而不是一句汇总数字。</p>
     */
    private static String describe(WanCleanup.Plan plan) {
        StringBuilder sb = new StringBuilder();
        for (WanCleanup.WanLink link : plan.keep()) {
            sb.append("[保留 ").append(shortPath(link.path()))
                    .append(" 业务=").append(WanCleanup.serviceOf(link))
                    .append(" 占用中=").append(WanCleanup.isBusy(link)).append(']');
        }
        for (WanCleanup.WanLink link : plan.delete()) {
            sb.append("[删除 ").append(shortPath(link.path()))
                    .append(" 业务=").append(WanCleanup.serviceOf(link))
                    .append(" 空闲=").append(!WanCleanup.isBusy(link)).append(']');
        }
        return sb.toString();
    }

    private static String shortPath(String path) {
        int i = path == null ? -1 : path.indexOf("WANDevice.");
        return i < 0 ? String.valueOf(path) : path.substring(i);
    }

    /**
     * 读出设备上现有的 WAN 连接及其参数。
     *
     * <p>取设备上报时存下的快照，不再为此发一轮 RPC：设备能连上来上报，
     * 这些参数就必然已经采过，拿快照比临时 GPN 更快也更稳。</p>
     */
    @SuppressWarnings("unchecked")
    private List<WanCleanup.WanLink> fetchWanLinks(String sn) {
        Map<String, Object> data = businessService.collect(sn);
        Object wanObj = data.get("wan");
        if (!(wanObj instanceof List<?> list)) {
            return List.of();
        }
        List<WanCleanup.WanLink> links = new ArrayList<>();
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) raw;
            Object paramObj = item.get("params");
            Map<String, String> params = paramObj instanceof Map<?, ?>
                    ? (Map<String, String>) paramObj
                    : Map.of();
            Object path = item.get("path");
            if (path != null) {
                links.add(new WanCleanup.WanLink(String.valueOf(path), params));
            }
        }
        return links;
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
