/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.controller;

import com.PilotACS.acl.DeviceAclService;
import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.cpe.model.BrandName;
import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.cpe.model.IspVariant;
import com.PilotACS.cpe.model.DeviceData;
import com.PilotACS.cpe.service.BusinessSnapshotService;
import com.PilotACS.cpe.service.DeviceAdminService;
import com.PilotACS.cpe.service.DispatchService;
import com.PilotACS.cpe.service.ModelProfileService;
import com.PilotACS.cpe.service.TaskService;
import com.PilotACS.cpe.server.CwmpService;
import com.PilotACS.cpe.session.CwmpSession;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.exception.BusinessException;
import com.PilotACS.common.log.Logs;
import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 设备管理接口（前端 web/src/api/device.js） */
@RestController
@RequestMapping("/device")
@RequiredArgsConstructor
public class DeviceController {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 排序键白名单 */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "manufacturer", "manufacturer",
            "productClass", "product_class",
            "hardwareVersion", "hardware_version",
            "softwareVersion", "software_version",
            "createdAt", "created_at",
            "lastContactTime", "last_inform_ms");

    private final CpeStore store;
    private final CpeProperties props;
    private final CwmpService cwmpService;
    private final TaskService taskService;
    private final DispatchService dispatchService;
    private final BusinessSnapshotService snapshotService;
    private final ModelProfileService modelProfileService;
    private final com.PilotACS.cpe.service.CapabilityDetector capabilityDetector;
    private final DeviceAclService aclService;
    private final DeviceAdminService deviceAdminService;
    private final com.PilotACS.cpe.service.DeviceSipService deviceSipService;
    private final com.PilotACS.service.SysOperLogService operLogService;

    private static final Map<String, String> ACTION_TEXT = Map.of(
            "view", "查看", "operate", "操作", "update", "编辑", "delete", "删除");

    /** 单设备动作权限校验 */
    private void requireOnu(String sn, String action) {
        if (!aclService.allowedOnu(UserContext.get(), sn, action)) {
            throw new BusinessException(403, "无该光猫的" + ACTION_TEXT.getOrDefault(action, action) + "权限");
        }
    }

    @GetMapping("/page")
    public Result<Map<String, Object>> page(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String sn,
                                            @RequestParam(required = false) String isp,
                                            @RequestParam(required = false) String deviceType,
                                            @RequestParam(required = false) String roomNo,
                                            @RequestParam(required = false) String sortKey,
                                            @RequestParam(required = false) String sortOrder,
                                            @RequestParam(required = false) String optic) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (sn != null && !sn.isBlank()) {
            where.append(" AND sn LIKE ?");
            args.add("%" + sn.trim() + "%");
        }
        if (isp != null && !isp.isBlank()) {
            where.append(" AND isp = ?");
            args.add(isp);
        }
        if (deviceType != null && !deviceType.isBlank()) {
            where.append(" AND device_type = ?");
            args.add(deviceType);
        }
        if (roomNo != null && !roomNo.isBlank()) {
            where.append(" AND room_no LIKE ?");
            args.add("%" + roomNo.trim() + "%");
        }
        Set<String> hidden = aclService.hiddenOnuRefs(UserContext.get());
        if (hidden != null) {
            if (hidden.isEmpty()) {
                return Result.ok(Map.of("items", List.of(), "total", 0));
            }
            where.append(" AND sn NOT IN (");
            for (int i = 0; i < hidden.size(); i++) {
                where.append(i == 0 ? "?" : ",?");
            }
            where.append(")");
            args.addAll(hidden);
        }
        String orderBy = "last_inform_ms DESC";
        if (sortKey != null && SORT_COLUMNS.containsKey(sortKey)) {
            orderBy = SORT_COLUMNS.get(sortKey) + ("desc".equalsIgnoreCase(sortOrder) ? " DESC" : " ASC");
        }
        int total = store.countDevices(where.toString(), args.toArray());
        List<Map<String, Object>> rows = store.pageDevices(where.toString(), args.toArray(),
                orderBy, (Math.max(1, page) - 1) * size, size);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = camel(row);
            item.put("online", "online".equals(row.get("status")));
            item.put("ispText", IspVariant.text(str(row.get("isp"))));
            Map<String, String> bizParams = store.loadBizParams(str(row.get("sn")));
            Map<String, Object> biz = new LinkedHashMap<>();
            String oui = str(row.get("oui"));
            biz.put("title", (oui == null || oui.isBlank() ? "" : oui + "-") + row.get("sn"));
            biz.put("mac", bizParams.entrySet().stream()
                    .filter(e -> e.getKey().endsWith("MACAddress"))
                    .map(Map.Entry::getValue).findFirst().orElse(null));
            biz.put("ponType", bizParams.entrySet().stream()
                    .filter(e -> e.getKey().contains("PON") && e.getKey().endsWith(".Mode"))
                    .map(Map.Entry::getValue).findFirst().orElse("GE"));
            biz.put("opticText", bizParams.entrySet().stream()
                    .filter(e -> e.getKey().substring(e.getKey().lastIndexOf('.') + 1).contains("RxPower"))
                    .map(e -> "收光 " + e.getValue() + " dBm").findFirst().orElse(null));
            biz.put("uptimeText", uptimeText(bizParams.entrySet().stream()
                    .filter(e -> e.getKey().endsWith(".UpTime"))
                    .map(Map.Entry::getValue).findFirst().orElse(null)));
            biz.put("ip", bizParams.entrySet().stream()
                    .filter(e -> e.getKey().endsWith("ExternalIPAddress"))
                    .map(Map.Entry::getValue).findFirst().orElse(null));
            item.put("biz", biz);
            items.add(item);
        }
        return Result.ok(Map.of("items", items, "total", total));
    }

    @GetMapping("/stat")
    public Result<Map<String, Integer>> stat() {
        return Result.ok(store.deviceStat());
    }

    @GetMapping("/online")
    public Result<List<Map<String, Object>>> online() {
        Set<String> hidden = aclService.hiddenOnuRefs(UserContext.get());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : store.onlineDevices()) {
            if (hidden != null && hidden.contains(String.valueOf(row.get("sn")))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sn", row.get("sn"));
            item.put("oui", row.get("oui"));
            item.put("state", "online");
            item.put("softwareVersion", row.get("software_version"));
            item.put("pendingCount", store.countQueued(str(row.get("sn"))));
            item.put("lastContactTime", fmtMs(row.get("last_inform_ms")));
            out.add(item);
        }
        return Result.ok(out);
    }

    @GetMapping("/{sn}")
    public Result<Map<String, Object>> detail(@PathVariable String sn) {
        requireOnu(sn, "view");
        Map<String, Object> row;
        try {
            row = store.findDevice(sn);
        } catch (Exception e) {
            throw new BusinessException(404, "设备不存在");
        }
        Map<String, Object> device = camel(row);
        device.put("connectionRequestUrl", row.get("connection_request_url"));
        device.put("cwmpVersion", row.get("cwmp_version"));
        device.put("lastContactTime", fmtMs(row.get("last_inform_ms")));
        device.put("ispText", IspVariant.text(str(row.get("isp"))));
        return Result.ok(Map.of("device", device, "session", sessionInfo(sn, row)));
    }

    @GetMapping("/{sn}/status")
    public Result<Map<String, Object>> status(@PathVariable String sn) {
        requireOnu(sn, "view");
        Map<String, Object> row;
        try {
            row = store.findDevice(sn);
        } catch (Exception e) {
            throw new BusinessException(404, "设备不存在");
        }
        return Result.ok(sessionInfo(sn, row));
    }

    @PostMapping("/{sn}/remark")
    public Result<Void> remark(@PathVariable String sn, @RequestBody Map<String, Object> body) {
        requireOnu(sn, "update");
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("room_no", strOrEmpty(body.get("roomNo")));
        cols.put("remark", strOrEmpty(body.get("remark")));
        Object isp = body.get("isp");
        if (isp != null && !String.valueOf(isp).isBlank()) {
            cols.put("isp", IspVariant.canonical(String.valueOf(isp)));
        }
        store.updateDeviceColumns(sn, cols);
        return Result.ok();
    }

    @GetMapping("/poll-config")
    public Result<Map<String, Object>> pollConfig() {
        return Result.ok(Map.of(
                "listSeconds", props.getListPollSeconds(),
                "taskSeconds", props.getTaskPollSeconds(),
                "bizSeconds", props.getBizPollSeconds(),
                "rpcSeconds", props.getRpcPollSeconds(),
                "rpcMaxAttempts", props.getRpcMaxAttempts(),
                "detailRpcSeconds", props.getDetailRpcPollSeconds(),
                "detailRpcMaxAttempts", props.getDetailRpcMaxAttempts()));
    }

    @GetMapping("/rpc/methods")
    public Result<List<String>> rpcMethods() {
        return Result.ok(List.of("Reboot", "FactoryReset", "GetParameterValues", "GetParameterNames",
                "SetParameterValues", "AddObject", "DeleteObject", "Download", "GetRPCMethods"));
    }

    @GetMapping("/rpc/quick-commands")
    public Result<Map<String, String>> quickCommands() {
        return Result.ok(Map.of(
                "reboot", "Reboot",
                "factory-reset", "FactoryReset",
                "refresh-biz", "RefreshBusiness",
                "probe-tree", "AdaptProbe",
                "list-methods", "GetRPCMethods"));
    }

    @GetMapping("/rpc/progress-labels")
    public Result<Map<String, Object>> progressLabels() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String rpc : List.of("Reboot", "FactoryReset", "SetParameterValues", "Download",
                "RefreshBusiness", "AdaptProbe", "GetParameterValues", "GetParameterNames",
                "AddObject", "DeleteObject", "GetRPCMethods", "Custom")) {
            out.put(rpc, Map.of("running", "正在下发 " + rpc, "success", rpc + " 已完成", "fail", rpc + " 失败"));
        }
        out.put("default", Map.of("running", "正在下发", "success", "已完成", "fail", "下发失败"));
        return Result.ok(out);
    }

    @GetMapping("/tasks/active")
    public Result<Map<String, Object>> activeTasks(@RequestParam(required = false) String sns) {
        List<String> list = sns == null || sns.isBlank() ? List.of() : Arrays.asList(sns.split(","));
        for (String sn : list) {
            requireOnu(sn, "view");
        }
        Map<String, List<Map<String, Object>>> grouped = store.activeTasksBySn(list);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String sn : list) {
            List<Map<String, Object>> tasks = new ArrayList<>();
            for (Map<String, Object> t : grouped.getOrDefault(sn, List.of())) {
                String type = str(t.get("type"));
                String status = str(t.get("status"));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", t.get("id"));
                item.put("taskNo", t.get("task_no"));
                item.put("label", labelOf(type));
                item.put("phase", status);
                item.put("phaseKey", status);
                item.put("queuedText", "queued".equals(status) ? "排队中" : "执行中");
                tasks.add(item);
            }
            Map<String, Object> snInfo = new LinkedHashMap<>();
            snInfo.put("tasks", tasks);
            snInfo.put("rpcPending", store.countQueued(sn));
            out.put(sn, snInfo);
        }
        return Result.ok(out);
    }

    @PostMapping("/dispatch")
    public Result<Map<String, Object>> dispatch(@RequestBody Map<String, Object> body) {
        Object snsObj = body.get("sns");
        List<String> sns = new ArrayList<>();
        if (snsObj instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    sns.add(String.valueOf(o));
                }
            }
        } else if (snsObj instanceof String s && !s.isBlank()) {
            for (String sn : s.split(",")) {
                if (!sn.isBlank()) {
                    sns.add(sn.trim());
                }
            }
        }
        for (String sn : sns) {
            requireOnu(sn, "operate");
        }
        Map<String, Object> out = dispatchService.dispatch(body);
        operLogService.log("设备", "直发下发（不走工单）",
                "设备=" + sns + "，受理成功=" + out.get("submitted") + "，失败=" + out.get("failed"));
        return Result.ok(out);
    }

    @PostMapping("/{sn}/rpc/{rpcName}")
    public Result<Map<String, Object>> dispatchRpc(@PathVariable String sn,
                                                   @PathVariable String rpcName,
                                                   @RequestBody(required = false) Map<String, Object> args) {
        requireOnu(sn, "operate");
        Map<String, Object> params = args == null ? Map.of() : args;
        long taskId = taskService.createTask(sn, rpcName, params, "console");
        String action = switch (rpcName) {
            case "Reboot" -> "重启设备";
            case "FactoryReset" -> "恢复出厂设置";
            default -> "下发RPC " + rpcName;
        };
        operLogService.log("设备", action, "sn=" + sn + "，任务=" + taskId);
        return Result.ok(Map.of("queued", true, "taskId", taskId));
    }

    @GetMapping("/{sn}/admin-account")
    public Result<Map<String, Object>> adminAccount(@PathVariable String sn) {
        requireOnu(sn, "operate");
        Map<String, Object> out = deviceAdminService.queryAdminAccount(sn);
        operLogService.log("设备", "查询超管密码",
                "sn=" + sn + "，结果=" + out.get("status") + "，账号数="
                        + (out.get("accounts") == null ? 0 : ((java.util.List<?>) out.get("accounts")).size()));
        return Result.ok(out);
    }

    @GetMapping("/{sn}/telnet")
    public Result<Map<String, Object>> telnetStatus(@PathVariable String sn) {
        requireOnu(sn, "view");
        return Result.ok(deviceAdminService.telnetStatus(sn));
    }

    @PostMapping("/{sn}/telnet")
    public Result<Map<String, Object>> telnetSwitch(@PathVariable String sn,
                                                    @RequestBody Map<String, Object> body) {
        requireOnu(sn, "operate");
        boolean enabled = Boolean.parseBoolean(str(body.get("enabled")));
        Boolean wanAccess = body.get("wanAccess") == null ? null
                : Boolean.parseBoolean(str(body.get("wanAccess")));
        Map<String, Object> out = deviceAdminService.setTelnet(sn, enabled, wanAccess);
        operLogService.log("设备", enabled ? "开启 Telnet" : "关闭 Telnet",
                "sn=" + sn + "，结果=" + out.get("status")
                        + (wanAccess == null ? "" : "，WAN侧访问=" + wanAccess));
        return Result.ok(out);
    }

    @GetMapping("/{sn}/telnet/credentials")
    public Result<Map<String, Object>> telnetCredentials(@PathVariable String sn) {
        requireOnu(sn, "operate");
        Map<String, Object> out = deviceAdminService.telnetCredentials(sn);
        operLogService.log("设备", "查询 Telnet 凭据",
                "sn=" + sn + "，结果=" + out.get("status")
                        + (out.get("enableTaskId") != null ? "（已自动下发开启，任务=" + out.get("enableTaskId") + "）" : ""));
        return Result.ok(out);
    }

    /** 语音线路清单 */
    @GetMapping("/{sn}/sip/lines")
    public Result<List<Map<String, Object>>> sipLines(@PathVariable String sn) {
        requireOnu(sn, "view");
        return Result.ok(deviceSipService.lines(sn));
    }

    /** 按路下发 SIP 配置 */
    @PostMapping("/{sn}/sip")
    public Result<Map<String, Object>> sipSubmit(@PathVariable String sn,
                                                 @RequestBody Map<String, Object> body) {
        requireOnu(sn, "operate");
        Map<String, Object> out = deviceSipService.submit(sn, body);
        operLogService.log("设备", "SIP 语音直发下发",
                "sn=" + sn + "，线路=" + out.get("lines")
                        + "，受理成功=" + out.get("submitted") + "，失败=" + out.get("failed"));
        return Result.ok(out);
    }

    @PostMapping("/{sn}/params/set")
    public Result<Void> setParams(@PathVariable String sn, @RequestBody Map<String, Object> body) {
        requireOnu(sn, "operate");
        Object items = body.get("items");
        if (!(items instanceof List<?> list) || list.isEmpty()) {
            throw new BusinessException(400, "items 不能为空");
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String name = str(m.get("name"));
            if (name == null || name.isBlank()) {
                throw new BusinessException(400, "items 存在缺少 name 的条目");
            }
            Map<String, Object> nv = new LinkedHashMap<>();
            nv.put("name", name);
            nv.put("value", m.get("value") == null ? "" : str(m.get("value")));
            nv.put("type", m.get("type") == null ? "string" : str(m.get("type")));
            values.add(nv);
        }
        if (values.isEmpty()) {
            throw new BusinessException(400, "items 无有效条目");
        }
        taskService.createTask(sn, "SetParameterValues", Map.of("values", values), "console");
        return Result.ok();
    }

    @GetMapping("/{sn}/business")
    public Result<Map<String, Object>> business(@PathVariable String sn) {
        requireOnu(sn, "view");
        Map<String, Object> device = store.findDevice(sn);
        Map<String, Object> snap = snapshotService.readSnapshot(store.findSnapshot(sn));
        if (snap == null) {
            Map<String, CpeParam> params = store.loadParams(sn);
            String status = params.isEmpty() ? "empty" : "success";
            snap = snapshotService.snapshot(device, params, status,
                    "empty".equals(status) ? "设备还没有采集过参数" : null);
        }
        snapshotService.refreshTimes(snap, device);
        return Result.ok(snap);
    }

    @GetMapping("/business/batch")
    public Result<Map<String, Object>> businessBatch(@RequestParam String sns) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String sn : sns.split(",")) {
            if (!aclService.allowedOnu(UserContext.get(), sn.trim(), "view")) {
                out.put(sn.trim(), snapshotService.snapshot(Map.of("sn", sn.trim()), Map.of(), "empty",
                        "无该光猫的查看权限"));
                continue;
            }
            try {
                Map<String, Object> device = store.findDevice(sn.trim());
                Map<String, Object> snap = snapshotService.readSnapshot(store.findSnapshot(sn.trim()));
                if (snap == null) {
                    Map<String, CpeParam> params = store.loadParams(sn.trim());
                    snap = snapshotService.snapshot(device, params,
                            params.isEmpty() ? "empty" : "success", null);
                }
                snapshotService.refreshTimes(snap, device);
                out.put(sn.trim(), snap);
            } catch (Exception e) {
                out.put(sn.trim(), snapshotService.snapshot(Map.of("sn", sn.trim()), Map.of(), "empty",
                        "设备不存在或未上报"));
            }
        }
        return Result.ok(out);
    }

    @PostMapping("/{sn}/business/collect")
    public Result<Map<String, Object>> collect(@PathVariable String sn) {
        requireOnu(sn, "operate");
        java.util.List<String> leaves = modelProfileService.bizRefreshNames(sn);
        long taskId = leaves.isEmpty()
                ? taskService.createTask(sn, "RefreshBusiness", Map.of(), "console")
                : taskService.createTask(sn, "GetParameterValues", Map.of("names", leaves), "console");
        Map<String, Object> task = taskService.awaitTask(taskId, props.getCollectTimeoutSeconds());
        Map<String, Object> result = task == null ? Map.of() : taskService.resultOf(task);
        String status = str(result.getOrDefault("collectStatus", "timeout"));
        Map<String, Object> device = store.findDevice(sn);
        Map<String, CpeParam> params = store.loadParams(sn);
        Map<String, Object> snap = snapshotService.snapshot(device, params, status,
                "timeout".equals(status) ? "采集超时，设备没有在等待时间内回参" : null);
        if (!"timeout".equals(status)) {
            try {
                store.saveSnapshot(sn, snapshotService.toJson(snap));
            } catch (Exception ignore) {
            }
        }
        return Result.ok(snap);
    }

    @PostMapping("/{sn}/adaptation/test")
    public Result<Map<String, Object>> adaptTest(@PathVariable String sn,
                                                 @RequestBody Map<String, Object> declared) {
        requireOnu(sn, "operate");
        Map<String, CpeParam> stored = store.loadParams(sn);
        if (stored.size() >= 100) {
            DeviceData data = new DeviceData();
            data.loadAll(stored);
            Object rootObj = store.findDevice(sn).get("root_node");
            String root = rootObj == null || String.valueOf(rootObj).isBlank()
                    ? "InternetGatewayDevice." : String.valueOf(rootObj);
            Map<String, Object> detected = capabilityDetector.detect(data, root);
            String wifiSpec = str(declared.get("wifiSpec"));
            boolean wifiWanted = wifiSpec != null && !wifiSpec.isBlank()
                    && !wifiSpec.toLowerCase().contains("无") && !wifiSpec.toLowerCase().contains("none");
            List<String> dBands = detected.get("wifiBands") instanceof List<?> l
                    ? (List<String>) l : List.of();
            if (wifiWanted && dBands.isEmpty() && capabilityDetector.hasWifiNodes(data)) {
                Logs.info(Logs.Module.CPE_PUSH, "存量树 WiFi 参数无值，转在线探测补采", "sn", sn);
            } else {
                Map<String, Object> out = new LinkedHashMap<>(
                        capabilityDetector.check(declared, detected, data, root));
                out.put("collectStatus", "success");
                out.put("collectInterrupted", false);
                out.put("rootNode", root);
                return Result.ok(out);
            }
        }
        long taskId = store.findActiveTaskId(sn, "AdaptProbe");
        if (taskId > 0) {
            store.updateTaskParams(taskId, toJson(declared));
        } else {
            taskId = taskService.createTask(sn, "AdaptProbe", declared, "console");
        }
        Map<String, Object> task = taskService.awaitTask(taskId, 280);
        if (task == null) {
            throw new BusinessException(500, "能力测试任务不存在");
        }
        String status = str(task.get("status"));
        Map<String, Object> result = taskService.resultOf(task);
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("collectInterrupted", !"success".equals(status));
        if ("fail".equals(status)) {
            out.put("collectStatus", "fail");
            out.put("reasons", List.of(str(task.get("fault_string"))));
            out.put("supported", false);
        } else if (!"success".equals(status)) {
            out.put("collectStatus", "queued");
            out.put("reasons", List.of(
                    "设备当前无活跃会话，任务已排队，等设备下一轮上报（周期上报约 5 分钟）自动执行，请稍后重试"));
            out.put("supported", false);
        }
        out.put("taskId", taskId);
        return Result.ok(out);
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    @PostMapping("/{sn}/adaptation/save")
    public Result<Map<String, Object>> adaptSave(@PathVariable String sn,
                                                 @RequestBody Map<String, Object> declared) {
        requireOnu(sn, "operate");
        Map<String, CpeParam> params = store.loadParams(sn);
        if (params.isEmpty()) {
            throw new BusinessException(400, "设备还没有采集到参数树，请先执行能力测试");
        }
        DeviceData data = new DeviceData();
        data.loadAll(params);
        Object rootObj = store.findDevice(sn).get("root_node");
        String root = rootObj == null || String.valueOf(rootObj).isBlank()
                ? "InternetGatewayDevice." : String.valueOf(rootObj);
        Map<String, Object> detected = new java.util.HashMap<>(modelCapabilities(data, root));
        long modelId = modelProfileService.saveTemplate(sn, declared, detected);
        return Result.ok(Map.of("templateLibId", modelId));
    }

    private Map<String, Object> modelCapabilities(DeviceData data, String root) {
        return new LinkedHashMap<>(capabilityDetector.detect(data, root));
    }

    private Map<String, Object> sessionInfo(String sn, Map<String, Object> row) {
        CwmpSession session = cwmpService.findSessionBySn(sn);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean online = "online".equals(row.get("status"));
        out.put("online", online && session != null);
        out.put("state", session == null ? 0 : session.getState());
        out.put("pendingCount", store.countQueued(sn));
        out.put("lastContactTime", fmtMs(row.get("last_inform_ms")));
        return out;
    }

    /** snake_case 行转 camelCase */
    private Map<String, Object> camel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            String[] parts = k.split("_");
            StringBuilder sb = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].isEmpty()) {
                    sb.append(Character.toUpperCase(parts[i].charAt(0)))
                            .append(parts[i].substring(1));
                }
            }
            if ("manufacturer".equals(k) && v instanceof String s) {
                String label = BrandName.label(s);
                v = label != null ? label : BrandName.text(s);
            }
            out.put(sb.toString(), v);
        });
        return out;
    }

    private String labelOf(String type) {
        return switch (type == null ? "" : type) {
            case "Reboot" -> "重启设备";
            case "FactoryReset" -> "恢复出厂";
            case "SetParameterValues" -> "下发参数";
            case "GetParameterValues" -> "读取参数";
            case "GetParameterNames" -> "探测参数树";
            case "AddObject" -> "添加节点";
            case "DeleteObject" -> "删除节点";
            case "Download" -> "固件下发";
            case "RefreshBusiness" -> "业务参数采集";
            case "AdaptProbe" -> "机型能力测试";
            case "GetRPCMethods" -> "查询支持的RPC";
            default -> type;
        };
    }

    private String uptimeText(String seconds) {
        if (seconds == null || seconds.isBlank()) {
            return null;
        }
        try {
            long s = (long) Double.parseDouble(seconds.trim());
            long d = s / 86400;
            long h = s % 86400 / 3600;
            long m = s % 3600 / 60;
            if (d > 0) {
                return d + "天" + h + "小时" + m + "分";
            }
            if (h > 0) {
                return h + "小时" + m + "分";
            }
            return m + "分钟";
        } catch (NumberFormatException e) {
            return seconds;
        }
    }

    private String fmtMs(Object o) {
        if (!(o instanceof Number n)) {
            return null;
        }
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date(n.longValue()));
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String strOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
