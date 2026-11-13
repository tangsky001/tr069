package com.tr069.acs.acs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tr069.acs.acs.config.CapabilityProfiles;
import com.tr069.acs.acs.adaptation.IspAdaptation;
import com.tr069.acs.acs.model.DataModel;
import com.tr069.acs.acs.model.Isp;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.PendingRpc;
import com.tr069.acs.acs.session.SessionManager;
import com.tr069.acs.agent.RpcDispatchService;
import com.tr069.acs.cwmp.model.EventStruct;
import com.tr069.acs.cwmp.model.InformRequest;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeDeviceMapper;
import com.tr069.acs.mapper.CpeParameterMapper;
import com.tr069.acs.queue.config.QueueProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 设备能力采集 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceCapabilityService {

    /** 0 BOOTSTRAP 事件码前缀，设备首次上线或恢复出厂后上报，此时设备能力可能已变，强制重采 */
    private static final String EVENT_BOOTSTRAP = "0 BOOTSTRAP";

    /** 采集状态：只拿到了已知路径那几个参数（设备身份），参数树没采到 */
    private static final String PARTIAL = "partial";

    /** 兜底的根数据模型节点：设备台账与会话都还没认出来时按 TR-098 处理 */
    private static final String DEFAULT_ROOT = DataModel.TR098.getRoot();

    /** 只采集这些子树，相对数据模型根节点书写 */
    private static final List<String> SUBTREES_TR098 = List.of(
            // 设备身份与运行时长都在 DeviceInfo 这层，节点不多但以前一棵没采，页面「运行时长」一直空白（UpTime 实测返回 unsignedInt 秒数）
            "DeviceInfo.",
            // WAN
            "WANDevice.",
            // LAN 网口（TR-098 的 WiFi 挂在 LANDevice 下面，随 LAN 子树一并取回）
            "LANDevice.",
            // SIP 电话
            "Services.VoiceService.");

    /** TR-181（{@code Device.}）下的业务子树 */
    private static final List<String> SUBTREES_TR181 = List.of(
            // 设备身份与运行时长，同上
            "DeviceInfo.",
            // WAN
            "WAN.",
            // LAN 网口
            "Ethernet.Interface.",
            "LAN.",
            // WiFi
            "WiFi.",
            // SIP 电话
            "Services.VoiceService.",
            "VoiceService.",
            // Device:2 核心子树
            "IP.Interface.",       // IP 接口（Device:2 主要的多实例 WAN 控制对象）
            "Hosts.",               // 终端设备列表（Device:2 Hosts 对象）
            "Bridging.",            // 桥接（Device:2 Bridging 对象）
            "GatewayInfo.",         // 网关信息（Device:2 GatewayInfo 对象）
            "ManagementServer.",    // 管理服务器（含 ConnectionRequestURL 等）
            "Diagnostics.");        // 诊断（IPPing / TraceRoute / Download / Upload）

    /** 会话级故障码：下发超时、连接断开、指令未入队，由会话层与本服务生成 */
    private static final String FAULT_SESSION = "8002";

    /** 指令入队失败（队列不可用或该设备队列已满），同样不是设备返回的故障 */
    private static final String FAULT_ENQUEUE = "8003";

    /** 批量写入的分片大小，避免单条 INSERT 过长 */
    private static final int INSERT_CHUNK = 500;

    /** 值回填的分片大小 */
    private static final int UPDATE_CHUNK = 200;

    /** 单个参数值的落库长度上限，超出截断，防止 TEXT 溢出导致整批写入失败 */
    private static final int MAX_VALUE_LENGTH = 20000;

    /**
     * 探测节奏：二分拆批与已知路径探测在固件缺很多候选路径时，会瞬间压出几十条背靠背的
     * GetParameterValues——低端固件的 CWMP agent 扛不住这种密度，直接不再回应，
     * 整轮采集以「下发窗口异常终止」收尾，能力树采不全，适配归档跟着缺货。批次间留口气。
     */
    private static final long PROBE_PACE_MILLIS = 300L;

    private static final DateTimeFormatter BATCH_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SessionManager sessionManager;
    private final RpcDispatchService rpcDispatchService;
    private final CpeParameterMapper parameterMapper;
    private final QueueProperties queueProperties;
    private final com.tr069.acs.worklist.service.ModelTreeStore modelTreeStore;
    /** 设备台账：取运营商，采完再把识别出的运营商写回去 */
    private final CpeDeviceMapper deviceMapper;
    /** 按运营商的能力采集标准 */
    private final CapabilityProfiles capabilityProfiles;

    private final com.tr069.acs.acs.adaptation.AdaptationManager adaptationManager;

    /** 连接请求安全防护：外呼前校验目标地址；配了主密钥时按序列号派生一台一密的凭据 */
    private final ConnectionRequestGuard connReqGuard;

    /** 采集总开关 */
    @Value("${acs.device.capability.enabled:true}")
    private boolean enabled;

    /** 收到 Inform 后是否自动触发采集 */
    @Value("${acs.device.capability.collect-on-inform:true}")
    private boolean collectOnInform;

    /** BOOTSTRAP 事件是否强制重新采集 */
    @Value("${acs.device.capability.force-on-bootstrap:true}")
    private boolean forceOnBootstrap;

    /** 距上次采集超过该小时数才重采，0 表示每次 Inform 都采 */
    @Value("${acs.device.capability.refresh-interval-hours:24}")
    private long refreshIntervalHours;

    /** 单条 GetParameterValues 携带的参数个数 */
    @Value("${acs.device.capability.value-batch-size:64}")
    private int valueBatchSize;

    /** 单次采集读取的参数个数上限 */
    @Value("${acs.device.capability.max-parameters:20000}")
    private int maxParameters;

    /** 递归 GetParameterNames 失败时，是否改用 NextLevel=true 逐层展开 */
    @Value("${acs.device.capability.fallback-next-level:true}")
    private boolean fallbackNextLevel;

    /** 逐层展开时最多展开多少个对象 */
    @Value("${acs.device.capability.max-object-rounds:500}")
    private int maxObjectRounds;

    /** 采集与回调使用的线程数 */
    @Value("${acs.device.capability.worker-threads:8}")
    private int workerThreads;

    /** 采集成功后是否把参数树导出为型号 XML 文件（models/{isp}/{型号}.xml） */
    @Value("${acs.device.capability.export-model-tree:true}")
    private boolean exportModelTree;

    /** 是否按能力树里的厂商私有前缀（X_CMCC_ / X_CT-COM_ / X_CU_）回写设备运营商 */
    private boolean autoDetectIsp = true;

    /** 下发前补采时最多等这么久，超时就按现有参数拼路径 */
    @Value("${acs.device.capability.auto-collect-timeout-millis:120000}")
    private long autoCollectTimeoutMillis;

    /** 每台设备最近一次采集状态 */
    private final Map<String, CollectStatus> statuses = new ConcurrentHashMap<>();

    /** 正在采集中的设备，避免同一台设备被并发采集 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** 自适应档设备「连续扫不动参数树」的次数（sn → 连续失败次数） */
    private final Map<String, Integer> treeFailures = new ConcurrentHashMap<>();

    /** 连续扫不动多少次才降级 */
    private static final int TREE_FAIL_THRESHOLD = 2;

    /** 一轮采集的总时长上限（毫秒） */
    @Value("${acs.capability.collect-timeout-ms:600000}")
    private long collectTimeoutMillis;

    /** 设备沉默中止阈值：连续这么多条指令一句回应都没有就中止整轮采集 */
    private static final int SILENCE_ABORT_THRESHOLD = 3;

    /** SIP 注册状态延迟复核：采集完成多少秒后重读注册状态（注册是异步的，刚开机肯定还没注册上） */
    private static final long SIP_REFRESH_DELAY_SECONDS = 180L;

    /** 延迟复核里等设备回应的总时长上限（秒） */
    private static final long SIP_REFRESH_TOTAL_WAIT_SECONDS = 300L;

    /** 本轮采集的到期时刻（sn → 毫秒时间戳） */
    private final Map<String, Long> deadlines = new ConcurrentHashMap<>();

    /** 连接请求的默认账号（ACS -> CPE），配置里写 auto 或留空表示设备侧免认证 */
    @Value("${acs.auth.acs-to-cpe-username:}")
    private String defaultAcsToCpeUser;

    @Value("${acs.auth.acs-to-cpe-password:}")
    private String defaultAcsToCpePassword;

    private static final String AUTH_AUTO = "auto";

    /** 连接请求凭据的最后兜底 */
    private static final String DEFAULT_CR_USER = "itms";

    private static final String DEFAULT_CR_PASSWORD = "itms";

    /** 是否在手动采集前主动唤醒设备（连接请求） */
    @Value("${acs.device.capability.wakeup-enabled:true}")
    private boolean wakeupEnabled;

    /** 采集中设备一直不建链时，隔多久重发一次连接请求（毫秒）；0 = 关闭重试 */
    @Value("${acs.device.capability.wakeup-retry-interval-millis:60000}")
    private long wakeupRetryIntervalMillis;

    /** 唤醒后最多等多少秒设备建链（手动采集、业务刷新等场景） */
    @Value("${acs.exec.wakeup-wait-seconds:30}")
    private long wakeupWaitSeconds;

    /** 唤醒过但没凭据的设备（sn → 下次再试的时刻），免得每次采集都撞一次 401 */
    private final Map<String, Long> wakeupRetryAfter = new ConcurrentHashMap<>();

    /** 无凭据唤醒失败后隔多久再试（毫秒） */
    private static final long WAKEUP_RETRY_INTERVAL = 60 * 60 * 1000L;

    /** 设备最近有通信就算还在下发窗口，不必叫它（秒） */
    private static final long WAKEUP_SESSION_FRESH_SECONDS = 60L;

    /** 最近一次非强制唤醒发出的时刻（sn → 时间戳）：批量入队时每条指令都喊一声没有意义 */
    private final Map<String, Long> wakeSentAt = new ConcurrentHashMap<>();

    /** 两次非强制唤醒之间的最小间隔（毫秒） */
    private static final long WAKE_MIN_INTERVAL_MILLIS = 15_000L;

    /** 从 Inform 上报参数预判出的运营商（sn → 运营商代码） */
    private final Map<String, String> informIsp = new ConcurrentHashMap<>();

    /** 本次采集强制使用的运营商（sn → 运营商代码）：适配页「测试」用，按用户选的档走，别让台账没登记的 standard 拖后腿 */
    private final Map<String, String> collectIspOverride = new ConcurrentHashMap<>();

    /** 队列关闭时退回会话内存 outbox，此时拿不到 rpc_record 主键 */
    private final AtomicInteger outboxSequence = new AtomicInteger(0);

    private volatile ExecutorService executor;

    // ==================== 入口 ====================

    /** Inform 到达时调用 */
    public void onInform(CpeSession session, InformRequest inform) {
        if (!enabled || !collectOnInform || session == null) {
            return;
        }
        String sn = session.getSn();
        try {
            // 采集跑在入库之前（要抢空 POST 窗口），台账上还没有运营商，只靠查库会按通用档开跑；Inform 自带私有前缀，先记下来给采集用
            rememberInformIsp(sn, inform);
            boolean bootstrap = hasBootstrap(inform);
            if (bootstrap && forceOnBootstrap) {
                start(sn, resolveRoot(session, inform), "bootstrap");
                return;
            }
            if (isStale(sn)) {
                start(sn, resolveRoot(session, inform), "inform");
            }
        } catch (Exception e) {
            // 采集只是旁路能力，任何异常都不能影响 Inform 的正常应答
            log.warn("触发设备能力采集失败: sn={}, err={}", sn, e.getMessage());
        }
    }

    /** 从 Inform 上报的参数里认运营商，记下来给紧接着的这次采集用 */
    private void rememberInformIsp(String sn, InformRequest inform) {
        if (inform == null || inform.getParameterList() == null) {
            return;
        }
        List<String> paths = new ArrayList<>();
        for (ParameterValueStruct p : inform.getParameterList()) {
            if (p != null && p.getName() != null) {
                paths.add(p.getName());
            }
        }
        com.tr069.acs.acs.adaptation.IspAdaptation adaptation = adaptationManager.detect(paths);
        if (adaptation == null) {
            return;
        }
        informIsp.put(sn, adaptation.code());
        log.info("按 Inform 私有前缀预判采集标准: sn={}, isp={}, 适配类={}",
                sn, adaptation.code(), adaptation.getClass().getSimpleName());
    }

    /** 手动触发一次采集，供管理端调用 */
    public CollectStatus collect(String sn, boolean force) {
        CpeSession session = sessionManager.find(sn);
        String root = session != null && session.getRootNode() != null
                ? session.getRootNode() : DEFAULT_ROOT;
        if (!force && !isStale(sn)) {
            CollectStatus status = statuses.get(sn);
            return status != null ? status : new CollectStatus(sn, "manual");
        }
        return start(sn, root, "manual");
    }

    /** 指定采集标准重采一次（适配页「测试」用）：强制用所选运营商档采集；台账还是 standard 时走通用档采不到 X_CMCC_/X_CT-COM_ 私有参数，结果没有参考价值；isp 为 null 按台账走 */
    public CollectStatus collectWithIsp(String sn, String isp) {
        if (isp == null || isp.isBlank()) {
            return collect(sn, true);
        }
        // 先强制收尾上一轮：running 且未超时（10 分钟内）时 start() 会直接返回旧 status，那是按别的 ISP 采的，不能让残轮挡住这次测试
        CollectStatus current = statuses.get(sn);
        if (current != null && "running".equals(current.getStatus())) {
            current.fail("适配测试强制重采，按新 ISP 重开");
            inFlight.remove(sn);
            deadlines.remove(sn);
            log.info("适配测试强制收尾上一轮采集，按新 ISP 重开: sn={}, 旧轮已运行={}ms",
                    sn, System.currentTimeMillis() - current.getStartTime());
        }
        collectIspOverride.put(sn, isp.trim());
        try {
            return collect(sn, true);
        } finally {
            // runCollect 在 start() 内同步调用，ISP 在 collect() 返回前就定下来了
            collectIspOverride.remove(sn);
        }
    }

    /** 查询采集状态，从未采集过返回 null */
    public CollectStatus status(String sn) {
        return statuses.get(sn);
    }

    /** 下发前确保这台设备的配置已上报过：没上报就现采一次，等它采完再拼路径 */
    public boolean ensureCollected(String sn) {
        if (sn == null || sn.isEmpty()) {
            return false;
        }
        if (paramCount(sn) > 0) {
            return true;
        }
        CpeSession session = sessionManager.find(sn);
        if (session == null) {
            log.warn("设备不在线，无法补采能力树: sn={}", sn);
            return false;
        }
        String root = session.getRootNode() != null ? session.getRootNode() : DEFAULT_ROOT;
        CollectStatus status = start(sn, root, "auto");
        long deadline = System.currentTimeMillis() + autoCollectTimeoutMillis;
        while (status != null && "running".equals(status.getStatus())
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long count = paramCount(sn);
        if (count > 0) {
            log.info("下发前补采设备能力完成: sn={}, 参数={}", sn, count);
        } else {
            log.warn("下发前补采设备能力未取到参数: sn={}, status={}",
                    sn, status == null ? "无" : status.getStatus());
        }
        return count > 0;
    }

    private long paramCount(String sn) {
        try {
            return parameterMapper.selectCount(new LambdaQueryWrapper<CpeParameter>()
                    .eq(CpeParameter::getSn, sn)
                    .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM));
        } catch (Exception e) {
            log.warn("查询设备参数数量失败: sn={}, err={}", sn, e.getMessage());
            return 0L;
        }
    }

    /** 设备能力汇总：支持多少路径、其中多少对象/参数、多少可写、最近采集时间 */
    public Map<String, Object> summary(String sn) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sn", sn);
        try {
            data.put("total", parameterMapper.selectCount(
                    new LambdaQueryWrapper<CpeParameter>().eq(CpeParameter::getSn, sn)));
            data.put("objectCount", parameterMapper.selectCount(
                    new LambdaQueryWrapper<CpeParameter>().eq(CpeParameter::getSn, sn)
                            .eq(CpeParameter::getNodeType, CpeParameter.TYPE_OBJECT)));
            data.put("paramCount", parameterMapper.selectCount(
                    new LambdaQueryWrapper<CpeParameter>().eq(CpeParameter::getSn, sn)
                            .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)));
            data.put("writableCount", parameterMapper.selectCount(
                    new LambdaQueryWrapper<CpeParameter>().eq(CpeParameter::getSn, sn)
                            .eq(CpeParameter::getNodeType, CpeParameter.TYPE_PARAM)
                            .eq(CpeParameter::getWritable, 1)));
            data.put("lastCollectTime", parameterMapper.lastCollectTime(sn));
        } catch (Exception e) {
            log.warn("查询设备能力汇总失败: sn={}, err={}", sn, e.getMessage());
            data.put("error", e.getMessage());
        }
        CollectStatus status = statuses.get(sn);
        data.put("collecting", status != null && "running".equals(status.getStatus()));
        data.put("status", status);
        return data;
    }

    // ==================== 采集编排 ====================

    private CollectStatus start(String sn, String root, String trigger) {
        CollectStatus current = statuses.get(sn);
        if (current != null && "running".equals(current.getStatus())) {
            if (System.currentTimeMillis() - current.getStartTime() < collectTimeoutMillis) {
                return current;
            }
            // 上一轮卡死（设备不在下发窗口，future 没个头）：不强制收尾 {inFlight} 永远占着，这台设备从此采不了，只能重启 ACS
            log.warn("上一轮采集超时未结束，强制收尾后重开: sn={}, 已运行={}ms",
                    sn, System.currentTimeMillis() - current.getStartTime());
            current.fail("上一轮采集超时未结束");
            inFlight.remove(sn);
            deadlines.remove(sn);
        }
        if (!inFlight.add(sn)) {
            return current != null ? current : new CollectStatus(sn, trigger);
        }
        CollectStatus status = new CollectStatus(sn, trigger);
        statuses.put(sn, status);
        log.info("开始采集设备能力: sn={}, root={}, trigger={}, batch={}, 时限={}ms",
                sn, root, trigger, status.getBatch(), collectTimeoutMillis);
        dropStaleRpc(sn, status.getBatch());
        deadlines.put(sn, System.currentTimeMillis() + collectTimeoutMillis);

        // 手动采集时设备多半不在下发窗口（十几分钟才上报一次），光发连接请求不够；叫完一直等到会话建立才入队，否则还是得等下一轮上报
        if ("manual".equals(trigger)) {
            wakeUpAndAwait(sn, wakeupWaitSeconds);
        }
        // 手动采集时设备多半不在线，指令只能排在队列里；写进状态免得界面看着像卡住
        CpeSession session = sessionManager != null ? sessionManager.find(sn) : null;
        if (session == null || session.isExpired(WAKEUP_SESSION_FRESH_SECONDS)) {
            status.setPhase("等待设备上报");
            log.info("设备当前不在下发窗口，指令已排队，将随它下一次上报下发: sn={}", sn);
        }
        runCollect(sn, root, status.getBatch(), status)
                .whenComplete((ignored, ex) -> {
                    inFlight.remove(sn);
                    deadlines.remove(sn);
                    status.setFinishTime(System.currentTimeMillis());
                    if (ex != null) {
                        status.fail(String.valueOf(ex.getMessage()));
                    } else if ("running".equals(status.getStatus())) {
                        if (status.isAborted()) {
                            List<String> errors = status.getErrors();
                            status.fail("采集中断: "
                                    + (errors.isEmpty() ? "设备未响应" : errors.get(errors.size() - 1)));
                        } else if (status.getValueCount() <= 0) {
                            status.fail("未读到任何参数值");
                        } else {
                            status.setStatus("success");
                        }
                    }
                    log.info("设备能力采集结束: sn={}, status={}, 路径={}, 读值={}, 耗时={}ms",
                            sn, status.getStatus(), status.getNameCount(), status.getValueCount(),
                            status.getFinishTime() - status.getStartTime());
                    if (!"success".equals(status.getStatus())) {
                        // 每棵子树试过哪些姿势、回了什么故障码全在这一条里，不用翻散落的单条日志
                        log.warn("采集未成功，各环节明细: sn={}, 结论={}, 明细={}",
                                sn, status.getMessage(),
                                status.getErrors().isEmpty() ? "无" : String.join(" | ", status.getErrors()));
                    }
                    if (exportModelTree && "success".equals(status.getStatus())) {
                        exportModelTreeAsync(sn);
                    }
                    // SIP 注册状态是异步生效的：BOOTSTRAP/下发后立刻采集，读到的多半还是
                    // 「注册中/未注册」——这个瞬间快照落库后，页面就一直挂着「未注册」，
                    // 而周期上报 24 小时才重采一次。延迟几分钟把注册状态重读落库，赶上注册完成
                    if ("success".equals(status.getStatus())) {
                        scheduleSipStatusRefresh(sn, status);
                    }
                });
        return status;
    }

    /**
     * 延迟重读 SIP 注册状态参数并落库。
     * 只挑树里真实存在的注册状态路径（SIPRegisterStatus / SIP 行的 Status），
     * 设备此刻不在线就排在队列里随下一次上报读，读完 readPathValues 自己落库
     */
    private void scheduleSipStatusRefresh(String sn, CollectStatus status) {
        try {
            List<String> paths = paramPaths(status.stagedTree()).stream()
                    .filter(p -> p.contains("SIP")
                            && (p.endsWith("SIPRegisterStatus") || p.endsWith(".Status")))
                    .distinct()
                    .collect(java.util.stream.Collectors.toList());
            if (paths.isEmpty()) {
                return;
            }
            CompletableFuture.delayedExecutor(SIP_REFRESH_DELAY_SECONDS, TimeUnit.SECONDS, executor())
                    .execute(() -> {
                        try {
                            readPathValues(sn, paths)
                                    .get(SIP_REFRESH_TOTAL_WAIT_SECONDS, TimeUnit.SECONDS);
                            log.info("SIP 注册状态延迟复核完成: sn={}, 路径={}", sn, paths.size());
                        } catch (Exception e) {
                            log.debug("SIP 注册状态延迟复核未完成，等下次采集: sn={}, err={}",
                                    sn, e.getMessage());
                        }
                    });
            log.info("SIP 注册状态已排延迟复核: sn={}, {} 秒后重读 {} 条状态路径",
                    sn, SIP_REFRESH_DELAY_SECONDS, paths.size());
        } catch (Exception e) {
            log.warn("SIP 注册状态延迟复核安排失败（不影响采集结果）: sn={}, err={}", sn, e.getMessage());
        }
    }

    /** 采集成功后把能力树导出成型号 XML，供同型号设备复用与下发取路径 */
    private void exportModelTreeAsync(String sn) {
        try {
            executor().execute(() -> {
                try {
                    // 归档走统一入口：都截到 models/standard/standard/，按运营商分目录是人工适配申报才做的；只写 XML 不登记模板库——采集不等于适配，留 PENDING 占位没意义
                    modelTreeStore.exportCollectedOnly(sn);
                } catch (Exception e) {
                    log.warn("导出型号能力树失败: sn={}, err={}", sn, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("提交型号能力树导出失败: sn={}, err={}", sn, e.getMessage());
        }
    }

    /** 两步采集的编排：先拉全量路径，再分批读值 */
    private CompletableFuture<Void> runCollect(String sn, String root, String batch,
                                               CollectStatus status) {
        status.setStatus("running");
        status.setPhase("names");
        status.setStartTime(System.currentTimeMillis());

        String isp = collectIspOverride.get(sn);
        if (isp == null || isp.isBlank()) {
            isp = deviceIsp(sn);
        }
        if (isp == null || isp.isBlank()) {
            // 台账还没登记（首次上报，入库走队列会比这次采集晚）：用 Inform 认出的那个
            isp = informIsp.get(sn);
        }
        CapabilityProfiles.Profile profile = profileFor(isp);
        log.info("本次采集采用的采集标准: sn={}, isp={}, 标准={}, 策略={}",
                sn, isp, profileName(profile), strategyText(profile));

        if (profile.isValuesOnly()
                || treeFailures.getOrDefault(sn, 0) >= TREE_FAIL_THRESHOLD) {
            // 电信天翼网关这类设备 GPN 一律 9002：不扫参数树只按需读关键字段。
            // 降级记忆按设备走、不分档位——华为 LS8025T 这类 GPV/GPN 一律 9002 的固件
            // 挂在通用档上，若只对自适应档记失败，它每轮采集都要白烧一遍 9002 窗口
            return probeGlobalPaths(sn, root, profile, status).thenApply(probed -> {
                if (probed) {
                    status.setStatus(PARTIAL);
                    status.setMessage("按采集标准只读关键字段，未扫参数树");
                } else {
                    status.fail("关键字段一个都没读到: " + String.join(", ", status.getErrors()));
                }
                flushIfPassed(sn, batch, status);
                return null;
            });
        }

        CompletableFuture<List<Node>> names = collectNames(sn, root, profile, status);

        return names.thenComposeAsync(nodes -> {
            if (nodes.isEmpty()) {
                // 整棵树 GPN 都没拿到：退到采集标准登记的已知路径直接读值，至少把设备身份留下
                // 任何档位都记：连续两次才降级，避免设备偶发 9002（重启中、CWMP 忙）就被永久判成扫不动
                int fails = treeFailures.merge(sn, 1, Integer::sum);
                log.warn("参数树扫不动（连续 {} 次）: sn={}, 明细={}", fails, sn,
                        status.getErrors().isEmpty() ? "子树均未返回节点"
                                : String.join("; ", status.getErrors()));
                if (fails >= TREE_FAIL_THRESHOLD) {
                    log.warn("连续 {} 次扫不动参数树，后续采集改走只读关键字段: sn={}",
                            fails, sn);
                }
                return probeGlobalPaths(sn, root, profile, status).thenApply(probed -> {
                    if (probed) {
                        status.setStatus(PARTIAL);
                        status.setMessage("参数树未采到，只读到已知路径参数（设备身份）");
                    } else {
                        status.fail("未能获取参数路径: " + (status.getErrors().isEmpty()
                                ? "子树均未返回节点: " + String.join(", ", resolveSubtrees(root, profile))
                                : String.join("; ", status.getErrors())));
                    }
                    flushIfPassed(sn, batch, status);
                    return null;
                });
            }
            // 扫到了就清零：之前的失败只是偶发，别累积到降级阈值
            treeFailures.remove(sn);
            List<Node> tree = completeTree(nodes, root);
            // 先暂存不落库：等值阶段跑完、能力测试通过再统一写入
            status.stageTree(tree);
            if (autoDetectIsp) {
                detectAndWriteIsp(sn, pathsOf(tree));
            }
            status.setNameCount(tree.size());
            status.setObjectCount(countNodes(tree, true));
            status.setParamCount(countNodes(tree, false));

            List<String> paths = paramPaths(tree);
            if (paths.isEmpty()) {
                flushIfPassed(sn, batch, status);
                return CompletableFuture.completedFuture(null);
            }
            status.setPhase("values");
            List<String> probePaths = absoluteProbePaths(root, profile);
            if (probePaths.isEmpty()) {
                return readValues(sn, paths, status).thenRun(() -> flushIfPassed(sn, batch, status));
            }
            // X_CT-COM_WANGponLinkConfig 这类私有对象有的固件不进 GPN 树，整棵替换会把 Inform 带进来的私有参数抹掉；按采集标准补读一遍兜住
            return readValues(sn, paths, status)
                    .thenCompose(v -> readValues(sn, probePaths, status))
                    .thenRun(() -> flushIfPassed(sn, batch, status));
        }, executor()).exceptionally(ex -> {
            Throwable cause = ex instanceof java.util.concurrent.CompletionException
                    && ex.getCause() != null ? ex.getCause() : ex;
            status.fail(String.valueOf(cause.getMessage()));
            return null;
        });
    }

    /** 按 {@link #SUBTREES} 逐个拉取参数路径 */
    private CompletableFuture<List<Node>> collectNames(String sn, String root,
                                                       CapabilityProfiles.Profile profile,
                                                       CollectStatus status) {
        List<String> paths = resolveSubtrees(root, profile);
        log.info("按子树采集设备能力: sn={}, 子树={}", sn, paths);
        AtomicInteger rounds = new AtomicInteger();
        // 各子树同时提交：设备一次只处理一条（MaxEnvelopes=1），但队列要一直有货——队列一空就得回 204，华为这类固件收到 204 就结束会话；各子树自带桶，最后按声明顺序合并，结果与串行一致
        List<List<Node>> buckets = new ArrayList<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String path : paths) {
            if (status.isAborted()) {
                break;
            }
            List<Node> bucket = Collections.synchronizedList(new ArrayList<>());
            buckets.add(bucket);
            futures.add(collectSubtree(sn, path, profile, bucket, rounds, status));
        }
        CompletableFuture<List<Node>> done = CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Node> sink = new ArrayList<>();
                    buckets.forEach(sink::addAll);
                    return sink;
                });
        if (root == null || root.isBlank()) {
            return done;
        }
        return done.thenComposeAsync(sink -> {
            if (!sink.isEmpty() || status.isAborted()) {
                return CompletableFuture.completedFuture(sink);
            }
            return probeRoot(sn, root, profile, rounds, status).thenApply(rootNodes -> {
                if (rootNodes.isEmpty()) {
                    return sink;
                }
                List<Node> merged = new ArrayList<>(sink.size() + rootNodes.size());
                merged.addAll(rootNodes);
                merged.addAll(sink);
                return merged;
            });
        }, executor());
    }

    /** 逐棵子树都问不出来时的最后一步：拿整棵树的根再问一遍 */
    private CompletableFuture<List<Node>> probeRoot(String sn, String root,
                                                    CapabilityProfiles.Profile profile,
                                                    AtomicInteger rounds, CollectStatus status) {
        List<String> candidates = new ArrayList<>();
        candidates.add(root);
        // 少数老固件认「不带尾点」的写法
        if (root.endsWith(".")) {
            String trimmed = root.substring(0, root.length() - 1);
            if (!trimmed.isBlank()) {
                candidates.add(trimmed);
            }
        }
        log.info("子树都问不出来，改从根节点试 GetParameterNames: sn={}, root={}", sn, root);
        List<Node> sink = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String candidate : candidates) {
            String path = candidate;
            chain = chain.thenComposeAsync(v -> {
                if (!sink.isEmpty() || status.isAborted()) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                return fetchRecursive(sn, path, sink, status).thenComposeAsync(hit -> {
                    if (hit) {
                        log.info("根节点成功取到参数树: sn={}, path={}, 节点={}", sn, path, sink.size());
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    List<Node> level = Collections.synchronizedList(new ArrayList<>());
                    return fetchLevel(sn, path, level, rounds, status, profile).thenApply(nodes -> {
                        if (nodes != null && nodes.booleanValue()) {
                            sink.addAll(level);
                            log.info("根节点逐层取到参数树: sn={}, path={}, 节点={}", sn, path, sink.size());
                        }
                        return null;
                    });
                }, executor());
            }, executor());
        }
        return chain.thenApply(v -> sink);
    }

    /** 拉取单个子树：先递归 GPN，递归没拿到东西时退化为逐层展开 */
    private CompletableFuture<Void> collectSubtree(String sn, String path,
                                                   CapabilityProfiles.Profile profile,
                                                   List<Node> sink,
                                                   AtomicInteger rounds, CollectStatus status) {
        boolean levelFirst = profile != null && profile.isLevelFirst();
        CompletableFuture<Boolean> first = levelFirst
                ? fetchLevel(sn, path, sink, rounds, status, profile)
                : fetchRecursive(sn, path, sink, status);
        return first.thenComposeAsync(hit -> {
            if (hit || status.isAborted()) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            if (!fallbackNextLevel) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            // 第一种姿势没拿到就换另一种：递归不成改逐层，逐层不成改递归
            CompletableFuture<Boolean> second = levelFirst
                    ? fetchRecursive(sn, path, sink, status)
                    : fetchLevel(sn, path, sink, rounds, status, profile);
            return second.thenComposeAsync(again -> {
                if (again || status.isAborted()) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                // 整棵子树问不出来：设备多半不吃「对象+待定实例号」路径，改按实例号 1..N 逐个探（电信定制机对 WANDevice. 回 9002，WANDevice.1. 正常）
                return probeInstances(sn, path, profile, sink, rounds, status)
                        .thenComposeAsync(probed -> {
                            if (probed || status.isAborted()) {
                                return CompletableFuture.<Void>completedFuture(null);
                            }
                            return probeKnownPaths(sn, path, profile, sink, status)
                                    .<Void>thenApply(ignored -> null);
                        }, executor());
            }, executor());
        }, executor());
    }

    /** 递归拉一棵子树：{@code NextLevel=false} 一次拿全量 */
    private CompletableFuture<Boolean> fetchRecursive(String sn, String path, List<Node> sink,
                                                      CollectStatus status) {
        return getParameterNames(sn, path, false).thenApply(resp -> {
            if (resp == null || resp.isFault()) {
                status.addError("GetParameterNames(" + path + ") 递归失败: " + faultText(resp));
                if (isSessionFault(resp) && !isTimeoutFault(resp)) {
                    status.abort();
                }
                return false;
            }
            List<Node> nodes = parseNodes(resp);
            if (nodes.isEmpty()) {
                log.info("子树递归拉取为空: sn={}, subtree={}", sn, path);
                return false;
            }
            sink.addAll(nodes);
            return true;
        });
    }

    /** 逐层展开一棵子树：{@code NextLevel=true} 拿到下一层，命中的对象继续往下钻 */
    private CompletableFuture<Boolean> fetchLevel(String sn, String path, List<Node> sink,
                                                  AtomicInteger rounds, CollectStatus status,
                                                  CapabilityProfiles.Profile profile) {
        List<Node> branch = Collections.synchronizedList(new ArrayList<>());
        return expandLevel(sn, path, branch, rounds, status, profile).thenApply(nodes -> {
            if (branch.isEmpty()) {
                return false;
            }
            sink.addAll(branch);
            return true;
        });
    }

    /** 按实例号逐个探测：{@code WANDevice.} 问不出来时，改问 {@code WANDevice.1.} / {@code .2.} …… */
    private CompletableFuture<Boolean> probeInstances(String sn, String path,
                                                      CapabilityProfiles.Profile profile,
                                                      List<Node> sink, AtomicInteger rounds,
                                                      CollectStatus status) {
        if (profile == null || !profile.isInstanceProbe()) {
            return CompletableFuture.completedFuture(false);
        }
        int max = profile.getMaxInstances();
        if (max <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        log.info("递归与逐层都没拿到，改按实例号探测: sn={}, subtree={}, 实例号 1..{}", sn, path, max);
        AtomicBoolean alive = new AtomicBoolean(true);
        AtomicBoolean hit = new AtomicBoolean(false);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 1; i <= max; i++) {
            String child = path + i + ".";
            chain = chain.thenComposeAsync(v -> {
                if (!alive.get() || status.isAborted()) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                List<Node> branch = Collections.synchronizedList(new ArrayList<>());
                return fetchRecursive(sn, child, branch, status)
                        .thenComposeAsync(recursiveHit -> {
                            if (recursiveHit) {
                                sink.addAll(branch);
                                hit.set(true);
                                return CompletableFuture.completedFuture(true);
                            }
                            return fetchLevel(sn, child, branch, rounds, status, profile)
                                    .thenApply(levelHit -> {
                                        if (levelHit) {
                                            sink.addAll(branch);
                                            hit.set(true);
                                        } else {
                                            // 这个实例号不存在，后面更大的也不会有
                                            alive.set(false);
                                        }
                                        return levelHit;
                                    });
                        }, executor())
                        .thenAccept(ignored -> {
                        });
            }, executor());
        }
        return chain.thenApply(v -> {
            if (hit.get()) {
                log.info("子树按实例号探测成功: sn={}, subtree={}", sn, path);
            } else {
                log.warn("子树按实例号探测也没拿到: sn={}, subtree={}, 实例号 1..{}", sn, path, max);
            }
            return hit.get();
        });
    }

    /** 最后一道兜底：GetParameterNames 各种姿势都不认时，按采集标准里登记的已知路径 */
    private CompletableFuture<Boolean> probeKnownPaths(String sn, String path,
                                                       CapabilityProfiles.Profile profile,
                                                       List<Node> sink, CollectStatus status) {
        if (profile == null || profile.getProbePaths() == null || profile.getProbePaths().isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        String root = rootOf(path);
        List<String> targets = new ArrayList<>();
        for (String candidate : profile.getProbePaths()) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String relative = candidate.trim();
            String absolute = relative.startsWith(root) ? relative : root + relative;
            if (absolute.startsWith(path)) {
                targets.add(absolute);
            }
        }
        if (targets.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String target : targets) {
            chain = chain.thenComposeAsync(v -> {
                if (status.isAborted()) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                // 逐条探测同样要留口气：目标清单里大半路径设备都没有，全拒完就是几十连发
                return pace().thenComposeAsync(ignored ->
                        getParameterValues(sn, List.of(target)).thenAccept(resp -> {
                    if (resp == null || resp.isFault()) {
                        status.addError("已知路径探测失败: " + target + " (" + faultText(resp) + ")");
                        return;
                    }
                    List<CpeParameter> rows = parseValues(sn, resp);
                    if (rows.isEmpty()) {
                        return;
                    }
                    status.stageValues(rows);
                    sink.add(new Node(target, false, true));
                }), executor());
            }, executor());
        }
        return chain.thenComposeAsync(v -> expandInstances(sn, root, profile, sink, status), executor())
                .thenApply(v -> true);
    }

    /** 已做过实例扩展的「sn|root」，同一次采集里每棵子树的兜底不重复扩展 */
    private final Set<String> expandedRoots = ConcurrentHashMap.newKeySet();

    /** 设备上报的条目数；解析不出来按 0 处理，也就是不扩实例 */
    private static int intOf(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 按设备自报的条目数把实例 2..N 补采回来：LAN 网口、Voice Line、WAN 连接（ConnectionDevice 层与 IP/PPP 子连接层），叶子路径从 probePaths 实例 1 模板派生 */
    private CompletableFuture<Void> expandInstances(String sn, String root,
                                                    CapabilityProfiles.Profile profile,
                                                    List<Node> sink, CollectStatus status) {
        if (root == null || root.isEmpty() || !expandedRoots.add(sn + "|" + root)) {
            return CompletableFuture.completedFuture(null);
        }
        List<String> entries = new ArrayList<>(List.of(
                root + "LANDevice.1.LANEthernetInterfaceNumberOfEntries",
                root + "Services.VoiceService.1.VoiceProfile.1.LineNumberOfEntries",
                root + "Services.VoiceService.1.VoiceProfile.1.PhyInterfaceNumberOfEntries"));
        // WAN 连接条目数：ConnectionDevice 层 + IP/PPP 子连接层
        entries.add(root + "WANDevice.1.WANConnectionDeviceNumberOfEntries");
        entries.add(root + "WANDevice.1.WANConnectionDevice.1.WANIPConnectionNumberOfEntries");
        entries.add(root + "WANDevice.1.WANConnectionDevice.1.WANPPPConnectionNumberOfEntries");
        return getParameterValues(sn, entries).thenComposeAsync(resp -> {
            if (resp == null || resp.isFault()) {
                // 条目数整批被拒（电信定制机常见）：NumberOfEntries 靠不住，改盲探实例
                return probeWanInstances(sn, profile, root, sink, status);
            }
            List<CpeParameter> rows = parseValues(sn, resp);
            status.stageValues(rows);
            int lanPorts = 0;
            int lines = 0;
            int wanConnDevs = 0;
            int wanIpConns = 0;
            int wanPppConns = 0;
            for (CpeParameter row : rows) {
                String leaf = nameOf(row.getParamPath());
                if ("LANEthernetInterfaceNumberOfEntries".equals(leaf)) {
                    lanPorts = Math.max(lanPorts, intOf(row.getParamValue()));
                } else if ("LineNumberOfEntries".equals(leaf)) {
                    lines = Math.max(lines, intOf(row.getParamValue()));
                } else if ("WANConnectionDeviceNumberOfEntries".equals(leaf)) {
                    wanConnDevs = Math.max(wanConnDevs, intOf(row.getParamValue()));
                } else if ("WANIPConnectionNumberOfEntries".equals(leaf)) {
                    wanIpConns = Math.max(wanIpConns, intOf(row.getParamValue()));
                } else if ("WANPPPConnectionNumberOfEntries".equals(leaf)) {
                    wanPppConns = Math.max(wanPppConns, intOf(row.getParamValue()));
                }
            }
            List<String> targets = new ArrayList<>();
            // 最多认到 32，防止设备报个离谱的数把下发队列灌满
            for (int i = 2; i <= Math.min(lanPorts, 32); i++) {
                String base = root + "LANDevice.1.LANEthernetInterfaceConfig." + i + ".";
                targets.add(base + "Enable");
                targets.add(base + "Status");
                targets.add(base + "MACAddress");
                targets.add(base + "MaxBitRate");
            }
            for (int i = 2; i <= Math.min(lines, 32); i++) {
                String base = root + "Services.VoiceService.1.VoiceProfile.1.Line." + i + ".";
                targets.add(base + "Enable");
                targets.add(base + "DirectoryNumber");
            }
            // WAN 连接多实例补采：取 probePaths 实例 1 的叶子模板，.1. 替换成 .N. 生成 2..N；WANConnectionDevice 层实例 2..wanConnDevs，每个下面都有 IP/PPP 连接
            targets.addAll(wanInstanceTargets(profile, root, wanConnDevs, wanIpConns, wanPppConns));
            if (targets.isEmpty()) {
                if (wanConnDevs == 0 && wanIpConns == 0 && wanPppConns == 0) {
                    // 设备没报任何条目数（多半是拒答）：照样盲探，别让库里只剩实例 1
                    return probeWanInstances(sn, profile, root, sink, status);
                }
                return CompletableFuture.completedFuture(null);
            }
            log.info("按条目数补采实例: sn={}, LAN 口数={}, Line 数={}, WAN 连接设备数={}, 追加路径 {} 条",
                    sn, lanPorts, lines, wanConnDevs, targets.size());
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (List<String> batch : partition(targets, Math.max(1, valueBatchSize))) {
                chain = chain.thenComposeAsync(v -> readValuesBatch(sn, batch, status, 0, 1),
                        executor());
            }
            return chain.thenAccept(v -> {
                for (String target : targets) {
                    sink.add(new Node(target, false, true));
                }
            });
        }, executor());
    }

    /** WAN 连接实例 2..N 的叶子路径，从 probePaths 中实例 1 的模板派生 */
    private static List<String> wanInstanceTargets(CapabilityProfiles.Profile profile,
                                                   String root,
                                                   int wanConnDevs, int wanIpConns, int wanPppConns) {
        List<String> result = new ArrayList<>();
        if (profile == null || profile.getProbePaths() == null || profile.getProbePaths().isEmpty()) {
            return result;
        }
        WanTemplates tpls = wanTemplates(profile);
        List<String> ipTemplates = tpls.ip();
        List<String> pppTemplates = tpls.ppp();
        // WANConnectionDevice 层实例 2..N：每个下都有 IP/PPP 连接实例 1；设备可能只在 .1 下建连接，也可能 2..N 都建
        for (int cd = 1; cd <= Math.min(wanConnDevs, 8); cd++) {
            // 每个 ConnectionDevice 下的 IP 连接实例 1..N
            int ipMax = (cd == 1) ? Math.min(wanIpConns, 8) : 1;
            for (int ci = (cd == 1 ? 2 : 1); ci <= Math.max(ipMax, 1); ci++) {
                if (cd == 1 && ci == 1) {
                    continue; // 实例 1 已经在 probePaths 里采过了
                }
                for (String tpl : ipTemplates) {
                    result.add(root + tpl
                            .replace("WANConnectionDevice.1.", "WANConnectionDevice." + cd + ".")
                            .replace("WANIPConnection.1.", "WANIPConnection." + ci + "."));
                }
            }
            // 每个 ConnectionDevice 下的 PPP 连接实例 1..N
            int pppMax = (cd == 1) ? Math.min(wanPppConns, 8) : 1;
            for (int ci = (cd == 1 ? 2 : 1); ci <= Math.max(pppMax, 1); ci++) {
                if (cd == 1 && ci == 1) {
                    continue;
                }
                for (String tpl : pppTemplates) {
                    result.add(root + tpl
                            .replace("WANConnectionDevice.1.", "WANConnectionDevice." + cd + ".")
                            .replace("WANPPPConnection.1.", "WANPPPConnection." + ci + "."));
                }
            }
        }
        // GPON 链路层参数（VLANIDMark 等）：每个 ConnectionDevice 一份，实例 1 已在 probePaths 里，这里补 2..N
        for (int cd = 2; cd <= Math.min(wanConnDevs, 8); cd++) {
            for (String tpl : tpls.link()) {
                result.add(root + tpl
                        .replace("WANConnectionDevice.1.", "WANConnectionDevice." + cd + "."));
            }
        }
        return result;
    }

    /** probePaths 里抽出来的 WAN 连接叶子模板：IP 连接与 PPP 连接各一组 */
    private record WanTemplates(List<String> ip, List<String> ppp, List<String> link) {
    }

    /** 从 probePaths 提取实例 1 的 WAN 叶子路径模板：IP 连接、PPP 连接各一组，外加 GPON 链路层（每个 ConnectionDevice 一份，不带连接实例号）一组 */
    private static WanTemplates wanTemplates(CapabilityProfiles.Profile profile) {
        List<String> ip = new ArrayList<>();
        List<String> ppp = new ArrayList<>();
        List<String> link = new ArrayList<>();
        if (profile != null && profile.getProbePaths() != null) {
            for (String path : profile.getProbePaths()) {
                if (path == null || path.isBlank()) {
                    continue;
                }
                String p = path.trim();
                if (p.contains("WANConnectionDevice.1.WANIPConnection.1.")) {
                    ip.add(p);
                } else if (p.contains("WANConnectionDevice.1.WANPPPConnection.1.")) {
                    ppp.add(p);
                } else if (p.contains("WANConnectionDevice.1.X_CT-COM_WANGponLinkConfig.")) {
                    link.add(p);
                }
            }
        }
        return new WanTemplates(ip, ppp, link);
    }

    /** NumberOfEntries 拿不到时的兜底：逐个 WANConnectionDevice GPV 一条探测叶子盲探，回值即实例存在、再补整组叶子——电信定制机常对 GPN 扫树拒答，只靠实例 1 会把 Internet/VoIP/Other 连接全丢掉 */
    private CompletableFuture<Void> probeWanInstances(String sn, CapabilityProfiles.Profile profile,
                                                      String root, List<Node> sink, CollectStatus status) {
        WanTemplates tpls = wanTemplates(profile);
        if (tpls.ip().isEmpty() && tpls.ppp().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (!expandedRoots.add(sn + "|wan-probe|" + root)) {
            return CompletableFuture.completedFuture(null);
        }
        log.info("条目数不可用，盲探 WAN 连接实例: sn={}", sn);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        // 电信机型普遍一个 ConnectionDevice 挂一条连接；实例 1 额外探到 2，覆盖少数把多条 IP 连接塞在 .1 下的机型
        for (int cd = 1; cd <= 8; cd++) {
            for (int ci = 1; ci <= (cd == 1 ? 2 : 1); ci++) {
                final int fcd = cd;
                final int fci = ci;
                chain = chain.thenComposeAsync(
                        v -> probeWanOne(sn, root, tpls, fcd, fci, sink, status), executor());
            }
        }
        return chain;
    }

    /** 盲探单个 (ConnectionDevice.cd, 连接.ci)：先探一条叶子，存在则整组补齐 */
    private CompletableFuture<Void> probeWanOne(String sn, String root, WanTemplates tpls,
                                                int cd, int ci, List<Node> sink, CollectStatus status) {
        List<String> probes = new ArrayList<>(3);
        String ipProbe = tpls.ip().isEmpty() ? null
                : root + tpls.ip().get(0)
                        .replace("WANConnectionDevice.1.", "WANConnectionDevice." + cd + ".")
                        .replace("WANIPConnection.1.", "WANIPConnection." + ci + ".");
        String pppProbe = tpls.ppp().isEmpty() ? null
                : root + tpls.ppp().get(0)
                        .replace("WANConnectionDevice.1.", "WANConnectionDevice." + cd + ".")
                        .replace("WANPPPConnection.1.", "WANPPPConnection." + ci + ".");
        String linkProbe = tpls.link().isEmpty() ? null
                : root + tpls.link().get(0)
                        .replace("WANConnectionDevice.1.", "WANConnectionDevice." + cd + ".");
        if (ipProbe != null) {
            probes.add(ipProbe);
        }
        if (pppProbe != null) {
            probes.add(pppProbe);
        }
        if (linkProbe != null && !probes.contains(linkProbe)) {
            probes.add(linkProbe);
        }
        if (probes.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return getParameterValues(sn, probes).thenComposeAsync(resp -> {
            if (resp == null || resp.isFault()) {
                // 实例不存在或被拒：静默跳过，正常情况
                return CompletableFuture.completedFuture(null);
            }
            status.stageValues(parseValues(sn, resp));
            List<String> full = new ArrayList<>();
            for (String tpl : tpls.ip()) {
                full.add(root + tpl
                        .replace("WANConnectionDevice.1.", "WANConnectionDevice." + cd + ".")
                        .replace("WANIPConnection.1.", "WANIPConnection." + ci + "."));
            }
            for (String tpl : tpls.ppp()) {
                full.add(root + tpl
                        .replace("WANConnectionDevice.1.", "WANConnectionDevice." + cd + ".")
                        .replace("WANPPPConnection.1.", "WANPPPConnection." + ci + "."));
            }
            for (String tpl : tpls.link()) {
                full.add(root + tpl
                        .replace("WANConnectionDevice.1.", "WANConnectionDevice." + cd + "."));
            }
            for (String p : full) {
                sink.add(new Node(p, false, true));
            }
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (List<String> batch : partition(full, Math.max(1, valueBatchSize))) {
                chain = chain.thenComposeAsync(v -> readValuesBatch(sn, batch, status, 0, 1), executor());
            }
            return chain;
        }, executor());
    }

    /** 这个对象是否在采集标准的「禁止展开」名单里：比对对象名（路径末段）的前缀 */
    private static boolean isSkipped(String path, CapabilityProfiles.Profile profile) {
        if (profile == null || profile.getSkipPrefixes() == null) {
            return false;
        }
        String name = nameOf(path).toUpperCase(Locale.ROOT);
        for (String prefix : profile.getSkipPrefixes()) {
            if (prefix != null && !prefix.isBlank()
                    && name.startsWith(prefix.trim().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 采集标准登记的探测路径 → 绝对路径（补根前缀），GPN 兜底与 GPN 成功后的私有参数补读共用 */
    private List<String> absoluteProbePaths(String root, CapabilityProfiles.Profile profile) {
        if (profile == null || profile.getProbePaths() == null || profile.getProbePaths().isEmpty()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (String candidate : profile.getProbePaths()) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String relative = candidate.trim();
            paths.add(relative.startsWith(root) ? relative : root + relative);
        }
        return paths;
    }

    /** 最后一档兜底（整棵树级别）：GetParameterNames 一句都问不出来时，按采集标准登记的 */
    private CompletableFuture<Boolean> probeGlobalPaths(String sn, String root,
                                                        CapabilityProfiles.Profile profile,
                                                        CollectStatus status) {
        if (profile == null || profile.getProbePaths() == null || profile.getProbePaths().isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        List<String> paths = absoluteProbePaths(root, profile);
        if (paths.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        log.info("按已知路径直接读值（不扫参数树）: sn={}, 路径 {} 条", sn, paths.size());
        status.setPhase("values");
        return getParameterValues(sn, paths).thenCompose(resp -> {
            if (resp != null && !resp.isFault()) {
                List<CpeParameter> rows = parseValues(sn, resp);
                if (!rows.isEmpty()) {
                    status.stageValues(rows);
                    status.addValueCount(rows.size());
                    // 读到 X_CT-COM_ / X_CTC_ 私有参数就认运营商：不走参数树采集的设备，这是唯一识别来源
                    if (autoDetectIsp) {
                        List<String> read = new ArrayList<>(rows.size());
                        for (CpeParameter row : rows) {
                            read.add(row.getParamPath());
                        }
                        detectAndWriteIsp(sn, read);
                    }
                    log.warn("只拿到已知路径参数，参数树未采到: sn={}, 读到 {} 条", sn, rows.size());
                    return CompletableFuture.completedFuture(true);
                }
            }
            // 整批被拒：多半清单里混着设备不支持的路径（电信固件对 GPV 也挑食），二分拆批把能读的剔出来
            return readValuesBatch(sn, paths, status, 0, 1).thenApply(v -> {
                if (status.getValueCount() <= 0) {
                    return false;
                }
                log.warn("只拿到已知路径参数，参数树未采到: sn={}, 读到 {} 条", sn, status.getValueCount());
                return true;
            });
        });
    }

    /** 本次采集采用的采集标准：按台账运营商取、没登记走通用档；yml 配了用配的，没配用 builtin，最后用 IspAdaptation 的采集策略覆盖 */
    private CapabilityProfiles.Profile profileFor(String isp) {
        if (capabilityProfiles == null) {
            return new CapabilityProfiles.Profile();
        }
        CapabilityProfiles.Profile profile = capabilityProfiles.forIsp(Isp.of(isp));
        // 用运营商适配实现覆盖采集策略（skipPrefixes / extraProbePaths / capabilitySubtrees / gpnStrategy），差异收在 IspAdaptation 实现类里，不再硬编码在 builtin 的 if 分支中
        IspAdaptation adaptation = adaptationManager.of(isp);
        if (!adaptation.skipPrefixes().isEmpty()) {
            profile.setSkipPrefixes(adaptation.skipPrefixes());
        }
        if (!adaptation.extraProbePaths().isEmpty()) {
            List<String> probePaths = new ArrayList<>(profile.getProbePaths());
            probePaths.addAll(adaptation.extraProbePaths());
            profile.setProbePaths(probePaths);
        }
        if (!adaptation.capabilitySubtrees().isEmpty()) {
            profile.setSubtrees(adaptation.capabilitySubtrees());
        }
        String strategy = adaptation.gpnStrategy();
        if (strategy != null && !strategy.isBlank()) {
            profile.setGpnStrategy(strategy);
        }
        return profile;
    }

    /** 设备台账上的运营商，查不到返回 null（由 {@link Isp#of} 归到通用档） */
    private String deviceIsp(String sn) {
        try {
            CpeDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                    .eq(CpeDevice::getSn, sn)
                    .last("LIMIT 1"));
            return device == null ? null : device.getIsp();
        } catch (Exception e) {
            log.warn("查询设备运营商失败，按通用档采集: sn={}, err={}", sn, e.getMessage());
            return null;
        }
    }

    /** 用设备的 ConnectionRequestURL 反连唤醒它，让它立刻来上报 */
    /** 反向唤醒的结果 */
    public enum WakeUpResult {
        /** 已发出连接请求 */
        SENT,
        /** 反向唤醒未启用 */
        DISABLED,
        /** 近期失败过，退避期内不再尝试 */
        THROTTLED,
        /** 设备刚通信过，按常规判断不必叫（只有非强制时才可能出现） */
        FRESH,
        /** 台账里没有这台设备 */
        NO_DEVICE,
        /** 没登记 ConnectionRequestURL，叫不了 */
        NO_URL,
        /** 发出去了，但被拒绝或异常 */
        FAILED
    }

    public WakeUpResult wakeUp(String sn) {
        return wakeUp(sn, false);
    }

    /** 反向唤醒设备 */
    public WakeUpResult wakeUp(String sn, boolean force) {
        if (!wakeupEnabled) {
            return WakeUpResult.DISABLED;
        }
        Long retryAfter = wakeupRetryAfter.get(sn);
        if (retryAfter != null && System.currentTimeMillis() < retryAfter) {
            return WakeUpResult.THROTTLED;
        }
        // 设备还有指令在等回应（下发/采集的会话正干活）：此刻发连接请求，部分固件会
        // 掐断当前会话重新 Inform，正在等回应的指令就死在这轮窗口里了。强制唤醒也不豁免
        CpeSession busy = sessionManager != null ? sessionManager.find(sn) : null;
        if (busy != null && busy.hasPendingAwaiting()) {
            log.debug("设备仍有指令在等回应，跳过连接请求以免打断会话: sn={}", sn);
            return WakeUpResult.THROTTLED;
        }
        // 设备刚上报过还在下发窗口就直接下发，不必叫它；强制唤醒时跳过：要的是「重新上来一轮」，旧会话对象说明不了它还连着
        if (!force) {
            CpeSession session = sessionManager != null ? sessionManager.find(sn) : null;
            if (session != null && !session.isExpired(WAKEUP_SESSION_FRESH_SECONDS)) {
                return WakeUpResult.FRESH;
            }
            Long sentAt = wakeSentAt.get(sn);
            if (sentAt != null && System.currentTimeMillis() - sentAt < WAKE_MIN_INTERVAL_MILLIS) {
                return WakeUpResult.THROTTLED;
            }
        }
        CpeDevice device = findDevice(sn);
        if (device == null) {
            return WakeUpResult.NO_DEVICE;
        }
        String url = device.getConnectionRequestUrl();
        if (url == null || url.isBlank()) {
            // 最常见的「有反向唤醒却用不上」：设备没报 ConnectionRequestURL、纳管也没改写成功，叫不了只能等周期上报
            log.warn("设备未登记连接请求地址，无法反向唤醒: sn={}。"
                    + "检查 acs.public-host 是否为真实地址（写成 127.0.0.1 时唤醒的是 ACS 自己）", sn);
            return WakeUpResult.NO_URL;
        }
        // 凭据选择：配了主密钥走派生模式（一台一密，纳管时已按同一算法写进设备），
        // 没配则走常规链：台账设备级（纳管改写 CR 凭据时登记）→ 已采集参数里翻
        // （采过完整树的设备值本来就在库里，不必再采一轮）→ 全局配置（排最后：各批次出厂密码不一样）
        boolean derived = connReqGuard.derivedCredentialsEnabled();
        String user;
        String password;
        if (derived) {
            user = connReqGuard.deriveConnReqUsername(sn);
            password = connReqGuard.deriveConnReqPassword(sn);
        } else {
            String[] creds = resolveLegacyCredentials(sn, device);
            user = creds[0];
            password = creds[1];
        }
        boolean withCredentials = !authDisabled(user);
        try {
            HttpResponse<String> resp = sendConnectionRequest(url, user, password);
            if (resp.statusCode() == 401 && derived) {
                // 派生密码还没写进设备（刚纳管/恢复出厂后）：退回常规凭据链再试一次
                String[] creds = resolveLegacyCredentials(sn, device);
                if (!authDisabled(creds[0])) {
                    resp = sendConnectionRequest(url, creds[0], creds[1]);
                }
            }
            if (resp.statusCode() >= 400) {
                if (!withCredentials && resp.statusCode() == 401) {
                    // 没凭据又被要求认证：短期内别再撞；唤醒只是加速手段，拿不到就等它自己上报，不影响采集
                    wakeupRetryAfter.put(sn, System.currentTimeMillis() + WAKEUP_RETRY_INTERVAL);
                    log.info("设备要求认证且本机未配置连接请求凭据，跳过唤醒（等它自行上报）: "
                            + "sn={}, url={}。需要唤醒就配 acs.auth.acs-to-cpe-username/password，"
                            + "或让它采集一轮把 ManagementServer.ConnectionRequestUsername 读回来", sn, url);
                } else {
                    log.warn("连接请求被设备拒绝: sn={}, url={}, 响应码={}, 已带认证={}",
                            sn, url, resp.statusCode(), withCredentials);
                }
                return WakeUpResult.FAILED;
            }
            log.info("已发连接请求唤醒设备: sn={}, url={}, 响应码={}", sn, url, resp.statusCode());
            wakeSentAt.put(sn, System.currentTimeMillis());
            return WakeUpResult.SENT;
        } catch (Exception e) {
            log.warn("连接请求唤醒失败，改为等待设备自行上报: sn={}, url={}, err={}",
                    sn, url, e.getMessage());
            return WakeUpResult.FAILED;
        }
    }

    /** 唤醒后轮询会话是否建立的间隔 */
    private static final long WAKEUP_POLL_INTERVAL_MILLIS = 500L;

    /**
     * 采集中设备一直不上来就周期性重发连接请求。
     * 唤醒原本只在指令入队时叫一声：那一次设备正忙/瞬断/不可达，指令就要在整个采集窗口里
     * 干等到超时，表现是「采集结束 status=fail、路径=0、读值=0」，各环节全是 8002「采集总时长已到」。
     * 这里按固定间隔对还挂着未完成采集、且不在下发窗口的设备再叫。
     * {@link #wakeUp(String, boolean)} 内部自带 15 秒最小间隔与 401 退避，重复调用是安全的；
     * 已收到回应的指令不受影响——设备只要上来一轮，积压指令就随它的空 POST 捎带下去。
     */
    @Scheduled(fixedDelayString = "${acs.device.capability.wakeup-retry-interval-millis:60000}")
    public void retryWakeUpDuringCollect() {
        if (!wakeupEnabled || wakeupRetryIntervalMillis <= 0 || statuses.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, CollectStatus> entry : statuses.entrySet()) {
            CollectStatus status = entry.getValue();
            if (status == null || !"running".equals(status.getStatus())) {
                continue;
            }
            String sn = entry.getKey();
            // 时限已过（deadlines 已摘除或到期）说明采集正在收尾，不必再叫
            Long deadline = deadlines.get(sn);
            if (deadline == null || deadline <= now) {
                continue;
            }
            CpeSession session = sessionManager != null ? sessionManager.find(sn) : null;
            if (session != null && !session.isExpired(WAKEUP_SESSION_FRESH_SECONDS)) {
                // 刚上报过，还在下发窗口，指令会随它的空 POST 下去
                continue;
            }
            WakeUpResult result = wakeUp(sn);
            if (result == WakeUpResult.SENT) {
                log.info("采集中设备未建链，已重发连接请求: sn={}", sn);
            } else {
                log.debug("采集中重试唤醒未直接生效: sn={}, 原因={}", sn, result);
            }
        }
    }

    /** 反向唤醒设备，并等到它真的建链 */
    public boolean wakeUpAndAwait(String sn, long waitSeconds) {
        long mark = lastContactOf(sn);
        // 设备本来就在会话窗口里就直接下发，不再叫它：对会话中的设备发连接请求，
        // 部分固件会掐断当前会话重新 Inform，把正在等回应的下发指令打死——
        // 整包的下一个成员、另一台并行任务的采集都会走到这里，必须防这一手
        CpeSession live = sessionManager != null ? sessionManager.find(sn) : null;
        if (live != null && !live.isExpired(WAKEUP_SESSION_FRESH_SECONDS)) {
            log.info("设备已在会话窗口中，跳过反向唤醒直接下发: sn={}", sn);
            return true;
        }
        WakeUpResult result = wakeUp(sn, true);
        if (result == WakeUpResult.SENT) {
            log.info("已反向唤醒设备，等待它建链: sn={}", sn);
        } else {
            log.warn("未发出反向唤醒: sn={}, 原因={}（只能等设备周期上报）", sn, result);
            // 连接请求根本没发出去（开关关/没地址/退避期内），等多久都不会因我们而建链，直接返回让调用方走兜底
            if (result != WakeUpResult.FAILED) {
                return false;
            }
        }
        long start = System.currentTimeMillis();
        long deadline = start + waitSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            // 设备这会儿连着（指令会被它紧接着的空 POST 取走），或叫完后刚上来过——都说明现在提交能立刻被取走
            if (isSessionActive(sn) || lastContactOf(sn) > mark) {
                log.info("设备已建链: sn={}, 等待={}ms", sn, System.currentTimeMillis() - start);
                return true;
            }
            try {
                Thread.sleep(WAKEUP_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("等待设备建链超时: sn={}, 已等={}s（指令仍会入队，等设备上报时再下发）", sn, waitSeconds);
        return false;
    }

    public boolean wakeUpAndAwait(String sn) {
        return wakeUpAndAwait(sn, wakeupWaitSeconds);
    }

    /** 这台设备现在能不能被反向唤醒：唤醒开关开着、台账里有它、且登记了连接请求地址 */
    public boolean canWakeUp(String sn) {
        if (!wakeupEnabled) {
            return false;
        }
        CpeDevice device = findDevice(sn);
        return device != null && device.getConnectionRequestUrl() != null
                && !device.getConnectionRequestUrl().isBlank();
    }

    private long lastContactOf(String sn) {
        CpeSession session = sessionManager != null ? sessionManager.find(sn) : null;
        return session == null ? 0L : session.getLastContactTime();
    }

    /**
     * 设备这会儿是否真的连着：不仅要登记着 TCP 连接，还必须最近真的通信过。
     * 只看 connectionId 会出假阳性——设备断会话（如 WAN 启用后管理通道切换）时
     * TCP 断开事件不一定被感知，connectionId 残留，误判「已建链」就会跳过唤醒、
     * 立刻提交指令，然后挂在没人取的队列上烧超时
     */
    private boolean isSessionActive(String sn) {
        CpeSession session = sessionManager != null ? sessionManager.find(sn) : null;
        return session != null && session.isOnline() && session.getConnectionId() != null
                && !session.isExpired(WAKEUP_SESSION_FRESH_SECONDS);
    }

    /** 常规凭据链：台账设备级 → 已采集参数 → 全局配置 → 纳管默认值 */
    private String[] resolveLegacyCredentials(String sn, CpeDevice device) {
        String user = device.getAcsToCpeName();
        String password = device.getAcsToCpePassword();
        if (authDisabled(user)) {
            user = paramValue(sn, "ManagementServer.ConnectionRequestUsername");
            password = paramValue(sn, "ManagementServer.ConnectionRequestPassword");
        }
        if (authDisabled(user)) {
            user = defaultAcsToCpeUser;
            password = defaultAcsToCpePassword;
        }
        if (authDisabled(user)) {
            // 三处都没有：按纳管写进设备的默认值试，试不过也只是退化成等它自己上报
            user = DEFAULT_CR_USER;
            password = DEFAULT_CR_PASSWORD;
        }
        return new String[]{user, password};
    }

    /** 发连接请求；设备要认证就带上凭据重发一次 */
    private HttpResponse<String> sendConnectionRequest(String url, String user, String password)
            throws Exception {
        // 外呼前校验目标：协议/端口/解析出的 IP 全部合规才放行，防 SSRF 与 DNS 重绑定
        connReqGuard.validate(url);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                // 不跟随重定向：设备给的地址可能把请求引向别的目标
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        URI uri = URI.create(url);
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 401 || authDisabled(user)) {
            return resp;
        }
        String challenge = resp.headers().firstValue("WWW-Authenticate").orElse("");
        String authorization = authorizationHeader(challenge, user, password, uri, "GET");
        if (authorization == null) {
            log.warn("设备要求认证但无法构造凭据: sn-url={}, challenge={}", url, challenge);
            return resp;
        }
        return client.send(HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(3))
                        .header("Authorization", authorization)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 按设备的 WWW-Authenticate 构造 Authorization 头，支持 Digest 与 Basic */
    private static String authorizationHeader(String challenge, String user, String password,
                                              URI uri, String method) {
        if (challenge == null || challenge.isBlank()) {
            return null;
        }
        String scheme = challenge.split("\\s+")[0];
        Map<String, String> params = parseAuthParams(challenge.substring(scheme.length()));
        if ("basic".equalsIgnoreCase(scheme)) {
            return "Basic " + Base64.getEncoder().encodeToString(
                    (user + ":" + password).getBytes(StandardCharsets.UTF_8));
        }
        if (!"digest".equalsIgnoreCase(scheme)) {
            return null;
        }
        String realm = params.getOrDefault("realm", "");
        String nonce = params.getOrDefault("nonce", "");
        String qop = params.get("qop");
        String uriPath = uri.getRawPath() == null || uri.getRawPath().isBlank()
                ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            uriPath = uriPath + "?" + uri.getRawQuery();
        }
        String ha1 = md5(user + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uriPath);
        String digest;
        StringBuilder header = new StringBuilder("Digest ")
                .append("username=\"").append(user).append("\", ")
                .append("realm=\"").append(realm).append("\", ")
                .append("nonce=\"").append(nonce).append("\", ")
                .append("uri=\"").append(uriPath).append("\", ");
        if (qop != null && qop.contains("auth")) {
            String cnonce = md5(user + nonce + System.nanoTime());
            digest = md5(ha1 + ":" + nonce + ":00000001:" + cnonce + ":auth:" + ha2);
            header.append("qop=auth, nc=00000001, cnonce=\"").append(cnonce).append("\", ");
        } else {
            digest = md5(ha1 + ":" + nonce + ":" + ha2);
        }
        header.append("response=\"").append(digest).append("\", algorithm=MD5");
        if (params.get("opaque") != null) {
            header.append(", opaque=\"").append(params.get("opaque")).append("\"");
        }
        return header.toString();
    }

    /** 解析 {@code realm="x", nonce="y"} 这类认证参数 */
    private static Map<String, String> parseAuthParams(String raw) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String part : raw.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            params.put(key, value);
        }
        return params;
    }

    private static String md5(String raw) {
        try {
            byte[] bytes = MessageDigest.getInstance("MD5")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    /** 凭据未配置（配置里写 auto 或留空 = 设备侧免认证） */
    private static boolean authDisabled(String user) {
        return user == null || user.isBlank() || AUTH_AUTO.equalsIgnoreCase(user.trim());
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    /** 从已采集到的参数里取某个路径的值，取不到返回 null */
    private String paramValue(String sn, String pathSuffix) {
        if (parameterMapper == null) {
            return null;
        }
        try {
            CpeParameter row = parameterMapper.selectOne(new LambdaQueryWrapper<CpeParameter>()
                    .eq(CpeParameter::getSn, sn)
                    .like(CpeParameter::getParamPath, pathSuffix)
                    .last("LIMIT 1"));
            return row == null ? null : row.getParamValue();
        } catch (Exception e) {
            log.warn("查询参数值失败: sn={}, path={}, err={}", sn, pathSuffix, e.getMessage());
            return null;
        }
    }

    /** 台账上的设备记录，查不到返回 null */
    private CpeDevice findDevice(String sn) {
        try {
            return deviceMapper.selectOne(new LambdaQueryWrapper<CpeDevice>()
                    .eq(CpeDevice::getSn, sn)
                    .last("LIMIT 1"));
        } catch (Exception e) {
            log.warn("查询设备台账失败: sn={}, err={}", sn, e.getMessage());
            return null;
        }
    }

    /** 按能力树里的厂商私有前缀认运营商，并回写台账 */
    private void detectAndWriteIsp(String sn, List<String> paths) {
        com.tr069.acs.acs.adaptation.IspAdaptation adaptation = adaptationManager.detect(paths);
        if (adaptation == null) {
            return;
        }
        if (Isp.of(deviceIsp(sn)) != Isp.STANDARD) {
            return;
        }
        try {
            deviceMapper.update(null, new LambdaUpdateWrapper<CpeDevice>()
                    .eq(CpeDevice::getSn, sn)
                    .set(CpeDevice::getIsp, adaptation.code()));
            log.info("按能力树私有前缀登记设备运营商: sn={}, isp={}, 适配类={}",
                    sn, adaptation.code(), adaptation.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("回写设备运营商失败: sn={}, isp={}, err={}", sn, adaptation.code(), e.getMessage());
        }
    }

    private static String strategyText(CapabilityProfiles.Profile profile) {
        if (profile == null) {
            return "递归优先";
        }
        if (profile.isValuesOnly()) {
            return "只读关键字段（不扫参数树）";
        }
        if (profile.isAdaptive()) {
            return "自适应（先试参数树，扫不动则只读关键字段）";
        }
        return profile.isLevelFirst() ? "逐层优先" : "递归优先";
    }

    private static String profileName(CapabilityProfiles.Profile profile) {
        if (profile == null) {
            return "内置默认";
        }
        return profile.getName() == null || profile.getName().isBlank()
                ? "内置默认" : profile.getName().trim();
    }

    /** 待采集的子树绝对路径：把采集标准的子树拼到设备的数据模型根节点下 */
    private static List<String> resolveSubtrees(String root) {
        return resolveSubtrees(root, null);
    }

    private static List<String> resolveSubtrees(String root, CapabilityProfiles.Profile profile) {
        String base = root == null || root.isEmpty() ? DEFAULT_ROOT : root;
        boolean tr181 = DataModel.TR181.getRoot().equals(base);
        List<String> subtrees = tr181 ? SUBTREES_TR181 : SUBTREES_TR098;
        if (profile != null) {
            List<String> custom = tr181 ? profile.getSubtreesTr181() : profile.getSubtrees();
            if (custom != null && !custom.isEmpty()) {
                subtrees = custom;
            }
        }
        List<String> paths = new ArrayList<>(subtrees.size());
        for (String subtree : subtrees) {
            paths.add(base + subtree);
        }
        return paths;
    }

    /** 路径所属的数据模型根：{@code InternetGatewayDevice.} 或 {@code Device.} */
    private static String rootOf(String path) {
        if (path == null || path.isEmpty()) {
            return DEFAULT_ROOT;
        }
        return DataModel.TR181.getRoot().equals(path) || path.startsWith(DataModel.TR181.getRoot())
                ? DataModel.TR181.getRoot() : DEFAULT_ROOT;
    }

    private static List<String> pathsOf(List<Node> tree) {
        List<String> paths = new ArrayList<>(tree.size());
        for (Node node : tree) {
            paths.add(node.path);
        }
        return paths;
    }

    /** 逐层展开：对 path 做 NextLevel=true 的 GetParameterNames，把命中的对象继续往下展开 */
    private CompletableFuture<List<Node>> expandLevel(String sn, String path, List<Node> sink,
                                                      AtomicInteger rounds, CollectStatus status,
                                                      CapabilityProfiles.Profile profile) {
        if (status.isAborted()) {
            return CompletableFuture.completedFuture(sink);
        }
        if (rounds.incrementAndGet() > maxObjectRounds) {
            status.addError("逐层展开已达上限 " + maxObjectRounds + " 个对象，能力树可能不完整");
            status.abort();
            return CompletableFuture.completedFuture(sink);
        }
        status.setObjectRounds(rounds.get());
        return getParameterNames(sn, path, true).thenComposeAsync(resp -> {
            if (resp == null || resp.isFault()) {
                status.addError("GetParameterNames(" + path + ") 失败: " + faultText(resp));
                if (isSessionFault(resp)) {
                    // 与 collectSubtree 同理：单纯超时只放弃这条分支，会话真没了才中止全部
                    if (!isTimeoutFault(resp)) {
                        status.abort();
                    }
                }
                return CompletableFuture.completedFuture(sink);
            }
            List<Node> nodes = parseNodes(resp);
            sink.addAll(nodes);
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (Node node : nodes) {
                if (!node.object) {
                    continue;
                }
                if (isSkipped(node.path, profile)) {
                    // 电信 X_CTC_ 那套私有分支：固件一展开就抛 9002，整棵子树跟着报废。
                    // 节点本身留着（路径已拿到），但到此为止不再往下钻
                    log.info("私有分支不再往下展开: sn={}, object={}", sn, node.path);
                    continue;
                }
                String child = node.path;
                chain = chain.thenComposeAsync(v ->
                        expandLevel(sn, child, sink, rounds, status, profile).thenAccept(x -> {
                        }), executor());
            }
            return chain.thenApply(v -> sink);
        }, executor());
    }

    /** 分批读取参数值。批次之间串行：一个会话同时只允许一条未回应的下发 */
    private CompletableFuture<Void> readValues(String sn, List<String> paths, CollectStatus status) {
        List<List<String>> batches = partition(paths, Math.max(1, valueBatchSize));
        status.setTotalBatches(batches.size());
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < batches.size(); i++) {
            List<String> batch = batches.get(i);
            int index = i;
            chain = chain.thenComposeAsync(v -> {
                if (status.isAborted()) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                return readValuesBatch(sn, batch, status, index, batches.size())
                        .thenAccept(ignored -> status.setFinishedBatches(index + 1));
            }, executor());
        }
        return chain;
    }

    /** 读一批参数值，整批被设备拒绝时二分拆开重试，把失效路径单独剔掉 */
    private CompletableFuture<Void> readValuesBatch(String sn, List<String> batch, CollectStatus status,
                                                    int index, int total) {
        return getParameterValues(sn, batch).thenComposeAsync(resp -> {
            if (resp == null || resp.isFault()) {
                if (isSessionFault(resp)) {
                    status.incrementFailBatches();
                    status.addError("GetParameterValues 第 " + (index + 1) + "/" + total
                            + " 批失败: " + faultText(resp));
                    status.abort();
                    return CompletableFuture.<Void>completedFuture(null);
                }
                if (batch.size() > 1) {
                    int mid = batch.size() / 2;
                    // 拆出来的每一片都先留口气再发：整批被拒往往意味着清单里大半路径设备都没有，
                    // 会一路拆到单条，不加间隔就是几十条背靠背的 RPC 把设备打挂
                    return pace().thenComposeAsync(v ->
                                    readValuesBatch(sn, batch.subList(0, mid), status, index, total), executor())
                            .thenComposeAsync(x -> pace(), executor())
                            .thenComposeAsync(v -> readValuesBatch(sn, batch.subList(mid, batch.size()),
                                    status, index, total), executor());
                }
                // 单条都过不去：这条路径设备没有，剔掉它，别让它拖垮整批
                status.incrementFailBatches();
                status.addError("GetParameterValues 第 " + (index + 1) + "/" + total
                        + " 批: 路径 " + batch.get(0) + " 设备不接受（" + faultText(resp) + "），已跳过");
                return CompletableFuture.<Void>completedFuture(null);
            }
            List<CpeParameter> rows = parseValues(sn, resp);
            status.addValueCount(rows.size());
            status.stageValues(rows);
            return CompletableFuture.<Void>completedFuture(null);
        }, executor());
    }

    // ==================== RPC 提交 ====================

    /** 探测批次之间的口气：给设备 CWMP agent 消化的时间，避免背靠背连发把它打挂 */
    private CompletableFuture<Void> pace() {
        if (PROBE_PACE_MILLIS <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(PROBE_PACE_MILLIS, TimeUnit.MILLISECONDS, executor())
                .execute(() -> gate.complete(null));
        return gate;
    }

    private CompletableFuture<SoapMessage> getParameterNames(String sn, String path,
                                                             boolean nextLevel) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ParameterPath", path);
        args.put("NextLevel", String.valueOf(nextLevel));
        return submit(sn, "GetParameterNames", args);
    }

    private CompletableFuture<SoapMessage> getParameterValues(String sn, List<String> names) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ParameterNames", names);
        return submit(sn, "GetParameterValues", args);
    }

    /** 提交一条采集指令 */

    /** 当前这台设备正在进行的采集批次号，用于给指令打标 */
    private String currentBatch(String sn) {
        CollectStatus status = statuses.get(sn);
        return status == null ? null : status.getBatch();
    }

    /** 开新采集前先丢掉上一轮没发完的指令 */
    private void dropStaleRpc(String sn, String batch) {
        if (!queueProperties.isEnabled()) {
            return;
        }
        try {
            int dropped = rpcDispatchService.dropStaleCollectTasks(sn, batch);
            if (dropped > 0) {
                log.warn("丢弃上一轮未发完的采集指令: sn={}, 条数={}, 本轮批次={}", sn, dropped, batch);
            }
        } catch (Exception e) {
            log.warn("清理上一轮采集指令失败，继续本轮采集: sn={}, err={}", sn, e.getMessage());
        }
    }
    private CompletableFuture<SoapMessage> submit(String sn, String rpcName,
                                                  Map<String, Object> args) {
        // 到点了就别再等（设备不在下发窗口时响应永远等不来，见 collectTimeoutMillis 说明）；措辞刻意不带「超时」——那会被当成单条指令超时只放弃当前分支，总时长到是要中止整轮的
        long remaining = deadlines.getOrDefault(sn, 0L) - System.currentTimeMillis();
        if (deadlines.containsKey(sn) && remaining <= 0) {
            log.warn("采集已到时限，不再等待响应: sn={}, rpc={}", sn, rpcName);
            return CompletableFuture.completedFuture(sessionFault("采集总时长已到，未等到设备回应"));
        }
        CompletableFuture<SoapMessage> future;
        if (queueProperties.isEnabled()) {
            // 带上批次号：新一轮采集开始时据此丢掉上一轮没发完的旧指令
            RpcDispatchService.DispatchHandle handle =
                    rpcDispatchService.submitAsync(sn, rpcName, args, null, currentBatch(sn));
            if (!handle.isQueued()) {
                log.warn("采集指令未入队: sn={}, rpc={}, taskId={}", sn, rpcName, handle.getTaskId());
            }
            future = handle.getFuture();
        } else {
            CpeSession session = sessionManager.find(sn);
            if (session == null) {
                log.warn("会话不存在，采集指令无法下发: sn={}, rpc={}", sn, rpcName);
                return CompletableFuture.completedFuture(sessionFault("会话不存在"));
            }
            PendingRpc rpc = session.enqueue(-outboxSequence.incrementAndGet(), rpcName, args, null);
            future = rpc.getResponse();
        }
        // 设备沉默追踪：连续多条指令设备一句回应都没有（收下就消失），说明 agent 已经装死，
        // 提前中止整轮采集。不追踪的话每条指令都要走满 60s 定时器 + 各子树全部备选姿势，
        // 单台烧满 10 分钟预算——万台规模下这类设备会把并发额度占光
        future = future.whenComplete((resp, ex) -> {
            CollectStatus st = statuses.get(sn);
            if (st == null) {
                return;
            }
            boolean noResponse = ex != null || (resp != null && isTimeoutFault(resp));
            if (noResponse) {
                int streak = st.markSilence();
                if (streak >= SILENCE_ABORT_THRESHOLD && !st.isAborted()) {
                    log.warn("连续 {} 条指令未等到任何设备回应，提前中止本轮采集: sn={}", streak, sn);
                    st.addError("设备连续 " + streak + " 条指令无回应，提前中止采集");
                    st.abort();
                }
            } else if (resp != null) {
                // 设备回了话（哪怕回的是 9002 故障）都算活着：清零沉默计数
                st.markAlive();
            }
        });
        if (!deadlines.containsKey(sn)) {
            return future;
        }
        return future.orTimeout(Math.max(remaining, 1L), TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    log.warn("采集指令等待回应超时: sn={}, rpc={}", sn, rpcName);
                    return sessionFault("采集总时长已到，未等到设备回应");
                });
    }

    /** 会话级故障：设备离线、下发超时、连接断开 */
    private static boolean isSessionFault(SoapMessage resp) {
        if (resp == null || resp.getFault() == null) {
            return false;
        }
        String code = resp.getFault().getFaultCode();
        return FAULT_SESSION.equals(code) || FAULT_ENQUEUE.equals(code);
    }

    /** 会话级故障里的「超时」类：设备忙、单条响应慢，设备本体还在 */
    private static boolean isTimeoutFault(SoapMessage resp) {
        if (resp == null || resp.getFault() == null) {
            return false;
        }
        String msg = String.valueOf(resp.getFault().getFaultString());
        return msg.contains("超时");
    }

    private static SoapMessage sessionFault(String reason) {
        return SoapMessage.builder()
                .fault(com.tr069.acs.cwmp.model.FaultStruct.builder()
                        .faultCode(FAULT_SESSION)
                        .faultString(reason)
                        .build())
                .build();
    }

    // ==================== 业务参数读取 ====================

    /** 按给定路径批量读取参数值并回填能力树，供业务信息采集复用；值先暂存内存，全部路径测完（读到或明确拒绝后二分剔出）才一次性落库，半途超时/断会话不写半成品 */
    public CompletableFuture<Map<String, String>> readPathValues(String sn, List<String> paths) {
        Map<String, String> result = new ConcurrentHashMap<>();
        if (paths == null || paths.isEmpty()) {
            return CompletableFuture.completedFuture(result);
        }
        List<CpeParameter> buffer = Collections.synchronizedList(new ArrayList<>());
        List<List<String>> batches = partition(paths, Math.max(1, valueBatchSize));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (List<String> batch : batches) {
            chain = chain.thenComposeAsync(v -> readBatch(sn, batch, result, buffer), executor());
        }
        return chain.thenApply(v -> {
            // 走到这里说明每条路径都有了结论（读到/明确拒答/异常跳过），整批落库
            if (!buffer.isEmpty()) {
                saveValues(sn, new ArrayList<>(buffer));
                log.info("业务参数读值落库: sn={}, 计划路径={}, 实际读到={}", sn, paths.size(), buffer.size());
            } else {
                log.warn("业务参数读值无一成功，本轮不落库: sn={}, 计划路径={}", sn, paths.size());
            }
            return result;
        });
    }

    /** 读一批参数；整批失败就二分拆批重读 */
    private CompletableFuture<Void> readBatch(String sn, List<String> batch,
                                              Map<String, String> result,
                                              List<CpeParameter> buffer) {
        return getParameterValues(sn, batch).thenCompose(resp -> {
            if (resp != null && !resp.isFault()) {
                List<CpeParameter> rows = parseValues(sn, resp);
                for (CpeParameter row : rows) {
                    result.put(row.getParamPath(), row.getParamValue());
                }
                buffer.addAll(rows);
                return CompletableFuture.completedFuture(null);
            }
            if (batch.size() > 1) {
                int mid = batch.size() / 2;
                return readBatch(sn, batch.subList(0, mid), result, buffer)
                        .thenCompose(v -> readBatch(sn, batch.subList(mid, batch.size()), result, buffer));
            }
            log.debug("业务参数读取失败（该路径设备不提供，剔除）: sn={}, path={}, err={}",
                    sn, batch.get(0), faultText(resp));
            return CompletableFuture.completedFuture(null);
        }).exceptionally(ex -> {
            // 断会话/60s 定时器判死这类异常完成不能让整条读值链报废：链一断，
            // 末尾的落库步骤永远不执行，本轮已读到的真实值全部丢弃——表现就是
            // 「设备中断会话后，上报重新采集也不落库，页面一直回到旧值」。
            // 跳过该批让链走完，已读到的照常落库，残余路径等下次采集补
            log.warn("业务参数批次读取异常，跳过该批继续: sn={}, 路径数={}, err={}",
                    sn, batch.size(), String.valueOf(ex.getMessage()));
            return null;
        });
    }

    // ==================== 解析 ====================

    /** 解析 GetParameterNamesResponse：路径以句点结尾即为对象 */
    private List<Node> parseNodes(SoapMessage resp) {
        List<Node> nodes = new ArrayList<>();
        for (Map<String, Object> item : resp.listValue("ParameterList")) {
            String name = str(item.get("Name"));
            if (name.isEmpty()) {
                continue;
            }
            nodes.add(new Node(name, name.endsWith("."), isTrue(item.get("Writable"))));
        }
        return nodes;
    }

    /** 解析 GetParameterValuesResponse 为待回填的行 */
    /** 可写性未知：参数存在（GPV 读到了），但设备没告诉我们能不能写 */
    public static final int WRITABLE_UNKNOWN = -1;

    private List<CpeParameter> parseValues(String sn, SoapMessage resp) {
        List<CpeParameter> rows = new ArrayList<>();
        for (Map<String, Object> item : resp.listValue("ParameterList")) {
            String name = str(item.get("Name"));
            if (name.isEmpty()) {
                continue;
            }
            CpeParameter row = new CpeParameter();
            row.setSn(sn);
            row.setParamPath(truncate(name, 400));
            row.setParamValue(truncate(str(item.get("Value")), MAX_VALUE_LENGTH));
            row.setValueType(truncate(str(item.get("Value_type")), 32));
            // GetParameterValues 不回 Writable（那是 GPN 才有的字段），记「未知」而不是 0：MSG2100E 这类 GPN 一律 9002 的固件只能靠兜底路径读参数，一律按只读会让适配试探挑不出候选
            row.setWritable(WRITABLE_UNKNOWN);
            // 补全树结构字段：saveValues 改用 upsert 后，GPV 兜底读到的路径即使能力树里没有（电信定制机 GPN 全 9002 的典型场景）也能插入新行，业务面板才能查到 WAN 口数据
            row.setParentPath(parentOf(row.getParamPath()));
            row.setNodeName(nameOf(row.getParamPath()));
            row.setNodeType(CpeParameter.TYPE_PARAM);
            row.setDepth(depthOf(row.getParamPath()));
            rows.add(row);
        }
        return rows;
    }

    /** 补全树：根节点与各子树之上的中间层对象节点都由这里补上 */
    private List<Node> completeTree(List<Node> nodes, String root) {
        String base = root == null || root.isEmpty() ? DEFAULT_ROOT : root;
        Set<String> seen = new LinkedHashSet<>();
        List<Node> tree = new ArrayList<>(nodes.size() + 8);
        seen.add(base);
        tree.add(new Node(base, true, false));
        for (Node node : nodes) {
            if (seen.add(node.path)) {
                tree.add(node);
            }
        }
        List<Node> ancestors = new ArrayList<>();
        for (Node node : nodes) {
            for (String parent = parentOf(node.path);
                 !parent.isEmpty() && !seen.contains(parent);
                 parent = parentOf(parent)) {
                seen.add(parent);
                ancestors.add(new Node(parent, true, false));
            }
        }
        tree.addAll(1, ancestors);
        return tree;
    }

    private List<String> paramPaths(List<Node> tree) {
        List<String> paths = new ArrayList<>();
        for (Node node : tree) {
            if (node.object) {
                continue;
            }
            if (paths.size() >= maxParameters) {
                log.warn("参数个数达到上限 {}，超出部分不读值: sn={}", maxParameters, tree.size());
                break;
            }
            paths.add(node.path);
        }
        return paths;
    }

    private int countNodes(List<Node> tree, boolean object) {
        int count = 0;
        for (Node node : tree) {
            if (node.object == object) {
                count++;
            }
        }
        return count;
    }

    // ==================== 落库 ====================

    /** 能力测试通过才落库：参数树与参数值先暂存内存，跑完且测试通过才一次性写入；会话中止/失败/一个值都没读到的半成品整批丢弃，库里永远不出现半套适配数据 */
    private void flushIfPassed(String sn, String batch, CollectStatus status) {
        List<Node> tree = status.stagedTree();
        List<CpeParameter> values = status.stagedValues();
        if (tree.isEmpty() && values.isEmpty()) {
            return;
        }
        if (status.isAborted()) {
            log.warn("能力测试未通过（会话中止），暂存结果整批丢弃: sn={}, 树 {} 节点 / 值 {} 条",
                    sn, tree.size(), values.size());
            return;
        }
        boolean passed = !tree.isEmpty() || status.getValueCount() > 0;
        if (!passed) {
            log.warn("能力测试未通过（未采到任何参数），暂存结果整批丢弃: sn={}", sn);
            return;
        }
        if (!tree.isEmpty()) {
            saveTree(sn, batch, tree);
        }
        if (!values.isEmpty()) {
            saveValues(sn, values);
        }
        log.info("能力测试通过，统一落库: sn={}, 参数树 {} 节点, 参数值 {} 条",
                sn, tree.size(), values.size());
    }

    /** 整棵替换：先清掉该设备的旧树，再分片写入 */
    private void saveTree(String sn, String batch, List<Node> tree) {
        try {
            parameterMapper.delete(new LambdaQueryWrapper<CpeParameter>()
                    .eq(CpeParameter::getSn, sn));
        } catch (Exception e) {
            log.error("清理旧能力树失败，放弃本次写入: sn={}, err={}", sn, e.getMessage());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<CpeParameter> rows = new ArrayList<>(tree.size());
        Set<String> seen = new LinkedHashSet<>();
        for (Node node : tree) {
            String path = truncate(node.path, 400);
            if (!seen.add(path)) {
                continue;
            }
            node.path = path;
            rows.add(toRow(sn, batch, now, node));
        }
        for (List<CpeParameter> chunk : partition(rows, INSERT_CHUNK)) {
            try {
                parameterMapper.insertBatch(chunk);
            } catch (Exception e) {
                log.error("能力树写入失败: sn={}, size={}, err={}", sn, chunk.size(), e.getMessage());
            }
        }
    }

    private void saveValues(String sn, List<CpeParameter> rows) {
        for (List<CpeParameter> chunk : partition(rows, UPDATE_CHUNK)) {
            try {
                // upsert：已存在只更新值（ON DUPLICATE KEY UPDATE 只碰 param_value / value_type），不存在插入新行——电信定制机 GPN 对父对象回 9002，能力树没有 WAN 参数行，兜底值必须靠 upsert 才能落库
                parameterMapper.upsertInformValues(chunk);
            } catch (Exception e) {
                log.error("参数值 upsert 失败: sn={}, size={}, err={}", sn, chunk.size(), e.getMessage());
            }
        }
        rememberConnectionRequestCredentials(sn, rows);
    }

    /** 读到设备的连接请求凭据就记进台账，唤醒设备时用得上 */
    private void rememberConnectionRequestCredentials(String sn, List<CpeParameter> rows) {
        String user = null;
        String password = null;
        for (CpeParameter row : rows) {
            String path = row.getParamPath();
            if (path == null) {
                continue;
            }
            if (path.endsWith("ManagementServer.ConnectionRequestUsername")) {
                user = row.getParamValue();
            } else if (path.endsWith("ManagementServer.ConnectionRequestPassword")) {
                password = row.getParamValue();
            }
        }
        if ((user == null || user.isBlank()) && (password == null || password.isBlank())) {
            return;
        }
        try {
            CpeDevice device = new CpeDevice();
            if (user != null && !user.isBlank()) {
                device.setAcsToCpeName(user);
            }
            if (password != null && !password.isBlank()) {
                device.setAcsToCpePassword(password);
            }
            deviceMapper.update(device,
                    new LambdaUpdateWrapper<CpeDevice>().eq(CpeDevice::getSn, sn));
            log.info("已登记设备连接请求凭据: sn={}, 账号={}", sn, user);
        } catch (Exception e) {
            log.warn("登记设备连接请求凭据失败: sn={}, err={}", sn, e.getMessage());
        }
    }

    private CpeParameter toRow(String sn, String batch, LocalDateTime now, Node node) {
        CpeParameter row = new CpeParameter();
        row.setSn(sn);
        row.setParamPath(node.path);
        row.setParentPath(parentOf(node.path));
        row.setNodeName(nameOf(node.path));
        row.setNodeType(node.object ? CpeParameter.TYPE_OBJECT : CpeParameter.TYPE_PARAM);
        row.setWritable(node.object ? 0 : (node.writable ? 1 : 0));
        row.setDepth(depthOf(node.path));
        row.setCollectBatch(batch);
        row.setCollectTime(now);
        return row;
    }

    // ==================== 触发判定 ====================

    /** 距上次采集是否已久到需要重采；从没采过也算 */
    /** partial 快照的重采冷却：避免 GPN 永久拒答的设备每个 Inform 都重跑一遍采集 */
    private static final long PARTIAL_RETRY_MINUTES = 30;

    private boolean isStale(String sn) {
        if (refreshIntervalHours <= 0) {
            return true;
        }
        try {
            LocalDateTime last = parameterMapper.lastCollectTime(sn);
            if (last == null) {
                return true;
            }
            if (ChronoUnit.HOURS.between(last, LocalDateTime.now()) >= refreshIntervalHours) {
                return true;
            }
            // partial 快照（GPN 拒答、只按已知路径 GPV，库里没有 object 行）不算新鲜：过短冷却就重采，让盲探有机会补全残缺 WAN 参数，否则设备顶着残缺快照过完整个刷新周期
            if (parameterMapper.countObjectNodes(sn) == 0
                    && ChronoUnit.MINUTES.between(last, LocalDateTime.now()) >= PARTIAL_RETRY_MINUTES) {
                log.info("快照为 partial（无参数树），冷却已过重采: sn={}, last={}", sn, last);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("查询采集时间失败，本次不采集: sn={}, err={}", sn, e.getMessage());
            return false;
        }
    }

    private boolean hasBootstrap(InformRequest inform) {
        if (inform == null || inform.getEventList() == null) {
            return false;
        }
        for (EventStruct event : inform.getEventList()) {
            if (event.getEventCode() != null && event.getEventCode().startsWith(EVENT_BOOTSTRAP)) {
                return true;
            }
        }
        return false;
    }

    /** 判定根数据模型节点。Inform 处理若走队列则是异步的，会话上的 rootNode 可能还没更新 */
    private String resolveRoot(CpeSession session, InformRequest inform) {
        List<ParameterValueStruct> parameters =
                inform == null || inform.getParameterList() == null
                        ? List.of() : inform.getParameterList();
        if (!parameters.isEmpty()) {
            List<String> paths = new ArrayList<>(parameters.size());
            for (ParameterValueStruct p : parameters) {
                if (p.getName() != null) {
                    paths.add(p.getName());
                }
            }
            // 只看第一条会被设备私有扩展参数带偏，这里按全部参数取多数
            return DataModel.detect(paths).getRoot();
        }
        return session.getRootNode() == null ? DEFAULT_ROOT : session.getRootNode();
    }

    // ==================== 工具 ====================

    private ExecutorService executor() {
        if (executor == null) {
            synchronized (this) {
                if (executor == null) {
                    executor = new ThreadPoolExecutor(workerThreads, workerThreads,
                            60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(2048),
                            r -> {
                                Thread t = new Thread(r, "acs-capability");
                                t.setDaemon(true);
                                return t;
                            }, new ThreadPoolExecutor.CallerRunsPolicy());
                }
            }
        }
        return executor;
    }

    private static <T> List<List<T>> partition(List<T> source, int size) {
        List<List<T>> result = new ArrayList<>();
        if (source.isEmpty() || size <= 0) {
            return result;
        }
        for (int i = 0; i < source.size(); i += size) {
            result.add(new ArrayList<>(source.subList(i, Math.min(i + size, source.size()))));
        }
        return result;
    }

    /** 父对象路径，根节点为空串。包级可见，便于单元测试直接校验层级推导 */
    static String parentOf(String path) {
        String p = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
        int idx = p.lastIndexOf('.');
        return idx < 0 ? "" : p.substring(0, idx + 1);
    }

    /** 路径末段名。包级可见，便于单元测试 */
    static String nameOf(String path) {
        String p = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
        int idx = p.lastIndexOf('.');
        return idx < 0 ? p : p.substring(idx + 1);
    }

    /** 层级深度，根节点为 0。包级可见，便于单元测试 */
    static int depthOf(String path) {
        String p = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
        int depth = 0;
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) == '.') {
                depth++;
            }
        }
        return depth;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean isTrue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private static String faultText(SoapMessage resp) {
        if (resp == null) {
            return "设备无响应";
        }
        if (resp.getFault() != null) {
            return "FaultCode=" + resp.getFault().getFaultCode()
                    + ", FaultString=" + resp.getFault().getFaultString();
        }
        return "未知错误";
    }

    // ==================== 内部模型 ====================

    /** 能力树上的一个节点，采集过程中的内存形态 */
    static class Node {

        private String path;

        private final boolean object;

        private final boolean writable;

        Node(String path, boolean object, boolean writable) {
            this.path = path;
            this.object = object;
            this.writable = writable;
        }

        /** 当前路径。落库时可能被截断为列宽上限 */
        String getPath() {
            return path;
        }
    }

    /** 一次采集的进度快照，管理端据此展示采集进度 */
    public static class CollectStatus {

        private final String sn;

        private final String trigger;

        private final String batch;

        private volatile String status = "idle";

        private volatile String phase = "";

        private volatile String message;

        private volatile long startTime;

        private volatile long finishTime;

        private volatile int nameCount;

        private volatile int objectCount;

        private volatile int paramCount;

        private volatile int valueCount;

        private volatile int totalBatches;

        private volatile int finishedBatches;

        /** 暂存的参数树节点：整轮采集跑完、能力测试通过才统一落库 */
        private final List<Node> stagedTree = Collections.synchronizedList(new ArrayList<>());

        /** 暂存的参数值行：整轮采集跑完、能力测试通过才统一落库 */
        private final List<CpeParameter> stagedValues = Collections.synchronizedList(new ArrayList<>());

        void stageTree(List<Node> tree) {
            if (tree != null && !tree.isEmpty()) {
                synchronized (stagedTree) {
                    stagedTree.addAll(tree);
                }
            }
        }

        void stageValues(List<CpeParameter> rows) {
            if (rows != null && !rows.isEmpty()) {
                synchronized (stagedValues) {
                    stagedValues.addAll(rows);
                }
            }
        }

        List<Node> stagedTree() {
            synchronized (stagedTree) {
                return new ArrayList<>(stagedTree);
            }
        }

        List<CpeParameter> stagedValues() {
            synchronized (stagedValues) {
                return new ArrayList<>(stagedValues);
            }
        }

        private volatile int failBatches;

        private volatile int objectRounds;

        private volatile boolean aborted;

        /** 连续无回应的指令数：任何一条真实回应（含 9002 这类故障）都清零 */
        private final java.util.concurrent.atomic.AtomicInteger silenceStreak =
                new java.util.concurrent.atomic.AtomicInteger();

        /** 记一条无回应指令，返回当前连续无回应计数 */
        int markSilence() {
            return silenceStreak.incrementAndGet();
        }

        /** 设备回了话：清零沉默计数 */
        void markAlive() {
            silenceStreak.set(0);
        }

        private final List<String> errors = Collections.synchronizedList(new ArrayList<>());

        public CollectStatus(String sn, String trigger) {
            this.sn = sn;
            this.trigger = trigger;
            this.batch = LocalDateTime.now().format(BATCH_FORMAT)
                    + "-" + Integer.toHexString(Math.abs(sn.hashCode()));
        }

        public String getSn() {
            return sn;
        }

        public String getTrigger() {
            return trigger;
        }

        public String getBatch() {
            return batch;
        }

        public String getStatus() {
            return status;
        }

        public String getPhase() {
            return phase;
        }

        public String getMessage() {
            return message;
        }

        public long getStartTime() {
            return startTime;
        }

        public long getFinishTime() {
            return finishTime;
        }

        public int getNameCount() {
            return nameCount;
        }

        public int getObjectCount() {
            return objectCount;
        }

        public int getParamCount() {
            return paramCount;
        }

        public int getValueCount() {
            return valueCount;
        }

        public int getTotalBatches() {
            return totalBatches;
        }

        public int getFinishedBatches() {
            return finishedBatches;
        }

        public int getFailBatches() {
            return failBatches;
        }

        public int getObjectRounds() {
            return objectRounds;
        }

        public boolean isAborted() {
            return aborted;
        }

        public List<String> getErrors() {
            return new ArrayList<>(errors);
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public void setPhase(String phase) {
            this.phase = phase;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public void setStartTime(long startTime) {
            this.startTime = startTime;
        }

        public void setFinishTime(long finishTime) {
            this.finishTime = finishTime;
        }

        public void setNameCount(int nameCount) {
            this.nameCount = nameCount;
        }

        public void setObjectCount(int objectCount) {
            this.objectCount = objectCount;
        }

        public void setParamCount(int paramCount) {
            this.paramCount = paramCount;
        }

        public void setTotalBatches(int totalBatches) {
            this.totalBatches = totalBatches;
        }

        public void setFinishedBatches(int finishedBatches) {
            this.finishedBatches = finishedBatches;
        }

        public void setObjectRounds(int objectRounds) {
            this.objectRounds = objectRounds;
        }

        public synchronized void addValueCount(int delta) {
            this.valueCount += delta;
        }

        public synchronized void incrementFailBatches() {
            this.failBatches++;
        }

        public synchronized void addError(String error) {
            if (errors.size() < 50) {
                errors.add(error);
            }
        }

        /** 中止采集：设备已不可达，后续批次不再下发 */
        public void abort() {
            this.aborted = true;
        }

        public synchronized void fail(String message) {
            this.status = "fail";
            this.message = message;
        }
    }
}
