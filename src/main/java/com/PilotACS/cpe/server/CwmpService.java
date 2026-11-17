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
import java.util.regex.Pattern;

/**
 * CWMP 会话核心：三态状态机 + Inform 处理 + RPC 单飞循环。
 * <p>流程：
 * <pre>
 * state 0：只接受 Inform → 回 InformResponse → state 1
 * state 1：空 POST（设备就绪）→ 取下一个任务展开成 RPC → state 2
 *          TransferComplete → 归因到任务；GetRPCMethods → 固定清单
 * state 2：只接受 ACS 所发 RPC 的响应/Fault → 应用结果 → 继续下一 RPC 或收尾
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class CwmpService {

    private final ConnectionRequestService connectionRequestService;

    /** 一次 HTTP 交互的输出：HTTP 状态 + 报文 + 需要下发的会话 cookie */
    public record Reply(int httpStatus, String body, String newSessionId) {
        public static Reply empty(int status) {
            return new Reply(status, null, null);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long OFFLINE_AFTER_MS = 15 * 60 * 1000L;
    private static final Pattern TASK_KEY = Pattern.compile("task-(\\d+)");

    private final CpeProperties props;
    private final SessionRegistry registry;
    private final CpeStore store;
    private final TaskPlanner planner;
    private final CapabilityDetector capabilityDetector;
    private final ModelProfileService modelProfileService;
    private final InitProvisionService initProvisionService;
    private final BusinessSnapshotService snapshotService;

    // ==================== 入口 ====================

    /**
     * 处理 CPE 的一次 POST（body 为空表示空 POST）
     *
     * @param remoteAddr 设备来源地址，无 Cookie 会话时按它兜底找回会话
     */
    public Reply processRequest(String body, String cookieSessionId, String remoteAddr) {
        SoapMessage msg;
        try {
            msg = SoapParser.parse(body);
        } catch (SoapParser.SoapException e) {
            Logs.warn(Logs.Module.CPE, "SOAP 报文解析失败", "原因", e.getMessage());
            return Reply.empty(400);
        }

        CwmpSession session = registry.bySessionId(cookieSessionId);
        String newSessionId = null;
        if (session == null) {
            if (msg.getName() == null) {
                // 无 Cookie 的空 POST：老设备不回传 session cookie，它的空 POST 找不到会话，
                // 会话里排的任务永远装不上。按 Inform 时记下的来源地址找回会话；
                // 只有"已 Inform 等 RPC"状态的会话才认（state 0 没身份，state 2 在飞响应不能空答）
                CwmpSession fallback = registry.byRemoteAddr(remoteAddr);
                if (fallback != null && fallback.getState() == CwmpSession.STATE_INFORMED) {
                    session = fallback;
                    Logs.info(Logs.Module.CPE, "设备不带 Cookie，按来源地址找回会话", "sn", session.getSn(),
                            "地址", remoteAddr);
                } else {
                    // 真没有会话：多为设备收尾（TR-069 A.3.2 里空响应即结束会话）。
                    // 不能再回 Inform 请求——Inform 只能 CPE→ACS，个别设备不认会立刻重发空 POST 形成风暴。
                    // 有些老固件会话结束后仍每 2 秒重发空 POST 要任务，按规范回空响应即可，降为 debug 防刷屏
                    Logs.debug(Logs.Module.CPE, "无会话空 POST，回 200 空响应结束会话", "地址", remoteAddr);
                    return Reply.empty(200);
                }
            }
            if (!"Inform".equals(msg.getName())) {
                // 没有会话又不报 Inform（含空 POST），直接 400
                return new Reply(400, SoapBuilder.acsFault(8000, "No session, Inform required"), null);
            }
            if (msg.getSerialNumber() == null || msg.getSerialNumber().isBlank()) {
                return new Reply(400, SoapBuilder.acsFault(8000, "Invalid device id"), null);
            }
            session = registry.create(msg.getSerialNumber());
            session.setRemoteAddr(remoteAddr);
            newSessionId = session.getSessionId();
            Logs.debug(Logs.Module.CPE, "会话来源地址已记录", "sn", session.getSn(), "地址", remoteAddr);
            // 同 SN 旧会话被顶替时必须完整收尾：在飞任务回队、参数落库、快照刷新。
            // 否则任务卡 running，新会话 loadQueuedTasks 装不到它（只装 queued）→ "无待执行任务"
            CwmpSession superseded = registry.lastSuperseded();
            if (superseded != null) {
                endSession(superseded, "设备重新 Inform，会话被新会话顶替");
            }
        }
        session.touch();

        return switch (session.getState()) {
            case CwmpSession.STATE_CREATED -> onCreated(session, msg);
            case CwmpSession.STATE_INFORMED -> onInformed(session, msg);
            default -> onRpcRunning(session, msg);
        };
    }

    // ==================== state 0：等 Inform ====================

    private Reply onCreated(CwmpSession s, SoapMessage msg) {
        if (!"Inform".equals(msg.getName())) {
            return new Reply(400, SoapBuilder.acsFault(8000, "Inform required"), null);
        }
        handleInform(s, msg);
        s.setState(CwmpSession.STATE_INFORMED);
        // InformResponse 的报文头 ID 必须回传设备请求里的 ID（TR-069 3.1.3），
        // 严格固件校验不过会丢弃响应并不再发空 POST，会话从此取不到任何任务
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
        s.getData().loadAll(store.loadParams(s.getSn()));
        for (CpeParam p : msg.getParams()) {
            s.getData().put(p);
        }
        String root = BusinessPaths.detectRoot(s.getData(), props.getDefaultRootNode());
        s.setConnectionRequestUrl(s.getData().value(root + "ManagementServer.ConnectionRequestURL"));
        s.setSoftwareVersion(s.getData().value(root + "DeviceInfo.SoftwareVersion"));
        s.setHardwareVersion(s.getData().value(root + "DeviceInfo.HardwareVersion"));

        // 事件落台账（0 BOOTSTRAP / 1 BOOT）
        long now = System.currentTimeMillis();
        Long boot = null;
        Long bootstrap = null;
        for (String ev : msg.getEvents()) {
            if (ev.startsWith("1 BOOT")) {
                boot = now;
            } else if (ev.startsWith("0 BOOTSTRAP")) {
                bootstrap = now;
                s.setBootstrap(true);
            }
        }

        // 台账 upsert：Inform 一到就刷新设备档案
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
        store.markEvents(s.getSn(), boot, bootstrap);

        // 绑定机型模板（品牌+型号+版本+运营商），有则挂到会话上供组装用
        modelProfileService.attachModel(s);
        // 首次上报：查库判断是否已下发反向连接账密与周期上报，未下发则本会话立即下发
        initProvisionService.provisionIfNeeded(s);
        // 每次上报：有参数树就排一条轻量 GPV 刷 WAN/WIFI/SIP 最新值（差异落库）
        modelProfileService.refreshBizInSession(s);

        Logs.info(Logs.Module.CPE, "设备上报 Inform", "sn", s.getSn(),
                "厂商", s.getManufacturer(), "型号", s.getProductClass(),
                "事件", msg.getEvents(), "根节点", root,
                "机型模板", s.getModelRow() == null ? "无" : s.getModelRow().get("id"));
    }

    // ==================== state 1：等空 POST / TransferComplete ====================

    private Reply onInformed(CwmpSession s, SoapMessage msg) {
        if (msg.isEmptyPost()) {
            // 设备就绪，取下一个任务展开成 RPC；没有活干就收尾
            RpcRequest next = nextRpc(s);
            if (next == null) {
                endSession(s, "无待执行任务");
                return Reply.empty(200);
            }
            s.setState(CwmpSession.STATE_RPC_RUNNING);
            return new Reply(200, SoapBuilder.request(next), s.getSessionId());
        }
        if ("TransferComplete".equals(msg.getName())) {
            handleTransferComplete(s, msg);
            return Reply.empty(200);
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
        if ("TransferComplete".equals(msg.getName())) {
            handleTransferComplete(s, msg);
            RpcRequest next = nextRpc(s);
            if (next == null) {
                endSession(s, "任务执行完毕");
                return Reply.empty(200);
            }
            return new Reply(200, SoapBuilder.request(next), s.getSessionId());
        }
        applyResponse(s, msg);
        if (s.isEnded()) {
            return Reply.empty(200);
        }
        RpcRequest next = nextRpc(s);
        if (next == null) {
            endSession(s, "任务执行完毕");
            return Reply.empty(200);
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
                    s.getData().put(new CpeParam(p.path(), null, null, p.object(), p.writable(),
                            known == null ? p.ts() : known.ts()));
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
                // 设备没回的参数记空串
                if (req.getNames() != null) {
                    for (String n : req.getNames()) {
                        if (!s.getData().has(n)) {
                            s.getData().put(new CpeParam(n, "", "string", false, true, 0));
                        }
                    }
                }
            }
            case SPV -> {
                completeTask(s, cur, Map.of("applied", msg.getStatus() == 0, "status", msg.getStatus()));
                Logs.info(Logs.Module.CPE, "参数下发完成", "sn", s.getSn(), "任务", cur.getTaskId(),
                        "参数数", req.getValues() == null ? 0 : req.getValues().size());
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
            case DELETE_OBJECT -> completeTask(s, cur, Map.of("deleted", req.getPath()));
            case REBOOT -> {
                completeTask(s, cur, Map.of("reboot", true));
                Logs.info(Logs.Module.CPE, "设备重启指令已受理", "sn", s.getSn(), "任务", cur.getTaskId());
            }
            case FACTORY_RESET -> completeTask(s, cur, Map.of("factoryReset", true));
            case DOWNLOAD -> completeTask(s, cur, Map.of("download", true, "url", String.valueOf(req.getUrl())));
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
            // 跳过即可，不能拖死整个采集任务；扫完就按已扫到的叶子补 GPV
            Logs.warn(Logs.Module.CPE, "扫描步骤失败，跳过继续", "sn", s.getSn(), "任务", cur.getTaskId(),
                    "步骤", purpose, "错误码", msg.getFaultCode(), "详情", detail.toString());
            String root = BusinessPaths.detectRoot(s.getData(), props.getDefaultRootNode());
            if (!planner.hasRemainingPurpose(cur, "scan") && !planner.hasRemainingPurpose(cur, "probe")) {
                planner.appendGpv(cur, bizLeaves(s, root), "collect");
            }
            return;
        }
        // GPV 采集步骤遇到 9005/9003（某个参数名不被设备接受）：拆分批次定位坏名，单个坏名直接丢弃，
        // 不能让一条脏名把整轮采集判死
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
                Logs.warn(Logs.Module.CPE, "跳过设备不认的参数", "sn", s.getSn(), "任务", cur.getTaskId(),
                        "参数", names, "错误码", msg.getFaultCode());
                return;
            }
        }
        if (Boolean.TRUE.equals(cur.getParams().get(InitProvisionService.PARAM_FLAG))) {
            // 首次初始化下发失败：放开去重，下次 Inform 再试
            initProvisionService.retryLater(s.getSn());
        }
        store.updateTask(cur.getTaskId(), "fail", String.valueOf(msg.getFaultCode()), detail.toString(), null);
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

    private void handleTransferComplete(CwmpSession s, SoapMessage msg) {
        String key = msg.getCommandKey() == null ? "" : msg.getCommandKey();
        long taskId = 0;
        var m = TASK_KEY.matcher(key);
        if (m.find()) {
            taskId = Long.parseLong(m.group(1));
        }
        if (taskId > 0) {
            boolean ok = msg.getTcFaultCode() == 0;
            store.updateTask(taskId, ok ? "success" : "fail",
                    ok ? null : String.valueOf(msg.getTcFaultCode()),
                    ok ? null : msg.getTcFaultString(), null);
            Logs.info(Logs.Module.CPE, "TransferComplete", "sn", s.getSn(), "任务", taskId,
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
                store.updateTask(cur.getTaskId(), "fail", "0",
                        "会话 RPC 次数超限(" + props.getMaxRpcCount() + ")", null);
                cur.markCompleted();
                s.setCurrent(null);
                continue;
            }
            TaskRun.RpcStep step = cur.poll();
            RpcRequest req = step.request();
            req.setId(s.getSessionId() + "-" + s.getRpcCount());
            s.setPending(req);
            s.setPendingPurpose(step.purpose());
            s.setRpcCount(s.getRpcCount() + 1);
            store.updateTask(cur.getTaskId(), "running", null, null, null);
            return req;
        }
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
        }
        store.updateTask(cur.getTaskId(), "success", null, null, toJson(result));
    }

    // ==================== 会话收尾 ====================

    private void endSession(CwmpSession s, String reason) {
        if (s.isEnded()) {
            return;
        }
        s.setEnded(true);
        TaskRun cur = s.getCurrent();
        if (cur != null && !cur.finished() && !cur.isCompleted()) {
            // 会话中断不判死：任务回队，设备下次上报续跑（与"排队等待设备上线"语义一致）。
            // 打续跑标记：重排时跳过已采到的参数，否则大任务整树重发，
            // 设备每轮跑到会话上限就断，永远跑不完 → 无限排队
            store.markTaskResume(cur.getTaskId());
            store.updateTask(cur.getTaskId(), "queued", null, "会话中断(" + reason + ")，待续跑", null);
            // 回队后立刻反踢续跑，不等下一轮周期上报（大树 99 次上限要多个会话接力）
            connectionRequestService.wakeWithRetry(s.getSn(), s.getConnectionRequestUrl(), null, null);
        }
        store.saveDeviceParams(s.getSn(), s.getData().all(), s.getData().dirtyPaths(), s.getData().isFullSync());
        // 先用本会话内存树识别（不等读库），认不出再走落库树 + 必要时排探测
        if (modelProfileService.detectIspFromPaths(s.getSn(), s.getData().all().keySet()) == null) {
            modelProfileService.detectIspAfterSession(s.getSn());
        }
        refreshSnapshot(s);
        registry.remove(s);
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
            Map<String, Object> snap = snapshotService.snapshot(store.findDevice(s.getSn()),
                    s.getData(), root, "success", null);
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

    /** 定时巡检：超时会话收尾、卡死任务判死、超时未上报的设备翻离线 */
    @Scheduled(fixedDelay = 10_000)
    public void sweep() {
        long timeout = props.getSessionTimeoutSeconds() * 1000L;
        for (CwmpSession s : List.copyOf(registry.allBySn().values())) {
            if (s.expired(timeout)) {
                endSession(s, "会话超时");
            }
        }
        store.failStaleRunningTasks(props.getCollectTimeoutSeconds() * 1000L + 120_000L);
        store.markOfflineStale(System.currentTimeMillis() - OFFLINE_AFTER_MS);
    }

    // ==================== 供 REST 层调用 ====================

    public CwmpSession findSessionBySn(String sn) {
        return registry.bySn(sn);
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
