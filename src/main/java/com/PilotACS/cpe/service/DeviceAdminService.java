/**
 * @author: tangsky001
 * @date:   2026年11月18日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.cpe.model.IspVariant;
import com.PilotACS.cpe.server.CwmpService;
import com.PilotACS.cpe.session.CwmpSession;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.exception.BusinessException;
import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 设备本地管理与敏感信息读取：超管账号查询、Telnet 状态/开关/凭据。 */
@Service
@RequiredArgsConstructor
public class DeviceAdminService {

    /** 本地账号所在的父路径形态（User.{i}. / WebUserInfo.{i}. / TeleComAccount.，含运营商定制前缀） */
    private static final Pattern LOCAL_ACCOUNT_PARENT =
            Pattern.compile("[._](User|WebUserInfo|TeleComAccount)\\.(\\d+\\.)?$");

    /** User 实例探测上限：超管一般就在前两路，多试几路成本可控 */
    private static final int MAX_USER_INSTANCE = 4;

    /** 账号叶子名：TR-069 标准写 Username，华为私有节点里写 UserName */
    private static final Set<String> USERNAME_LEAVES = Set.of("username");
    private static final Set<String> PASSWORD_LEAVES = Set.of("password");

    /** GPV 采集任务等待窗口（秒），前端 axios 超时 90s，这里留出轮询余量 */
    private static final int READ_TIMEOUT_SECONDS = 70;

    /** Telnet 开关下发等待窗口（秒） */
    private static final int TELNET_SWITCH_TIMEOUT_SECONDS = 30;

    private final CpeStore store;
    private final TaskService taskService;
    private final CwmpService cwmpService;
    private final CpeProperties props;

    // ==================== 超管账号查询 ====================

    /** 查询设备本地账号（含超管 telecomadmin 等）。 */
    public Map<String, Object> queryAdminAccount(String sn) {
        Map<String, Object> device = requireDevice(sn);
        String root = rootOf(device);
        String isp = str(device.get("isp"));
        boolean online = sessionAlive(sn) || "online".equals(device.get("status"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sn", sn);
        out.put("online", online);
        out.put("rootNode", root);

        if (!online && blank(str(device.get("connection_request_url")))) {
            // 离线且没有连接请求地址：反向唤醒也没有抓手，直接如实回
            return finish(out, "offline", List.of(),
                    "设备离线且无法反向唤醒：台账里没有这台设备的连接请求地址，"
                            + "检查纳管时设备是否上报了 ConnectionRequestURL");
        }

        // 第一轮候选：参数树里真实采到过的账号路径 + 运营商定制超管节点，都没有再上通用形态。
        Map<String, CpeParam> tree = mergedTree(sn);
        List<String> candidates = adminCandidates(root, isp, tree);
        ReadResult read = readValues(sn, candidates, READ_TIMEOUT_SECONDS);
        if ("fail".equals(read.status)) {
            return finish(out, "fail", List.of(),
                    "读取账号参数失败：" + blankTo(read.fault, "设备回 Fault，重试无效请查后端日志"));
        }
        if ("timeout".equals(read.status)) {
            return finish(out, "timeout", List.of(),
                    online
                            ? "设备 " + READ_TIMEOUT_SECONDS + " 秒内没有回账号参数，稍后重试"
                            : "设备离线，指令已排队，等设备上报（周期上报约 5 分钟）后自动执行，请稍后重试");
        }
        List<Map<String, Object>> accounts = assemble(read.values);
        if (accounts.isEmpty()) {
            return finish(out, "empty", List.of(),
                    "设备没有可用的本地账号节点，定制节点与通用 User 实例都读不到");
        }
        Logs.info(Logs.Module.CPE, "查询设备本地账号完成", "sn", sn, "账号数", accounts.size());
        return finish(out, "success", accounts, null);
    }

    /** GPV 读取结果：status + fault + 值表 */
    private record ReadResult(String status, String fault, Map<String, String> values) {
        static ReadResult of(String status, Map<String, String> values) {
            return new ReadResult(status, null, values);
        }

        static ReadResult of(String status, String fault, Map<String, String> values) {
            return new ReadResult(status, fault, values);
        }
    }

    /** 建一条 GPV 任务读指定参数，等任务完成后再短轮询取值。 */
    private ReadResult readValues(String sn, List<String> names, int timeoutSeconds) {
        if (names.isEmpty()) {
            return ReadResult.of("fail", "没有可读取的参数路径", Map.of());
        }
        long taskId;
        try {
            taskId = taskService.createTask(sn, "GetParameterValues", Map.of("names", names), "console");
        } catch (Exception e) {
            return ReadResult.of("fail", e.getMessage(), Map.of());
        }
        Map<String, Object> task = taskService.awaitTask(taskId, timeoutSeconds);
        String status = task == null ? "timeout" : str(task.get("status"));
        if ("fail".equals(status)) {
            return ReadResult.of("fail", str(task.get("fault_string")), Map.of());
        }
        if (!"success".equals(status)) {
            return ReadResult.of("timeout", Map.of());
        }
        Map<String, String> values = new LinkedHashMap<>();
        long deadline = System.currentTimeMillis() + 6000;
        int lastFound = -1;
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            Map<String, CpeParam> tree = mergedTree(sn);
            int found = 0;
            for (String n : names) {
                CpeParam p = tree.get(n);
                if (p != null && p.value() != null && !p.value().isBlank()) {
                    values.put(n, p.value());
                    found++;
                }
            }
            if (found == lastFound) {
                if (++stable >= 2) {
                    break;
                }
            } else {
                stable = 0;
                lastFound = found;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ReadResult.of("success", values);
    }

    /** 账号候选路径：参数树里真实存在的账号节点 → 运营商定制节点 → 通用形态兜底 */
    private List<String> adminCandidates(String root, String isp, Map<String, CpeParam> tree) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (String p : tree.keySet()) {
            if (p.endsWith(".")) {
                continue;
            }
            String leaf = leafOf(p);
            if (!USERNAME_LEAVES.contains(leaf.toLowerCase()) && !PASSWORD_LEAVES.contains(leaf.toLowerCase())) {
                continue;
            }
            // WAN 拨号账号的叶子名也叫 Username，父路径不是本地账号对象的一律不要
            String parent = parentOf(p);
            if (!LOCAL_ACCOUNT_PARENT.matcher(parent).find()) {
                continue;
            }
            paths.add(parent + "Username");
            paths.add(parent + "UserName");
            paths.add(parent + "Password");
        }
        for (String node : ispAdminNodes(isp)) {
            paths.add(root + node + "Username");
            paths.add(root + node + "Password");
        }
        if (paths.isEmpty()) {
            paths.addAll(genericPaths(root));
        }
        return new ArrayList<>(paths);
    }

    /** 运营商定制固件的超管节点（TeleComAccount），按台账归属走对应节点 */
    private List<String> ispAdminNodes(String isp) {
        String code = str(IspVariant.of(isp));
        return switch (code == null ? "" : code) {
            case "CM" -> List.of("DeviceInfo.X_CMCC_TeleComAccount.");
            case "CT" -> List.of("DeviceInfo.X_CT-COM_TeleComAccount.");
            case "CU" -> List.of("DeviceInfo.X_CU_TeleComAccount.");
            default -> List.of();
        };
    }

    /** 通用形态：华为公开版 Web 账号节点 + TR-098 / TR-181 标准 User 实例 */
    private static List<String> genericPaths(String root) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        // 华为公开版固件，两种叶子写法都试，各固件不一致
        for (int i = 1; i <= MAX_USER_INSTANCE; i++) {
            String base = root + "UserInterface.X_HW_WebUserInfo." + i + ".";
            paths.add(base + "UserName");
            paths.add(base + "Username");
            paths.add(base + "Password");
        }
        for (int i = 1; i <= MAX_USER_INSTANCE; i++) {
            paths.add(root + "User." + i + ".Username");
            paths.add(root + "User." + i + ".Password");
        }
        return new ArrayList<>(paths);
    }

    /** 把读到的路径按账号对象实例配对成账号（WAN 拨号账号已在候选阶段过滤） */
    static List<Map<String, Object>> assemble(Map<String, String> values) {
        Map<String, Map<String, String>> grouped = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String path = entry.getKey();
            String parent = parentOf(path);
            if (!LOCAL_ACCOUNT_PARENT.matcher(parent).find()) {
                continue;
            }
            String field;
            String leaf = leafOf(path).toLowerCase();
            if (USERNAME_LEAVES.contains(leaf)) {
                field = "username";
            } else if (PASSWORD_LEAVES.contains(leaf)) {
                field = "password";
            } else {
                continue;
            }
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(parent, k -> new LinkedHashMap<>())
                    .putIfAbsent(field, value);
        }
        List<Map<String, Object>> accounts = new ArrayList<>(grouped.size());
        for (Map.Entry<String, Map<String, String>> entry : grouped.entrySet()) {
            Map<String, String> fields = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", entry.getKey());
            item.put("instance", instanceOf(entry.getKey()));
            item.put("username", fields.getOrDefault("username", ""));
            item.put("password", fields.getOrDefault("password", ""));
            accounts.add(item);
        }
        return accounts;
    }

    // ==================== Telnet 状态 / 开关 / 凭据 ====================

    /** 查询设备 Telnet 使能状态。 */
    public Map<String, Object> telnetStatus(String sn) {
        Map<String, Object> device = requireDevice(sn);
        Map<String, Object> out = baseOut(sn, device);
        String enablePath = telnetEnablePath(mergedTree(sn));
        if (enablePath == null) {
            return finish(out, "unsupported", null,
                    "设备参数树中未发现 Telnet 开关节点（TelnetEnable / X_CMCC_TelnetEnable 等），"
                            + "请先完成机型适配采集参数树");
        }
        String value = valueOf(mergedTree(sn), enablePath);
        if (value == null || value.isBlank()) {
            // 树里有节点但没值（只做过 GPN 扫描）：补一轮 GPV
            ReadResult read = readValues(sn, List.of(enablePath), TELNET_SWITCH_TIMEOUT_SECONDS);
            value = read.values().get(enablePath);
            if ("fail".equals(read.status)) {
                return finish(out, "fail", null, "读取 Telnet 状态失败：" + blankTo(read.fault, "设备回 Fault"));
            }
            if ("timeout".equals(read.status)) {
                return finish(out, "timeout", null, "设备没有在等待时间内回 Telnet 状态，稍后重试");
            }
        }
        Boolean enabled = value == null ? null : truthy(value);
        out.put("path", enablePath);
        if (enabled == null) {
            return finish(out, "empty", null, "设备没有回 Telnet 使能值");
        }
        out.put("enabled", enabled);
        return finish(out, enabled ? "enabled" : "disabled", null, null);
    }

    /** 开启 / 关闭 Telnet（可选同时切换 WAN 侧访问）。 */
    public Map<String, Object> setTelnet(String sn, boolean enabled, Boolean wanAccess) {
        Map<String, Object> device = requireDevice(sn);
        Map<String, Object> out = baseOut(sn, device);
        Map<String, CpeParam> tree = mergedTree(sn);
        String enablePath = telnetEnablePath(tree);
        if (enablePath == null) {
            return finish(out, "unsupported", null,
                    "设备参数树中未发现 Telnet 开关节点，请先完成机型适配采集参数树");
        }
        List<Map<String, Object>> values = new ArrayList<>();
        values.add(nv(enablePath, enabled ? "1" : "0"));
        // WAN 侧访问：勾了才下发，参数树里有对应节点才写（各厂商节点名不一）
        String wanPath = wanAccess == null ? null : wanAccessPath(tree);
        if (wanPath != null) {
            values.add(nv(wanPath, wanAccess ? "1" : "0"));
        }
        long taskId = taskService.createTask(sn, "SetParameterValues",
                Map.of("values", values), "console");
        Map<String, Object> task = taskService.awaitTask(taskId, TELNET_SWITCH_TIMEOUT_SECONDS);
        String status = task == null ? "timeout" : str(task.get("status"));
        out.put("taskId", taskId);
        out.put("path", enablePath);
        if ("success".equals(status)) {
            Logs.info(Logs.Module.CPE, "Telnet 开关下发成功", "sn", sn,
                    "目标", enabled ? "开启" : "关闭", "路径", enablePath);
            return finish(out, "success", null,
                    wanPath == null && wanAccess != null ? "已下发 Telnet 开关；设备参数树中没有 WAN 侧访问节点，该项未下发" : null);
        }
        if ("fail".equals(status)) {
            return finish(out, "fail", null, "Telnet 开关下发失败：" + blankTo(str(task.get("fault_string")),
                    "设备回 Fault，确认参数是否可写"));
        }
        return finish(out, "timeout", null,
                "设备离线，指令已排队（任务 " + taskId + "），等设备上报后自动执行");
    }

    /** 查询 Telnet 状态及默认账号密码；未开启会自动下发开启指令再读。 */
    public Map<String, Object> telnetCredentials(String sn) {
        Map<String, Object> device = requireDevice(sn);
        Map<String, Object> out = baseOut(sn, device);

        // 1. 使能状态
        Map<String, CpeParam> tree = mergedTree(sn);
        String enablePath = telnetEnablePath(tree);
        if (enablePath == null) {
            return finish(out, "unsupported", null,
                    "设备参数树中未发现 Telnet 开关节点，请先完成机型适配采集参数树");
        }
        String enableValue = valueOf(tree, enablePath);
        Boolean enabled;
        if (enableValue == null || enableValue.isBlank()) {
            ReadResult read = readValues(sn, List.of(enablePath), TELNET_SWITCH_TIMEOUT_SECONDS);
            if ("fail".equals(read.status)) {
                return finish(out, "fail", null, "读取 Telnet 状态失败：" + blankTo(read.fault, "设备回 Fault"));
            }
            if ("timeout".equals(read.status)) {
                return finish(out, "timeout", null, "设备没有在等待时间内回 Telnet 状态，稍后重试");
            }
            enableValue = read.values().get(enablePath);
        }
        enabled = enableValue == null || enableValue.isBlank() ? null : truthy(enableValue);
        if (enabled == null) {
            return finish(out, "empty", null, "设备没有回 Telnet 使能值");
        }
        out.put("enabled", enabled);
        out.put("path", enablePath);

        // 2. 未开启自动开启：受理即发，不建工单，进度走下发任务
        if (!enabled) {
            Map<String, Object> sw = setTelnet(sn, true, null);
            out.put("enablePath", enablePath);
            out.put("enableTaskId", sw.get("taskId"));
            if (!"success".equals(sw.get("status"))) {
                out.put("status", sw.get("status"));
                out.put("message", "Telnet 原为关闭，自动开启未成功：" + sw.get("message"));
                return out;
            }
            out.put("enabled", true);
        }

        // 3. 读默认账号密码：参数树里真实存在的优先，没有再按常规路径试探
        List<String> credPaths = credentialPaths(mergedTree(sn), rootOf(device));
        if (credPaths.isEmpty()) {
            out.put("status", "success");
            out.put("statusText", "已开启");
            out.put("statusLevel", "success");
            out.put("credentialStatus", "empty");
            out.put("message", "Telnet 已开启，但设备参数树中没有 TelnetUserName / TelnetPassword / TelnetPort 节点");
            return out;
        }
        ReadResult read = readValues(sn, credPaths, READ_TIMEOUT_SECONDS);
        if ("fail".equals(read.status) || "timeout".equals(read.status)) {
            out.put("status", read.status);
            out.put("statusText", "fail".equals(read.status) ? "失败" : "读取超时");
            out.put("statusLevel", "fail".equals(read.status) ? "danger" : "warning");
            out.put("credentialStatus", read.status);
            out.put("message", "Telnet 已开启，读取账号密码失败："
                    + blankTo(read.fault, "设备没有在等待时间内回值"));
            return out;
        }
        Map<String, String> values = read.values();
        String username = firstValue(values, credPaths, "telnetusername");
        String password = firstValue(values, credPaths, "telnetpassword");
        String port = firstValue(values, credPaths, "telnetport");
        out.put("username", username == null ? "" : username);
        out.put("password", password == null ? "" : password);
        out.put("port", port == null ? "" : port);
        boolean empty = (username == null || username.isBlank()) && (password == null || password.isBlank());
        out.put("credentialStatus", empty ? "empty" : "success");
        return finish(out, "success", null, empty ? "设备没有回 Telnet 账号密码" : null);
    }

    /** Telnet 凭据路径：树里有的优先，都没有按根节点常规路径试探 */
    private List<String> credentialPaths(Map<String, CpeParam> tree, String root) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (String p : tree.keySet()) {
            if (p.endsWith(".")) {
                continue;
            }
            String leaf = leafOf(p).toLowerCase();
            if (leaf.equals("telnetusername") || leaf.equals("telnetpassword") || leaf.equals("telnetport")) {
                paths.add(p);
            }
        }
        if (paths.isEmpty()) {
            paths.add(root + "TelnetUserName");
            paths.add(root + "TelnetPassword");
            paths.add(root + "TelnetPort");
        }
        return new ArrayList<>(paths);
    }

    /** Telnet 使能节点：参数树里叶子名为 TelnetEnable 的路径（含 X_CMCC_/X_CT-COM_ 前缀变体） */
    private static String telnetEnablePath(Map<String, CpeParam> tree) {
        for (String p : tree.keySet()) {
            if (!p.endsWith(".") && leafOf(p).equalsIgnoreCase("TelnetEnable")) {
                return p;
            }
        }
        return null;
    }

    /** WAN 侧访问节点：叶子名 WANAccess / RemoteAccess / TelnetWanAccess 等 */
    private static String wanAccessPath(Map<String, CpeParam> tree) {
        Set<String> want = Set.of("wanaccess", "remoteaccess", "telnetwanaccess", "wanremoteaccess");
        for (String p : tree.keySet()) {
            if (!p.endsWith(".") && want.contains(leafOf(p).toLowerCase())) {
                return p;
            }
        }
        return null;
    }

    // ==================== 公共工具 ====================

    /** 设备存在校验，返回台账行 */
    private Map<String, Object> requireDevice(String sn) {
        try {
            return store.findDevice(sn);
        } catch (Exception e) {
            throw new BusinessException(404, "设备不存在");
        }
    }

    private Map<String, Object> baseOut(String sn, Map<String, Object> device) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sn", sn);
        out.put("online", sessionAlive(sn) || "online".equals(device.get("status")));
        return out;
    }

    /** 统一补状态文案与标签色（前端 statusText / statusLevel） */
    private Map<String, Object> finish(Map<String, Object> out, String status,
                                       List<Map<String, Object>> accounts, String message) {
        out.put("status", status);
        out.put("message", message);
        if (accounts != null) {
            out.put("accounts", accounts);
        }
        switch (status) {
            case "success", "enabled" -> {
                out.put("statusText", "success".equals(status) ? "成功" : "已开启");
                out.put("statusLevel", "success");
            }
            case "disabled", "empty", "unsupported" -> {
                out.put("statusText", switch (status) {
                    case "disabled" -> "未开启";
                    case "empty" -> "无结果";
                    default -> "不支持";
                });
                out.put("statusLevel", "info");
            }
            case "timeout" -> {
                out.put("statusText", "超时");
                out.put("statusLevel", "warning");
            }
            default -> {
                out.put("statusText", "offline".equals(status) ? "离线" : "失败");
                out.put("statusLevel", "danger");
            }
        }
        return out;
    }

    /** 合并参数树：落库树打底，活跃会话的内存树覆盖（GPV 刚采的值落库有延迟，会话里的最新）。 */
    private Map<String, CpeParam> mergedTree(String sn) {
        Map<String, CpeParam> out = new LinkedHashMap<>();
        try {
            out.putAll(store.loadParams(sn));
        } catch (Exception ignore) {
            // 设备还没采集过参数树
        }
        CwmpSession session = cwmpService.findSessionBySn(sn);
        if (session != null && !session.isEnded()) {
            out.putAll(session.getData().all());
        }
        return out;
    }

    private boolean sessionAlive(String sn) {
        CwmpSession session = cwmpService.findSessionBySn(sn);
        return session != null && !session.isEnded();
    }

    /** 台账登记的根节点，没登记按配置默认（TR-098）处理 */
    private String rootOf(Map<String, Object> device) {
        String root = str(device.get("root_node"));
        return root == null || root.isBlank() ? props.getDefaultRootNode() : root;
    }

    private static String valueOf(Map<String, CpeParam> tree, String path) {
        CpeParam p = tree.get(path);
        return p == null ? null : p.value();
    }

    /** "1"/"true"/"enable"/"on" 视为开 */
    private static boolean truthy(String value) {
        String v = value.trim().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("enable") || v.equals("enabled") || v.equals("on");
    }

    /** 从值表里按叶子名取第一个非空值 */
    private static String firstValue(Map<String, String> values, List<String> paths, String leafLower) {
        for (String p : paths) {
            if (leafOf(p).toLowerCase().equals(leafLower)) {
                String v = values.get(p);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> nv(String name, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        m.put("type", "string");
        return m;
    }

    /** 路径叶子名，如 …User.1.Username → Username */
    private static String leafOf(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String p = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
        int i = p.lastIndexOf('.');
        return i < 0 ? p : p.substring(i + 1);
    }

    /** 路径父对象，如 …User.1.Username → …User.1. */
    private static String parentOf(String path) {
        String p = path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
        int i = p.lastIndexOf('.');
        return i < 0 ? "" : p.substring(0, i + 1);
    }

    /** 从 {@code …User.2.} 里取实例号，TeleComAccount 这类无实例节点返回空串 */
    private static String instanceOf(String parent) {
        Matcher m = LOCAL_ACCOUNT_PARENT.matcher(parent);
        return m.find() && m.group(2) != null ? m.group(2).replace(".", "") : "";
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankTo(String s, String fallback) {
        return blank(s) ? fallback : s;
    }
}
