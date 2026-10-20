package com.tr069.acs.acs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tr069.acs.acs.config.CapabilityProfiles;
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

/**
 * 设备能力采集。
 *
 * <p>一次采集分两步，全程跑在「Inform -> InformResponse -> 空 POST 捎带下发」这条链路上：</p>
 * <ol>
 *   <li>{@code GetParameterNames(子树, NextLevel=false)}：逐个子树递归拿到设备支持的路径，
 *       路径以句点结尾的是对象，其余是普通参数，同时带回读写权限。子树只取
 *       WAN / WiFi / LAN 网口 / SIP 电话四类，清单按运营商的采集标准取，
 *       见 {@link CapabilityProfiles}</li>
 *   <li>{@code GetParameterValues}：把上一步得到的参数路径分批读值，回填进同一张表</li>
 * </ol>
 *
 * <p>子树按什么姿势问，各家运营商的定制机答案不一样：移动定制机一次递归就给全量，电信定制机
 * 对 {@code InternetGatewayDevice.WANDevice.} 直接回 9002 Internal error，得换成
 * {@code InternetGatewayDevice.WANDevice.1.} 才认。所以每棵子树依次试「递归 → 逐层 →
 * 按实例号 1..N 探测 → 已知路径 GPV」，哪种成了用哪种，策略本身也写在采集标准里。</p>
 *
 * <p>关键在于「抢窗口」：Inform 的响应体只能是 InformResponse，不能夹带下发；CPE 收到
 * InformResponse 后会立刻在<strong>同一条 TCP 连接</strong>上空 POST 一次，ACS 的指令就是在
 * 这次空 POST 的响应体里捎带出去的。所以采集指令必须在回复 InformResponse 之前就排进下发队列，
 * 空 POST 到达时才能被立即取走，否则就要等设备下一次周期上报。</p>
 *
 * <p>两步之间用 {@link CompletableFuture} 串联，等待设备的那段时间不占线程：
 * 指令从 {@link RpcDispatchService} 入队即返回，设备回应后凭据完成，后续批次才提交。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceCapabilityService {

    /**
     * 0 BOOTSTRAP 事件码前缀，设备首次上线或恢复出厂后上报，此时设备能力可能已变，强制重采。
     */
    private static final String EVENT_BOOTSTRAP = "0 BOOTSTRAP";

    /**
     * 采集状态：只拿到了已知路径那几个参数（设备身份），参数树没采到。
     *
     * <p>不算 success——它导不出型号能力档案，拿它归档会把一份残缺的 XML 锁成型号档案；
     * 但也不是失败：设备身份进库了，页面上能看到这是什么猫。</p>
     */
    private static final String PARTIAL = "partial";

    /**
     * 兜底的根数据模型节点：设备台账与会话都还没认出来时按 TR-098 处理。
     * 真正生效的根节点来自 {@link DataModel#detect}，别在不确定的地方写死字符串。
     */
    private static final String DEFAULT_ROOT = DataModel.TR098.getRoot();

    /**
     * 只采集这些子树，相对数据模型根节点书写。
     *
     * <p>整机参数树动辄上万节点，而平台真正用到的只有 WAN、WiFi、LAN 网口、SIP 电话四类业务，
     * 全量拉一遍既慢又占库，所以只在子树上发 GetParameterNames，拼成一棵够用的树。</p>
     *
     * <p>前缀同时覆盖 TR-098（{@code InternetGatewayDevice.}）与 TR-181（{@code Device.}）两种
     * 数据模型：前者用 {@code WANDevice./LANDevice.}，后者用 {@code WAN./Ethernet.Interface./WiFi.}；
     * TR-098 的 WiFi（{@code WLANConfiguration}）挂在 {@code LANDevice.} 下面，一并随 LAN 子树取回。
     * 某块子树取不到由 {@link #collectSubtree} 兜底处理，不影响其它子树。</p>
     *
     * <p>这两份是<strong>内置默认</strong>：只在采集标准没配子树时兜底，
     * 各运营商真正生效的清单在 {@code acs.device.capability.profiles}。</p>
     */
    /**
     * TR-098（{@code InternetGatewayDevice.}）下的业务子树。
     *
     * <p>子树名和根节点是绑定的：TR-098 的网口叫 {@code LANDevice.}、语音挂在
     * {@code Services.VoiceService.} 下面；TR-181 则是 {@code Ethernet.Interface.} / {@code WiFi.}。
     * 早先两类名字混在一个列表里无差别往根节点上拼，TR-098 设备就会收到
     * {@code InternetGatewayDevice.LAN.} 这种根本不存在的路径，每次采集必定回一串
     * 9005 Invalid parameter name，还白白多跑一轮逐层展开兜底。</p>
     */
    private static final List<String> SUBTREES_TR098 = List.of(
            // WAN
            "WANDevice.",
            // LAN 网口（TR-098 的 WiFi 挂在 LANDevice 下面，随 LAN 子树一并取回）
            "LANDevice.",
            // SIP 电话
            "Services.VoiceService.");

    /** TR-181（{@code Device.}）下的业务子树。 */
    private static final List<String> SUBTREES_TR181 = List.of(
            // WAN
            "WAN.",
            // LAN 网口
            "Ethernet.Interface.",
            "LAN.",
            // WiFi
            "WiFi.",
            // SIP 电话
            "Services.VoiceService.",
            "VoiceService.");

    /**
     * 会话级故障码：下发超时、连接断开、指令未入队，由会话层与本服务生成，
     * 区别于设备返回的 CWMP 故障码。
     */
    private static final String FAULT_SESSION = "8002";

    /**
     * 指令入队失败（队列不可用或该设备队列已满），同样不是设备返回的故障。
     */
    private static final String FAULT_ENQUEUE = "8003";

    /**
     * 批量写入的分片大小，避免单条 INSERT 过长。
     */
    private static final int INSERT_CHUNK = 500;

    /**
     * 值回填的分片大小。
     */
    private static final int UPDATE_CHUNK = 200;

    /**
     * 单个参数值的落库长度上限，超出截断，防止 TEXT 溢出导致整批写入失败。
     */
    private static final int MAX_VALUE_LENGTH = 20000;

    private static final DateTimeFormatter BATCH_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SessionManager sessionManager;
    private final RpcDispatchService rpcDispatchService;
    private final CpeParameterMapper parameterMapper;
    private final QueueProperties queueProperties;
    private final com.tr069.acs.worklist.service.ModelTreeStore modelTreeStore;
    /** 设备台账：取运营商，采完再把识别出的运营商写回去。 */
    private final CpeDeviceMapper deviceMapper;
    /** 按运营商的能力采集标准。 */
    private final CapabilityProfiles capabilityProfiles;

    /** 采集总开关。 */
    @Value("${acs.device.capability.enabled:true}")
    private boolean enabled;

    /** 收到 Inform 后是否自动触发采集。 */
    @Value("${acs.device.capability.collect-on-inform:true}")
    private boolean collectOnInform;

    /** BOOTSTRAP 事件是否强制重新采集。 */
    @Value("${acs.device.capability.force-on-bootstrap:true}")
    private boolean forceOnBootstrap;

    /** 距上次采集超过该小时数才重采，0 表示每次 Inform 都采。 */
    @Value("${acs.device.capability.refresh-interval-hours:24}")
    private long refreshIntervalHours;

    /** 单条 GetParameterValues 携带的参数个数。 */
    @Value("${acs.device.capability.value-batch-size:64}")
    private int valueBatchSize;

    /** 单次采集读取的参数个数上限。 */
    @Value("${acs.device.capability.max-parameters:20000}")
    private int maxParameters;

    /** 递归 GetParameterNames 失败时，是否改用 NextLevel=true 逐层展开。 */
    @Value("${acs.device.capability.fallback-next-level:true}")
    private boolean fallbackNextLevel;

    /** 逐层展开时最多展开多少个对象。 */
    @Value("${acs.device.capability.max-object-rounds:500}")
    private int maxObjectRounds;

    /** 采集与回调使用的线程数。 */
    @Value("${acs.device.capability.worker-threads:8}")
    private int workerThreads;

    /** 采集成功后是否把参数树导出为型号 XML 文件（models/{isp}/{型号}.xml）。 */
    @Value("${acs.device.capability.export-model-tree:true}")
    private boolean exportModelTree;

    /**
     * 是否按能力树里的厂商私有前缀（X_CMCC_ / X_CT-COM_ / X_CU_）回写设备运营商。
     *
     * <p>台账的运营商原本只有人工适配时才会填，而采集发生在适配之前：设备刚进来时运营商
     * 是空的，只能按通用档采。认出前缀后写回台账，下一轮采集与型号归档就按这家运营商走。</p>
     *
     * <p>写死开启：这是「设备进来就能认出是哪家的运营商」的唯一自动来源，关掉后未适配设备
     * 会一直停在通用档。要临时关掉改这里。</p>
     */
    private boolean autoDetectIsp = true;

    /** 下发前补采时最多等这么久，超时就按现有参数拼路径。 */
    @Value("${acs.device.capability.auto-collect-timeout-millis:120000}")
    private long autoCollectTimeoutMillis;

    /** 每台设备最近一次采集状态。 */
    private final Map<String, CollectStatus> statuses = new ConcurrentHashMap<>();

    /** 正在采集中的设备，避免同一台设备被并发采集。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * 自适应档设备「连续扫不动参数树」的次数（sn → 连续失败次数）。
     *
     * <p>连续两次扫不动才降级为只读关键字段。为什么要两次：9002 不一定是设备不支持，
     * 也可能只是它当时状态不对（正在重启、CWMP 模块忙）。只凭一次就把设备永久判成
     * 「扫不动」，会让本该采到完整能力树的设备从此只剩十几条关键字段，
     * 型号档案也跟着停更——这个代价太大，宁可多试一轮。</p>
     */
    private final Map<String, Integer> treeFailures = new ConcurrentHashMap<>();

    /** 连续扫不动多少次才降级。 */
    private static final int TREE_FAIL_THRESHOLD = 2;

    /**
     * 一轮采集的总时长上限（毫秒）。
     *
     * <p>设备不在下发窗口时（比如手动点采集，而设备的下一次上报要十几分钟后），
     * 指令只会压在队列里，等响应的 future 就一直挂着。没有上限的话这个 future
     * 永远不完成，{@link #inFlight} 也跟着不释放——这台设备从此再也采不了，
     * 只能重启 ACS。到点就结束本轮，指令清掉或留到下一轮。</p>
     */
    @Value("${acs.capability.collect-timeout-ms:600000}")
    private long collectTimeoutMillis;

    /** 本轮采集的到期时刻（sn → 毫秒时间戳）。 */
    private final Map<String, Long> deadlines = new ConcurrentHashMap<>();

    /** 连接请求的默认账号（ACS -> CPE），配置里写 auto 或留空表示设备侧免认证。 */
    @Value("${acs.auth.acs-to-cpe-username:}")
    private String defaultAcsToCpeUser;

    @Value("${acs.auth.acs-to-cpe-password:}")
    private String defaultAcsToCpePassword;

    private static final String AUTH_AUTO = "auto";

    /**
     * 是否在手动采集前主动唤醒设备（连接请求）。
     *
     * <p>不是每家的猫都吃这一套：有的设备对这个请求要求 HTTP 认证，ACS 手上没有凭据就
     * 只能拿到 401，而它的采集本来靠周期上报也跑得好好的。现场确认不需要就整个关掉。</p>
     */
    @Value("${acs.capability.wakeup-enabled:true}")
    private boolean wakeupEnabled;

    /** 唤醒过但没凭据的设备（sn → 下次再试的时刻），免得每次采集都撞一次 401。 */
    private final Map<String, Long> wakeupRetryAfter = new ConcurrentHashMap<>();

    /** 无凭据唤醒失败后隔多久再试（毫秒）。 */
    private static final long WAKEUP_RETRY_INTERVAL = 60 * 60 * 1000L;

    /** 设备最近有通信就算还在下发窗口，不必叫它（秒）。 */
    private static final long WAKEUP_SESSION_FRESH_SECONDS = 60L;

    /**
     * 从 Inform 上报参数预判出的运营商（sn → 运营商代码）。
     *
     * <p>只在这次 Inform 触发的采集里用：采集跑在台账入库之前，查库拿不到运营商，
     * 靠它先顶上。台账入库后以库里的为准（人工申报过的也以申报为准）。</p>
     */
    private final Map<String, String> informIsp = new ConcurrentHashMap<>();

    /**
     * 队列关闭时退回会话内存 outbox，此时拿不到 rpc_record 主键，
     * 用负数序号避免与数据库主键相撞。
     */
    private final AtomicInteger outboxSequence = new AtomicInteger(0);

    private volatile ExecutorService executor;

    // ==================== 入口 ====================

    /**
     * Inform 到达时调用。
     *
     * <p>必须发生在回复 InformResponse 之前：采集指令要提前排进下发队列，
     * 才能被 CPE 紧接着的那次空 POST 取走，抢住这个窗口期。</p>
     *
     * @param session 设备会话
     * @param inform  Inform 内容，用于识别 BOOTSTRAP 与判定根数据模型节点
     */
    public void onInform(CpeSession session, InformRequest inform) {
        if (!enabled || !collectOnInform || session == null) {
            return;
        }
        String sn = session.getSn();
        try {
            // 采集跑在入库之前（要抢空 POST 窗口），这时台账上还没有运营商，
            // 只靠查库就会按通用档开跑。Inform 自带私有前缀，先记下来给采集用
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

    /**
     * 从 Inform 上报的参数里认运营商，记下来给紧接着的这次采集用。
     *
     * <p>为什么不等查库：{@link #onInform} 必须在回复 InformResponse 之前跑完（要抢空 POST
     * 窗口），而台账入库走队列消费者，晚 100ms 以上。所以第一次上报的设备，采集开跑时
     * 台账上还没有运营商，只能按通用档走——电信设备就享受不到本档的策略
     * （自适应降级、绕开 {@code X_CTC_}）。</p>
     */
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
        Isp detected = Isp.detect(paths);
        if (detected == null || detected == Isp.STANDARD) {
            return;
        }
        informIsp.put(sn, detected.getCode());
        log.info("按 Inform 私有前缀预判采集标准: sn={}, isp={}", sn, detected.getCode());
    }

    /**
     * 手动触发一次采集，供管理端调用。
     *
     * @param sn    设备序列号
     * @param force 是否跳过「距上次采集时间」的判断
     * @return 采集状态快照
     */
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

    /**
     * 查询采集状态，从未采集过返回 null。
     */
    public CollectStatus status(String sn) {
        return statuses.get(sn);
    }

    /**
     * 下发前确保这台设备的配置已上报过：没上报就现采一次，等它采完再拼路径。
     *
     * <p>模板只说明要下发哪些业务属性，真正能写什么节点得看设备自己上报的树。设备从没上报过
     * 时这里补一次采集，业务方不用先手工采集再下发。</p>
     *
     * @return 结束时这台设备是否已经有了可用参数树
     */
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

    /**
     * 设备能力汇总：支持多少路径、其中多少对象/参数、多少可写、最近采集时间。
     */
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
            // 上一轮卡死了：设备不在下发窗口，指令压在队列里等不到回应，future 没个头。
            // 不强制收尾的话 {inFlight} 永远占着，这台设备从此再也采不了，只能重启 ACS。
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

        // 手动采集是「人点下去的那一刻」开跑的，设备多半不在下发窗口里——光猫十几分钟
        // 才上报一次，不叫它一声就得干等。先叫再入队：设备收到连接请求到真正上报
        // 有几百毫秒网络往返，足够第一批指令进队列
        if ("manual".equals(trigger)) {
            wakeUp(sn);
        }
        // 手动采集时设备多半不在线（下一次上报可能十几分钟后），指令只能排在队列里。
        // 把这一点写进状态，界面上不至于看着像卡住了
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
                        // 每棵子树试过哪些姿势、设备回了什么故障码，全在这一条里，
                        // 不用去翻散落的单条日志
                        log.warn("采集未成功，各环节明细: sn={}, 结论={}, 明细={}",
                                sn, status.getMessage(),
                                status.getErrors().isEmpty() ? "无" : String.join(" | ", status.getErrors()));
                    }
                    if (exportModelTree && "success".equals(status.getStatus())) {
                        exportModelTreeAsync(sn);
                    }
                });
        return status;
    }

    /**
     * 采集成功后把能力树导出成型号 XML，供同型号设备复用与下发取路径。
     *
     * <p>导出是旁路动作：失败只记日志，不影响采集结果本身。</p>
     */
    private void exportModelTreeAsync(String sn) {
        try {
            executor().execute(() -> {
                try {
                    // 归档走统一入口：任何运营商的猫都先截到 models/standard/standard/，
                    // 按运营商分目录是人工适配申报时才做的事
                    modelTreeStore.exportCollected(sn);
                } catch (Exception e) {
                    log.warn("导出型号能力树失败: sn={}, err={}", sn, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("提交型号能力树导出失败: sn={}, err={}", sn, e.getMessage());
        }
    }

    /**
     * 两步采集的编排：先拉全量路径，再分批读值。
     */
    private CompletableFuture<Void> runCollect(String sn, String root, String batch,
                                               CollectStatus status) {
        status.setStatus("running");
        status.setPhase("names");
        status.setStartTime(System.currentTimeMillis());

        String isp = deviceIsp(sn);
        if (isp == null || isp.isBlank()) {
            // 台账还没登记（首次上报，入库走队列会比这次采集晚）：用 Inform 认出的那个
            isp = informIsp.get(sn);
        }
        CapabilityProfiles.Profile profile = profileFor(isp);
        log.info("本次采集采用的采集标准: sn={}, isp={}, 标准={}, 策略={}",
                sn, isp, profileName(profile), strategyText(profile));

        if (profile.isValuesOnly()
                || (profile.isAdaptive() && treeFailures.getOrDefault(sn, 0) >= TREE_FAIL_THRESHOLD)) {
            // 电信天翼网关这类设备：对象路径的 GPN 一律不答（连「只列下一层」都 9002），
            // 与官方 ITMS 一致，不扫参数树，只按需读清单里的关键字段。
            // 自适应档是上一轮刚撞过 9002 记住的，本次直接跳过，不再白等一个周期
            return probeGlobalPaths(sn, root, profile, status).thenApply(probed -> {
                if (probed) {
                    status.setStatus(PARTIAL);
                    status.setMessage("按采集标准只读关键字段，未扫参数树");
                } else {
                    status.fail("关键字段一个都没读到: " + String.join(", ", status.getErrors()));
                }
                return null;
            });
        }

        CompletableFuture<List<Node>> names = collectNames(sn, root, profile, status);

        return names.thenComposeAsync(nodes -> {
            if (nodes.isEmpty()) {
                // 整棵树的 GPN 都没拿到：退到采集标准登记的已知路径直接读值，
                // 至少把设备身份留下，别让这台设备在库里什么都不剩
                if (profile.isAdaptive()) {
                    // 记一次扫不动：连续两次才降级为只读关键字段，避免设备偶发 9002
                    // （重启中、CWMP 模块忙）就被永久判成扫不动
                    int fails = treeFailures.merge(sn, 1, Integer::sum);
                    log.warn("参数树扫不动（连续 {} 次）: sn={}, 明细={}", fails, sn,
                            status.getErrors().isEmpty() ? "子树均未返回节点"
                                    : String.join("; ", status.getErrors()));
                    if (fails >= TREE_FAIL_THRESHOLD) {
                        log.warn("连续 {} 次扫不动参数树，后续采集改走只读关键字段: sn={}",
                                fails, sn);
                    }
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
                    return null;
                });
            }
            // 扫到了就清零：之前的失败只是偶发，别累积到降级阈值
            treeFailures.remove(sn);
            List<Node> tree = completeTree(nodes, root);
            saveTree(sn, batch, tree);
            if (autoDetectIsp) {
                detectAndWriteIsp(sn, pathsOf(tree));
            }
            status.setNameCount(tree.size());
            status.setObjectCount(countNodes(tree, true));
            status.setParamCount(countNodes(tree, false));

            List<String> paths = paramPaths(tree);
            if (paths.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            status.setPhase("values");
            return readValues(sn, paths, status);
        }, executor()).exceptionally(ex -> {
            Throwable cause = ex instanceof java.util.concurrent.CompletionException
                    && ex.getCause() != null ? ex.getCause() : ex;
            status.fail(String.valueOf(cause.getMessage()));
            return null;
        });
    }

    /**
     * 按 {@link #SUBTREES} 逐个拉取参数路径。
     *
     * <p>不再从根一次性递归整棵树：光猫全树动辄上万节点，平台实际只用得上
     * WAN、WiFi、LAN 网口、SIP 电话这几块。子树逐个下发，某块设备没有就跳过去。</p>
     *
     * <p>一个会话同时只允许一条未回应的下发，所以子树之间必须串行。</p>
     */
    private CompletableFuture<List<Node>> collectNames(String sn, String root,
                                                       CapabilityProfiles.Profile profile,
                                                       CollectStatus status) {
        List<String> paths = resolveSubtrees(root, profile);
        log.info("按子树采集设备能力: sn={}, 子树={}", sn, paths);
        AtomicInteger rounds = new AtomicInteger();
        // 各子树同时提交：设备一次只处理一条（MaxEnvelopes=1），但队列里要一直有货——
        // 下发窗口是设备给的，队列一空我们就得回 204，华为这类固件收到 204 就结束会话，
        // 后面每棵子树都得等它下一次上报（周期常常十几分钟）。各子树自带桶，
        // 最后按声明顺序合并，结果与串行一致
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
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Node> sink = new ArrayList<>();
                    buckets.forEach(sink::addAll);
                    return sink;
                });
    }

    /**
     * 拉取单个子树：先递归 GPN，递归没拿到东西时退化为逐层展开。
     *
     * <p>子树取不到有两种原因：设备压根没有这条路径（另一种数据模型、或者该功能是选配），
     * 以及设备不吃 NextLevel=false。两者都往 {@link #expandLevel} 兜一次，宁可多一轮来回，
     * 也不误判成设备没有这块业务；会话级故障（离线、超时）才直接中止。</p>
     */
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
                // 整棵子树都问不出来：设备多半不吃「对象 + 待定实例号」的路径，
                // 改按实例号 1..N 逐个探（电信定制机对 WANDevice. 回 9002，WANDevice.1. 正常）
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

    /**
     * 递归拉一棵子树：{@code NextLevel=false} 一次拿全量。
     *
     * @return 是否拿到了节点
     */
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

    /**
     * 逐层展开一棵子树：{@code NextLevel=true} 拿到下一层，命中的对象继续往下钻。
     *
     * @return 是否拿到了节点
     */
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

    /**
     * 按实例号逐个探测：{@code WANDevice.} 问不出来时，改问 {@code WANDevice.1.} / {@code .2.} ……
     *
     * <p>设备对「对象后面没跟实例号」的路径回 9002 Internal error 时，这是唯一还能拿到树的办法。
     * 代价是每个实例号要等设备一次上报周期，所以探到第一个不存在的实例号就停，
     * 上限由采集标准的 {@code max-instances} 控制。</p>
     */
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

    /**
     * 最后一道兜底：GetParameterNames 各种姿势都不认时，按采集标准里登记的已知路径
     * 直接发 GetParameterValues（一条一回），至少把业务参数读回来。
     *
     * <p>只在采集标准显式配了 {@code probe-paths} 时才走，默认不开——每条路径都要等
     * 设备一次上报周期，全靠它采太慢。</p>
     */
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
                return getParameterValues(sn, List.of(target)).thenAccept(resp -> {
                    if (resp == null || resp.isFault()) {
                        status.addError("已知路径探测失败: " + target + " (" + faultText(resp) + ")");
                        return;
                    }
                    List<CpeParameter> rows = parseValues(sn, resp);
                    if (rows.isEmpty()) {
                        return;
                    }
                    saveValues(sn, rows);
                    sink.add(new Node(target, false, true));
                });
            }, executor());
        }
        return chain.thenApply(v -> true);
    }

    /**
     * 这个对象是否在采集标准的「禁止展开」名单里：比对对象名（路径末段）的前缀。
     *
     * <p>电信定制机 {@code X_CTC_} 那一套（天翼插件、ITMS 探针、天翼管家、DPI 软探针、
     * 业务虚拟对象）固件一遍历进去就抛 9002 Internal error，整棵子树连带报废；
     * ITMS 自己也从不递归遍历，只按需读指定字段。展开时绕开这些分支，
     * 标准子树才走得完。</p>
     */
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

    /**
     * 最后一档兜底（整棵树级别）：GetParameterNames 一句都问不出来时，按采集标准登记的
     * 已知路径直接发 GetParameterValues。
     *
     * <p>与 {@link #probeKnownPaths} 的分工：后者只在单棵子树内兜底，这里管的是
     * 「设备连一句话都不答」的场景，路径不限于业务子树，主要是 DeviceInfo 那几个身份参数。</p>
     *
     * @return 是否读到了值
     */
    private CompletableFuture<Boolean> probeGlobalPaths(String sn, String root,
                                                        CapabilityProfiles.Profile profile,
                                                        CollectStatus status) {
        if (profile == null || profile.getProbePaths() == null || profile.getProbePaths().isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        List<String> paths = new ArrayList<>();
        for (String candidate : profile.getProbePaths()) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String relative = candidate.trim();
            paths.add(relative.startsWith(root) ? relative : root + relative);
        }
        if (paths.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        log.info("按已知路径直接读值（不扫参数树）: sn={}, 路径 {} 条", sn, paths.size());
        status.setPhase("values");
        return getParameterValues(sn, paths).thenCompose(resp -> {
            if (resp != null && !resp.isFault()) {
                List<CpeParameter> rows = parseValues(sn, resp);
                if (!rows.isEmpty()) {
                    saveValues(sn, rows);
                    status.addValueCount(rows.size());
                    // 读到 X_CT-COM_ / X_CTC_ 这类私有参数就认运营商：
                    // 不走参数树采集这条路的设备，这是唯一的识别来源
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
            // 整批被拒：多半是清单里混着这台设备不支持的路径（电信固件对 GPV 也挑食），
            // 走二分拆批把能读的剔出来
            return readValuesBatch(sn, paths, status, 0, 1).thenApply(v -> {
                if (status.getValueCount() <= 0) {
                    return false;
                }
                log.warn("只拿到已知路径参数，参数树未采到: sn={}, 读到 {} 条", sn, status.getValueCount());
                return true;
            });
        });
    }

    /**
     * 本次采集采用的采集标准：按设备台账的运营商取，台账没登记就走通用档。
     */
    private CapabilityProfiles.Profile profileFor(String isp) {
        if (capabilityProfiles == null) {
            return new CapabilityProfiles.Profile();
        }
        return capabilityProfiles.forIsp(Isp.of(isp));
    }

    /**
     * 设备台账上的运营商，查不到返回 null（由 {@link Isp#of} 归到通用档）。
     */
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

    /**
     * 用设备的 ConnectionRequestURL 反连唤醒它，让它立刻来上报。
     *
     * <p>手动采集是人点下去的那一刻开跑的，可设备不一定在下发窗口里——光猫往往十几分钟
     * 才上报一次，指令只能压在队列里干等，等到采集时限到了还是一场空。TR-069 允许 ACS
     * 反向请求设备建会话（ConnectionRequest），设备收到就会立刻 Inform，
     * 队列里的指令马上能发出去。</p>
     *
     * <p>唤醒失败一律忽略：URL 没上报过、设备侧端口不通、要求认证都可能，
     * 这时只是退化成「等它自己上报」，不牵连采集本身。</p>
     */
    private void wakeUp(String sn) {
        if (!wakeupEnabled) {
            return;
        }
        Long retryAfter = wakeupRetryAfter.get(sn);
        if (retryAfter != null && System.currentTimeMillis() < retryAfter) {
            return;
        }
        // 设备刚上报过、还在下发窗口里的话直接下发就行，不必多此一举去叫它
        CpeSession session = sessionManager != null ? sessionManager.find(sn) : null;
        if (session != null && !session.isExpired(WAKEUP_SESSION_FRESH_SECONDS)) {
            return;
        }
        CpeDevice device = findDevice(sn);
        if (device == null) {
            return;
        }
        String url = device.getConnectionRequestUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        // 台账上的设备级凭据优先，没有就用全局配置（电信现场多见 itms/itms）
        String user = firstNonBlank(device.getAcsToCpeName(), defaultAcsToCpeUser);
        String password = firstNonBlank(device.getAcsToCpePassword(), defaultAcsToCpePassword);
        if (authDisabled(user)) {
            // 台账和全局都没配：翻这台已经采集到的参数——采过完整树的设备，
            // 凭据的值本来就在库里，不用为它再采一轮
            user = paramValue(sn, "ManagementServer.ConnectionRequestUsername");
            password = paramValue(sn, "ManagementServer.ConnectionRequestPassword");
        }
        boolean withCredentials = !authDisabled(user);
        try {
            HttpResponse<String> resp = sendConnectionRequest(url, user, password);
            if (resp.statusCode() >= 400) {
                if (!withCredentials && resp.statusCode() == 401) {
                    // 手上没有凭据，这台又要求认证：短期内别再撞了。
                    // 唤醒只是加速手段，拿不到就等它自己上报，不影响采集
                    wakeupRetryAfter.put(sn, System.currentTimeMillis() + WAKEUP_RETRY_INTERVAL);
                    log.info("设备要求认证且本机未配置连接请求凭据，跳过唤醒（等它自行上报）: "
                            + "sn={}, url={}。需要唤醒就配 acs.auth.acs-to-cpe-username/password，"
                            + "或让它采集一轮把 ManagementServer.ConnectionRequestUsername 读回来", sn, url);
                } else {
                    log.warn("连接请求被设备拒绝: sn={}, url={}, 响应码={}, 已带认证={}",
                            sn, url, resp.statusCode(), withCredentials);
                }
            } else {
                log.info("已发连接请求唤醒设备: sn={}, url={}, 响应码={}", sn, url, resp.statusCode());
            }
        } catch (Exception e) {
            log.warn("连接请求唤醒失败，改为等待设备自行上报: sn={}, url={}, err={}",
                    sn, url, e.getMessage());
        }
    }

    /**
     * 发连接请求；设备要认证就带上凭据重发一次。
     *
     * <p>TR-069 的连接请求认证普遍是 HTTP Digest，而 JDK 自带的 {@link HttpClient}
     * 只支持 Basic，Digest 得自己算（HA1 / HA2 / response）。设备回 401 时不重试，
     * 就只能眼看着唤醒失败。</p>
     */
    private HttpResponse<String> sendConnectionRequest(String url, String user, String password)
            throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
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

    /** 按设备的 WWW-Authenticate 构造 Authorization 头，支持 Digest 与 Basic。 */
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

    /** 解析 {@code realm="x", nonce="y"} 这类认证参数。 */
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

    /** 凭据未配置（配置里写 auto 或留空 = 设备侧免认证）。 */
    private static boolean authDisabled(String user) {
        return user == null || user.isBlank() || AUTH_AUTO.equalsIgnoreCase(user.trim());
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    /** 从已采集到的参数里取某个路径的值，取不到返回 null。 */
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

    /** 台账上的设备记录，查不到返回 null。 */
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

    /**
     * 按能力树里的厂商私有前缀认运营商，并回写台账。
     *
     * <p>只补「台账还没运营商」的设备：人工适配申报过的以申报为准，自动识别不去覆盖，
     * 同型号硬件卖给多家运营商时以人的判断为准。</p>
     */
    private void detectAndWriteIsp(String sn, List<String> paths) {
        Isp detected = Isp.detect(paths);
        if (detected == null || detected == Isp.STANDARD) {
            return;
        }
        if (Isp.of(deviceIsp(sn)) != Isp.STANDARD) {
            return;
        }
        try {
            deviceMapper.update(null, new LambdaUpdateWrapper<CpeDevice>()
                    .eq(CpeDevice::getSn, sn)
                    .set(CpeDevice::getIsp, detected.getCode()));
            log.info("按能力树私有前缀登记设备运营商: sn={}, isp={}", sn, detected.getCode());
        } catch (Exception e) {
            log.warn("回写设备运营商失败: sn={}, isp={}, err={}", sn, detected.getCode(), e.getMessage());
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

    /**
     * 待采集的子树绝对路径：把采集标准的子树拼到设备的数据模型根节点下。
     */
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

    /**
     * 路径所属的数据模型根：{@code InternetGatewayDevice.} 或 {@code Device.}。
     */
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

    /**
     * 逐层展开：对 path 做 NextLevel=true 的 GetParameterNames，把命中的对象继续往下展开。
     */
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

    /**
     * 分批读取参数值。批次之间串行：一个会话同时只允许一条未回应的下发。
     */
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

    /**
     * 读一批参数值，整批被设备拒绝时二分拆开重试，把失效路径单独剔掉。
     *
     * <p>GetParameterValues 是整批事务：批里只要有一条路径设备不认（实例删了、
     * 或另一种数据模型没有这个节点），整批回 9005，这一批的值一个都落不了库。
     * 快照路径又来自上次采集——设备侧删过连接，清单里就留着一批僵尸路径，
     * 于是每次采集 WAN 那段都刷不回来，前端一直显示旧的那几条。</p>
     *
     * <p>所以失败后二分：拆到单条还失败，才认定这条路径设备确实没有，
     * 丢掉它并记进 errors，其余照常落库。会话级故障（离线、超时）不拆——
     * 那时拆也没用，直接中止，跟原来的行为一致。</p>
     *
     * @param counted 这次重试是否已计入「已完成批次」，避免拆批时把进度算重
     */
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
                    return readValuesBatch(sn, batch.subList(0, mid), status, index, total)
                            .thenComposeAsync(x -> readValuesBatch(sn, batch.subList(mid, batch.size()),
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
            saveValues(sn, rows);
            return CompletableFuture.<Void>completedFuture(null);
        }, executor());
    }

    // ==================== RPC 提交 ====================

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

    /**
     * 提交一条采集指令。
     *
     * <p>队列开启时走 Redis；关闭时退回会话的内存 outbox，
     * 保证关掉队列后能力采集依然可用。</p>
     */

    /** 当前这台设备正在进行的采集批次号，用于给指令打标。 */
    private String currentBatch(String sn) {
        CollectStatus status = statuses.get(sn);
        return status == null ? null : status.getBatch();
    }

    /**
     * 开新采集前先丢掉上一轮没发完的指令。
     *
     * <p>设备掉线、采集中断都会让指令留在队列里；不清的话新一轮会把它们逐条消费，
     * 于是同一条 {@code GetParameterNames} 被反复下发，队列还可能被占满
     * （每条设备有上限），导致这一轮的新指令根本入不了队。工单指令不属采集批次，不动。</p>
     */
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
        // 到点了就别再等：设备不在下发窗口时响应永远等不来，见 collectTimeoutMillis 的说明。
        // 措辞里刻意不带「超时」——那会被当成单条指令超时而只放弃当前分支，
        // 总时长到了是要中止整轮的
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
        if (!deadlines.containsKey(sn)) {
            return future;
        }
        return future.orTimeout(Math.max(remaining, 1L), TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    log.warn("采集指令等待回应超时: sn={}, rpc={}", sn, rpcName);
                    return sessionFault("采集总时长已到，未等到设备回应");
                });
    }

    /**
     * 会话级故障：设备离线、下发超时、连接断开。
     *
     * <p>与设备返回的 CWMP 故障要区分开——前者重试也没有意义，
     * 设备已经不在了，继续往下问只会拖出一长串超时。</p>
     */
    private static boolean isSessionFault(SoapMessage resp) {
        if (resp == null || resp.getFault() == null) {
            return false;
        }
        String code = resp.getFault().getFaultCode();
        return FAULT_SESSION.equals(code) || FAULT_ENQUEUE.equals(code);
    }

    /**
     * 会话级故障里的「超时」类：设备忙、单条响应慢，设备本体还在。
     *
     * <p>这类故障换个子树/换个时机就能成，不该中止整次采集；
     * 连接断开、会话关闭（"connection lost"、"device reconnected" 等）才是真不在了。</p>
     */
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

    /**
     * 按给定路径批量读取参数值，并回填能力树，供业务信息采集复用。
     *
     * <p>与内部 {@link #readValues} 的区别：不挂采集进度、不受采集开关约束——
     * 业务信息是用户主动查询的场景，设备在线就应当能读。</p>
     *
     * @return 路径 -> 参数值，只包含成功读到的路径；某批失败不影响其它批，
     *         失败批次的路径不会出现在结果里
     */
    public CompletableFuture<Map<String, String>> readPathValues(String sn, List<String> paths) {
        Map<String, String> result = new ConcurrentHashMap<>();
        if (paths == null || paths.isEmpty()) {
            return CompletableFuture.completedFuture(result);
        }
        List<List<String>> batches = partition(paths, Math.max(1, valueBatchSize));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (List<String> batch : batches) {
            chain = chain.thenComposeAsync(v -> getParameterValues(sn, batch).thenAccept(resp -> {
                if (resp == null || resp.isFault()) {
                    log.warn("业务参数读取失败: sn={}, size={}, err={}",
                            sn, batch.size(), faultText(resp));
                    return;
                }
                List<CpeParameter> rows = parseValues(sn, resp);
                for (CpeParameter row : rows) {
                    result.put(row.getParamPath(), row.getParamValue());
                }
                saveValues(sn, rows);
            }), executor());
        }
        return chain.thenApply(v -> result);
    }

    // ==================== 解析 ====================

    /**
     * 解析 GetParameterNamesResponse：路径以句点结尾即为对象。
     */
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

    /**
     * 解析 GetParameterValuesResponse 为待回填的行。
     */
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
            rows.add(row);
        }
        return rows;
    }

    /**
     * 补全树：根节点与各子树之上的中间层对象节点都由这里补上。
     *
     * <p>GetParameterNames 从子树发起时不会把子树自己的祖先带回来——比如
     * {@code InternetGatewayDevice.Services.VoiceService.} 上面的 {@code Services.}、
     * 以及根节点自己。少了这几层，型号 XML 导出与能力树浏览按 parent_path 逐级渲染，
     * 整棵子树会渲染不出来（参数都在表里，但读不到）。</p>
     */
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

    /**
     * 整棵替换：先清掉该设备的旧树，再分片写入。
     *
     * <p>不清空直接插入会撞唯一键；清理失败就放弃本次写入，保留旧树比留半棵新树好。</p>
     */
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
                parameterMapper.updateValues(sn, chunk);
            } catch (Exception e) {
                log.error("参数值回填失败: sn={}, size={}, err={}", sn, chunk.size(), e.getMessage());
            }
        }
        rememberConnectionRequestCredentials(sn, rows);
    }

    /**
     * 读到设备的连接请求凭据就记进台账，唤醒设备时用得上。
     *
     * <p>ACS 反连设备要做 HTTP 认证，而账号密码是设备侧生成的
     * （{@code ManagementServer.ConnectionRequestUsername/Password}），全局配一个不一定对得上。
     * 所以这里把设备自己报的凭据存下来，下次唤醒就能认证通过，不必靠人去猜。</p>
     */
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

    /**
     * 距上次采集是否已久到需要重采；从没采过也算。
     */
    private boolean isStale(String sn) {
        if (refreshIntervalHours <= 0) {
            return true;
        }
        try {
            LocalDateTime last = parameterMapper.lastCollectTime(sn);
            if (last == null) {
                return true;
            }
            return ChronoUnit.HOURS.between(last, LocalDateTime.now()) >= refreshIntervalHours;
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

    /**
     * 判定根数据模型节点。Inform 处理若走队列则是异步的，会话上的 rootNode 可能还没更新，
     * 这里直接从 Inform 参数列表里判定，避免采错根节点。
     */
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

    /**
     * 父对象路径，根节点为空串。包级可见，便于单元测试直接校验层级推导。
     */
    static String parentOf(String path) {
        String p = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
        int idx = p.lastIndexOf('.');
        return idx < 0 ? "" : p.substring(0, idx + 1);
    }

    /**
     * 路径末段名。包级可见，便于单元测试。
     */
    static String nameOf(String path) {
        String p = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
        int idx = p.lastIndexOf('.');
        return idx < 0 ? p : p.substring(idx + 1);
    }

    /**
     * 层级深度，根节点为 0。包级可见，便于单元测试。
     */
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

    /**
     * 能力树上的一个节点，采集过程中的内存形态。
     */
    static class Node {

        private String path;

        private final boolean object;

        private final boolean writable;

        Node(String path, boolean object, boolean writable) {
            this.path = path;
            this.object = object;
            this.writable = writable;
        }

        /**
         * 当前路径。落库时可能被截断为列宽上限。
         */
        String getPath() {
            return path;
        }
    }

    /**
     * 一次采集的进度快照，管理端据此展示采集进度。
     */
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

        private volatile int failBatches;

        private volatile int objectRounds;

        private volatile boolean aborted;

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

        /**
         * 中止采集：设备已不可达，后续批次不再下发。
         */
        public void abort() {
            this.aborted = true;
        }

        public synchronized void fail(String message) {
            this.status = "fail";
            this.message = message;
        }
    }
}
