package com.tr069.acs.acs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.acs.model.DataModel;
import com.tr069.acs.acs.session.CpeSession;
import com.tr069.acs.acs.session.PendingRpc;
import com.tr069.acs.acs.session.SessionManager;
import com.tr069.acs.agent.RpcDispatchService;
import com.tr069.acs.cwmp.model.EventStruct;
import com.tr069.acs.cwmp.model.InformRequest;
import com.tr069.acs.cwmp.model.ParameterValueStruct;
import com.tr069.acs.cwmp.model.SoapMessage;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeParameterMapper;
import com.tr069.acs.queue.config.QueueProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 设备能力采集。
 *
 * <p>一次采集分两步，全程跑在「Inform -> InformResponse -> 空 POST 捎带下发」这条链路上：</p>
 * <ol>
 *   <li>{@code GetParameterNames(子树, NextLevel=false)}：逐个子树递归拿到设备支持的路径，
 *       路径以句点结尾的是对象，其余是普通参数，同时带回读写权限。子树固定只取
 *       WAN / WiFi / LAN 网口 / SIP 电话四类，见 {@link #SUBTREES}</li>
 *   <li>{@code GetParameterValues}：把上一步得到的参数路径分批读值，回填进同一张表</li>
 * </ol>
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

    /** 下发前补采时最多等这么久，超时就按现有参数拼路径。 */
    @Value("${acs.device.capability.auto-collect-timeout-millis:120000}")
    private long autoCollectTimeoutMillis;

    /** 每台设备最近一次采集状态。 */
    private final Map<String, CollectStatus> statuses = new ConcurrentHashMap<>();

    /** 正在采集中的设备，避免同一台设备被并发采集。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

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
            return current;
        }
        if (!inFlight.add(sn)) {
            return current != null ? current : new CollectStatus(sn, trigger);
        }
        CollectStatus status = new CollectStatus(sn, trigger);
        statuses.put(sn, status);
        log.info("开始采集设备能力: sn={}, root={}, trigger={}, batch={}",
                sn, root, trigger, status.getBatch());

        runCollect(sn, root, status.getBatch(), status)
                .whenComplete((ignored, ex) -> {
                    inFlight.remove(sn);
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
                    modelTreeStore.export(sn, false);
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

        CompletableFuture<List<Node>> names = collectNames(sn, root, status);

        return names.thenComposeAsync(nodes -> {
            if (nodes.isEmpty()) {
                status.fail("未能获取参数路径: " + (status.getErrors().isEmpty()
                        ? "子树均未返回节点: " + String.join(", ", resolveSubtrees(root))
                        : String.join("; ", status.getErrors())));
                return CompletableFuture.completedFuture(null);
            }
            List<Node> tree = completeTree(nodes, root);
            saveTree(sn, batch, tree);
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
    private CompletableFuture<List<Node>> collectNames(String sn, String root, CollectStatus status) {
        List<String> paths = resolveSubtrees(root);
        log.info("按子树采集设备能力: sn={}, 子树={}", sn, paths);
        List<Node> sink = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger rounds = new AtomicInteger();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String path : paths) {
            chain = chain.thenComposeAsync(v -> {
                if (status.isAborted()) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                status.setPhase("names:" + path);
                return collectSubtree(sn, path, sink, rounds, status);
            }, executor());
        }
        return chain.thenApply(v -> sink);
    }

    /**
     * 拉取单个子树：先递归 GPN，递归没拿到东西时退化为逐层展开。
     *
     * <p>子树取不到有两种原因：设备压根没有这条路径（另一种数据模型、或者该功能是选配），
     * 以及设备不吃 NextLevel=false。两者都往 {@link #expandLevel} 兜一次，宁可多一轮来回，
     * 也不误判成设备没有这块业务；会话级故障（离线、超时）才直接中止。</p>
     */
    private CompletableFuture<Void> collectSubtree(String sn, String path, List<Node> sink,
                                                   AtomicInteger rounds, CollectStatus status) {
        return getParameterNames(sn, path, false).thenComposeAsync(resp -> {
            if (resp != null && !resp.isFault()) {
                List<Node> nodes = parseNodes(resp);
                if (!nodes.isEmpty()) {
                    sink.addAll(nodes);
                    return CompletableFuture.<Void>completedFuture(null);
                }
                log.info("子树递归拉取为空，改用逐层展开: sn={}, subtree={}", sn, path);
            } else {
                if (isSessionFault(resp)) {
                    status.addError("GetParameterNames(" + path + ") 失败: " + faultText(resp));
                    // 单纯的指令超时≠会话没了：设备忙、单条响应慢时其它子树照样能采，
                    // 一刀切 abort 会把「WAN 子树超时」放大成「整棵树采不到」；
                    // 只有连接断开/会话关闭（设备真的不在了）才中止全部子树
                    if (!isTimeoutFault(resp)) {
                        status.abort();
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                } else {
                    status.addError("GetParameterNames(" + path + ") 失败: " + faultText(resp));
                }
            }
            if (!fallbackNextLevel) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            List<Node> branch = Collections.synchronizedList(new ArrayList<>());
            return expandLevel(sn, path, branch, rounds, status)
                    .thenAccept(ignored -> sink.addAll(branch));
        }, executor());
    }

    /**
     * 待采集的子树绝对路径：把固定子树拼到设备的数据模型根节点下。
     */
    private static List<String> resolveSubtrees(String root) {
        String base = root == null || root.isEmpty() ? DEFAULT_ROOT : root;
        List<String> subtrees = DataModel.TR181.getRoot().equals(base)
                ? SUBTREES_TR181 : SUBTREES_TR098;
        List<String> paths = new ArrayList<>(subtrees.size());
        for (String subtree : subtrees) {
            paths.add(base + subtree);
        }
        return paths;
    }

    /**
     * 逐层展开：对 path 做 NextLevel=true 的 GetParameterNames，把命中的对象继续往下展开。
     */
    private CompletableFuture<List<Node>> expandLevel(String sn, String path, List<Node> sink,
                                                      AtomicInteger rounds, CollectStatus status) {
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
                String child = node.path;
                chain = chain.thenComposeAsync(v ->
                        expandLevel(sn, child, sink, rounds, status).thenAccept(x -> {
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
                return getParameterValues(sn, batch).thenAccept(resp -> {
                    status.setFinishedBatches(index + 1);
                    if (resp == null || resp.isFault()) {
                        status.incrementFailBatches();
                        status.addError("GetParameterValues 第 " + (index + 1) + "/"
                                + batches.size() + " 批失败: " + faultText(resp));
                        if (isSessionFault(resp)) {
                            status.abort();
                        }
                        return;
                    }
                    List<CpeParameter> rows = parseValues(sn, resp);
                    status.addValueCount(rows.size());
                    saveValues(sn, rows);
                });
            }, executor());
        }
        return chain;
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
    private CompletableFuture<SoapMessage> submit(String sn, String rpcName,
                                                  Map<String, Object> args) {
        if (queueProperties.isEnabled()) {
            RpcDispatchService.DispatchHandle handle =
                    rpcDispatchService.submitAsync(sn, rpcName, args, null);
            if (!handle.isQueued()) {
                log.warn("采集指令未入队: sn={}, rpc={}, taskId={}", sn, rpcName, handle.getTaskId());
            }
            return handle.getFuture();
        }
        CpeSession session = sessionManager.find(sn);
        if (session == null) {
            log.warn("会话不存在，采集指令无法下发: sn={}, rpc={}", sn, rpcName);
            return CompletableFuture.completedFuture(sessionFault("会话不存在"));
        }
        PendingRpc rpc = session.enqueue(-outboxSequence.incrementAndGet(), rpcName, args, null);
        return rpc.getResponse();
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
