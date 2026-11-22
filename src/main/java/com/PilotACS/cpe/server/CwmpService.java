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

    /** 一次 HTTP 交互的输出 */
    public record Reply(int httpStatus, String body, String newSessionId) {
        public static Reply empty(int status) {
            return new Reply(status, null, null);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long OFFLINE_AFTER_MS = 15 * 60 * 1000L;
    private static final Pattern TASK_KEY = Pattern.compile("task-(\\d+)");
    private static final long RESUME_KICK_DELAY_MS = 30_000L;

    private final Map<String, Long> delayedResumeKicks = new ConcurrentHashMap<>();

    private final CpeProperties props;
    private final SessionRegistry registry;
    private final CpeStore store;
    private final TaskPlanner planner;
    private final CapabilityDetector capabilityDetector;
    private final ModelProfileService modelProfileService;
    private final InitProvisionService initProvisionService;
    private final BusinessSnapshotService snapshotService;

    /** 处理 CPE 的一次 POST（body 为空表示空 POST） */
    public Reply processRequest(String body, String cookieSessionId, String remoteAddr) {
        SoapMessage msg;
        try {
            msg = SoapParser.parse(body);
        } catch (SoapParser.SoapException e) {
            Logs.warn(Logs.Module.CPE_ONLINE, "SOAP 报文解析失败", "原因", e.getMessage());
            return Reply.empty(400);
        }

        CwmpSession known = registry.bySessionId(cookieSessionId);
        boolean sessionBound = known != null || registry.byRemoteAddr(remoteAddr) != null;
        if (!sessionBound) {
            String limitId = limitId(msg, remoteAddr);
            if (!rateLimiter.tryAcquire(limitId)) {
                Logs.warn(Logs.Module.CPE_ONLINE, "报文限流丢弃（回 204 空响应）", "地址", remoteAddr,
                        "主体", limitId, "报文", msg.getName() == null ? "空POST" : msg.getName());
                return Reply.empty(204);
            }
        }
        if (known != null) {
            return locked(known.getSn(), () -> handleKnown(known, msg, remoteAddr));
        }
        if (msg.getName() == null) {
            CwmpSession fallback = registry.byRemoteAddr(remoteAddr);
            if (fallback != null && fallback.getState() == CwmpSession.STATE_INFORMED) {
                Logs.info(Logs.Module.CPE_ONLINE, "设备不带 Cookie，按来源地址找回会话", "sn", fallback.getSn(),
                        "地址", remoteAddr);
                return locked(fallback.getSn(), () -> handleKnown(fallback, msg, remoteAddr));
            }
            Logs.debug(Logs.Module.CPE_ONLINE, "无会话空 POST，回 204 空响应结束会话", "地址", remoteAddr);
            return Reply.empty(204);
        }
        if (!"Inform".equals(msg.getName())) {
            CwmpSession fallback = registry.byRemoteAddr(remoteAddr);
            if (fallback != null && fallback.getState() != CwmpSession.STATE_CREATED) {
                Logs.info(Logs.Module.CPE_ONLINE, "设备不带 Cookie，按来源地址找回会话", "sn", fallback.getSn(),
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

    /** 限流主体：有 SN 按 SN，否则按来源 IP */
    private String limitId(SoapMessage msg, String remoteAddr) {
        String sn = msg.getSerialNumber();
        return (sn != null && !sn.isBlank()) ? sn : ("ip:" + remoteAddr);
    }

    /** 设备声明的会话超时落到会话 */
    private void applyHeaderTimeout(CwmpSession s, SoapMessage msg) {
        if (msg.getSessionTimeoutSeconds() > 0) {
            long old = s.getTimeoutMs();
            s.setTimeoutMs(msg.getSessionTimeoutSeconds() * 1000L);
            if (old == 0) {
                Logs.debug(Logs.Module.CPE_OFFLINE, "设备声明会话超时", "sn", s.getSn(),
                        "秒", msg.getSessionTimeoutSeconds());
            }
        }
    }

    /** per-SN 串行处理锁 */
    private <T> T locked(String sn, Supplier<T> action) {
        ReentrantLock lock = registry.lockFor(sn);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** 既有会话处理 */
    private Reply handleKnown(CwmpSession s, SoapMessage msg, String remoteAddr) {
        if (!registry.isCurrent(s)) {
            if (isTransferComplete(msg)) {
                handleTransferComplete(s, msg);
                return new Reply(200, tcResponse(msg,
                        msg.getId() == null || msg.getId().isBlank() ? "acs-tc" : msg.getId()), null);
            }
            if ("Inform".equals(msg.getName()) && msg.getSerialNumber() != null
                    && !msg.getSerialNumber().isBlank()) {
                return newInformNestedSafe(s.getSn(), msg.getSerialNumber(), msg, remoteAddr);
            }
            if (msg.getName() == null) {
                Logs.debug(Logs.Module.CPE_ONLINE, "空 POST 命中已收尾会话，回 204 空响应结束", "sn", s.getSn());
                return Reply.empty(204);
            }
            return new Reply(400, SoapBuilder.acsFault(8000, "Session no longer valid"), null);
        }
        if ("Inform".equals(msg.getName()) && msg.getSerialNumber() != null
                && !msg.getSerialNumber().isBlank()) {
            if (!msg.getSerialNumber().equals(s.getSn())) {
                Logs.warn(Logs.Module.CPE_ONLINE, "Cookie 与报文 SN 不匹配，按报文 SN 重建会话",
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
        if (reply.httpStatus() == 200 && !s.isEnded()) {
            lifecycle.renew(s);
        }
        return reply;
    }

    /** 嵌套处理另一 SN 的新 Inform（tryLock 防死锁） */
    private Reply newInformNestedSafe(String currentSn, String newSn, SoapMessage msg, String remoteAddr) {
        if (newSn.equals(currentSn) || registry.lockFor(newSn) == registry.lockFor(currentSn)) {
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
        Logs.warn(Logs.Module.CPE_ONLINE, "跨 SN Inform 锁竞争，回 400 让设备重发", "持锁SN", currentSn, "报文SN", newSn);
        return new Reply(400, SoapBuilder.acsFault(8000, "Session conflict, resend Inform"), null);
    }

    /** 新 Inform 建会话（顶替旧会话） */
    private Reply handleNewInform(String sn, SoapMessage msg, String remoteAddr) {
        SessionRegistry.Created created = registry.create(sn);
        CwmpSession session = created.session();
        if (created.superseded() != null) {
            endSession(created.superseded(), "设备重新 Inform，会话被新会话顶替");
        }
        session.setRemoteAddr(remoteAddr);
        session.touch();
        applyHeaderTimeout(session, msg);
        lifecycle.register(session);
        Logs.debug(Logs.Module.CPE_ONLINE, "会话来源地址已记录", "sn", sn, "地址", remoteAddr);
        Reply reply = onCreated(session, msg);
        if (reply.httpStatus() == 200 && !session.isEnded()) {
            lifecycle.renew(session);
        }
        return reply;
    }

    private Reply onCreated(CwmpSession s, SoapMessage msg) {
        if (!"Inform".equals(msg.getName())) {
            return new Reply(400, SoapBuilder.acsFault(8000, "Inform required"), null);
        }
        try {
            handleInform(s, msg);
        } catch (Exception e) {
            Logs.error(Logs.Module.CPE_ONLINE, "Inform 处理失败，会话终止待设备重试", e, "sn", s.getSn());
            endSession(s, "Inform 处理异常");
            return Reply.empty(500);
        }
        s.setState(CwmpSession.STATE_INFORMED);
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

        s.getData().loadAll(store.loadParams(s.getSn()));
        for (CpeParam p : msg.getParams()) {
            s.getData().putInform(p);
        }
        String root = BusinessPaths.detectRoot(s.getData(), props.getDefaultRootNode());
        s.setConnectionRequestUrl(s.getData().value(root + "ManagementServer.ConnectionRequestURL"));
        s.setSoftwareVersion(s.getData().value(root + "DeviceInfo.SoftwareVersion"));
        s.setHardwareVersion(s.getData().value(root + "DeviceInfo.HardwareVersion"));

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
                try {
                    store.clearInitProvisioned(s.getSn());
                    initProvisionService.resetForBootstrap(s.getSn());
                    Logs.info(Logs.Module.CPE_ONLINE, "检测到 0 BOOTSTRAP，初始化参数将重新下发", "sn", s.getSn());
                } catch (Exception e) {
                    Logs.warn(Logs.Module.CPE_ONLINE, "BOOTSTRAP 重置初始化标记失败", "sn", s.getSn(), "原因", e.toString());
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
                Logs.debug(Logs.Module.CPE_ONLINE, "未特殊处理的事件码", "sn", s.getSn(), "事件", ev);
            }
        }
        if (connReq != null) {
            Logs.info(Logs.Module.CPE_ONLINE, "设备经 Connection Request 唤醒上报", "sn", s.getSn(),
                    "地址", s.getRemoteAddr());
        } else if (registry.consumeKick(s.getSn())) {
            Logs.info(Logs.Module.CPE_ONLINE, "设备 Inform 落在唤醒窗口内，归因为 Connection Request 触发",
                    "sn", s.getSn(), "事件", msg.getEvents());
        }
        if (diag != null) {
            Logs.info(Logs.Module.CPE_ONLINE, "设备诊断完成上报", "sn", s.getSn(), "事件", msg.getEvents());
        }

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

        modelProfileService.attachModel(s);
        initProvisionService.provisionIfNeeded(s);
        modelProfileService.refreshBizInSession(s);

        boolean routineInform = !msg.getEvents().isEmpty()
                && msg.getEvents().stream().allMatch(ev -> ev.startsWith("2 PERIODIC"));
        if (routineInform) {
            Logs.debug(Logs.Module.CPE_ONLINE, "设备上报 Inform（周期）", "sn", s.getSn(),
                    "事件", msg.getEvents(), "根节点", root);
        } else {
            Logs.info(Logs.Module.CPE_ONLINE, "设备上报 Inform", "sn", s.getSn(),
                    "厂商", s.getManufacturer(), "型号", s.getProductClass(),
                    "事件", msg.getEvents(), "根节点", root,
                    "机型模板", s.getModelRow() == null ? "无" : s.getModelRow().get("id"));
        }
        if (msg.isHoldRequests()) {
            Logs.debug(Logs.Module.CPE_ONLINE, "设备声明 HoldRequests（收到 ACS 应答前暂缓新请求）", "sn", s.getSn());
        }
    }

    private Reply onInformed(CwmpSession s, SoapMessage msg) {
        if (msg.isEmptyPost()) {
            if (msg.isNoMoreRequests()) {
                endSession(s, "设备声明无后续请求(NoMoreRequests)");
                return Reply.empty(204);
            }
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

    private Reply onRpcRunning(CwmpSession s, SoapMessage msg) {
        if (isTransferComplete(msg)) {
            handleTransferComplete(s, msg);
            return new Reply(200, tcResponse(msg,
                    msg.getId() == null || msg.getId().isBlank() ? sessionRpcId(s) : msg.getId()),
                    s.getSessionId());
        }
        applyResponse(s, msg);
        if (s.isEnded()) {
            return Reply.empty(204);
        }
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
                for (CpeParam p : msg.getResults()) {
                    CpeParam known = s.getData().get(p.path());
                    if (known == null) {
                        s.getData().put(new CpeParam(p.path(), null, null, p.object(), p.writable(), p.ts()));
                    } else if (known.object() != p.object() || known.writable() != p.writable()) {
                        s.getData().put(new CpeParam(p.path(), known.value(), known.type(),
                                p.object(), p.writable(), known.ts()));
                    }
                }
                if (!req.isNextLevel() && req.getPath() != null && req.getPath().equals(root)) {
                    s.getData().markFullSync();
                }
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
                if (req.getNames() != null) {
                    List<String> missing = new ArrayList<>();
                    for (String n : req.getNames()) {
                        if (!s.getData().has(n)) {
                            missing.add(n);
                        }
                    }
                    if (!missing.isEmpty()) {
                        Logs.warn(Logs.Module.CPE_PUSH, "设备未回报部分请求参数", "sn", s.getSn(),
                                "任务", cur.getTaskId(), "参数", missing);
                    }
                }
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
                if ((msg.getStatus() == 0 || msg.getStatus() == 1) && req.getValues() != null) {
                    long ts = System.currentTimeMillis();
                    for (RpcRequest.NameValue nv : req.getValues()) {
                        s.getData().putValue(nv.name(), nv.value(),
                                nv.type() == null ? "string" : nv.type(), ts);
                    }
                }
                if (cur.peek() != null) {
                    cur.getCollected().put("applied", msg.getStatus() == 0);
                    cur.getCollected().put("status", msg.getStatus());
                    Logs.info(Logs.Module.CPE_PUSH, "参数下发已受理，等待回读校验", "sn", s.getSn(),
                            "任务", cur.getTaskId(), "参数数",
                            req.getValues() == null ? 0 : req.getValues().size(), "待重启", msg.getStatus() == 1);
                } else {
                    completeTask(s, cur, Map.of("applied", msg.getStatus() == 0, "status", msg.getStatus()));
                    Logs.info(Logs.Module.CPE_PUSH, "参数下发完成", "sn", s.getSn(), "任务", cur.getTaskId(),
                            "参数数", req.getValues() == null ? 0 : req.getValues().size(),
                            "待重启", msg.getStatus() == 1);
                }
                if (Boolean.TRUE.equals(cur.getParams().get(InitProvisionService.PARAM_FLAG))) {
                    store.markInitProvisioned(s.getSn());
                    Logs.info(Logs.Module.CPE_PUSH, "首次初始化参数下发成功，已落库", "sn", s.getSn(),
                            "任务", cur.getTaskId());
                }
            }
            case ADD_OBJECT -> {
                completeTask(s, cur, Map.of("instanceNumber", msg.getInstanceNumber(), "status", msg.getStatus()));
                s.getData().put(new CpeParam(pathOf(req.getPath(), msg.getInstanceNumber()), null, null, true,
                        true, System.currentTimeMillis()));
            }
            case DELETE_OBJECT -> {
                List<String> removed = s.getData().removeSubtree(req.getPath());
                int status = msg == null ? 0 : msg.getStatus();
                completeTask(s, cur, Map.of("deleted", req.getPath(), "removedParams", removed.size(),
                        "applied", status == 0, "pendingReboot", status == 1));
            }
            case REBOOT -> {
                completeTask(s, cur, Map.of("reboot", true));
                Logs.info(Logs.Module.CPE_PUSH, "设备重启指令已受理", "sn", s.getSn(), "任务", cur.getTaskId());
            }
            case FACTORY_RESET -> completeTask(s, cur, Map.of("factoryReset", true));
            case DOWNLOAD -> completeTask(s, cur, Map.of(
                    "download", true,
                    "url", String.valueOf(req.getUrl()),
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
            Logs.warn(Logs.Module.CPE_PUSH, "扫描步骤失败，跳过继续", "sn", s.getSn(), "任务", cur.getTaskId(),
                    "步骤", purpose, "错误码", msg.getFaultCode(), "详情", detail.toString());
            String root = BusinessPaths.detectRoot(s.getData(), props.getDefaultRootNode());
            if (!planner.hasRemainingPurpose(cur, "scan") && !planner.hasRemainingPurpose(cur, "probe")) {
                planner.appendGpv(cur, bizLeaves(s, root), "collect");
            }
            return;
        }
        if ("verify".equals(purpose)) {
            Logs.warn(Logs.Module.CPE_PUSH, "回读校验步失败，按受理结果收口", "sn", s.getSn(), "任务", cur.getTaskId(),
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
        if ("collect".equals(purpose) && req != null && req.getKind() == RpcRequest.Kind.GPV
                && (msg.getFaultCode() == 9005 || msg.getFaultCode() == 9003 || msg.getFaultCode() == 9002
                    || (msg.getFaultString() != null
                        && msg.getFaultString().toLowerCase().contains("internal error")))) {
            List<String> names = req.getNames();
            if (names != null && names.size() > 1) {
                int mid = names.size() / 2;
                String key = "task-" + cur.getTaskId();
                cur.getSteps().addFirst(new TaskRun.RpcStep(
                        RpcRequest.gpv(new ArrayList<>(names.subList(mid, names.size())), key), "collect"));
                cur.getSteps().addFirst(new TaskRun.RpcStep(
                        RpcRequest.gpv(new ArrayList<>(names.subList(0, mid)), key), "collect"));
                Logs.debug(Logs.Module.CPE_PUSH, "参数采集含无效名，拆分重试: sn={} 参数数={} 错误码={}",
                        s.getSn(), names.size(), msg.getFaultCode());
                return;
            }
            if (names != null) {
                modelProfileService.rememberBadParam(s.getSn(), names.get(0));
                Logs.debug(Logs.Module.CPE_PUSH, "跳过设备不认的参数", "sn", s.getSn(), "任务", cur.getTaskId(),
                        "参数", names, "错误码", msg.getFaultCode());
                return;
            }
        }
        if (Boolean.TRUE.equals(cur.getParams().get(InitProvisionService.PARAM_FLAG))) {
            initProvisionService.retryLater(s.getSn());
        }
        store.updateTask(cur.getTaskId(), "fail", String.valueOf(msg.getFaultCode()), detail.toString(), null);
        lifecycle.rpcIdle(s.getSn(), cur.getTaskId());
        cur.markCompleted();
        s.setCurrent(null);
        Logs.warn(Logs.Module.CPE_PUSH, "任务执行失败", "sn", s.getSn(), "任务", cur.getTaskId(),
                "类型", cur.getType(), "错误码", msg.getFaultCode(), "详情", detail.toString());
    }

    /** 业务域叶子（滤掉设备不认的参数名） */
    private List<String> bizLeaves(CwmpSession s, String root) {
        List<String> leaves = new ArrayList<>();
        for (String br : BusinessPaths.businessRoots(root)) {
            leaves.addAll(s.getData().leaves(br));
        }
        leaves.addAll(BusinessPaths.vendorWifiLeaves(s.getData()));
        leaves.addAll(BusinessPaths.blindWifiLeaves(s.getData()));
        Set<String> bad = modelProfileService.badParamsOf(s.getSn());
        if (!bad.isEmpty()) {
            leaves.removeIf(bad::contains);
        }
        return leaves;
    }

    /** 是否 TransferComplete 类报文 */
    private static boolean isTransferComplete(SoapMessage msg) {
        return "TransferComplete".equals(msg.getName())
                || "AutonomousTransferComplete".equals(msg.getName());
    }

    /** 按 TC 类型回对应响应 */
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
                Logs.warn(Logs.Module.CPE_PUSH, "TransferComplete CommandKey 非法，忽略归因",
                        "sn", s == null ? "?" : s.getSn(), "commandKey", key);
            }
        }
        if (taskId > 0) {
            boolean ok = msg.getTcFaultCode() == 0;
            store.updateTask(taskId, ok ? "success" : "fail",
                    ok ? null : String.valueOf(msg.getTcFaultCode()),
                    ok ? null : msg.getTcFaultString(), null);
            Logs.info(Logs.Module.CPE_PUSH, "TransferComplete", "sn", s == null ? "?" : s.getSn(), "任务", taskId,
                    "错误码", msg.getTcFaultCode(), "完成时间", msg.getEndValue());
        } else if (!key.isBlank()) {
            Logs.debug(Logs.Module.CPE_PUSH, "TransferComplete 非本 ACS 任务，忽略归因",
                    "sn", s == null ? "?" : s.getSn(), "commandKey", key, "错误码", msg.getTcFaultCode());
        } else if ("AutonomousTransferComplete".equals(msg.getName())) {
            Logs.info(Logs.Module.CPE_PUSH, "设备自主传输完成（AutonomousTransferComplete）",
                    "sn", s == null ? "?" : s.getSn(),
                    "错误码", msg.getTcFaultCode(), "完成时间", msg.getEndValue());
        }
    }

    /** 取下一条要发的 RPC */
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
                store.markTaskResume(cur.getTaskId());
                store.updateTask(cur.getTaskId(), "queued", null,
                        "会话 RPC 次数超限(" + props.getMaxRpcCount() + ")，待续跑", null);
                lifecycle.rpcIdle(s.getSn(), cur.getTaskId());
                s.setCurrent(null);
                delayedResumeKicks.put(s.getSn(), System.currentTimeMillis() + RESUME_KICK_DELAY_MS);
                return null;
            }
            TaskRun.RpcStep step = cur.poll();
            RpcRequest req = step.request();
            req.setId(s.getSessionId() + "-" + s.getRpcCount());
            s.setPending(req);
            s.setPendingPurpose(step.purpose());
            s.setRpcCount(s.getRpcCount() + 1);
            store.updateTask(cur.getTaskId(), "running", null,
                    "进度 " + cur.getSentSteps() + "/" + (cur.getSentSteps() + cur.getSteps().size()), null);
            lifecycle.rpcRunning(s.getSn(), cur.getTaskId());
            return req;
        }
    }

    /** SPV 任务期望值表 */
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
                Logs.error(Logs.Module.CPE_PUSH, "任务组装失败", e, "sn", s.getSn(), "任务", id);
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
            result.put("collectStatus", s.getData().all().isEmpty() ? "empty" : "success");
        } else if (TaskPlanner.TYPE_SPV.equals(cur.getType())) {
            Map<String, Object> collected = new LinkedHashMap<>(cur.getCollected());
            collected.remove("verifyExpected");
            result.putAll(collected);
            Map<?, ?> dev = (Map<?, ?>) collected.get("readbackDeviations");
            result.put("readback", dev == null ? "skipped" : dev.isEmpty() ? "done" : "deviation");
        }
        store.updateTask(cur.getTaskId(), "success", null, null, toJson(result));
        lifecycle.rpcIdle(s.getSn(), cur.getTaskId());
    }

    /** 会话收尾（幂等） */
    private void endSession(CwmpSession s, String reason) {
        if (s.isEnded()) {
            return;
        }
        s.setEnded(true);
        try {
            TaskRun cur = s.getCurrent();
            if (cur != null && !cur.finished() && !cur.isCompleted()) {
                if (TaskPlanner.WRITE_TYPES.contains(cur.getType())) {
                    store.updateTask(cur.getTaskId(), "fail", "interrupted",
                            "会话中断(" + reason + ")。写操作类不自动重发——设备可能已实际执行，请确认设备状态后重新下发",
                            null);
                    lifecycle.rpcIdle(s.getSn(), cur.getTaskId());
                    if (cur.getParams().get(InitProvisionService.PARAM_FLAG) != null) {
                        initProvisionService.retryLater(s.getSn());
                    }
                    Logs.warn(Logs.Module.CPE_OFFLINE, "写操作任务随会话中断判失败", "sn", s.getSn(),
                            "任务", cur.getTaskId(), "类型", cur.getType(), "原因", reason);
                } else {
                    store.markTaskResume(cur.getTaskId());
                    store.updateTask(cur.getTaskId(), "queued", null, "会话中断(" + reason + ")，待续跑", null);
                    delayedResumeKicks.put(s.getSn(), System.currentTimeMillis() + RESUME_KICK_DELAY_MS);
                }
            }
            store.saveDeviceParams(s.getSn(), s.getData().all(), s.getData().dirtyPaths(),
                    s.getData().isFullSync(), s.getData().tombstones());
            if (modelProfileService.detectIspFromPaths(s.getSn(), s.getData().all().keySet()) == null) {
                modelProfileService.detectIspAfterSession(s.getSn());
            }
            refreshSnapshot(s);
        } catch (Exception e) {
            Logs.error(Logs.Module.CPE_OFFLINE, "会话收尾异常（会话已终止，残留任务由兜底回队）", e,
                    "sn", s.getSn(), "原因", reason);
        } finally {
            registry.remove(s);
            lifecycle.remove(s.getSn(), s.getSessionId());
        }
        Logs.info(Logs.Module.CPE_OFFLINE, "会话结束", "sn", s.getSn(), "原因", reason,
                "RPC数", s.getRpcCount(), "参数数", s.getData().all().size());
    }

    /** 会话收尾时刷新业务快照 */
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
                return;
            }
            store.saveSnapshot(s.getSn(), json);
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE_PUSH, "业务快照刷新失败", "sn", s.getSn(), "原因", e.toString());
        }
    }

    /** 定时巡检兜底 */
    @Scheduled(fixedDelay = 10_000)
    public void sweep() {
        long defaultTimeout = props.getSessionTimeoutSeconds() * 1000L;
        for (CwmpSession s : List.copyOf(registry.allBySn().values())) {
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
                Logs.error(Logs.Module.CPE_OFFLINE, "会话超时收尾异常", e, "sn", s.getSn());
            }
        }
        try {
            store.failStaleRunningTasks(props.getCollectTimeoutSeconds() * 1000L + 120_000L,
                    registry.runningTaskIds());
        } catch (Exception e) {
            Logs.error(Logs.Module.CPE_OFFLINE, "卡死任务回队异常", e);
        }
        try {
            store.markOfflineStale(System.currentTimeMillis() - OFFLINE_AFTER_MS);
        } catch (Exception e) {
            Logs.error(Logs.Module.CPE_OFFLINE, "离线标记异常", e);
        }
        registry.sweepExpiredKicks();
        if (!delayedResumeKicks.isEmpty()) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> e : delayedResumeKicks.entrySet()) {
                if (e.getValue() <= now && delayedResumeKicks.remove(e.getKey(), e.getValue())) {
                    try {
                        connectionRequestService.wakeWithRetry(e.getKey(), resolveCrUrl(e.getKey()), null, null);
                    } catch (Exception ex) {
                        Logs.warn(Logs.Module.CPE_OFFLINE, "延迟反踢异常", "sn", e.getKey(), "原因", ex.getMessage());
                    }
                }
            }
        }
    }

    /** 反踢用的 CR URL */
    private String resolveCrUrl(String sn) {
        Map<String, Object> row = infoCache.get(sn);
        if (row == null || row.get("connection_request_url") == null) {
            row = store.findDevice(sn);
        }
        Object url = row == null ? null : row.get("connection_request_url");
        return url == null ? null : String.valueOf(url);
    }

    /** Redis 会话 TTL 过期回调入口 */
    public void endSessionBySn(String sn, String reason) {
        CwmpSession s = registry.bySn(sn);
        if (s == null) {
            return;
        }
        locked(sn, () -> {
            CwmpSession cur = registry.bySn(sn);
            if (cur == s && cur.expired(cur.effectiveTimeout(props.getSessionTimeoutSeconds() * 1000L))) {
                endSession(cur, reason);
            }
            return null;
        });
    }

    public CwmpSession findSessionBySn(String sn) {
        return registry.bySn(sn);
    }

    /** REST 建任务入队用的 per-SN 锁 */
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
