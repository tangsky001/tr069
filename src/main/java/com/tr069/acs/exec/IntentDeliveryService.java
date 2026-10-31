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
import com.tr069.acs.worklist.service.ModelTreeStore;
import com.tr069.acs.worklist.tpl.TemplateVariables;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /** 下发后回读参数等待设备应答的秒数。超时就不再等，交给后面的全量补采。 */
    private static final int READBACK_TIMEOUT_SECONDS = 8;

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
    /** 读型号能力树，下发前靠它把模板里的参数名校正成这台设备真实的那套 */
    private final ModelTreeStore modelTreeStore;
    /** 下发后补采一次能力树，让新建的实例即时出现在设备列表里 */
    private final com.tr069.acs.acs.service.DeviceCapabilityService capabilityService;

    /**
     * 路径里的 WAN 挂载位置，形如 {@code WANConnectionDevice.2.}。
     *
     * <p>实例号是捕获组：{@link #detectWanDevice(String)} 要把它取出来比对，
     * 写成非捕获的 {@code \d+} 时 {@code group(1)} 会抛
     * {@code IndexOutOfBoundsException}，判读静默失败、计划原样下发。</p>
     */
    private static final Pattern WAN_DEVICE = Pattern.compile("WANConnectionDevice\\.(\\d+)\\.");

    /**
     * 每台设备一把清理锁：同一批次的多个业务任务不能同时清点、删除连接。
     *
     * <p>批量下发 Internet + VoIP 是两个任务，各自跑在 {@link #EXECUTOR} 的不同线程上，
     * 不加锁就会「一个在建、一个在删」，删掉对方刚建好的连接。</p>
     */
    private final Map<String, Object> wanCleanupLocks = new ConcurrentHashMap<>();

    /**
     * 每台设备的清理台账：批次窗口内不重复清理，本批次新建的连接永不进删除清单。
     *
     * <p>这是「删除只跑一次」的落点。窗口只解决前后脚的两个任务，
     * 新建清单解决窗口过期后再来一单的情况：只要是我们建的，什么时候都不删。</p>
     */
    private final Map<String, WanCleanupState> wanCleanupLedger = new ConcurrentHashMap<>();

    /** 下发等待设备回应的超时（秒）。 */
    @Value("${acs.exec.request-timeout-seconds:90}")
    private long requestTimeoutSeconds;

    /**
     * 同一台设备两次 WAN 清理之间的最小间隔（秒）：间隔内的第二单视为同一批次，不再清理。
     *
     * <p>批量下发两条业务时，第二单开工时第一单的连接刚建好，
     * 此时再清一次就把它删了——这正是「下发两条只剩一条」的根因。</p>
     */
    @Value("${acs.exec.wan-cleanup-window-seconds:600}")
    private long wanCleanupWindowSeconds;

    /**
     * 下发前反向唤醒后，最多等多少秒设备上来建链。
     *
     * <p>设备收到连接请求一般在几秒内上来。等满这个时间还没建链就先发，
     * 指令留在队列里，不把「暂时叫不醒」直接判成失败。</p>
     */
    @Value("${acs.exec.wakeup-wait-seconds:30}")
    private long wakeupWaitSeconds;

    /**
     * 单条清理指令（DeleteObject）等设备回应的秒数。
     *
     * <p>比 {@link #requestTimeoutSeconds} 短：清理是尽力而为，设备不在线时
     * 为它等满下发窗口，整包后面几个成员就全被拖住了。</p>
     */
    @Value("${acs.exec.wan-cleanup-timeout-seconds:30}")
    private long wanCleanupTimeoutSeconds;

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
        // 计划已定稿，剩下的就是等设备取走指令——不能堵在调用方的 HTTP 线程上，
        // 丢给后台线程推进状态
        if (DeviceTask.STATUS_RUNNING.equals(task.getStatus())) {
            // 指令只能捎带在设备自己发起的那轮会话里：先反向唤醒，等它上来建链再提交。
            // 以前是「提交指令」与「发连接请求」并行，会话还没建立指令就进了队列，
            // 只能干等下一次周期上报（十几分钟），于是每条指令都撞满超时
            EXECUTOR.execute(() -> {
                awaitSession(request.sn(), task.getTaskNo());
                execute(task);
            });
        }
        return task;
    }

    /**
     * 先反向唤醒设备，等它建链再返回。
     *
     * <p>CWMP 的指令只能捎在设备主动发起的那轮会话里下发：连接请求发出后，设备会上来
     * Inform 并紧接着发空 POST 把队列里的指令取走。会话没建立就提交指令，设备这轮
     * 取不到，只能等下一次周期上报才有机会——周期通常是十几分钟，
     * 于是每条指令都撞满 {@code request-timeout-seconds} 超时，整单判死。</p>
     *
     * <p>等待有上限：叫不醒就照旧下发，指令留在队列里，设备下次上报仍会取走，
     * 不至于把「设备暂时不上线」直接判成下发失败。</p>
     */
    private void awaitSession(String sn, String taskNo) {
        // 唤醒 + 等建链的判定统一放在 DeviceCapabilityService，采集/刷新走的是同一套
        if (capabilityService.wakeUpAndAwait(sn, wakeupWaitSeconds)) {
            log.info("设备已建链，开始下发: sn={}, taskNo={}", sn, taskNo);
        } else {
            log.warn("等待设备建链超时，按原样下发（指令留在队列里等设备上报，可能超时）: sn={}, taskNo={}, 已等={}s",
                    sn, taskNo, wakeupWaitSeconds);
        }
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

        // 配置模板字段有两处用途：按字段名覆盖渲染结果，以及给命名不一致的业务补下发变量，
        // 这里取一次后面复用
        Map<String, Object> tplFields = request.templateId() == null ? null
                : configTemplateService.fieldsOf(request.templateId());

        // SIP 配置模板的字段名与下发变量根本不是一套：模板上叫 registrar / proxy /
        // sip_user_info，下发要的是 %sip_proxy% / %sip_num% / %sip_pwd%。不转换的话
        // 模板里填的语音参数一个都传不进去，下发只会报「缺业务字段值」。
        Map<String, Object> requested = request.values() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.values());
        if (tplFields != null && !tplFields.isEmpty()) {
            applySipTemplateVariables(requested, stringValues(tplFields), resolveInstance(requested));
        }

        // 变量兜底放在渲染前这个唯一汇聚点上：工单单台、批量、北向接口都从这里过，
        // 房间号取设备登记值、口令取约定默认值，调用方一个变量都不传也能渲染出完整计划
        Map<String, Object> values = TemplateVariables.applyDefaults(
                request.category(), requested, device.getRoomNo());

        // 字段覆盖优先级：本次下发显式传的值 > 配置模板里保存的字段 > SQLite 默认值。
        // 必须按这个顺序塞：先放高优先级的 values，再用 putIfAbsent 补模板字段。
        // 反过来（先模板后 putIfAbsent 补 values）会让模板里存的旧值压住本次要下发的新值，
        // 表现为「业务模板上填了 0000，设备上下去的还是模板库里的 1111」。
        // 空串不算显式值——表单把每个变量初始化成空串提交上来，那不代表要清掉模板值。
        Map<String, String> overrides = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((k, v) -> {
                String text = textValue(v);
                if (text != null && !text.isBlank()) {
                    overrides.put(k, text);
                }
            });
        }
        Map<String, Object> intentFields = new LinkedHashMap<>();
        if (tplFields != null) {
            intentFields.putAll(tplFields);
            tplFields.forEach((k, v) -> overrides.putIfAbsent(k, textValue(v)));
        }

        // 重启这类设备管理意图是标准 RPC，不依赖型号适配：没做过适配的设备也应该能重启
        Device model = "Device_Mgmt".equals(bizObject) ? null : findAdaptedModel(device, bizObject);
        // 数据模型对不上时宁可不发：把 IGD 的路径写进 TR-181 设备，设备只会回 9005
        checkDataModel(model, device);

        IntentPlan plan = renderer.render(bizObject, request.category(), model,
                values, resolveInstance(values), overrides);
        plan = alignParameterNames(request.sn(), plan);
        plan = ensureServiceBinding(request.sn(), plan, values, overrides);
        plan = ensureVlanMode(request.sn(), plan);
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
     * 把渲染出的参数名校正为该型号真实存在的那套，并剔掉设备上根本没有的参数。
     *
     * <p>模板是跨厂商通用的写法，落到具体型号常常要换名：地址获取方式模板写
     * {@code IPAddressingType}，这台设备按 TR-098 叫 {@code AddressingType}；VLAN 模板写
     * {@code X_CMCC_VLANID}，移动定制机上真实存在的是 {@code X_CMCC_VLANIDMark}。
     * 照模板原样发，设备回 9003 Invalid arguments——SetParameterValues 是按批生效的，
     * 一条写错整批都废，前面 AddObject 刚建好的连接也就白建了。所以这一步必须落在下发之前。</p>
     *
     * <p>校不动的不硬写：宁可跳过并在日志里留痕由人补映射，也不猜一个节点写进去。
     * 这台设备的参数和型号档案都查不到时，{@link ModelTreeStore#resolvePaths} 返回空结果，
     * 这里保持模板原路径——信息不足时不能反过来把参数删掉。</p>
     */
    private IntentPlan alignParameterNames(String sn, IntentPlan plan) {
        if (plan.parameters().isEmpty()) {
            return plan;
        }
        List<String> paths = plan.parameters().stream().map(PlanItem::path).toList();
        ModelTreeStore.PathResolution resolved = modelTreeStore.resolvePaths(sn, paths);
        if (resolved.getRewritten().isEmpty() && resolved.getUnsupported().isEmpty()
                && resolved.getTypes().isEmpty() && resolved.getReadOnly().isEmpty()) {
            return plan;
        }
        List<PlanItem> items = new ArrayList<>();
        for (PlanItem item : plan.parameters()) {
            if (resolved.getUnsupported().contains(item.path())) {
                log.warn("该型号能力树上没有这个参数，跳过不下发: {}", item.path());
                continue;
            }
            String path = resolved.getRewritten().getOrDefault(item.path(), item.path());
            if (resolved.getReadOnly().contains(path)) {
                log.warn("该参数在该型号上是只读，跳过不下发: {}", path);
                continue;
            }
            // 下发格式以能力树声明的类型为准：SSID 取值 1111 全是数字，靠值猜会被写成整数
            String type = resolved.getTypes().getOrDefault(path, item.valueType());
            String value = item.value();
            if ("boolean".equalsIgnoreCase(type)) {
                value = booleanValue(value);
            }
            if (LAN_INTERFACE_NODE.equalsIgnoreCase(leafOf(path))) {
                // 绑定端口落到 X_CMCC_LanInterface 上时，值必须是 LAN 口的完整路径串，
                // 页面给的 LAN1,LAN2 写下去会被整批拒（SPV 按批生效，会连 Enable 一起废掉）
                String bound = lanInterfaceValue(path, value);
                if (bound == null) {
                    log.warn("绑定端口值无法映射到 LAN 口路径，本次不下发: {} = {}", path, value);
                    continue;
                }
                value = bound;
            }
            items.add(new PlanItem(item.bizObject(), item.bizField(), path, value, type));
        }
        List<String> errors = new ArrayList<>(plan.errors());
        if (items.isEmpty()) {
            errors.add("模板里的参数在这台设备上一个都对不上，请重新适配该型号");
        }
        return new IntentPlan(plan.bizObject(), plan.instance(), plan.objectsToAdd(), items, errors);
    }

    /** 绑定端口节点：中国移动定制机上真叫这个名字，取值为 LAN 口路径。 */
    private static final String LAN_INTERFACE_NODE = "X_CMCC_LanInterface";

    private static String leafOf(String path) {
        if (path == null) {
            return "";
        }
        int idx = path.lastIndexOf('.');
        return idx < 0 ? path : path.substring(idx + 1);
    }

    /**
     * 把页面勾选的 {@code LAN1,LAN2} 翻成设备认的 LAN 口路径串。
     *
     * <p>实测（中兴 P22g-CIac）：{@code X_CMCC_LanInterface} 上现存的值是
     * {@code InternetGatewayDevice.LANDevice.1.LANEthernetInterfaceConfig.1}——一个口的完整
     * 路径，多口按逗号分隔，空串＝不绑定（设备上默认绑全部口）。直接写 {@code LAN1} 会被
     * 判 9003 Invalid arguments。</p>
     *
     * @return 设备侧取值；无法映射（如勾了 SSID / PON）返回 null，由调用方跳过
     */
    private static String lanInterfaceValue(String path, String value) {
        String v = value == null ? "" : value.trim();
        StringBuilder sb = new StringBuilder();
        for (String part : v.split("[,;\\s]+")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String port = lanPortPath(path, p);
            if (port == null) {
                return null;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(port);
        }
        return sb.toString();
    }

    /** LAN{n} → 根节点下的 LANEthernetInterfaceConfig.{n}；已经是路径的照原样收下。 */
    private static String lanPortPath(String wanPath, String port) {
        if (port.startsWith("InternetGatewayDevice.") || port.startsWith("Device.")) {
            return port;
        }
        if (port.length() < 4 || !port.regionMatches(true, 0, "LAN", 0, 3)) {
            return null;
        }
        String no = port.substring(3);
        for (int i = 0; i < no.length(); i++) {
            if (!Character.isDigit(no.charAt(i))) {
                return null;
            }
        }
        int dot = wanPath.indexOf('.');
        String root = dot < 0 ? "InternetGatewayDevice." : wanPath.substring(0, dot + 1);
        return root + "LANDevice.1.LANEthernetInterfaceConfig." + no;
    }

    /**
     * WAN 连接补写「业务绑定」参数（这台设备上是 {@code X_CMCC_ServiceList}）。
     *
     * <p>实测这条不加，业务就下不去：新建出来的连接在没绑业务之前拒绝启用——
     * {@code Enable} 单独回 9003 Invalid arguments，同一批里带着它就整批 9003。
     * 补写 {@code X_CMCC_ServiceList=INTERNET} 之后，同一实例的同一批六条（含 Enable）
     * 一次成功。设备上原有的连接也印证了这套规则：
     * {@code Name=2_INTERNET_R_VID_41} 对应 {@code X_CMCC_ServiceList=INTERNET}、
     * {@code Name=3_VOIP_R_VID_45} 对应 {@code VOIP}。</p>
     *
     * <p>参数名由设备的能力树决定（各厂商写法不一，CMCC 是 ServiceList），这里逐个候选名
     * 去树上试，树上没有就不写——宁缺毋滥，写错名字比不写更糟。模板自己带了业务绑定的
     * 时候以模板为准，不重复加。</p>
     */
    private IntentPlan ensureServiceBinding(String sn, IntentPlan plan, Map<String, Object> values,
                                            Map<String, String> overrides) {
        if (!isWanConnection(plan.bizObject()) || plan.parameters().isEmpty()) {
            return plan;
        }
        if (plan.parameters().stream().anyMatch(IntentDeliveryService::isServiceBinding)) {
            return plan;
        }
        String service = serviceOf(values, overrides);
        if ("OTHER".equalsIgnoreCase(service)) {
            // 「其他 / Other」不是不绑业务，而是业务绑定就写 OTHER：设备上现存的桥接
            // 4_OTHER_B_VID_100 的 X_CMCC_ServiceList 正是 OTHER，且它连着。
            // 以前这里当成"纯二层透传不绑业务"跳过，结果同一批里的 Enable 被设备拒
            // （没绑业务的连接不许启用），整批 9003，桥接一条也建不起来。
            log.info("业务模式为 Other，按 OTHER 补写业务绑定（不绑业务时设备拒绝启用）: sn={}", sn);
        }
        String first = plan.parameters().get(0).path();
        int cut = first.lastIndexOf('.');
        String parent = cut < 0 ? first : first.substring(0, cut + 1);
        for (String candidate : SERVICE_LIST_NAMES) {
            String path = parent + candidate;
            ModelTreeStore.PathResolution resolved = modelTreeStore.resolvePaths(sn, List.of(path));
            if (resolved.getUnsupported().contains(path)) {
                continue;
            }
            // 树上真有这个节点才写。能力树/型号档案都没有时 resolvePaths 返回空结果
            //（不是 unsupported），此时这道 ...ServiceList 是猜的，猜错名字会被判 9005
            boolean exists = resolved.getTypes().containsKey(path)
                    || resolved.getCurrentValues().containsKey(path)
                    || resolved.getRewritten().containsKey(path);
            if (!exists) {
                continue;
            }
            String real = resolved.getRewritten().getOrDefault(path, path);
            String type = resolved.getTypes().getOrDefault(real, "string");
            List<PlanItem> items = new ArrayList<>(plan.parameters());
            items.add(new PlanItem(plan.bizObject(), "service_list", real, service, type));
            log.info("补写业务绑定: sn={}, 参数={}={}", sn, real, service);
            return new IntentPlan(plan.bizObject(), plan.instance(),
                    plan.objectsToAdd(), items, plan.errors());
        }
        log.warn("这台设备的 WAN 连接上没有业务绑定参数，按原样下发（可能起不来）: sn={}", sn);
        return plan;
    }

    /**
     * WAN 连接补写「VLAN 模式」（这台设备上是 {@code X_CMCC_VLANMode}）。
     *
     * <p>下发 VLAN ID 而不带模式，等于没下发：这台设备在 {@code VLANMode=0}
     * （透传/不打标签）时，会把写进来的 {@code X_CMCC_VLANIDMark} 原样回成功、
     * 转头丢掉，读回来还是 0——于是连接以无标签方式拨上去，拿到的是上级
     * 网关的 192.168.1.x，VLAN 形同没配。实测对比：</p>
     * <ul>
     *   <li>实例 8：整批 {@code [VLANIDMark=100, …, Enable=true]} 返回成功，
     *       回读 {@code VLANIDMark=0}，设备自动命名为 {@code 8_INTERNET_R_VID_}
     *       （VID 段为空），Connected 拿到 192.168.1.7。</li>
     *   <li>实例 5：先有 {@code VLANMode=2}，再写 {@code VLANIDMark=100}，
     *       两项都存活，设备自动命名为 {@code 5_INTERNET_R_VID_100}。</li>
     * </ul>
     *
     * <p>所以 VLAN ID 有值时，模式必须跟着写，且要写在 ID 之前——
     * {@link #orderByDependency} 同档内保持原序，这里直接插在 ID 那一项前面。</p>
     *
     * <p>与业务绑定同样的规矩：参数名由这台设备的能力树决定，逐个候选名去试，
     * 树上没有就不写；VLAN ID 为空或 0 时不写模式（本来就不打标签）。</p>
     */
    IntentPlan ensureVlanMode(String sn, IntentPlan plan) {
        if (!isWanConnection(plan.bizObject()) || plan.parameters().isEmpty()) {
            return plan;
        }
        int at = -1;
        for (int i = 0; i < plan.parameters().size(); i++) {
            PlanItem item = plan.parameters().get(i);
            if (isVlanMode(item)) {
                return plan;
            }
            if (at < 0 && isVlanId(item) && vlanIdOf(item) > 0) {
                at = i;
            }
        }
        if (at < 0) {
            return plan;
        }
        PlanItem vid = plan.parameters().get(at);
        String parent = vid.path().substring(0, vid.path().lastIndexOf('.') + 1);
        for (String candidate : VLAN_MODE_NAMES) {
            String path = parent + candidate;
            ModelTreeStore.PathResolution resolved = modelTreeStore.resolvePaths(sn, List.of(path));
            if (resolved.getUnsupported().contains(path)) {
                continue;
            }
            // 与业务绑定同理：树上真有这个节点才写，查不到数据时 resolvePaths 返回空结果，
            // 此时不猜名字
            String real = resolved.getRewritten().getOrDefault(path, path);
            if (!resolved.getTypes().containsKey(real)
                    && !resolved.getCurrentValues().containsKey(real)
                    && !resolved.getRewritten().containsKey(path)) {
                continue;
            }
            String type = resolved.getTypes().getOrDefault(real, "int");
            List<PlanItem> items = new ArrayList<>(plan.parameters());
            items.add(at, new PlanItem(plan.bizObject(), "vlan_mode", real, VLAN_TAG_MODE, type));
            log.info("补写 VLAN 模式: sn={}, 参数={}={}", sn, real, VLAN_TAG_MODE);
            return new IntentPlan(plan.bizObject(), plan.instance(),
                    plan.objectsToAdd(), items, plan.errors());
        }
        return plan;
    }

    /**
     * 把本次刚写下去的参数读回来落库，让设备列表立刻对得上现场。
     *
     * <p>下发改的是设备现值，库里的快照不会自己跟上。只靠 {@link #refreshCapability} 的
     * 全量补采是不够的：它要把整棵树重新走一遍，实测六十秒上下，而运维在下发完几秒内
     * 就会刷新列表——那一刻库里还是下发前的旧值，看着像「下发没生效」，
     * 实际手机上已经能搜到新 SSID 了。</p>
     *
     * <p>所以这里先按本次下发的路径回读一次（几秒内），全量补采仍照常异步跑，
     * 负责把新建出来的实例补进快照。回读以设备上报值为准：下发成功但设备没吃进去的，
     * 库里就留真实值，比留我们以为下发的值更可信。</p>
     */
    private void refreshDeliveredValues(String sn, IntentPlan plan) {
        List<String> paths = new ArrayList<>();
        for (PlanItem item : plan.parameters()) {
            if (item.path() != null && !item.path().isBlank()) {
                paths.add(item.path());
            }
        }
        if (paths.isEmpty()) {
            return;
        }
        try {
            capabilityService.readPathValues(sn, paths)
                    .get(READBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("已回读下发参数: sn={}, 参数={}", sn, paths.size());
        } catch (TimeoutException e) {
            log.warn("回读下发参数超时，等后续全量采集: sn={}, 参数={}", sn, paths.size());
        } catch (Exception e) {
            log.warn("回读下发参数失败: sn={}, err={}", sn, e.getMessage());
        }
    }

    /**
     * 下发完成后补采一次能力树，让新实例出现在设备列表里。
     *
     * <p>前端那条「N 条 WAN 连接」读的是 {@code cpe_parameter} 的快照，而快照只在
     * 全量采集（GetParameterNames 走一遍子树）时才会更新。新建出来的实例不在
     * 上次采集的路径清单里，于是下发明明成功了，列表上还是旧的那几条。
     * 这里在建完实例后补一次采集，把新连接补进快照。</p>
     *
     * <p>采集是异步的，失败只记日志，不影响本次下发的结论。</p>
     */
    private void refreshCapability(String sn) {
        if (sn == null || sn.isBlank()) {
            return;
        }
        try {
            capabilityService.collect(sn, true);
        } catch (Exception e) {
            log.warn("下发后补采能力树失败: sn={}, err={}", sn, e.getMessage());
        }
    }

    /**
     * 判断本次下发要不要在成功后重启设备。
     *
     * <p>「下发业务完成后重启光猫」是配置模板（OTHER/Device_Mgmt）里的字段
     * {@code reboot_after}，渲染时会把它写进 {@code intentFields} 快照。这里把它读出来，
     * true/1/是 都视为启用。</p>
     *
     * <p>独立建单的「重启」模板，以及业务模板整包里作为最后一个成员的「重启」成员，
     * 都会走到这里。</p>
     */
    private boolean needReboot(DeviceTask task) {
        String json = task.getIntentFields();
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            Object v = map.get("reboot_after");
            if (v == null) {
                return false;
            }
            String s = String.valueOf(v).trim();
            return "true".equalsIgnoreCase(s) || "1".equals(s) || "是".equals(s);
        } catch (Exception e) {
            log.warn("解析 intentFields 失败: sn={}, err={}", task.getSn(), e.getMessage());
            return false;
        }
    }

    private void reboot(DeviceTask task) {
        try {
            rpcService.reboot(task.getSn(), task.getTaskNo());
            log.info("已下发重启指令: sn={}, taskNo={}", task.getSn(), task.getTaskNo());
        } catch (Exception e) {
            log.warn("下发重启指令失败: sn={}, taskNo={}, err={}", task.getSn(), task.getTaskNo(), e.getMessage());
        }
    }

    /**
     * 整批被拒时逐条重发，定位到底哪一条不合法。
     *
     * <p>SetParameterValues 按批生效：一条不合法整批都废，而设备只回一个笼统的
     * 9003/9007，无从判断错在哪个路径、哪个值。这里把这一批拆开逐条再发一遍，
     * 被拒的那条就浮出来了。重复写同一个参数是幂等的，不会有额外副作用。</p>
     *
     * @return 被拒参数的说明；全都能写进去时返回 null（说明整批失败是别的原因）
     */
    /** 逐条定位的结果：设备拒收的（判失败），以及设备不接受、已跳过的（仅提示）。 */
    private static class LocateResult {

        private final List<String> rejected = new ArrayList<>();

        private final List<String> skipped = new ArrayList<>();
    }

    private void locateRejected(DeviceTask task, List<ParameterValueStruct> values, LocateResult out) {
        if (values == null || values.size() <= 1) {
            return;
        }
        for (ParameterValueStruct v : values) {
            if (tryWrite(task, v)) {
                continue;
            }
            // 设备对同一个参数常只认某一种 xsi:type：VoiceService 的 Line.Enable 按 boolean
            // 带 true 被拒、按 unsignedInt 带 1 也被拒，换 string 反而收。换写法再试一遍，
            // 接受哪个就用哪个，省得为了一个开关让整单失败
            ParameterValueStruct accepted = null;
            for (ParameterValueStruct alt : alternates(v)) {
                if (tryWrite(task, alt)) {
                    accepted = alt;
                    break;
                }
            }
            if (accepted != null) {
                log.info("该参数换写法后被设备接受: {}={}（type {} -> {}）",
                        v.getName(), accepted.getValue(), v.getValueType(), accepted.getValueType());
                continue;
            }
            // 启用开关类（Enable/Disable）设备收不下就算了：核心的账号、密码、代理都已写进去，
            // 为一个设备压根不支持写入的开关判整单失败，运维还得重下一次
            if (isSwitch(v.getName())) {
                log.warn("启用开关设备不接受，跳过不下发: {}", v.getName());
                out.skipped.add(v.getName());
                continue;
            }
            out.rejected.add(v.getName() + "=" + v.getValue() + "（type=" + v.getValueType()
                    + "，设备当前值=" + currentValue(task.getSn(), v.getName()) + "）");
        }
    }

    /** 启用开关类参数：{@code Enable} / {@code SSIDAdvertisementEnabled} 等。 */
    private static boolean isSwitch(String path) {
        String leaf = leafOfPath(path);
        return leaf != null && leaf.matches("(?i).*(enable|enabled|disable|disabled)$");
    }

    private boolean tryWrite(DeviceTask task, ParameterValueStruct v) {
        try {
            SoapMessage resp = await(rpcService.setParameterValues(
                    task.getSn(), List.of(v), task.getTaskNo()));
            return failureOf(resp, "SetParameterValues") == null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 同一个参数换几种 xsi:type 与值的写法，设备认哪种就用哪种。 */
    private static List<ParameterValueStruct> alternates(ParameterValueStruct v) {
        List<ParameterValueStruct> alts = new ArrayList<>();
        String name = v.getName();
        String raw = v.getValue();
        alts.add(ParameterValueStruct.builder().name(name).value(raw).valueType("string").build());
        String bool = booleanValue(raw);
        if (!bool.equals(raw)) {
            alts.add(ParameterValueStruct.builder().name(name).value(bool).valueType("boolean").build());
        }
        if (raw != null && raw.matches("\\d+")) {
            alts.add(ParameterValueStruct.builder().name(name).value(raw).valueType("unsignedInt").build());
        }
        return alts;
    }

    /** 库里这台设备该参数的当前值。被拒时打出来对照——设备原本用什么写法，一目了然。 */
    private String currentValue(String sn, String path) {
        try {
            List<CpeParameter> rows = parameterMapper.selectList(
                    new LambdaQueryWrapper<CpeParameter>()
                            .eq(CpeParameter::getSn, sn)
                            .eq(CpeParameter::getParamPath, path)
                            .last("LIMIT 1"));
            return rows.isEmpty() || rows.get(0).getParamValue() == null
                    ? "-" : rows.get(0).getParamValue();
        } catch (Exception e) {
            return "-";
        }
    }

    /** 业务绑定的默认取值：没指定业务模式时按上网处理。 */
    private static final String SERVICE_INTERNET = "INTERNET";

    /**
     * 设备认识的业务绑定取值，取型号能力树里 {@code X_CMCC_ServiceList} 的实测值。
     *
     * <p>{@code OTHER} 也是实测值：设备上自带的桥接 {@code 4_OTHER_B_VID_100} 就是 OTHER。</p>
     */
    private static final List<String> SERVICE_TOKENS =
            List.of("INTERNET", "TR069", "VOIP", "IPTV", "OTHER");

    /**
     * 桥接会用到、但本机型能力树里还没采到样本的取值。
     *
     * <p>{@code OTT} 是移动规范里的桥接业务选项之一，样机上没采到过。照填下发并留一条
     * 提示——被设备拒时日志里能直接看出是这个值的问题，而不是静默改成 INTERNET。</p>
     */
    private static final List<String> SERVICE_TOKENS_UNVERIFIED = List.of("OTT");

    /**
     * 业务绑定的取值：本次显式传的值 &gt; 配置模板里保存的业务模式 &gt; 默认 INTERNET。
     *
     * <p>配置模板把「业务模式」存在 fields 里、调用方放进的是 overrides，只看 values 会让
     * 模板上选的 VOIP 完全失效——每条连接都被绑成 INTERNET，语音通道被当成上网连接走。
     * 实测过：模板 {@code WAN_DHCP_VOIP_45} 的业务模式选的是 VOIP，下发补写的却是 INTERNET。
     * 这里按与参数覆盖一致的优先级取，再统一成设备认的大写写法。</p>
     */
    private static String serviceOf(Map<String, Object> values, Map<String, String> overrides) {
        Object raw = values == null ? null : values.get("service_list");
        if (raw == null || String.valueOf(raw).isBlank()) {
            raw = overrides == null ? null : overrides.get("service_list");
        }
        return normalizeServiceList(raw == null ? null : String.valueOf(raw));
    }

    /**
     * 业务模式归一成设备口径：{@code Internet / internet, VOIP} 变成 {@code INTERNET,VOIP}。
     *
     * <p>配置模板的选项是给人看的写法（{@code Internet}、{@code Internet,TR069}），设备上
     * 实测只认 {@code INTERNET} / {@code TR069} / {@code VOIP} / {@code IPTV} 这几种大写写法，
     * 混着大小写发过去会被判 9007。</p>
     */
    static String normalizeServiceList(String raw) {
        if (raw == null || raw.isBlank()) {
            return SERVICE_INTERNET;
        }
        List<String> out = new ArrayList<>();
        for (String token : raw.split("[,，、+/\\s]+")) {
            String v = token.trim().toUpperCase(Locale.ROOT);
            if (v.isEmpty()) {
                continue;
            }
            if (!SERVICE_TOKENS.contains(v) && !SERVICE_TOKENS_UNVERIFIED.contains(v)) {
                log.warn("业务模式取值不在设备口径内，按 INTERNET 处理: {}", token);
                v = SERVICE_INTERNET;
            } else if (SERVICE_TOKENS_UNVERIFIED.contains(v)) {
                log.info("业务模式取值本机型未采样到，按填写值下发: {}", v);
            }
            if (!out.contains(v)) {
                out.add(v);
            }
        }
        return out.isEmpty() ? SERVICE_INTERNET : String.join(",", out);
    }

    /** VLAN 模式的取值：2 = 打 802.1Q 标签，路由/桥接带 VLAN 的业务都要它。 */
    private static final String VLAN_TAG_MODE = "2";

    /** VLAN 模式参数的候选写法，逐个去能力树上试，谁在树上用谁。 */
    private static final List<String> VLAN_MODE_NAMES =
            List.of("X_CMCC_VLANMode", "X_CT-COM_VLANMode", "VLANMode");

    /** 业务绑定参数的候选写法，逐个去能力树上试，谁在树上用谁。 */
    private static final List<String> SERVICE_LIST_NAMES =
            List.of("X_CMCC_ServiceList", "ServiceList", "X_CT-COM_ServiceList");

    private static boolean isWanConnection(String bizObject) {
        return "Internet_WAN".equalsIgnoreCase(bizObject) || "Bridge_WAN".equalsIgnoreCase(bizObject);
    }

    private static boolean isServiceBinding(PlanItem item) {
        return "service_list".equalsIgnoreCase(item.bizField())
                || "X_CMCC_ServiceList".equalsIgnoreCase(leafOfPath(item.path()))
                || "ServiceList".equalsIgnoreCase(leafOfPath(item.path()));
    }

    /**
     * 布尔型参数统一按 xsd:boolean 的标准词法 {@code true/false} 下发。
     *
     * <p>{@code xsd:boolean} 的词法值是 {@code true/false/1/0} 四种，但实现照不全的
     * 设备不少：只接受 {@code true} 的见过，只接受 {@code 1} 的也见过。业务字段填的是
     * {@code 1/0}，这台设备自己上报用的是 {@code TRUE/FALSE}，平台另一条下发通道历来写
     * {@code true/false} 且在这台设备上成功过——取三方都认的标准布尔词。</p>
     *
     * <p>认不出来的原样返回：这里要是自作主张改了非布尔值，就又多一个踩坑点。</p>
     */
    static String booleanValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        if ("1".equals(value) || "true".equalsIgnoreCase(value)) {
            return "true";
        }
        if ("0".equals(value) || "false".equalsIgnoreCase(value)) {
            return "false";
        }
        return raw;
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
            // 设备都报出这个频段的 SSID 了，说明适配时 WIFI 规格那一项选漏了。
            // 这里按设备实有频段补齐申报再生成——漏报 5G 的后果是模板库里根本没有
            // wlan_b 那张表，想改一次 5G 的 SSID 得重做整轮适配
            String spec = adapted.getCapWifiSpec();
            boolean has24 = spec != null && spec.contains("2.4");
            boolean has58 = spec != null && spec.contains("5");
            if ("WiFi_5G".equals(bizObject) && !has58) {
                adapted.setCapWifiSpec("2.4G+5.8G");
                log.info("型号申报的 WIFI 规格不含 5.8G，按设备实有频段补齐: model={}", modelKey);
            } else if ("WiFi_2G".equals(bizObject) && !has24) {
                adapted.setCapWifiSpec(has58 ? "2.4G+5.8G" : "2.4G");
                log.info("型号申报的 WIFI 规格不含 2.4G，按设备实有频段补齐: model={}", modelKey);
            }
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
        // 型号档案为 null 是允许的：重启这类标准 RPC 不依赖适配，没有型号档案可比
        if (model == null || device == null) {
            return;
        }
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
            // 动 WAN 的业务：先删后建——清理必须发生在任何 AddObject 之前，
            // 且一个批次只清一次（见 cleanupWan 的台账），否则第二个业务会把
            // 第一个刚建好的连接当成残留删掉
            if (isWan(task.getCategory())) {
                plan = alignWanDevice(task, plan);
                // 模板上勾了「增量业务」就完全跳过清理：只 AddObject 新增这一条，
                // 设备上已有的 IPTV / VOIP / 用户自建 Internet 一条都不动
                if (incremental(task)) {
                    log.info("增量业务，跳过 WAN 清理（只新增，不删不改已有连接）: sn={}, taskNo={}",
                            task.getSn(), task.getTaskNo());
                } else {
                    cleanupWan(task);
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
                    // 记进清理台账：这是本次新建的连接，之后任何清理都不得删它。
                    // 实例号必须是设备响应里的这个号——删过一轮后设备会重排编号，
                    // 照模板里的固定号找会找到别人头上
                    rememberCreated(task.getSn(), objectName, instance);
                }
                log.info("已创建业务对象: sn={}, object={}, instance={}",
                        task.getSn(), objectName, instance);
            }
            List<List<PlanItem>> chunks = chunks(orderByDependency(remapInstances(plan, allocated)));
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
                    // SPV 是按批生效的，一条写错整批都废。不打这批参数根本不知道错在哪个路径、
                    // 错在哪个值——设备只回一个笼统的 9003/9007，只能拆开逐条重发来定位
                    log.warn("下发参数被设备拒绝: sn={}, taskNo={}, 批次={}/{}, err={}, 参数={}",
                            task.getSn(), task.getTaskNo(), i + 1, chunks.size(), error, dump(values));
                    LocateResult located = new LocateResult();
                    locateRejected(task, values, located);
                    if (!located.rejected.isEmpty()) {
                        String rejected = "逐条重发后被拒的参数: " + String.join(" | ", located.rejected);
                        log.warn("逐条定位结果: sn={}, taskNo={}, {}", task.getSn(), task.getTaskNo(), rejected);
                        finish(task, DeviceTask.STATUS_FAILED, error + "；" + rejected);
                        return;
                    }
                    // 整批被拒但拆开发全都成了：参数已经在设备上生效，按成功收尾，
                    // 否则为了一个兼容性写法就得让运维重下一次
                    log.info("整批被拒但逐条重发全部成功，按成功收尾: sn={}, taskNo={}",
                            task.getSn(), task.getTaskNo());
                    refreshDeliveredValues(task.getSn(), plan);
                    if (needReboot(task)) {
                        reboot(task);
                    } else {
                        refreshCapability(task.getSn());
                    }
                    finish(task, DeviceTask.STATUS_SUCCESS, located.skipped.isEmpty() ? null
                            : "已跳过设备不接受的参数: " + String.join(" | ", located.skipped));
                    return;
                }
            }
            log.info("下发完成: sn={}, taskNo={}, 参数={}", task.getSn(), task.getTaskNo(),
                    plan.parameters().size());
            // 下发改的是设备现值，库里的快照不会自己跟上。全量补采要几十秒，
            // 页面这会儿早刷新过了——先把本次写下去的参数读回来落库，列表立刻对得上现场
            refreshDeliveredValues(task.getSn(), plan);
            if (needReboot(task)) {
                // 马上要重启了，补采只会采到重启前的一瞬，设备上报 0 BOOTSTRAP 时自然会再采
                reboot(task);
            } else {
                refreshCapability(task.getSn());
            }
            finish(task, DeviceTask.STATUS_SUCCESS, null);
        } catch (Exception e) {
            log.warn("下发异常: sn={}, taskNo={}, err={}", task.getSn(), task.getTaskNo(), e.getMessage());
            finish(task, DeviceTask.STATUS_FAILED, e.getMessage());
        }
    }

    /**
     * 重排下发顺序：先写「定身份」的 VLAN 参数，剩下的按原序，{@code Enable} 一律放最后。
     *
     * <p>顺序不是讲究，是这台 CMCC 光猫能不能收下的前提，实测如下（均在 AddObject 新建出来的
     * 实例上逐条单发验证）：</p>
     * <ul>
     *   <li>刚建出来的连接上，只有 {@code X_CMCC_VLANIDMark} / {@code X_CMCC_VLANMode}
     *       写得进去；{@code ConnectionType} / {@code AddressingType} / {@code NATEnabled} /
     *       {@code Name} / {@code X_CMCC_ServiceList} 一律回 9007 Invalid parameter value。
     *       把 VLAN 那几个写完，其余参数才开放写——所以要打头。</li>
     *   <li>{@code Enable} 反过来，必须等连接配齐：VLAN 之前写它是 9007，VLAN 之后写它是 9003。
     *       它是每批都被判 9003 的那一条。</li>
     *   <li>两者都满足后，五条按顺序单点全部成功。</li>
     * </ul>
     *
     * <p>{@code X_CMCC_ServiceList} 这类业务绑定紧随 VLAN 之后：先绑业务，后面的连接参数
     * 才写得进去。只重排、不增删改：原先这批参数的取值一个都没变，改的只是下发次序。</p>
     */
    static List<PlanItem> orderByDependency(List<PlanItem> items) {
        if (items == null || items.size() < 2) {
            return items;
        }
        List<PlanItem> leading = new ArrayList<>();
        List<PlanItem> binding = new ArrayList<>();
        List<PlanItem> middle = new ArrayList<>();
        List<PlanItem> trailing = new ArrayList<>();
        for (PlanItem item : items) {
            if (isEnable(item)) {
                trailing.add(item);
            } else if (isVlan(item)) {
                leading.add(item);
            } else if (isServiceBinding(item)) {
                binding.add(item);
            } else {
                middle.add(item);
            }
        }
        if (leading.isEmpty() && binding.isEmpty() && trailing.size() == items.size()) {
            // 全是 Enable（不太可能）：照原序返回，不要把一整单都当成要挪到末尾的
            return items;
        }
        List<PlanItem> ordered = new ArrayList<>(items.size());
        ordered.addAll(leading);
        ordered.addAll(binding);
        ordered.addAll(middle);
        ordered.addAll(trailing);
        return ordered;
    }

    /** 业务字段就叫 enable（WiFi / LAN / WAN 各模板一致）；字段名不可靠时退到参数末段名。 */
    private static boolean isEnable(PlanItem item) {
        return "enable".equalsIgnoreCase(item.bizField())
                || "Enable".equalsIgnoreCase(leafOfPath(item.path()));
    }

    private static boolean isVlan(PlanItem item) {
        return item.bizField() != null && item.bizField().toLowerCase().contains("vlan");
    }

    /**
     * 这是 VLAN ID 那一条，而不是 VLAN 模式。
     *
     * <p>两个参数的业务字段名都带 vlan，得靠末段名区分：ID 是 {@code VLANIDMark} /
     * {@code VLANID} 这类，模式是 {@code VLANMode} 这类。判错了要么漏写模式
     * （VLAN 不生效），要么把模式当成 ID 跳过了补写。</p>
     */
    private static boolean isVlanId(PlanItem item) {
        if (!isVlan(item)) {
            return false;
        }
        String leaf = leafOfPath(item.path()).toLowerCase();
        return leaf.contains("vlanid") || leaf.contains("vlan-id") || leaf.contains("vlanidmark");
    }

    private static boolean isVlanMode(PlanItem item) {
        if (!isVlan(item)) {
            return false;
        }
        String leaf = leafOfPath(item.path()).toLowerCase();
        return leaf.contains("mode");
    }

    /** VLAN ID 的数值，取不出来（空/负数/非数字）返回 0，表示「这次不带 VLAN」。 */
    private static int vlanIdOf(PlanItem item) {
        String raw = item.value() == null ? "" : item.value().trim();
        if (raw.isEmpty() || "-1".equals(raw)) {
            return 0;
        }
        try {
            int n = Integer.parseInt(raw);
            return n > 0 ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String leafOfPath(String path) {
        return path == null || path.isEmpty() ? "" : path.substring(path.lastIndexOf('.') + 1);
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
     * 业务字段值转成可下发的字符串。
     *
     * <p>多选字段（绑定端口）存的是 {@code ["LAN1","LAN2"]}，直接 {@code String.valueOf}
     * 会写出 {@code [LAN1, LAN2]} 这种带方括号的串，设备只会回 9003 / 9007；按模板定义的
     * 约定拼成 {@code LAN1,LAN2}。空集合得到空串，仍按「未填写」处理，不覆盖默认值。</p>
     */
    private static String textValue(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Collection<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String s = String.valueOf(item).trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(s);
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    /**
     * 该业务是否要动 WAN 连接。只有 WAN 类才需要清理，WiFi / SIP 下发不要碰。
     */
    private static boolean isWan(String category) {
        return category != null && category.toUpperCase().startsWith("WAN_");
    }

    /**
     * 这一单是不是「增量业务」：模板上勾了这个开关，就只新增、不清理也不改写已有业务。
     *
     * <p>判据取任务上的模板 id，现查模板：开关是模板级语义，改了就该对排队中的任务同样生效，
     * 不必给 device_task 再落一列。没有模板（手工临时下发）按覆盖语义走，保持原行为。</p>
     */
    private boolean incremental(DeviceTask task) {
        return task != null && configTemplateService.incrementalOf(task.getTemplateId());
    }

    /**
     * WAN 下发前清理旧业务连接：TR069 管理通道保留，其余逐个删除。
     *
     * <p><strong>先删后建，且一个批次只删一次</strong>。批量下发 Internet + VoIP 是两个
     * 任务，各跑各的线程：第二个任务开工时若再清一次，第一个刚建好的连接就在存量快照里，
     * 会被当成出厂残留删掉——现场看到的就是「两条都下发成功了，最后只剩一条」。
     * 所以这里用 {@link #wanCleanupLedger} 记账：窗口内来过的第二单直接跳过清理，
     * 台账里已记的新建连接无论何时都不进删除清单。</p>
     *
     * <p>识不出管理通道时不删任何一条，照常继续下发：删错管理通道设备就脱管了，
     * 那比旧连接抢路由严重得多。</p>
     *
     * <p><strong>清理失败不判死整单</strong>：设备不回、删不动、快照读不全都是
     * 「没清掉」，不是「不能发」。清不掉就留着旧连接继续下发，业务起来了比什么都强；
     * 真要清理由下一次下发或人工处理。只有清理这一步，绝不能反过来拖垮下发。</p>
     */
    private void cleanupWan(DeviceTask task) {
        String sn = task.getSn();
        // 同一台设备串行：一个任务在建、另一个在删是最难复现也最难查的故障
        synchronized (wanCleanupLocks.computeIfAbsent(sn, k -> new Object())) {
            WanCleanupState state = wanCleanupLedger.computeIfAbsent(sn, k -> new WanCleanupState());
            if (state.fresh(wanCleanupWindowSeconds * 1000L)) {
                log.info("本批次已清理过 WAN，本次跳过（避免删掉同批次刚建的业务连接）: sn={}, taskNo={}, 窗口={}s",
                        sn, task.getTaskNo(), wanCleanupWindowSeconds);
                return;
            }
            List<WanCleanup.WanLink> existing;
            String managementHint;
            WanCleanup.Plan clean;
            try {
                // 1. 存量快照：只删这次读到的连接，本次要新建的此刻还不存在，不参与判断
                existing = fetchWanLinks(sn);
                // 2. 管理通道线索：按 ManagementServer 的指向认，不按实例编号认
                managementHint = findManagementWanPath(sn);
                clean = WanCleanup.plan(existing, managementHint, state.createdPaths());
            } catch (Exception e) {
                // 现场读不清就不知道该保住哪条：一条都不删，但也不中止下发——
                // 不删就不会误删管理通道，业务照发。台账照样记账，免得整包里
                // 每个成员都再干等一轮采集超时
                log.warn("读取现有 WAN 失败，本次跳过清理继续下发: sn={}, taskNo={}, err={}",
                        sn, task.getTaskNo(), textOf(e));
                state.markCleaned(System.currentTimeMillis());
                return;
            }
            if (clean.delete().isEmpty()) {
                log.info("下发前清理旧 WAN: sn={}, 存量={}条, 不删任何连接（{}）",
                        sn, existing.size(), clean.reason());
                state.markCleaned(System.currentTimeMillis());
                return;
            }
            log.info("下发前清理旧 WAN: sn={}, 保留={}条, 删除={}条, 管理通道线索={}, 原因={}, 明细={}",
                    sn, clean.keep().size(), clean.delete().size(), managementHint,
                    clean.reason(), describe(clean));
            List<String> skipped = new ArrayList<>();
            // 3. 存量里不在保留集合的，一次删完
            for (WanCleanup.WanLink victim : clean.delete()) {
                try {
                    SoapMessage resp = awaitCleanup(rpcService.deleteObject(
                            sn, victim.path(), task.getTaskNo()));
                    String error = failureOf(resp, "DeleteObject " + victim.path());
                    if (error != null) {
                        // 设备拒绝删除（实测回 9002 Internal error）就是最硬的「占用」证据：
                        // 这条连接要么正在承载业务，要么是内置不允许删的。清理是尽力而为——
                        // 删不掉就留着继续下发，总比整单判死、新业务一条都发不下去强
                        log.warn("清理旧 WAN 被设备拒绝，跳过该连接继续下发: sn={}, path={}, err={}",
                                sn, victim.path(), error);
                        skipped.add(victim.path());
                    }
                } catch (Exception e) {
                    // 设备不回（等满 request-timeout-seconds 的 TimeoutException）也是「没清掉」，
                    // 不能当成致命错误：把整单判死了，新业务一条都发不下去
                    log.warn("清理旧 WAN 未拿到设备回应，跳过该连接继续下发: sn={}, path={}, err={}",
                            sn, victim.path(), textOf(e));
                    skipped.add(victim.path());
                }
            }
            if (!skipped.isEmpty()) {
                log.warn("旧 WAN 未清理干净，继续下发新业务（可能残留冲突路由）: sn={}, 未清理={}",
                        sn, String.join(", ", skipped));
            }
            // 4. 清理只此一次；后面的 AddObject 与 SetParameterValues 不再触发任何删除
            state.markCleaned(System.currentTimeMillis());
        }
    }

    /** 异常的可读描述：超时类异常没有 message，只打 null 现场没法排查。 */
    private static String textOf(Throwable e) {
        if (e == null) {
            return "未知原因";
        }
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }

    /**
     * 记下本次新建的连接路径，后续任何清理都不许删它。
     *
     * <p>路径按「对象名 + 响应返回的实例号」拼：删过一轮后设备会重排实例号，
     * 只有响应里那个号是准的。</p>
     */
    private void rememberCreated(String sn, String objectName, int instance) {
        String prefix = objectName.endsWith(".") ? objectName + instance + "." : objectName + "." + instance + ".";
        wanCleanupLedger.computeIfAbsent(sn, k -> new WanCleanupState()).remember(prefix);
        log.info("已登记本次新建的 WAN 连接（后续清理不再删它）: sn={}, path={}", sn, prefix);
    }

    /**
     * 设备 {@code ManagementServer} 指向的那条 WAN 连接。
     *
     * <p>管理通道不能按「第几个实例」认：出厂自带的实例号会漂移，换批设备就可能认错。
     * 这里取设备自己申报的指向——{@code ManagementServer} 下哪个参数的值带
     * {@code WANIPConnection.n.} / {@code WANPPPConnection.n.}，那条就是管理通道；
     * 只给了实例号的（有些厂商只写 {@code 9}）也照收，由
     * {@link WanCleanup#matchesManagement(String, String)} 按号比对。</p>
     *
     * @return 连接片段（{@code WANIPConnection.9.}）或实例号（{@code 9}），认不出返回 null
     */
    String findManagementWanPath(String sn) {
        try {
            List<CpeParameter> rows = parameterMapper.selectList(
                    new LambdaQueryWrapper<CpeParameter>()
                            .eq(CpeParameter::getSn, sn)
                            .like(CpeParameter::getParamPath, "ManagementServer.")
                            .last("LIMIT 200"));
            // ManagementServer 下还有 PeriodicInformInterval 这类纯数字参数，
            // 它们跟管理通道无关，所以先找「带连接片段」的值，找不到才退到纯数字
            String numeric = null;
            for (CpeParameter row : rows) {
                String value = row.getParamValue();
                if (value == null || value.isBlank()) {
                    continue;
                }
                String text = value.trim();
                String segment = WanCleanup.connectionSegmentOf(text);
                if (segment != null) {
                    return segment;
                }
                if (numeric == null && text.matches("\\d+")) {
                    numeric = text;
                }
            }
            if (numeric != null) {
                return numeric;
            }
            log.warn("未能从 ManagementServer 认出管理通道，改按 ServiceList 判定: sn={}", sn);
        } catch (Exception e) {
            log.warn("读取 ManagementServer 参数失败，改按 ServiceList 判定: sn={}, err={}",
                    sn, e.getMessage());
        }
        return null;
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
        // 常打一行：挂载位置对不对是「下发 9002」最常见的根因，事后要能看到当时取的是几号
        log.info("WAN 挂载位置: sn={}, 设备实际=WANConnectionDevice.{}, {}",
                task.getSn(), actual, changed ? "已按设备结构校正模板" : "与模板一致");
        return new IntentPlan(plan.bizObject(), plan.instance(), objects, params, plan.errors());
    }

    /**
     * 这台设备的 WAN 连接挂在 {@code WANConnectionDevice} 的第几个实例下。
     *
     * <p>取设备自己上报的能力快照，不发 RPC；采不到就返回 null，让计划保持原样
     * （宁可按模板发，也好过凭空猜一个实例号）。快照记录的是「清理前」的状态，
     * 业务连接还在，正是判挂载位置该看的那一份。</p>
     *
     * <p>一份快照里可能残留多个批次采集出的 {@code WANConnectionDevice} 编号，
     * 取最小的那个：设备上真正生效的挂载点是唯一的，编号最小即最靠前的一级。
     * 早先这里是「取第一条匹配行」，行序由数据库决定，同一台设备两次下发可能落到
     * 不同编号上。</p>
     */
    /** 包内可见：挂载位置判读是要盯住的逻辑，留给单元测试直接验证。 */
    Integer detectWanDevice(String sn) {
        try {
            List<CpeParameter> rows = parameterMapper.selectList(
                    new LambdaQueryWrapper<CpeParameter>()
                            .eq(CpeParameter::getSn, sn)
                            .like(CpeParameter::getParamPath, "WANConnectionDevice.")
                            .orderByAsc(CpeParameter::getParamPath)
                            .last("LIMIT 200"));
            Integer found = null;
            for (CpeParameter row : rows) {
                Matcher m = WAN_DEVICE.matcher(row.getParamPath());
                if (m.find()) {
                    int value = Integer.parseInt(m.group(1));
                    if (found == null || value < found) {
                        found = value;
                    }
                }
            }
            if (found == null) {
                log.warn("未能判读 WAN 挂载位置，按模板原样下发: sn={}", sn);
            }
            return found;
        } catch (Exception e) {
            log.warn("读取 WAN 挂载位置失败，按模板原样下发: sn={}, err={}", sn, e.getMessage());
        }
        return null;
    }

    static String withWanDevice(String path, int instance) {
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
     * <p>先取库里的快照（{@code summary}），库里没有（这台设备还没采过）才向设备要一轮。
     * 清理要的只是「有哪些存量连接」，快照足够；每次清理都真采一轮是几百条路径的
     * GetParameterValues，会把设备这一轮的下发窗口占满，设备回应不过来就是整轮超时、
     * 指令被重排队列，后面的业务全被拖垮。</p>
     *
     * <p>快照陈旧只会「少删」（漏掉新出现的连接），不会「误删」，方向是安全的。</p>
     */
    private List<WanCleanup.WanLink> fetchWanLinks(String sn) {
        List<WanCleanup.WanLink> cached = wanLinksFrom(businessService.summary(sn));
        if (!cached.isEmpty()) {
            return cached;
        }
        log.info("库里没有 WAN 快照，向设备采一轮再清点存量: sn={}", sn);
        return wanLinksFrom(businessService.collect(sn));
    }

    @SuppressWarnings("unchecked")
    private List<WanCleanup.WanLink> wanLinksFrom(Map<String, Object> data) {
        Object wanObj = data == null ? null : data.get("wan");
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
     * SIP 配置模板字段 → 下发变量。
     *
     * <p>模板面向开通界面，字段名是 {@code registrar} / {@code proxy} / {@code sip_user_info}；
     * 下发要的变量是 {@code %sip_proxy%} / {@code %sip_num%} / {@code %sip_pwd%}。
     * 名字对不上，模板上填好的语音参数就一个都传不进去，下发只剩「缺业务字段值」。</p>
     *
     * <p>账号密码那块是一整段文本，一行一路语音，行内「账号 密码」，按本次要写的第几路
     * 取对应行；行里还能带 {@code %room%} 这类变量，后面统一替换。</p>
     *
     * <p>一律 {@code putIfAbsent}：调用方显式传的变量优先，模板只做补位。</p>
     */
    private static void applySipTemplateVariables(Map<String, Object> values,
                                                  Map<String, String> fields, int instance) {
        String proxy = firstNonBlank(fields.get("proxy"), fields.get("registrar"));
        if (proxy != null) {
            values.putIfAbsent(TemplateVariables.SIP_PROXY, proxy);
        }
        String registrar = fields.get("registrar");
        if (registrar != null && !registrar.isBlank()) {
            values.putIfAbsent(TemplateVariables.SIP_REGISTRAR, registrar);
        }
        String port = firstNonBlank(fields.get("proxy_port"), fields.get("registrar_port"));
        if (port != null) {
            values.putIfAbsent(TemplateVariables.SIP_PORT, port);
        }
        String registrarPort = fields.get("registrar_port");
        if (registrarPort != null && !registrarPort.isBlank()) {
            values.putIfAbsent(TemplateVariables.SIP_REGISTRAR_PORT, registrarPort);
        }
        String info = fields.get("sip_user_info");
        if (info == null || info.isBlank()) {
            return;
        }
        String[] lines = info.split("\\R");
        int idx = Math.min(Math.max(instance, 1), lines.length) - 1;
        if (idx < 0) {
            return;
        }
        String[] pair = lines[idx].trim().split("\\s+");
        if (pair.length > 0 && !pair[0].isBlank()) {
            values.putIfAbsent(TemplateVariables.SIP_NUM, pair[0]);
        }
        if (pair.length > 1 && !pair[1].isBlank()) {
            values.putIfAbsent(TemplateVariables.SIP_PWD, pair[1]);
        }
    }

    /** 取第一个非空值，都没有返回 null。 */
    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }

    private static Map<String, String> stringValues(Map<String, Object> fields) {
        Map<String, String> out = new LinkedHashMap<>();
        fields.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
        return out;
    }

    /**
     * 把一批待下发参数拼成可读文本，只在下发失败时打进日志。
     *
     * <p>值一并打印：9003 报的是「值不合法」，只看到参数名无从判断。
     * 下发计划本就明文存在 {@code device_task} 里，日志里出现的口令与库里是同一份。</p>
     */
    private static String dump(List<ParameterValueStruct> values) {
        StringBuilder sb = new StringBuilder();
        for (ParameterValueStruct v : values) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(v.getName()).append('=').append(v.getValue());
        }
        return sb.toString();
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

    /** 清理指令的等待：短于正式下发，见 {@link #wanCleanupTimeoutSeconds}。 */
    private SoapMessage awaitCleanup(CompletableFuture<SoapMessage> future) throws Exception {
        return future.get(wanCleanupTimeoutSeconds, TimeUnit.SECONDS);
    }

    private List<List<PlanItem>> chunks(List<PlanItem> items) {
        // WAN 连接的 Enable 单独放最后一批：设备要求先把业务绑定 / VLAN / 绑定端口配好
        // 才允许启用，同一批里带着 Enable 时，前面任何一条被拒都会把启用一起拖掉，
        // 而且日志里只看得到整批 9003，分不清是"配置不对"还是"不许启用"
        List<PlanItem> body = new ArrayList<>(items.size());
        List<PlanItem> enables = new ArrayList<>();
        for (PlanItem item : items) {
            if (isWanEnable(item)) {
                enables.add(item);
            } else {
                body.add(item);
            }
        }
        List<List<PlanItem>> chunks = new ArrayList<>();
        for (int i = 0; i < body.size(); i += VALUE_BATCH) {
            chunks.add(body.subList(i, Math.min(body.size(), i + VALUE_BATCH)));
        }
        if (!enables.isEmpty()) {
            // 配置那一批先发；它失败时整个任务就此收尾，绝不会顺手把半成品启用
            chunks.add(enables);
        }
        return chunks;
    }

    /** WAN 连接的启用开关：{@code WANIPConnection.{i}.Enable} / {@code WANPPPConnection.{i}.Enable}。 */
    private static boolean isWanEnable(PlanItem item) {
        String path = item.path();
        return path != null && path.contains("Connection.")
                && "Enable".equalsIgnoreCase(leafOf(path));
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

    /**
     * 一台设备的 WAN 清理台账：上次清理时间 + 本批次新建的连接路径。
     *
     * <p>「删除只跑一次」靠它落地：批量下发第二条业务时，{@link #fresh(long)} 为真
     * 说明同批次已经清过，直接跳过；{@link #createdPaths()} 里的连接无论隔多久都不删——
     * 下发完会补采快照，它们随后就会出现在存量里，只靠时间窗口挡不住。</p>
     */
    private static final class WanCleanupState {

        private volatile long lastCleanedAtMillis;

        private final Set<String> created = ConcurrentHashMap.newKeySet();

        /** 距上次清理还没超过窗口：视为同一批次，不再清理。 */
        boolean fresh(long windowMillis) {
            long last = lastCleanedAtMillis;
            return last > 0 && System.currentTimeMillis() - last < windowMillis;
        }

        void markCleaned(long nowMillis) {
            lastCleanedAtMillis = nowMillis;
        }

        void remember(String createdPath) {
            if (createdPath != null && !createdPath.isBlank()) {
                created.add(createdPath);
            }
        }

        Set<String> createdPaths() {
            return Set.copyOf(created);
        }
    }
}
