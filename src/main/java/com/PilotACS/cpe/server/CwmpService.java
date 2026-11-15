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
import com.PilotACS.cpe.service.CapabilityDetector;
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
import java.util.regex.Pattern;

/**
 * CWMP 会话核心：三态状态机 + Inform 处理 + RPC 单飞循环。
 * <p>流程复刻 genieacs cwmp.ts processRequest：
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

    // ==================== 入口 ====================

    /**
     * 处理 CPE 的一次 POST（body 为空表示空 POST）
     */
    public Reply processRequest(String body, String cookieSessionId) {
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
                // 部分（如收到反向连接后）设备用空 POST 启动会话（TR-069 3.3.3）：
                // 此时还没有会话，回 Inform 请求让设备上报身份，下一 POST 才是 Inform
                Logs.info(Logs.Module.CPE, "设备以空 POST 请求建会话，回 Inform 索取设备信息",
                        "ACS", "path/query 已接受");
                return new Reply(200, SoapBuilder.informRequest("0"), null);
            }
            if (!"Inform".equals(msg.getName())) {
                // 没有会话又不报 Inform（含空 POST）：参考 genieacs 直接 400
                return new Reply(400, SoapBuilder.acsFault(8000, "No session, Inform required"), null);
            }
            if (msg.getSerialNumber() == null || msg.getSerialNumber().isBlank()) {
                return new Reply(400, SoapBuilder.acsFault(8000, "Invalid device id"), null);
            }
            session = registry.create(msg.getSerialNumber());
            newSessionId = session.getSessionId();
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
        return new Reply(200, SoapBuilder.informResponse(sessionRpcId(s)), s.getSessionId());
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
            onTaskFault(s, cur, msg);
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
                    planner.appendBizGpv(cur, s.getData(), root);
                }
                if (TaskPlanner.TYPE_ADAPT_PROBE.equals(cur.getType())
                        && !planner.hasRemainingPurpose(cur, "probe")) {
                    List<String> leaves = new ArrayList<>();
                    for (String br : BusinessPaths.businessRoots(root)) {
                        leaves.addAll(s.getData().leaves(br));
                    }
                    planner.appendGpv(cur, leaves, "collect");
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
                // 设备没回的参数记空串，与 genieacs 行为一致
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

    private void onTaskFault(CwmpSession s, TaskRun cur, SoapMessage msg) {
        StringBuilder detail = new StringBuilder(msg.getFaultString() == null ? "" : msg.getFaultString());
        if (!msg.getSetFaults().isEmpty()) {
            detail.append('；');
            msg.getSetFaults().forEach(f -> detail.append(f.name()).append(':').append(f.faultString()).append(' '));
        }
        store.updateTask(cur.getTaskId(), "fail", String.valueOf(msg.getFaultCode()), detail.toString(), null);
        cur.markCompleted();
        s.setCurrent(null);
        Logs.warn(Logs.Module.CPE, "任务执行失败", "sn", s.getSn(), "任务", cur.getTaskId(),
                "类型", cur.getType(), "错误码", msg.getFaultCode(), "详情", detail.toString());
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
            store.updateTask(cur.getTaskId(), "fail", "0", "会话提前结束，任务未完成: " + reason, null);
        }
        store.saveDeviceParams(s.getSn(), s.getData().all(), s.getData().dirtyPaths(), s.getData().isFullSync());
        registry.remove(s);
        Logs.info(Logs.Module.CPE, "会话结束", "sn", s.getSn(), "原因", reason,
                "RPC数", s.getRpcCount(), "参数数", s.getData().all().size());
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
