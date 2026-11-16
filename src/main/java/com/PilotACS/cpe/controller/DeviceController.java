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
import com.PilotACS.cpe.service.DispatchService;
import com.PilotACS.cpe.service.ModelProfileService;
import com.PilotACS.cpe.service.TaskService;
import com.PilotACS.cpe.server.CwmpService;
import com.PilotACS.cpe.session.CwmpSession;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.exception.BusinessException;
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

/**
 * 设备管理接口（前端 web/src/api/device.js）
 */
@RestController
@RequestMapping("/device")
@RequiredArgsConstructor
public class DeviceController {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 排序键白名单（列名），pon/ip/upTime 由参数树派生，退回最近上报排序 */
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
    /** 光猫资源组 ACL：台账可见性与单设备操作校验（权限归属在「光猫设备」分配树页完成） */
    private final DeviceAclService aclService;

    private static final Map<String, String> ACTION_TEXT = Map.of(
            "view", "查看", "operate", "操作", "update", "编辑", "delete", "删除");

    /** 单设备动作校验：无授权直接 403（管理员/未配置 ACL 放行） */
    private void requireOnu(String sn, String action) {
        if (!aclService.allowedOnu(UserContext.get(), sn, action)) {
            throw new BusinessException(403, "无该光猫的" + ACTION_TEXT.getOrDefault(action, action) + "权限");
        }
    }

    // ==================== 台账 ====================

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
        // 资源组 ACL：只显示当前用户有 view 授权的光猫（null = 不限制）
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
            // 在线口径与 /device/stat 一致：台账 status 列（15 分钟没上报会被 sweep 翻成 offline）
            item.put("online", "online".equals(row.get("status")));
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
        return Result.ok(Map.of("device", device, "session", sessionInfo(sn, row)));
    }

    @GetMapping("/{sn}/status")
    public Result<Map<String, Object>> status(@PathVariable String sn) {
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

    // ==================== 轮询/元数据 ====================

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

    // ==================== 下发 ====================

    @PostMapping("/dispatch")
    public Result<Map<String, Object>> dispatch(@RequestBody Map<String, Object> body) {
        // 批量下发前逐台校验操作权限
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
        return Result.ok(dispatchService.dispatch(body));
    }

    @PostMapping("/{sn}/rpc/{rpcName}")
    public Result<Map<String, Object>> dispatchRpc(@PathVariable String sn,
                                                   @PathVariable String rpcName,
                                                   @RequestBody(required = false) Map<String, Object> args) {
        requireOnu(sn, "operate");
        Map<String, Object> params = args == null ? Map.of() : args;
        long taskId = taskService.createTask(sn, rpcName, params, "console");
        return Result.ok(Map.of("queued", true, "taskId", taskId));
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
            if (o instanceof Map<?, ?> m) {
                values.add(Map.of("name", str(m.get("name")), "value", str(m.get("value")),
                        "type", m.get("type") == null ? "string" : str(m.get("type"))));
            }
        }
        taskService.createTask(sn, "SetParameterValues", Map.of("values", values), "console");
        return Result.ok();
    }

    // ==================== 业务快照 ====================

    @GetMapping("/{sn}/business")
    public Result<Map<String, Object>> business(@PathVariable String sn) {
        requireOnu(sn, "view");
        Map<String, Object> device = store.findDevice(sn);
        // 优先读落库快照（每次会话结束实时刷新），没有再现算
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
        // 有参数树走轻量 GPV（只刷 WAN/WIFI/SIP 叶子，十几秒内回来）；没树才做 GPN 全扫描
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
            // 采集成功：快照同步落库，下次面板直接读快照
            try {
                store.saveSnapshot(sn, snapshotService.toJson(snap));
            } catch (Exception ignore) {
                // 落库失败不影响本次返回
            }
        }
        return Result.ok(snap);
    }

    // ==================== 机型适配 ====================

    @PostMapping("/{sn}/adaptation/test")
    public Result<Map<String, Object>> adaptTest(@PathVariable String sn,
                                                 @RequestBody Map<String, Object> declared) {
        requireOnu(sn, "operate");
        long taskId = taskService.createTask(sn, "AdaptProbe", declared, "console");
        Map<String, Object> task = taskService.awaitTask(taskId, props.getCollectTimeoutSeconds());
        if (task == null) {
            throw new BusinessException(500, "能力测试任务不存在");
        }
        Map<String, Object> result = taskService.resultOf(task);
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("collectInterrupted", "fail".equals(str(task.get("status"))));
        if ("fail".equals(str(task.get("status")))) {
            out.put("collectStatus", "fail");
            out.put("reasons", List.of(str(task.get("fault_string"))));
            out.put("supported", false);
        }
        out.put("taskId", taskId);
        return Result.ok(out);
    }

    @PostMapping("/{sn}/adaptation/save")
    public Result<Map<String, Object>> adaptSave(@PathVariable String sn,
                                                 @RequestBody Map<String, Object> declared) {
        requireOnu(sn, "operate");
        // 先做一次能力比对（用库里已采集的参数树），达标才允许入库
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
        // 复用探测器的实测逻辑（只是不做比对）
        return Map.of(
                "lanPortCount", lanPortCount(data),
                "wifiBands", wifiBands(data),
                "sipCount", sipCount(data));
    }

    private int lanPortCount(DeviceData data) {
        Set<String> keys = new java.util.LinkedHashSet<>();
        java.util.regex.Pattern tr098 = java.util.regex.Pattern
                .compile("\\.LANDevice\\.(\\d+)\\.LANEthernetInterface(?:Config)?\\.(\\d+)\\.");
        java.util.regex.Pattern tr181 = java.util.regex.Pattern.compile("\\.Ethernet\\.Interface\\.(\\d+)\\.");
        for (String p : data.allPaths("")) {
            var m = tr098.matcher(p);
            if (m.find()) {
                keys.add(m.group(1) + '.' + m.group(2));
                continue;
            }
            m = tr181.matcher(p);
            if (m.find()) {
                keys.add(m.group(1));
            }
        }
        return keys.size();
    }

    private List<String> wifiBands(DeviceData data) {
        Set<String> bands = new java.util.LinkedHashSet<>();
        for (String p : data.allPaths("")) {
            String leaf = p.substring(p.lastIndexOf('.') + 1);
            String v = data.value(p);
            if (v == null || v.isBlank()) {
                continue;
            }
            if ("OperatingFrequencyBand".equals(leaf)) {
                if (v.contains("2.4")) {
                    bands.add("2.4G");
                }
                if (v.contains("5")) {
                    bands.add("5G");
                }
            } else if ("Standard".equals(leaf) || "OperatingStandards".equals(leaf)) {
                Set<String> toks = Set.of(v.toLowerCase().split("[,;\\s]+"));
                if (toks.contains("b") || toks.contains("g") || toks.contains("n")) {
                    bands.add("2.4G");
                }
                if (toks.contains("a") || toks.contains("ac") || toks.contains("ax")) {
                    bands.add("5G");
                }
            }
        }
        return new ArrayList<>(bands);
    }

    private int sipCount(DeviceData data) {
        java.util.regex.Pattern pat = java.util.regex.Pattern
                .compile("\\.VoiceService\\.(\\d+)\\.VoiceProfile\\.(\\d+)\\.Line\\.(\\d+)\\.");
        Set<String> lines = new java.util.LinkedHashSet<>();
        for (String p : data.allPaths("")) {
            var m = pat.matcher(p);
            if (m.find()) {
                lines.add(m.group(1) + '.' + m.group(2) + '.' + m.group(3));
            }
        }
        return lines.size();
    }

    // ==================== 工具 ====================

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

    /** snake_case 行 → camelCase（台账字段直接映射前端字段名），厂家字段顺带转中文品牌 */
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
                v = BrandName.text(s);
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
