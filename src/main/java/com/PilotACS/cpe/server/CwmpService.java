/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.server;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.cpe.model.RpcRequest;
import com.PilotACS.cpe.model.SoapMessage;
import com.PilotACS.cpe.model.TaskRun;
import com.PilotACS.cpe.redis.DeviceInfoCache;
import com.PilotACS.cpe.redis.RedisSessionLifecycle;
import com.PilotACS.cpe.redis.SnRateLimiter;
import com.PilotACS.cpe.rpc.TaskPlanner;
import com.PilotACS.cpe.service.BusinessPaths;
import com.PilotACS.cpe.service.BusinessSnapshotService;
import com.PilotACS.cpe.service.CapabilityDetector;
import com.PilotACS.cpe.service.ConnectionRequestService;
import com.PilotACS.cpe.service.InitProvisionService;
import com.PilotACS.cpe.service.ModelProfileService;
import com.PilotACS.cpe.session.CwmpSession;
import com.PilotACS.cpe.session.SessionRegistry;
import com.PilotACS.cpe.soap.SoapBuilder;
import com.PilotACS.cpe.soap.SoapParser;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.log.Logs;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** CWMP 会话核心：三态状态机 + Inform 处理 + RPC 单飞循环。 */
@Service
@RequiredArgsConstructor
public class CwmpService {

    private final ConnectionRequestService connectionRequestService;
    private final SnRateLimiter rateLimiter;
    private final RedisSessionLifecycle lifecycle;
    private final DeviceInfoCache infoCache;

    /** 一次 HTTP 交互的输出：HTTP 状态 + 报文 + 需要下发的会话 cookie */
    public record Reply(int httpStatus, String body, String newSessionId) {
        public static Reply empty(int status) {
            return new Reply(status, null, null);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long OFFLINE_AFTER_MS = 15 * 60 * 1000L;
    private static final Pattern TASK_KEY = Pattern.compile("task-(\\d+)");
    /** 会话中断后采集任务的延迟反踢间隔：刚断连立即踢大概率打空，等设备侧缓一缓 */
    private static final long RESUME_KICK_DELAY_MS = 30_000L;

    /** 待延迟反踢的设备（sn → 到期时间戳），由 sweep 到期触发 */
    private final Map<String, Long> delayedResumeKicks = new ConcurrentHashMap<>();

    private final CpeProperties props;
    private final SessionRegistry registry;
    private final CpeStore store;
    private final TaskPlanner planner;
    private final CapabilityDetector capabilityDetector;
    private final ModelProfileService modelProfileService;
    private final InitProvisionService initProvisionService;
    private final BusinessSnapshotService snapshotService;

    // ==================== 入口 ====================

    /** 处理 CPE 的一次 POST（body 为空表示空 POST） */
    public Reply processRequest(String body, String cookieSessionId, String remoteAddr) {
        SoapMessage msg;
        try {
            msg = SoapParser.parse(body);
        } catch (SoapParser.SoapException e) {
            Logs.warn(Logs.Module.CPE, "SOAP 报文解析失败", "原因", e.getMessage());
            return Reply.empty(400);
        }

        // 【前置限流】只拦"无会话"的洪泛报文（风暴保护，见 SnRateLimiter）。
        // 注：ACS 空 HTTP 响应按 TR-069 3.6 必须用 204 No Content（载荷才用 200），
        // 已绑定会话的后续往返（GPVResponse / Fault / 空POST）是正常 CWMP 交互，不能消耗令牌：
        // 令牌桶容量只有 10，第 10 个 RPC 后这些报文会被丢弃并回 200 空响应，
        // 设备收到空响应即结束会话，ACS 侧 60s 空闲超时 → 任务回队 → 反踢 → 新会话 → 无限循环。
        CwmpSession known = registry.bySessionId(cookieSessionId);
        boolean sessionBound = known != null || registry.byRemoteAddr(remoteAddr) != null;
        if (!sessionBound) {
            String limitId = limitId(msg, remoteAddr);
            if (!rateLimiter.tryAcquire(limitId)) {
                Logs.warn(Logs.Module.CPE, "报文限流丢弃（回 204 空响应）", "地址", remoteAddr,
                        "主体", limitId, "报文", msg.getName() == null ? "空POST" : msg.getName());
                return Reply.empty(204);
            }
        }
        if (known != null) {
            return locked(known.getSn(), () -> handleKnown(known, msg, remoteAddr));
        }
        if (msg.getName() == null) {
            // 无 Cookie 的空 POST：老设备不回传 session cookie，它的空 POST 找不到会话，
            CwmpSession fallback = registry.byRemoteAddr(remoteAddr);
            if (fallback != null && fallback.getState() == CwmpSession.STATE_INFORMED) {
                Logs.info(Logs.Module.CPE, "设备不带 Cookie，按来源地址找回会话", "sn", fallback.getSn(),
                        "地址", remoteAddr);
                return locked(fallback.getSn(), () -> handleKnown(fallback, msg, remoteAddr));
            }
            // 真没有会话：多为设备收尾（TR-069 A.3.2 里空响应即结束会话）。
            Logs.debug(Logs.Module.CPE, "无会话空 POST，回 204 空响应结束会话", "地址", remoteAddr);
            return Reply.empty(204);
        }
        if (!"Inform".equals(msg.getName())) {
            // 无 Cookie 的 RPC 响应/Fault/TransferComplete：同样按来源地址找回会话。
            CwmpSession fallback = registry.byRemoteAddr(remoteAddr);
            if (fallback != null && fallback.getState() != CwmpSession.STATE_CREATED) {
                Logs.info(Logs.Module.CPE, "设备不带 Cookie，按来源地址找回会话", "sn", fallback.getSn(),
                        "地址", remoteAddr, "报文", msg.getName());
                return locked(fallback.getSn(), () -> handleKnown(fallback, msg, remoteAddr));
            }
            return new Reply(400, SoapBuilder.acsFault(8000, "No session, Inform required"), null);
        }
        if (msg.getSerialNumber() == null || msg.getSerialNumber().isBlank()) {
            return new Reply(400, SoapBuilder.acsFault(8000, "Invalid device id"), null);
        }
        return locked(msg.getSerialNumber(), () -> handleNewInform(msg.getSerialNumber(), msg, remoteAddr));
    }

    /** 限流主体：有 SN 按 SN，否则按来源 IP（前缀区分，避免撞同一令牌桶） */
    private String limitId(SoapMessage msg, String remoteAddr) {
        String sn = msg.getSerialNumber();
        return (sn != null && !sn.isBlank()) ? sn : ("ip:" + remoteAddr);
    }

    /** 设备 SOAP 头声明的会话超时落到会话（CwmpSession.effectiveTimeout 里做上下限收敛） */
    private void applyHeaderTimeout(CwmpSession s, SoapMessage msg) {
        if (msg.getSessionTimeoutSeconds() > 0) {
            long old = s.getTimeoutMs();
            s.setTimeoutMs(msg.getSessionTimeoutSeconds() * 1000L);
            if (old == 0) {
                Logs.debug(Logs.Module.CPE, "设备声明会话超时", "sn", s.getSn(),
                        "秒", msg.getSessionTimeoutSeconds());
            }
        }
    }

    /** per-SN 串行处理锁（ReentrantLock 可重入：死会话重建走同一把锁） */
    private <T> T locked(String sn, Supplier<T> action) {
        ReentrantLock lock = registry.lockFor(sn);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** Cookie/地址找回的既有会话处理：先校验仍是现役会话，死了按无会话分支处理 */
    private Reply handleKnown(CwmpSession s, SoapMessage msg, String remoteAddr) {
        if (!registry.isCurrent(s)) {
            // 会话已被顶替/收尾/TTL 判死：TransferComplete 仍要归因（落库与会话无关）；
            if (isTransferComplete(msg)) {
                handleTransferComplete(s, msg);
                // 会话已死也必须应答 TC，否则设备保留 7 TRANSFER COMPLETE 事件反复重发
                return new Reply(200, tcResponse(msg,
                        msg.getId() == null || msg.getId().isBlank() ? "acs-tc" : msg.getId()), null);
            }
            if ("Inform".equals(msg.getName()) && msg.getSerialNumber() != null
                    && !msg.getSerialNumber().isBlank()) {
                return newInformNestedSafe(s.getSn(), msg.getSerialNumber(), msg, remoteAddr);
            }
            if (msg.getName() == null) {
                Logs.debug(Logs.Module.CPE, "空 POST 命中已收尾会话，回 204 空响应结束", "sn", s.getSn());
                return Reply.empty(204);
            }
            return new Reply(400, SoapBuilder.acsFault(8000, "Session no longer valid"), null);
        }
        if ("Inform".equals(msg.getName()) && msg.getSerialNumber() != null
                && !msg.getSerialNumber().isBlank()) {
            // 会话进行中又收到 Inform（周期上报撞上反向连接会话/设备重发）：
            if (!msg.getSerialNumber().equals(s.getSn())) {
                Logs.warn(Logs.Module.CPE, "Cookie 与报文 SN 不匹配，按报文 SN 重建会话",
                        "会话SN", s.getSn(), "报文SN", msg.getSerialNumber());
            }
            return newInformNestedSafe(s.getSn(), msg.getSerialNumber(), msg, remoteAddr);
        }
        s.touch();
        applyHeaderTimeout(s, msg);
        Reply reply = switch (s.getState()) {
            case CwmpSession.STATE_CREATED -> onCreated(s, msg);
            case CwmpSession.STATE_INFORMED -> onInformed(s, msg);
            default -> onRpcRunning(s, msg);
        };
        // 只有合法报文（200 且会话未收尾）才续期；被拒的报文（4xx/异常）不给会话续命
        if (reply.httpStatus() == 200 && !s.isEnded()) {
            lifecycle.renew(s);
        }
        return reply;
    }

    /** 持有 currentSn 锁的前提下处理另一 SN 的新 Inform：条纹锁不同桶时嵌套取锁有 ABBA 死锁风险，
     *  改 tryLock 限时获取，取不到让设备重发（无 Cookie 走正常入口，不再嵌套） */
    private Reply newInformNestedSafe(String currentSn, String newSn, SoapMessage msg, String remoteAddr) {
        if (newSn.equals(currentSn) || registry.lockFor(newSn) == registry.lockFor(currentSn)) {
            // 同一把条纹锁（同桶）：ReentrantLock 可重入，直接处理
            return handleNewInform(newSn, msg, remoteAddr);
        }
        try {
            if (registry.lockFor(newSn).tryLock(3, TimeUnit.SECONDS)) {
                try {
                    return handleNewInform(newSn, msg, remoteAddr);
                } finally {
                    registry.lockFor(newSn).unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Logs.warn(Logs.Module.CPE, "跨 SN Inform 锁竞争，回 400 让设备重发", "持锁SN", currentSn, "报文SN", newSn);
        return new Reply(400, SoapBuilder.acsFault(8000, "Session conflict, resend Inform"), null);
    }

    /** 新 Inform 建会话：顶替旧会话并完整收尾（返回值带回旧会话，不再依赖共享字段） */
    private Reply handleNewInform(String sn, SoapMessage msg, String remoteAddr) {
        SessionRegistry.Created created = registry.create(sn);
        CwmpSession session = created.session();
        // 同 SN 旧会话被顶替时必须完整收尾：在飞任务回队、参数落库、快照刷新。
        if (created.superseded() != null) {
            endSession(created.superseded(), "设备重新 Inform，会话被新会话顶替");
        }
        session.setRemoteAddr(remoteAddr);
        session.touch();
        applyHeaderTimeout(session, msg);
        lifecycle.register(session);
        Logs.debug(Logs.Module.CPE, "会话来源地址已记录", "sn", sn, "地址", remoteAddr);
        Reply reply = onCreated(session, msg);
        if (reply.httpStatus() == 200 && !session.isEnded()) {
            lifecycle.renew(session);
        }
        return reply;
    }

    // ==================== state 0：等 Inform ====================

    private Reply onCreated(CwmpSession s, SoapMessage msg) {
        if (!"Inform".equals(msg.getName())) {
            return new Reply(400, SoapBuilder.acsFault(8000, "Inform required"), null);
        }
        try {
            handleInform(s, msg);
        } catch (Exception e) {
            // Inform 处理异常（DB 抖动等）：收尾本次会话回 500，设备稍后重试；
            Logs.error(Logs.Module.CPE, "Inform 处理失败，会话终止待设备重试", e, "sn", s.getSn());
            endSession(s, "Inform 处理异常");
            return Reply.empty(500);
        }
        s.setState(CwmpSession.STATE_INFORMED);
        // InformResponse 的报文头 ID 必须回传设备请求里的 ID（TR-069 3.1.3），
        return new Reply(200, SoapBuilder.informResponse(
                msg.getId() == null || msg.getId().isBlank() ? sessionRpcId(s) : msg.getId()),
                s.getSessionId());
    }

    private void handleInform(CwmpSession s, SoapMessage msg) {
        s.setDeviceId(msg.getOui() + "-" + msg.getProductClass() + "-" + s.getSn());
        s.setManufacturer(msg.getManufacturer());
        s.setOui(msg.getOui());
        s.setProductClass(msg.getProductClass());
        s.setEvents(msg.getEvents());

        // 库里的存量参数树先灌进内存，再叠加 Inform 自带参数
        //（Inform 参数只更新值/类型，不覆盖 GPN 的 writable，后者是只读拦截的依据）
        s.getData().loadAll(store.loadParams(s.getSn()));
        for (CpeParam p : msg.getParams()) {
            s.getData().putInform(p);
        }
        String root = BusinessPaths.detectRoot(s.getData(), props.getDefaultRootNode());
        s.setConnectionRequestUrl(s.getData().value(root + "ManagementServer.ConnectionRequestURL"));
        s.setSoftwareVersion(s.getData().value(root + "DeviceInfo.SoftwareVersion"));
        s.setHardwareVersion(s.getData().value(root + "DeviceInfo.HardwareVersion"));

        // 事件落台账（0 BOOTSTRAP / 1 BOOT / 2 PERIODIC / 4 VALUE CHANGE / 6 CONNECTION REQUEST /
        // 8 DIAGNOSTICS COMPLETE；7 TRANSFER COMPLETE 走 handleTransferComplete 归因，不经此处）。
        // 匹配口径参照 Netcwmp event.c：按 "码 前缀" 区分，"1 BOOT" 不会误吞 "11 M REBOOT"。
        long now = System.currentTimeMillis();
        Long boot = null;
        Long bootstrap = null;
        Long periodic = null;
        Long valueChange = null;
        Long connReq = null;
        Long diag = null;
        for (String ev : msg.getEvents()) {
            if (ev.startsWith("1 BOOT")) {
                boot = now;
            } else if (ev.startsWith("0 BOOTSTRAP")) {
                bootstrap = now;
                s.setBootstrap(true);
                // 恢复出厂/首次建档：设备侧管理参数已被清空，放开初始化标记重新下发
                // （CR 账密、周期上报间隔不重发的话，反向连接踢不动、设备也不再按期上报）
                try {
                    store.clearInitProvisioned(s.getSn());
                    initProvisionService.resetForBootstrap(s.getSn());
                    Logs.info(Logs.Module.CPE, "检测到 0 BOOTSTRAP，初始化参数将重新下发", "sn", s.getSn());
                } catch (Exception e) {
                    Logs.warn(Logs.Module.CPE, "BOOTSTRAP 重置初始化标记失败", "sn", s.getSn(), "原因", e.toString());
                }
            } else if (ev.startsWith("2 PERIODIC")) {
                periodic = now;
            } else if (ev.startsWith("4 VALUE CHANGE")) {
                valueChange = now;
            } else if (ev.startsWith("6 CONNECTION REQUEST")) {
                connReq = now;
            } else if (ev.startsWith("8 DIAGNOSTICS COMPLETE")) {
                diag = now;
            } else if (!ev.isBlank()) {
                Logs.debug(Logs.Module.CPE, "未特殊处理的事件码", "sn", s.getSn(), "事件", ev);
            }
        }
        if (connReq != null) {
            // CR 唤醒闭环观测点：反向连接踢成功 → 设备带 6 CONNECTION REQUEST 事件 Inform 上来
            Logs.info(Logs.Module.CPE, "设备经 Connection Request 唤醒上报", "sn", s.getSn(),
                    "地址", s.getRemoteAddr());
        } else if (registry.consumeKick(s.getSn())) {
            // 设备没带 6 事件但 Inform 落在唤醒窗口内：同样归因为 CR 唤醒（兼容不规范设备）
            Logs.info(Logs.Module.CPE, "设备 Inform 落在唤醒窗口内，归因为 Connection Request 触发",
                    "sn", s.getSn(), "事件", msg.getEvents());
        }
        if (diag != null) {
            Logs.info(Logs.Module.CPE, "设备诊断完成上报", "sn", s.getSn(), "事件", msg.getEvents());
        }

        // 台账 upsert：Inform 一到就刷新设备档案，并同步覆写 Redis 档案缓存
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sn", s.getSn());
        row.put("oui", s.getOui());
        row.put("manufacturer", s.getManufacturer());
        row.put("product_class", s.getProductClass());
        row.put("hardware_version", s.getHardwareVersion());
        row.put("software_version", s.getSoftwareVersion());
        row.put("connection_request_url", s.getConnectionRequestUrl());
        row.put("root_node", root);
        row.put("last_inform_ms", now);
        store.upsertDevice(row);
        infoCache.put(s.getSn(), row);
        store.markEvents(s.getSn(), boot, bootstrap, periodic, valueChange, connReq, diag);

        // 绑定机型模板（品牌+型号+版本+运营商），有则挂到会话上供组装用
        modelProfileService.attachModel(s);
        // 首次上报：查库判断是否已下发反向连接账密与周期上报，未下发则本会话立即下发
        initProvisionService.provisionIfNeeded(s);
        // 每次上报：有参数树就排一条轻量 GPV 刷 WAN/WIFI/SIP 最新值（差异落库）
        modelProfileService.refreshBizInSession(s);

        // 周期 Inform 是常态流量（万级设备 × 每 5 分钟一条）：降为 debug，异常/首次/CR 仍打 info
        boolean routineInform = !msg.getEvents().isEmpty()
                && msg.getEvents().stream().allMatch(ev -> ev.startsWith("2 PERIODIC"));
        if (routineInform) {
            Logs.debug(Logs.Module.CPE, "设备上报 Inform（周期）", "sn", s.getSn(),
                    "事件", msg.getEvents(), "根节点", root);
        } else {
            Logs.info(Logs.Module.CPE, "设备上报 Inform", "sn", s.getSn(),
                    "厂商", s.getManufacturer(), "型号", s.getProductClass(),
                    "事件", msg.getEvents(), "根节点", root,
                    "机型模板", s.getModelRow() == null ? "无" : s.getModelRow().get("id"));
        }
        if (msg.isHoldRequests()) {
            Logs.debug(Logs.Module.CPE, "设备声明 HoldRequests（收到 ACS 应答前暂缓新请求）", "sn", s.getSn());
        }
    }

    // ==================== state 1：等空 POST / TransferComplete ====================

    private Reply onInformed(CwmpSession s, SoapMessage msg) {
        if (msg.isEmptyPost()) {
            // 设备已声明本会话不再收请求（TR-069 A.3.2.1）：不再派 RPC，直接收尾
            if (msg.isNoMoreRequests()) {
                endSession(s, "设备声明无后续请求(NoMoreRequests)");
                return Reply.empty(204);
            }
            // 设备就绪，取下一个任务展开成 RPC；没有活干就收尾
            RpcRequest next = nextRpc(s);
            if (next == null) {
                endSession(s, "无待执行任务");
                return Reply.empty(204);
            }
            s.setState(CwmpSession.STATE_RPC_RUNNING);
            return new Reply(200, SoapBuilder.request(next), s.getSessionId());
        }
        if (isTransferComplete(msg)) {
            handleTransferComplete(s, msg);
            // 一个 HTTP 往返只能带一个 SOAP 报文：先应答 TC，设备发下一个空 POST 再继续派 RPC。
            return new Reply(200, tcResponse(msg,
                    msg.getId() == null || msg.getId().isBlank() ? sessionRpcId(s) : msg.getId()),
                    s.getSessionId());
        }
        if ("GetRPCMethods".equals(msg.getName())) {
            return new Reply(200, SoapBuilder.getRpcMethodsResponse(
                    msg.getId() == null ? sessionRpcId(s) : msg.getId(),
                    List.of("Inform", "GetRPCMethods", "TransferComplete")), s.getSessionId());
        }
        return new Reply(200, SoapBuilder.acsFault(8000, "Method not supported: " + msg.getName()),
                s.getSessionId());
    }

    // ==================== state 2：RPC 在飞 ====================

    private Reply onRpcRunning(CwmpSession s, SoapMessage msg) {
        // 个别设备会把 TransferComplete 夹在会话中途发，同样归因
        if (isTransferComplete(msg)) {
            handleTransferComplete(s, msg);
            // 先应答 TC，下一个空 POST 再继续派 RPC（一个响应只能带一个 SOAP 报文）
            return new Reply(200, tcResponse(msg,
                    msg.getId() == null || msg.getId().isBlank() ? sessionRpcId(s) : msg.getId()),
                    s.getSessionId());
        }
        applyResponse(s, msg);
        if (s.isEnded()) {
            return Reply.empty(204);
        }
        // 设备声明不再收请求：不派下一个 RPC（TR-069 A.3.2.1），剩余任务留在队列等下个会话
        if (msg.isNoMoreRequests()) {
            endSession(s, "设备声明无后续请求(NoMoreRequests)");
            return Reply.empty(204);
        }
        RpcRequest next = nextRpc(s);
        if (next == null) {
            endSession(s, "任务执行完毕");
            return Reply.empty(204);
        }
        return new Reply(200, SoapBuilder.request(next), s.getSessionId());
    }

    /** 应用 CPE 对在飞 RPC 的响应或 Fault */
    private void applyResponse(CwmpSession s, SoapMessage msg) {
        RpcRequest req = s.getPending();
        TaskRun cur = s.getCurrent();
        s.setPending(null);
        if (req == null || cur == null) {
            return;
        }
        if (msg.isFault()) {
            onTaskFault(s, cur, msg, req);
            return;
        }
        String root = BusinessPaths.detectRoot(s.getData(), props.getDefaultRootNode());
        switch (req.getKind()) {
            case GPN -> {
                // GPN 只带回结构信息（object/writable），不带值：合并时保留已有 value/type，
                for (CpeParam p : msg.getResults()) {
                    CpeParam known = s.getData().get(p.path());
                    if (known == null) {
                        s.getData().put(new CpeParam(p.path(), null, null, p.object(), p.writable(), p.ts()));
                    } else if (known.object() != p.object() || known.writable() != p.writable()) {
                        s.getData().put(new CpeParam(p.path(), known.value(), known.type(),
                                p.object(), p.writable(), known.ts()));
                    }
                }
                // 全量 GPN → 落库时整树重写
                if (!req.isNextLevel() && req.getPath() != null && req.getPath().equals(root)) {
                    s.getData().markFullSync();
                }
                // 扫描步骤结束：业务叶子补 GPV（能力探测/业务采集的第二阶段）
                if (TaskPlanner.TYPE_REFRESH_BIZ.equals(cur.getType())
                        && !planner.hasRemainingPurpose(cur, "scan")) {
                    planner.appendGpv(cur, bizLeaves(s, root), "collect");
                }
                if (TaskPlanner.TYPE_ADAPT_PROBE.equals(cur.getType())
                        && !planner.hasRemainingPurpose(cur, "probe")) {
                    planner.appendGpv(cur, bizLeaves(s, root), "collect");
                }
            }
            case GPV -> {
                for (CpeParam p : msg.getResults()) {
                    if (s.getData().has(p.path())) {
                        s.getData().putValue(p.path(), p.value(), p.type(), p.ts());
                    } else {
                        s.getData().put(new CpeParam(p.path(), p.value(), p.type(), false, true, p.ts()));
                    }
                }
                // 设备没回的参数：不再写空串进参数树——历史实现无条件 put 必标脏，
                if (req.getNames() != null) {
                    List<String> missing = new ArrayList<>();
                    for (String n : req.getNames()) {
                        if (!s.getData().has(n)) {
                            missing.add(n);
                        }
                    }
                    if (!missing.isEmpty()) {
                        Logs.warn(Logs.Module.CPE, "设备未回报部分请求参数", "sn", s.getSn(),
                                "任务", cur.getTaskId(), "参数", missing);
                    }
                }
                // 回读校验步：把设备实际回报值与任务下发值比对，偏差记进任务结果；
                if ("verify".equals(s.getPendingPurpose())) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> expected = cur.getCollected().containsKey("verifyExpected")
                            ? (Map<String, String>) cur.getCollected().get("verifyExpected")
                            : spvExpected(cur.getParams());
                    cur.getCollected().put("verifyExpected", expected);
                    @SuppressWarnings("unchecked")
                    Map<String, String> deviations = (Map<String, String>) cur.getCollected()
                            .computeIfAbsent("readbackDeviations", k -> new LinkedHashMap<String, String>());
                    for (CpeParam p : msg.getResults()) {
                        String exp = expected.get(p.path());
                        if (exp != null && p.value() != null && !exp.equals(p.value())) {
                            deviations.put(p.path(), "下发值[" + exp + "] 设备值[" + p.value() + "]");
                        }
                    }
                }
            }
            case SPV -> {
                // 下发受理成功（Status 0=已生效 / 1=待重启生效）就把新值写回会话参数树：
                if ((msg.getStatus() == 0 || msg.getStatus() == 1) && req.getValues() != null) {
                    long ts = System.currentTimeMillis();
                    for (RpcRequest.NameValue nv : req.getValues()) {
                        s.getData().putValue(nv.name(), nv.value(),
                                nv.type() == null ? "string" : nv.type(), ts);
                    }
                }
                if (cur.peek() != null) {
                    // 还有回读校验步（verify GPV）：受理结果先暂存，等回读步走完统一收口
                    cur.getCollected().put("applied", msg.getStatus() == 0);
                    cur.getCollected().put("status", msg.getStatus());
                    Logs.info(Logs.Module.CPE, "参数下发已受理，等待回读校验", "sn", s.getSn(),
                            "任务", cur.getTaskId(), "参数数",
                            req.getValues() == null ? 0 : req.getValues().size(), "待重启", msg.getStatus() == 1);
                } else {
                    completeTask(s, cur, Map.of("applied", msg.getStatus() == 0, "status", msg.getStatus()));
                    Logs.info(Logs.Module.CPE, "参数下发完成", "sn", s.getSn(), "任务", cur.getTaskId(),
                            "参数数", req.getValues() == null ? 0 : req.getValues().size(),
                            "待重启", msg.getStatus() == 1);
                }
                if (Boolean.TRUE.equals(cur.getParams().get(InitProvisionService.PARAM_FLAG))) {
                    // 首次初始化下发成功：台账打标，后续上报不再重复下发
                    store.markInitProvisioned(s.getSn());
                    Logs.info(Logs.Module.CPE, "首次初始化参数下发成功，已落库", "sn", s.getSn(),
                            "任务", cur.getTaskId());
                }
            }
            case ADD_OBJECT -> {
                completeTask(s, cur, Map.of("instanceNumber", msg.getInstanceNumber(), "status", msg.getStatus()));
                s.getData().put(new CpeParam(pathOf(req.getPath(), msg.getInstanceNumber()), null, null, true,
                        true, System.currentTimeMillis()));
            }
            case DELETE_OBJECT -> {
                // 删除成功：该对象实例子树从内存树摘除并打墓碑，落库时同步清行。
                // TR-069 Table 32：Status=1 表示已提交但会话结束（或重启）后才真正生效，
                // 此时设备侧同会话内 GPN/GPV 也应表现为已删除（内存树摘除语义一致），
                // 记录 status 供上层排查，且不应追加 Reboot（CPE 会自行重启）。
                List<String> removed = s.getData().removeSubtree(req.getPath());
                int status = msg == null ? 0 : msg.getStatus();
                completeTask(s, cur, Map.of("deleted", req.getPath(), "removedParams", removed.size(),
                        "applied", status == 0, "pendingReboot", status == 1));
            }
            case REBOOT -> {
                completeTask(s, cur, Map.of("reboot", true));
                Logs.info(Logs.Module.CPE, "设备重启指令已受理", "sn", s.getSn(), "任务", cur.getTaskId());
            }
            case FACTORY_RESET -> completeTask(s, cur, Map.of("factoryReset", true));
            case DOWNLOAD -> completeTask(s, cur, Map.of(
                    "download", true,
                    "url", String.valueOf(req.getUrl()),
                    // Status 1=进行中：最终成败由 7 TRANSFER COMPLETE 按 CommandKey 归因回写
                    "status", msg.getStatus(),
                    "startTime", String.valueOf(msg.getStartTime()),
                    "completeTime", String.valueOf(msg.getCompleteTime())));
            case GET_RPC_METHODS -> completeTask(s, cur, Map.of("methods", msg.getMethods()));
            case CUSTOM -> completeTask(s, cur, Map.of());
        }
        if (cur.finished() && !cur.isCompleted()) {
            completeTask(s, cur, Map.of());
            s.setCurrent(null);
        }
    }

    private void onTaskFault(CwmpSession s, TaskRun cur, SoapMessage msg, RpcRequest req) {
        StringBuilder detail = new StringBuilder(msg.getFaultString() == null ? "" : msg.getFaultString());
        if (!msg.getSetFaults().isEmpty()) {
            detail.append('；');
            msg.getSetFaults().forEach(f -> detail.append(f.name()).append(':').append(f.faultString()).append(' '));
        }
        String purpose = s.getPendingPurpose();
        if ("scan".equals(purpose) || "probe".equals(purpose)) {
            // 扫描/探测步骤失败（常见 9005：设备没有这棵业务子树，如 Services.VoiceService.）：
            Logs.warn(Logs.Module.CPE, "扫描步骤失败，跳过继续", "sn", s.getSn(), "任务", cur.getTaskId(),
                    "步骤", purpose, "错误码", msg.getFaultCode(), "详情", detail.toString());
            String root = BusinessPaths.detectRoot(s.getData(), props.getDefaultRootNode());
            if (!planner.hasRemainingPurpose(cur, "scan") && !planner.hasRemainingPurpose(cur, "probe")) {
                planner.appendGpv(cur, bizLeaves(s, root), "collect");
            }
            return;
        }
        if ("verify".equals(purpose)) {
            // 回读校验步失败不推翻下发结果：SPV 已被设备受理（Status 0/1），
            Logs.warn(Logs.Module.CPE, "回读校验步失败，按受理结果收口", "sn", s.getSn(), "任务", cur.getTaskId(),
                    "错误码", msg.getFaultCode(), "详情", detail.toString());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("applied", Boolean.TRUE.equals(cur.getCollected().get("applied")));
            Object st = cur.getCollected().get("status");
            result.put("status", st instanceof Number n ? n.intValue() : 0);
            result.put("readback", "skipped(" + msg.getFaultCode() + ")");
            store.updateTask(cur.getTaskId(), "success", null, null, toJson(result));
            lifecycle.rpcIdle(s.getSn(), cur.getTaskId());
            cur.markCompleted();
            s.setCurrent(null);
            return;
        }
        // GPV 采集步骤遇到 9005/9003（某个参数名不被设备接受）：拆分批次定位坏名，单个坏名直接丢弃，
        if ("collect".equals(purpose) && req != null && req.getKind() == RpcRequest.Kind.GPV
                && (msg.getFaultCode() == 9005 || msg.getFaultCode() == 9003)) {
            List<String> names = req.getNames();
            if (names != null && names.size() > 1) {
                int mid = names.size() / 2;
                String key = "task-" + cur.getTaskId();
                cur.getSteps().addFirst(new TaskRun.RpcStep(
                        RpcRequest.gpv(new ArrayList<>(names.subList(mid, names.size())), key), "collect"));
                cur.getSteps().addFirst(new TaskRun.RpcStep(
                        RpcRequest.gpv(new ArrayList<>(names.subList(0, mid)), key), "collect"));
                Logs.debug(Logs.Module.CPE, "参数采集含无效名，拆分重试: sn={} 参数数={} 错误码={}",
                        s.getSn(), names.size(), msg.getFaultCode());
                return;
            }
            if (names != null) {
                // 记住这颗坏名：后续采集直接过滤，不必每轮重新二分
                modelProfileService.rememberBadParam(s.getSn(), names.get(0));
                // 采集过程中的正常容错（坏名已持久化到 badParams，后续采集直接过滤），
                // 大树探测时每颗坏名一条 WARN 会刷屏；需要排查时开 debug 即可
                Logs.debug(Logs.Module.CPE, "跳过设备不认的参数", "sn", s.getSn(), "任务", cur.getTaskId(),
                        "参数", names, "错误码", msg.getFaultCode());
                return;
            }
        }
        if (Boolean.TRUE.equals(cur.getParams().get(InitProvisionService.PARAM_FLAG))) {
            // 首次初始化下发失败：放开去重，下次 Inform 再试
            initProvisionService.retryLater(s.getSn());
        }
        store.updateTask(cur.getTaskId(), "fail", String.valueOf(msg.getFaultCode()), detail.toString(), null);
        lifecycle.rpcIdle(s.getSn(), cur.getTaskId());
        cur.markCompleted();
        s.setCurrent(null);
        Logs.warn(Logs.Module.CPE, "任务执行失败", "sn", s.getSn(), "任务", cur.getTaskId(),
                "类型", cur.getType(), "错误码", msg.getFaultCode(), "详情", detail.toString());
    }

    /** 业务域叶子（滤掉该设备不认的参数名），补 GPV 用 */
    private List<String> bizLeaves(CwmpSession s, String root) {
        List<String> leaves = new ArrayList<>();
        for (String br : BusinessPaths.businessRoots(root)) {
            leaves.addAll(s.getData().leaves(br));
        }
        Set<String> bad = modelProfileService.badParamsOf(s.getSn());
        if (!bad.isEmpty()) {
            leaves.removeIf(bad::contains);
        }
        return leaves;
    }

    /** TransferComplete / AutonomousTransferComplete（Table 44/45，后者无 CommandKey）统一定位 */
    private static boolean isTransferComplete(SoapMessage msg) {
        return "TransferComplete".equals(msg.getName())
                || "AutonomousTransferComplete".equals(msg.getName());
    }

    /** 按 TC 类型回对应响应（Table 45 两个响应均无参数，Autonomous 也必须应答：Table 5 Responding REQUIRED） */
    private static String tcResponse(SoapMessage msg, String id) {
        return "AutonomousTransferComplete".equals(msg.getName())
                ? SoapBuilder.autonomousTransferCompleteResponse(id)
                : SoapBuilder.transferCompleteResponse(id);
    }

    private void handleTransferComplete(CwmpSession s, SoapMessage msg) {
        String key = msg.getCommandKey() == null ? "" : msg.getCommandKey();
        long taskId = 0;
        var m = TASK_KEY.matcher(key);
        if (m.find()) {
            try {
                taskId = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                // 超过 long 上界的异常 CommandKey 不归因，也不能让整个请求 500（设备会重发 TC 死循环）
                Logs.warn(Logs.Module.CPE, "TransferComplete CommandKey 非法，忽略归因",
                        "sn", s == null ? "?" : s.getSn(), "commandKey", key);
            }
        }
        if (taskId > 0) {
            boolean ok = msg.getTcFaultCode() == 0;
            store.updateTask(taskId, ok ? "success" : "fail",
                    ok ? null : String.valueOf(msg.getTcFaultCode()),
                    ok ? null : msg.getTcFaultString(), null);
            Logs.info(Logs.Module.CPE, "TransferComplete", "sn", s == null ? "?" : s.getSn(), "任务", taskId,
                    "错误码", msg.getTcFaultCode(), "完成时间", msg.getEndValue());
        } else if (!key.isBlank()) {
            // 非本 ACS 产生的 CommandKey（外部平台任务等）：归因不了就明确记日志，不再静默丢弃
            Logs.debug(Logs.Module.CPE, "TransferComplete 非本 ACS 任务，忽略归因",
                    "sn", s == null ? "?" : s.getSn(), "commandKey", key, "错误码", msg.getTcFaultCode());
        } else if ("AutonomousTransferComplete".equals(msg.getName())) {
            // 设备自主传输（非 ACS 任务触发，无 CommandKey）：Table 5 要求 ACS 必须应答，归因留观测
            Logs.info(Logs.Module.CPE, "设备自主传输完成（AutonomousTransferComplete）",
                    "sn", s == null ? "?" : s.getSn(),
                    "错误码", msg.getTcFaultCode(), "完成时间", msg.getEndValue());
        }
    }

    // ==================== RPC 取数（任务驱动） ====================

    /** 取下一条要发的 RPC；队列空则从库里装载排队任务 */
    private RpcRequest nextRpc(CwmpSession s) {
        while (true) {
            if (s.getCurrent() == null) {
                if (s.getQueue().isEmpty()) {
                    loadQueuedTasks(s);
                }
                if (s.getQueue().isEmpty()) {
                    return null;
                }
                s.setCurrent(s.getQueue().poll());
            }
            TaskRun cur = s.getCurrent();
            if (cur.finished()) {
                if (!cur.isCompleted()) {
                    completeTask(s, cur, Map.of());
                }
                s.setCurrent(null);
                continue;
            }
            if (s.getRpcCount() >= props.getMaxRpcCount()) {
                // 达到会话 RPC 上限：本任务打断点续跑标记回队（不判死），队列里排在其后的任务
                store.markTaskResume(cur.getTaskId());
                store.updateTask(cur.getTaskId(), "queued", null,
                        "会话 RPC 次数超限(" + props.getMaxRpcCount() + ")，待续跑", null);
                lifecycle.rpcIdle(s.getSn(), cur.getTaskId());
                s.setCurrent(null);
                // 会话还在进行中，立即反踢必被"设备已有活会话"自检挡掉，任务只能等 5 分钟周期 Inform；
                // 登记延迟反踢，等会话收尾后由 sweep 触发（与 endSession 回队续跑同一机制）
                delayedResumeKicks.put(s.getSn(), System.currentTimeMillis() + RESUME_KICK_DELAY_MS);
                return null;
            }
            TaskRun.RpcStep step = cur.poll();
            RpcRequest req = step.request();
            req.setId(s.getSessionId() + "-" + s.getRpcCount());
            s.setPending(req);
            s.setPendingPurpose(step.purpose());
            s.setRpcCount(s.getRpcCount() + 1);
            // 进度持久化（参考实现的逐 RPC 记录语义的轻量版）：复用每步必有的 updateTask
            store.updateTask(cur.getTaskId(), "running", null,
                    "进度 " + cur.getSentSteps() + "/" + (cur.getSentSteps() + cur.getSteps().size()), null);
            // Redis 会话镜像：rpcState → RUNNING，记 currentTaskId（跨 Inform 的任务执行状态，
            lifecycle.rpcRunning(s.getSn(), cur.getTaskId());
            return req;
        }
    }

    /** SPV 任务的期望值表（values Map 或 items 三元组两种入参形态），回读比对用 */
    @SuppressWarnings("unchecked")
    private Map<String, String> spvExpected(Map<String, Object> params) {
        Map<String, String> out = new LinkedHashMap<>();
        Object values = params.get("values");
        if (values instanceof Map<?, ?> m) {
            ((Map<Object, Object>) m).forEach((k, v) ->
                    out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        Object items = params.get("items");
        if (items instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> it && it.get("name") != null) {
                    out.put(String.valueOf(it.get("name")),
                            it.get("value") == null ? "" : String.valueOf(it.get("value")));
                }
            }
        }
        return out;
    }

    private void loadQueuedTasks(CwmpSession s) {
        String root = BusinessPaths.detectRoot(s.getData(), props.getDefaultRootNode());
        for (Map<String, Object> row : store.loadQueuedTasks(s.getSn())) {
            try {
                s.getQueue().add(planner.build(row, s.getData(), root, s.getModelParams()));
            } catch (Exception e) {
                long id = row.get("id") instanceof Number n ? n.longValue() : 0;
                store.updateTask(id, "fail", "0", "任务组装失败: " + e.getMessage(), null);
                Logs.error(Logs.Module.CPE, "任务组装失败", e, "sn", s.getSn(), "任务", id);
            }
        }
    }

    /** 任务收口：结果写 cpe_task；能力探测在这里做实测/申报比对 */
    private void completeTask(CwmpSession s, TaskRun cur, Map<String, Object> extra) {
        if (cur.isCompleted()) {
            return;
        }
        cur.markCompleted();
        Map<String, Object> result = new LinkedHashMap<>(extra);
        if (TaskPlanner.TYPE_ADAPT_PROBE.equals(cur.getType())) {
            String root = BusinessPaths.detectRoot(s.getData(), props.getDefaultRootNode());
            Map<String, Object> detected = capabilityDetector.detect(s.getData(), root);
            Map<String, Object> check = capabilityDetector.check(cur.getParams(), detected, s.getData(), root);
            result.putAll(check);
            result.put("collectStatus", s.getData().all().isEmpty() ? "empty" : "success");
            result.put("rootNode", root);
        } else if (TaskPlanner.TYPE_REFRESH_BIZ.equals(cur.getType())) {
            result.put("collectStatus", s.getData().all().isEmpty() ? "empty" : "success");
        } else if (TaskPlanner.TYPE_GPV.equals(cur.getType())) {
            // 控制台/业务轻量刷新的采集结果口径与 RefreshBusiness 一致
            result.put("collectStatus", s.getData().all().isEmpty() ? "empty" : "success");
        } else if (TaskPlanner.TYPE_SPV.equals(cur.getType())) {
            // SPV：合并受理结果与回读校验结论（readback: done=一致 / deviation=有偏差 / skipped=未校验）
            Map<String, Object> collected = new LinkedHashMap<>(cur.getCollected());
            collected.remove("verifyExpected");
            result.putAll(collected);
            Map<?, ?> dev = (Map<?, ?>) collected.get("readbackDeviations");
            result.put("readback", dev == null ? "skipped" : dev.isEmpty() ? "done" : "deviation");
        }
        store.updateTask(cur.getTaskId(), "success", null, null, toJson(result));
        lifecycle.rpcIdle(s.getSn(), cur.getTaskId());
    }

    // ==================== 会话收尾 ====================

    /** 会话收尾（幂等 + 异步安全）：任何一步 DB 异常都不能让会话残留在注册表里 */
    private void endSession(CwmpSession s, String reason) {
        if (s.isEnded()) {
            return;
        }
        s.setEnded(true);
        try {
            TaskRun cur = s.getCurrent();
            if (cur != null && !cur.finished() && !cur.isCompleted()) {
                if (TaskPlanner.WRITE_TYPES.contains(cur.getType())) {
                    // 写操作类（SPV/Reboot/Download/AddObject/DeleteObject/Custom）不回队重发：
                    store.updateTask(cur.getTaskId(), "fail", "interrupted",
                            "会话中断(" + reason + ")。写操作类不自动重发——设备可能已实际执行，请确认设备状态后重新下发",
                            null);
                    lifecycle.rpcIdle(s.getSn(), cur.getTaskId());
                    if (cur.getParams().get(InitProvisionService.PARAM_FLAG) != null) {
                        // 初始化下发随会话中断：放开 scheduled 去重，否则该 SN 本进程存活期内永不再下发
                        initProvisionService.retryLater(s.getSn());
                    }
                    Logs.warn(Logs.Module.CPE, "写操作任务随会话中断判失败", "sn", s.getSn(),
                            "任务", cur.getTaskId(), "类型", cur.getType(), "原因", reason);
                } else {
                    // 采集类任务回队续跑（与"排队等待设备上线"语义一致）。
                    store.markTaskResume(cur.getTaskId());
                    store.updateTask(cur.getTaskId(), "queued", null, "会话中断(" + reason + ")，待续跑", null);
                    // 延迟反踢：会话刚断（超时/异常收尾）时立即踢大概率打空，等 30s 由 sweep 触发；
                    // 设备若先自行 Inform 上来，队列里的任务会直接续跑，届时反踢发现活会话自动停
                    delayedResumeKicks.put(s.getSn(), System.currentTimeMillis() + RESUME_KICK_DELAY_MS);
                }
            }
            store.saveDeviceParams(s.getSn(), s.getData().all(), s.getData().dirtyPaths(),
                    s.getData().isFullSync(), s.getData().tombstones());
            // 先用本会话内存树识别（不等读库），认不出再走落库树 + 必要时排探测
            if (modelProfileService.detectIspFromPaths(s.getSn(), s.getData().all().keySet()) == null) {
                modelProfileService.detectIspAfterSession(s.getSn());
            }
            refreshSnapshot(s);
        } catch (Exception e) {
            Logs.error(Logs.Module.CPE, "会话收尾异常（会话已终止，残留任务由兜底回队）", e,
                    "sn", s.getSn(), "原因", reason);
        } finally {
            registry.remove(s);
            lifecycle.remove(s.getSn(), s.getSessionId());
        }
        Logs.info(Logs.Module.CPE, "会话结束", "sn", s.getSn(), "原因", reason,
                "RPC数", s.getRpcCount(), "参数数", s.getData().all().size());
    }

    /** 会话收尾时刷新业务快照落库（MySQL cpe_device_snapshot）；内容没变化就跳过写库 */
    private void refreshSnapshot(CwmpSession s) {
        if (s.getData().all().isEmpty()) {
            return;
        }
        try {
            String root = BusinessPaths.detectRoot(s.getData(), props.getDefaultRootNode());
            Map<String, Object> device = infoCache.get(s.getSn());
            if (device == null) {
                device = store.findDevice(s.getSn());
                infoCache.put(s.getSn(), device);
            }
            Map<String, Object> snap = snapshotService.snapshot(device, s.getData(), root, "success", null);
            String json = snapshotService.toJson(snap);
            if (json == null) {
                return;
            }
            if (json.equals(store.findSnapshot(s.getSn()))) {
                // 快照没差异：跳过，只有刷新时间随 /business 读取时动态算
                return;
            }
            store.saveSnapshot(s.getSn(), json);
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE, "业务快照刷新失败", "sn", s.getSn(), "原因", e.toString());
        }
    }

    /** 定时巡检兜底（Redis 过期事件为主、这里为辅）： */
    @Scheduled(fixedDelay = 10_000)
    public void sweep() {
        long defaultTimeout = props.getSessionTimeoutSeconds() * 1000L;
        for (CwmpSession s : List.copyOf(registry.allBySn().values())) {
            // 设备声明过 SessionTimeout 的会话用自己的口径
            long timeout = s.effectiveTimeout(defaultTimeout);
            if (!s.expired(timeout)) {
                continue;
            }
            try {
                locked(s.getSn(), () -> {
                    CwmpSession cur = registry.bySn(s.getSn());
                    if (cur == s && cur.expired(cur.effectiveTimeout(defaultTimeout))) {
                        endSession(cur, "会话空闲超时");
                    }
                    return null;
                });
            } catch (Exception e) {
                Logs.error(Logs.Module.CPE, "会话超时收尾异常", e, "sn", s.getSn());
            }
        }
        // 卡死任务回队兜底：排除活会话正在执行的任务——接力跑的大任务 started_at 不刷新，
        try {
            store.failStaleRunningTasks(props.getCollectTimeoutSeconds() * 1000L + 120_000L,
                    registry.runningTaskIds());
        } catch (Exception e) {
            Logs.error(Logs.Module.CPE, "卡死任务回队异常", e);
        }
        try {
            store.markOfflineStale(System.currentTimeMillis() - OFFLINE_AFTER_MS);
        } catch (Exception e) {
            Logs.error(Logs.Module.CPE, "离线标记异常", e);
        }
        // CR 唤醒标记过期清理：设备一直不上线时防条目滞留
        registry.sweepExpiredKicks();
        // 会话中断任务的延迟反踢：到期才踢（设备先上线的场景由 wakeWithRetry 的活会话自检兜住）
        if (!delayedResumeKicks.isEmpty()) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> e : delayedResumeKicks.entrySet()) {
                if (e.getValue() <= now && delayedResumeKicks.remove(e.getKey(), e.getValue())) {
                    try {
                        connectionRequestService.wakeWithRetry(e.getKey(), resolveCrUrl(e.getKey()), null, null);
                    } catch (Exception ex) {
                        Logs.warn(Logs.Module.CPE, "延迟反踢异常", "sn", e.getKey(), "原因", ex.getMessage());
                    }
                }
            }
        }
    }

    /** 会话收尾后反踢用的 CR URL：先查档案缓存，再落库兜底 */
    private String resolveCrUrl(String sn) {
        Map<String, Object> row = infoCache.get(sn);
        if (row == null || row.get("connection_request_url") == null) {
            row = store.findDevice(sn);
        }
        Object url = row == null ? null : row.get("connection_request_url");
        return url == null ? null : String.valueOf(url);
    }

    /** Redis 会话 TTL 过期回调（SessionExpiryListener）入口：按 sn 就地收尾 */
    public void endSessionBySn(String sn, String reason) {
        CwmpSession s = registry.bySn(sn);
        if (s == null) {
            return;
        }
        locked(sn, () -> {
            CwmpSession cur = registry.bySn(sn);
            // 双重确认：事件到达时设备可能刚恢复活动（TTL 已被续期），只收尾确实空闲的会话
            if (cur == s && cur.expired(cur.effectiveTimeout(props.getSessionTimeoutSeconds() * 1000L))) {
                endSession(cur, reason);
            }
            return null;
        });
    }

    // ==================== 供 REST 层调用 ====================

    public CwmpSession findSessionBySn(String sn) {
        return registry.bySn(sn);
    }

    /** REST 建任务入队用的 per-SN 锁（与会话处理同锁，消除入队/取任务的竞态） */
    public ReentrantLock lockFor(String sn) {
        return registry.lockFor(sn);
    }

    private String sessionRpcId(CwmpSession s) {
        return s.getSessionId() + "-0";
    }

    private String pathOf(String objectPath, long instance) {
        if (objectPath == null) {
            return "";
        }
        return objectPath + instance + ".";
    }

    private String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o == null ? Map.of() : o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
