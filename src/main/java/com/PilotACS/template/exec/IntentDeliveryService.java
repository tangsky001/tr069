package com.PilotACS.template.exec;

import com.PilotACS.cpe.model.DeviceData;
import com.PilotACS.cpe.service.BusinessPaths;
import com.PilotACS.cpe.service.TaskService;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图下发服务：把配置模板的意图字段翻译成设备真实参数并执行。
 *
 * 与参考实现的差异：本版不依赖 SQLite 模板包的业务表，而是直接用设备的存量参数树
 * （cpe_model_param，会话期已灌入 DeviceData）做叶子探测——设备上有什么叶子就用什么，
 * 什么都没有就明确记录「跳过/失败」，绝不盲发。
 *
 * 步骤都走现有任务管线（TaskService：ADD / DEL / SPV / REBOOT），一条条同步等待，
 * 保证串行语义，结果逐条记入 plan detail。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentDeliveryService {

    /** SPV 等待上限：普通参数包几十秒内应答 */
    private static final long SPV_TIMEOUT_MS = 120_000L;
    /** AddObject / DeleteObject 等待上限：光猫重建 WAN 对象会慢一些 */
    private static final long OBJECT_TIMEOUT_MS = 180_000L;
    /** Reboot 等待上限 */
    private static final long REBOOT_TIMEOUT_MS = 180_000L;

    private final CpeStore store;
    private final TaskService taskService;

    // ==================== 入口 ====================

    /**
     * 下发单个意图（同步阻塞，逐条任务等待）。
     * 返回 { success, error, detail:[{step, path, status, message}] }。
     */
    public Map<String, Object> deliver(String sn, String category, Map<String, Object> fields,
                                       boolean incremental, String username) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, String>> detail = new ArrayList<>();
        out.put("detail", detail);
        try {
            DeviceData data = new DeviceData();
            data.loadAll(store.loadParams(sn));
            if (data.all() == null || data.all().isEmpty()) {
                throw new BusinessException("设备 " + sn + " 无存量参数树（未采集过），先做一次全量同步");
            }
            String root = BusinessPaths.detectRoot(data, null);
            if (root == null) {
                throw new BusinessException("无法识别设备数据模型（TR-098 / TR-181）");
            }

            List<Map.Entry<String, String>> plan = new ArrayList<>();
            switch (category.toUpperCase(Locale.ROOT)) {
                case "WAN_DHCP", "WAN_PPPOE", "WAN_STATIC", "WAN_BRIDGE" ->
                        renderWan(root, data, category, fields, incremental, plan, detail);
                case "WIFI_24G", "WIFI_58G" ->
                        renderWifi(root, data, category, fields, plan, detail);
                case "SIP" ->
                        renderSip(root, data, fields, plan, detail);
                case "OTHER" ->
                        renderOther(root, data, fields, plan, detail);
                default -> throw new BusinessException("未知模板类型: " + category);
            }

            // 逐条执行计划；AddObject 返回的新实例号回填到后续步骤的 __NEW__ 占位
            boolean allOk = true;
            String newInstance = null;
            Map<String, String> expected = new LinkedHashMap<>();
            for (Map.Entry<String, String> step : plan) {
                String desc = step.getKey();
                Map<String, Object> taskParams = parseParams(step.getValue());
                String type = String.valueOf(taskParams.remove("__type"));
                String path = String.valueOf(taskParams.remove("__path"));
                String fallback = String.valueOf(taskParams.remove("__fallback"));
                // lambda 内要用 effectively final 变量
                final String newOrFallback = newInstance != null ? newInstance : fallback;
                String realPath = path.contains("__NEW__") ? path.replace("__NEW__", newOrFallback) : path;
                // SPV 参数路径里也可能带占位
                Object values = taskParams.get("values");
                if (values instanceof List<?> list) {
                    List<Map<String, String>> fixed = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> mv) {
                            Map<String, String> nv = new LinkedHashMap<>();
                            mv.forEach((k, v) -> {
                                String sv = String.valueOf(v);
                                nv.put(String.valueOf(k),
                                        sv.contains("__NEW__") ? sv.replace("__NEW__", newOrFallback) : sv);
                            });
                            fixed.add(nv);
                        }
                    }
                    taskParams.put("values", fixed);
                }
                Map<String, Object> stepResult = runTask(sn, type, realPath, taskParams, username);
                boolean ok = stepResult != null && Boolean.TRUE.equals(stepResult.get("ok"));
                if ("AddObject".equals(type) && ok) {
                    String result = String.valueOf(stepResult.getOrDefault("result_content", ""));
                    Matcher m = Pattern.compile("(?i)instance[^0-9]{0,6}(\\d+)").matcher(result);
                    newInstance = m.find() ? m.group(1) : fallback;
                }
                detail.add(row("执行: " + desc, realPath, ok ? "ok" : "fail",
                        ok ? "" : String.valueOf(stepResult == null ? "无结果" : stepResult.get("fault"))));
                if (ok && "SetParameterValues".equals(type)
                        && taskParams.get("values") instanceof List<?> vlist) {
                    for (Object item : vlist) {
                        if (item instanceof Map<?, ?> mv) {
                            String name = String.valueOf(mv.get("name"));
                            String val = String.valueOf(mv.get("value"));
                            // 口令类参数设备常回掩码，不参与回读比对
                            if (name.contains("Password") || name.contains("PreSharedKey")
                                    || name.contains("KeyPassphrase")) {
                                continue;
                            }
                            expected.put(name, val);
                        }
                    }
                }
                if (!ok) {
                    allOk = false;
                    break; // 一条失败即中止，避免半套参数继续发
                }
            }
            out.put("success", allOk);
            if (allOk) {
                verify(sn, expected, detail, out);
            }
            out.put("error", allOk ? "" : "存在失败步骤，详见 detail");
            return out;
        } catch (BusinessException e) {
            detail.add(row("计划", "", "fail", e.getMessage()));
            out.put("success", false);
            out.put("error", e.getMessage());
            return out;
        } catch (Exception e) {
            log.warn("意图下发异常 sn={} category={}", sn, category, e);
            detail.add(row("计划", "", "fail", e.getMessage()));
            out.put("success", false);
            out.put("error", e.getMessage());
            return out;
        }
    }

    // ==================== 单任务执行 ====================

    /** 走任务管线同步执行一条 RPC，超时视为失败 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> runTask(String sn, String type, String path,
                                        Map<String, Object> taskParams, String username) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (path != null && !path.isBlank()) {
            params.put("path", path);
        }
        if (taskParams != null) {
            params.putAll(taskParams);
        }
        long timeoutMs = switch (type) {
            case "SPV" -> SPV_TIMEOUT_MS;
            case "Reboot" -> REBOOT_TIMEOUT_MS;
            default -> OBJECT_TIMEOUT_MS;
        };
        long taskId = taskService.createTask(sn, type, params, username);
        Map<String, Object> task = taskService.awaitTask(taskId, (int) (timeoutMs / 1000));
        if (task == null) {
            return Map.of("ok", false, "fault", "任务超时");
        }
        String status = String.valueOf(task.get("status"));
        boolean ok = "success".equals(status);
        String fault = String.valueOf(task.get("fault_code"));
        if (fault == null || "null".equals(fault) || fault.isBlank()) {
            fault = String.valueOf(task.get("result_content"));
        }
        return Map.of("ok", ok, "fault", ok ? "" : fault);
    }

    private Map<String, Object> parseParams(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new BusinessException("任务参数解析失败: " + e.getMessage());
        }
    }

    // ==================== 回读确认 ====================

    /** 下发成功后 GPV 回读比对，确认设备真正生效 */
    private void verify(String sn, Map<String, String> expected,
                        List<Map<String, String>> detail, Map<String, Object> out) {
        if (expected.isEmpty()) {
            out.put("verified", "skip");
            return;
        }
        try {
            long taskId = taskService.createTask(sn, "GetParameterValues",
                    Map.of("names", List.copyOf(expected.keySet())), "verify");
            taskService.awaitTask(taskId, 60);
            DeviceData data = new DeviceData();
            data.loadAll(store.loadParams(sn));
            int diff = 0;
            for (Map.Entry<String, String> e : expected.entrySet()) {
                String actual = data.has(e.getKey()) ? data.value(e.getKey()) : null;
                if (actual == null) {
                    detail.add(row("回读确认", e.getKey(), "fail", "设备未上报该参数"));
                    diff++;
                } else if (!same(actual, e.getValue())) {
                    detail.add(row("回读确认", e.getKey(), "fail",
                            "期望 " + e.getValue() + "，设备实际 " + actual));
                    diff++;
                }
            }
            out.put("verified", diff == 0 ? "yes" : "partial");
            out.put("verifyTotal", expected.size());
            out.put("verifyDiff", diff);
            if (diff == 0) {
                detail.add(row("回读确认", expected.size() + " 个参数全部一致", "ok", ""));
            }
        } catch (Exception e) {
            log.warn("回读确认失败 sn={}", sn, e);
            out.put("verified", "unknown");
            detail.add(row("回读确认", "", "skip", "回读失败: " + e.getMessage()));
        }
    }

    private static boolean same(String a, String b) {
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static Map<String, String> row(String step, String path, String status, String message) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("step", step);
        row.put("path", path == null ? "" : path);
        row.put("status", status);
        row.put("message", message == null ? "" : message);
        return row;
    }

    // ==================== 参数渲染：WAN ====================

    /**
     * WAN 类意图：先清理存量（非增量时，跳过带 TR069 的连接），再 AddObject，再 SPV。
     * 建哪个对象由意图决定：PPPoE 路由 / PPPoE 桥接 → WANPPPConnection，其余 → WANIPConnection。
     */
    private void renderWan(String root, DeviceData data, String category, Map<String, Object> fields,
                           boolean incremental,
                           List<Map.Entry<String, String>> plan, List<Map<String, String>> detail) {
        String wanDevicePrefix = root + "WANDevice.";
        List<Integer> wanDevices = data.instances(wanDevicePrefix);
        // 找到第一个 WANConnectionDevice 实例（绝大多数光猫只有 1 个）
        String connDevice = null;
        outer:
        for (Integer wd : wanDevices) {
            String connPrefix = wanDevicePrefix + wd + ".WANConnectionDevice.";
            for (Integer cd : data.instances(connPrefix)) {
                connDevice = connPrefix + cd;
                break outer;
            }
        }
        if (connDevice == null) {
            throw new BusinessException("设备参数树无 WANConnectionDevice 实例，无法下发 WAN 业务");
        }

        boolean pppoe = "WAN_PPPOE".equals(category)
                || ("WAN_BRIDGE".equals(category) && "PPPoE".equalsIgnoreCase(text(fields, "bridge_type")));
        boolean bridge = "WAN_BRIDGE".equals(category);
        String connObj = connDevice + (pppoe ? ".WANPPPConnection." : ".WANIPConnection.");
        String serviceList = text(fields, "service_list");
        boolean keepTr069 = !bridge && serviceList != null && serviceList.toUpperCase(Locale.ROOT).contains("TR069");

        // 1) 清理存量连接（增量跳过）：同类型对象逐个删，带 TR069 的跳过
        if (!incremental) {
            for (String connType : List.of("WANIPConnection", "WANPPPConnection")) {
                for (Integer i : data.instances(connDevice + "." + connType + ".")) {
                    String existing = connDevice + "." + connType + "." + i;
                    String svc = findLeafValue(data, existing, "ServiceList", "X_CMCC_ServiceList");
                    if (svc != null && svc.toUpperCase(Locale.ROOT).contains("TR069")) {
                        detail.add(row("跳过存量连接", existing, "skip", "承载 TR069，不动"));
                        continue;
                    }
                    plan.add(step("删除存量 " + connType + "." + i,
                            taskJson("DeleteObject", existing, Map.of())));
                }
            }
        }

        // 2) AddObject 建新连接；增量模式若已有空闲实例则复用第一条
        String base;
        List<Integer> existing = data.instances(connObj);
        if (incremental && !existing.isEmpty()) {
            base = connObj + existing.get(0) + ".";
            detail.add(row("复用增量连接", base, "ok", ""));
        } else {
            int known = existing.size();
            plan.add(step("新增实例 " + connObj,
                    taskJson("AddObject", connObj, Map.of("__fallback", known + 1))));
            base = connObj + "__NEW__.";
            detail.add(row("新实例号", connObj, "ok",
                    "以 AddObject 返回为准，兜底为 " + (known + 1)));
        }

        // 3) 渲染参数
        Map<String, String> values = new LinkedHashMap<>();
        boolean routed = !bridge;
        values.put("Enable", "1");
        if (routed) {
            values.put(leaf(data, base, "ConnectionType"), "IP_Routed");
        } else {
            values.put(leaf(data, base, "ConnectionType"), pppoe ? "PPPoE_Bridged" : "IP_Bridged");
        }
        String name = "WAN_" + (text(fields, "vlan_id") == null ? "" : text(fields, "vlan_id"))
                + (bridge ? "_BRIDGE" : "") + "_" + category.replace("WAN_", "");
        values.put(leaf(data, base, "Name"), name);
        if (routed && !pppoe) {
            values.put(leaf(data, base, "NATEnabled"), "1");
        }
        if (serviceList != null && !serviceList.isBlank()) {
            values.put(leaf(data, base, "ServiceList", "X_CMCC_ServiceList"), serviceList);
        }
        // VLAN：CMCC 设备用 X_CMCC_VLANIDMark（vlan 优先级合成「v p」），标准设备用 VLANIDMark
        String vlanId = text(fields, "vlan_id");
        if (vlanId != null && !vlanId.isBlank()) {
            String prio = text(fields, "vlan_priority");
            String combined = vlanId + " " + (prio == null || prio.isBlank() ? "0" : prio);
            String cmcc = leaf(data, base, "X_CMCC_VLANIDMark");
            if (cmcc != null) {
                values.put(cmcc, combined);
            }
            String std = leaf(data, base, "VLANIDMark", "X_CT-COM_VLANIDMark");
            if (std != null) {
                values.put(std, vlanId);
            }
        }
        String proto = text(fields, "ip_protocol");
        if (proto != null && !proto.isBlank() && !"IPv4".equalsIgnoreCase(proto)) {
            values.put(leaf(data, base, "X_CMCC_IPMode"), proto);
        }
        String mtu = text(fields, "mtu");
        if (mtu != null && !mtu.isBlank()) {
            values.put(leaf(data, base, "MaxMTUSize"), mtu);
        }
        String multicastVlan = text(fields, "multicast_vlan");
        if (multicastVlan != null && !multicastVlan.isBlank() && !"0".equals(multicastVlan)) {
            values.put(leaf(data, base, "X_CMCC_MulticastVlan", "MulticastVlan"), multicastVlan);
        }
        if ("WAN_PPPOE".equals(category)) {
            values.put(leaf(data, base, "Username"), textOrEmpty(fields, "username"));
            values.put(leaf(data, base, "Password"), textOrEmpty(fields, "password"));
        }
        if ("WAN_STATIC".equals(category)) {
            values.put(leaf(data, base, "AddressingType"), "Static");
            values.put(leaf(data, base, "ExternalIPAddress"), textOrEmpty(fields, "external_ip"));
            values.put(leaf(data, base, "SubnetMask"), textOrEmpty(fields, "subnet_mask"));
            values.put(leaf(data, base, "DefaultGateway"), textOrEmpty(fields, "default_gateway"));
        }
        // 绑定端口依赖机型适配表（参考实现的 AdaptationManager），本版不做，明确记录
        if (fields.get("bind_ports") != null) {
            detail.add(row("绑定端口", "", "skip", "需机型适配表，本版未实现，已在下发结果中标注"));
        }
        emitSpv(plan, "WAN 参数包", values, detail);
    }

    // ==================== 参数渲染：WiFi ====================

    private void renderWifi(String root, DeviceData data, String category, Map<String, Object> fields,
                            List<Map.Entry<String, String>> plan, List<Map<String, String>> detail) {
        boolean band5 = "WIFI_58G".equals(category);
        // 兼容标准名 LANWLANConfiguration 与私有变体 WLANConfiguration（如 CIOT）
        String cfgBase = root + "LANDevice.1.LANWLANConfiguration.";
        if (data.instances(cfgBase).isEmpty()) {
            cfgBase = root + "LANDevice.1.WLANConfiguration.";
        }
        List<Integer> instances = data.instances(cfgBase);
        if (instances.isEmpty()) {
            throw new BusinessException("设备参数树无 WLAN 配置实例，无法下发 WiFi");
        }
        // 按频段选实例：看 Standard 叶子里的频段关键字，找不到就取第 1/第 2 个
        String base = null;
        for (Integer i : instances) {
            String std = findLeafValue(data, cfgBase + i, "Standard");
            String s = std == null ? "" : std.toLowerCase(Locale.ROOT);
            boolean has5 = s.contains("802.11a") || s.contains("802.11ac") || s.contains("802.11ax");
            if (band5 == has5) {
                base = cfgBase + i + ".";
                break;
            }
        }
        if (base == null) {
            int pick = band5 && instances.size() > 1 ? instances.get(1) : instances.get(0);
            base = cfgBase + pick + ".";
            detail.add(row("频段实例匹配", base, "ok", "未识别到频段标记，按序取实例 " + pick));
        }

        Map<String, String> values = new LinkedHashMap<>();
        String hw = text(fields, "radio_hardware_enable");
        if (hw != null && !hw.isBlank()) {
            values.put(leaf(data, base, "Enable"), "true".equalsIgnoreCase(hw) ? "1" : "0");
        }
        if (text(fields, "radio_enable") != null) {
            values.put(leaf(data, base, "Enable"), "true".equalsIgnoreCase(text(fields, "radio_enable")) ? "1" : "0");
        }
        String ssid = text(fields, "ssid");
        if (ssid != null && !ssid.isBlank()) {
            values.put(leaf(data, base, "SSID"), ssid);
        }
        String channel = text(fields, "channel");
        if (channel != null && !channel.isBlank() && !"auto".equalsIgnoreCase(channel)) {
            values.put(leaf(data, base, "Channel"), channel);
        }
        String hidden = text(fields, "hidden");
        if (hidden != null) {
            values.put(leaf(data, base, "HideSSID", "X_CMCC_HideSSID"), "true".equalsIgnoreCase(hidden) ? "1" : "0");
        }
        String wps = text(fields, "wps_disable");
        if ("true".equalsIgnoreCase(wps)) {
            values.put(leaf(data, base, "X_CMCC_WpsDisable", "X_CT-COM_WPSDisable"), "1");
        }
        String tx = text(fields, "tx_power");
        if (tx != null && !tx.isBlank() && !"auto".equalsIgnoreCase(tx)) {
            values.put(leaf(data, base, "TransmitPower"), tx);
        }
        String encryption = text(fields, "encryption");
        if (encryption != null && !encryption.isBlank()) {
            values.put(leaf(data, base, "BeaconType"), encryption);
        }
        String algo = text(fields, "wpa_algorithm");
        if (algo != null && !algo.isBlank()) {
            values.put(leaf(data, base, "WPAEncryptionModes", "BasicEncryptionModes"), algo);
        }
        String key = text(fields, "wpa_key");
        if (key != null && !key.isBlank()) {
            values.put(leaf(data, base, "KeyPassphrase", "PreSharedKey"), key);
        }
        String bandwidth = text(fields, "bandwidth");
        if (bandwidth != null && !"auto".equalsIgnoreCase(bandwidth)) {
            values.put(leaf(data, base, "X_CMCC_ChannelWidth", "X_CT-COM_ChannelWidth"), bandwidth);
        }
        emitSpv(plan, "WiFi 参数包 [" + (band5 ? "5.8G" : "2.4G") + "]", values, detail);
    }

    // ==================== 参数渲染：SIP ====================

    private void renderSip(String root, DeviceData data, Map<String, Object> fields,
                           List<Map.Entry<String, String>> plan, List<Map<String, String>> detail) {
        String protocol = text(fields, "protocol");
        if (protocol != null && "H248".equalsIgnoreCase(protocol)) {
            throw new BusinessException("H.248 协议下发未适配，请使用 SIP 模板");
        }
        List<Integer> services = data.instances(root + "Services.VoiceService.");
        if (services.isEmpty()) {
            throw new BusinessException("设备参数树无 VoiceService 实例（不支持语音业务）");
        }
        int svc = services.get(0);
        String svcBase = root + "Services.VoiceService." + svc + ".";
        List<Integer> profiles = data.instances(svcBase + "VoiceProfile.");
        if (profiles.isEmpty()) {
            throw new BusinessException("设备参数树无 VoiceProfile 实例");
        }
        String base = svcBase + "VoiceProfile." + profiles.get(0) + ".";

        Map<String, String> values = new LinkedHashMap<>();
        String registrar = text(fields, "registrar");
        if (registrar != null && !registrar.isBlank()) {
            values.put(leaf(data, base, "RegistrarServer"), registrar);
        }
        putNumber(values, data, base, fields, "registrar_port", "RegistrarServerPort");
        String proxy = text(fields, "proxy");
        if (proxy == null || proxy.isBlank()) {
            proxy = registrar; // 没填代理时与注册服务器一致
        }
        if (proxy != null && !proxy.isBlank()) {
            values.put(leaf(data, base, "ProxyServer"), proxy);
        }
        putNumber(values, data, base, fields, "proxy_port", "ProxyServerPort");
        putNumber(values, data, base, fields, "outbound_proxy_port", "OutboundProxyPort");
        String outbound = text(fields, "outbound_proxy");
        if (outbound != null && !outbound.isBlank()) {
            values.put(leaf(data, base, "OutboundProxy"), outbound);
        }
        String backup = text(fields, "backup_registrar");
        if (backup != null && !backup.isBlank()) {
            values.put(leaf(data, base, "X_CMCC_RegistrarServer2", "X_CT-COM_RegistrarServer2"), backup);
        }
        putNumber(values, data, base, fields, "backup_registrar_port", "X_CMCC_RegistrarServerPort2");
        putNumber(values, data, base, fields, "min_digits", "X_CMCC_MinDigits");
        String uaDomain = text(fields, "user_agent_domain");
        if (uaDomain != null && !uaDomain.isBlank()) {
            values.put(leaf(data, base, "UserAgentDomain", "X_CMCC_UserAgentDomain"), uaDomain);
        }
        String dmEnable = text(fields, "digitmap_enable");
        String dmRule = text(fields, "digitmap_rule");
        if ("enable".equalsIgnoreCase(dmEnable) && dmRule != null && !dmRule.isBlank()) {
            values.put(leaf(data, base, "DigitMap"), dmRule);
        }
        if ("true".equalsIgnoreCase(text(fields, "disable_special_dial"))) {
            values.put(leaf(data, base, "X_CMCC_DisableSpecialDial"), "1");
        }

        // SIP 账号：默认逐行写到 Line.{i}；prefer_existing_account 时只改已有账号的密码
        String userLines = text(fields, "sip_user_info");
        if (userLines != null && !userLines.isBlank()) {
            String[] lines = userLines.split("\\r?\\n");
            List<Integer> lineInstances = data.instances(base + "Line.");
            int written = 0;
            for (String line : lines) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2 || parts[0].isBlank()) {
                    continue;
                }
                if (written >= lineInstances.size()) {
                    detail.add(row("SIP 账号", parts[0], "skip", "Line 实例不够（设备只暴露 "
                            + lineInstances.size() + " 个）"));
                    break;
                }
                String lineBase = base + "Line." + lineInstances.get(written++) + ".";
                values.put(leaf(data, lineBase, "Enable"), "1");
                values.put(leaf(data, lineBase, "URI", "SIP_URI"), "sip:" + parts[0] + "@"
                        + (registrar == null ? "" : registrar));
                values.put(leaf(data, lineBase, "AuthUserName"), parts[0]);
                values.put(leaf(data, lineBase, "AuthPassword"), parts[1]);
            }
        }
        emitSpv(plan, "SIP 参数包", values, detail);
    }

    // ==================== 参数渲染：其他 ====================

    private void renderOther(String root, DeviceData data, Map<String, Object> fields,
                             List<Map.Entry<String, String>> plan, List<Map<String, String>> detail) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean reboot = "true".equalsIgnoreCase(text(fields, "reboot_after"));

        String telnet = text(fields, "telnet_enable");
        if ("true".equalsIgnoreCase(telnet)) {
            values.put(leaf(data, root, "X_CMCC_TelnetEnable", "TelnetEnable", "X_CT-COM_TelnetEnable"), "1");
        }
        String telnetUser = text(fields, "telnet_username");
        if (telnetUser != null && !telnetUser.isBlank()) {
            values.put(leaf(data, root, "X_CMCC_TelnetUserName", "TelnetUserName"), telnetUser);
        }
        String telnetPwd = text(fields, "telnet_password");
        if (telnetPwd != null && !telnetPwd.isBlank()) {
            values.put(leaf(data, root, "X_CMCC_TelnetPassword", "TelnetPassword"), telnetPwd);
        }
        String superUser = text(fields, "super_account");
        if (superUser != null && !superUser.isBlank()) {
            values.put(leaf(data, root, "X_CMCC_AdminUserName", "SuperUserName", "AdminUserName"), superUser);
        }
        String superPwd = text(fields, "super_password");
        if (superPwd != null && !superPwd.isBlank()) {
            values.put(leaf(data, root, "X_CMCC_AdminPassword", "SuperPassword", "AdminPassword"), superPwd);
        }
        if ("true".equalsIgnoreCase(text(fields, "firewall_disable"))) {
            values.put(leaf(data, root, "X_CMCC_FirewallEnable", "FirewallEnabled"), "0");
        }
        String igmp = text(fields, "igmp_support");
        if ("enable".equalsIgnoreCase(igmp) || "disable".equalsIgnoreCase(igmp)) {
            values.put(leaf(data, root, "X_CMCC_IGMPEnable", "IGMPEnabled", "X_CT-COM_IGMPEnable"),
                    "enable".equalsIgnoreCase(igmp) ? "1" : "0");
        }
        String mode = text(fields, "multicast_mode");
        if (mode != null && !"auto".equalsIgnoreCase(mode) && !mode.isBlank()) {
            values.put(leaf(data, root, "X_CMCC_IGMPMode", "IGMPMode", "X_CT-COM_IGMPMode"),
                    mode.toLowerCase(Locale.ROOT));
        }
        String led = text(fields, "led_control");
        if ("on".equalsIgnoreCase(led) || "off".equalsIgnoreCase(led)) {
            values.put(leaf(data, root, "X_CMCC_LedEnable", "LEDControl", "X_CT-COM_LedControl"),
                    "on".equalsIgnoreCase(led) ? "1" : "0");
        }
        String roaming = text(fields, "wifi_roaming");
        if ("enable".equalsIgnoreCase(roaming) || "disable".equalsIgnoreCase(roaming)) {
            values.put(leaf(data, root, "X_CMCC_WiFiRoamingEnable", "X_CT-COM_WiFiRoamingEnable"),
                    "enable".equalsIgnoreCase(roaming) ? "1" : "0");
        }
        putNumber(values, data, root, fields, "roaming_threshold_24g", "X_CMCC_RoamingThreshold24G");
        putNumber(values, data, root, fields, "roaming_threshold_58g", "X_CMCC_RoamingThreshold58G");

        // 重启放最后单独执行，避免参数包还没生效就重启
        emitSpv(plan, "设备管理参数包", values, detail);
        if (reboot) {
            plan.add(step("重启光猫", taskJson("Reboot", "", Map.of())));
        }
    }

    // ==================== 渲染工具 ====================

    /** 拼一个 SPV 步骤；值为空白的跳过，全空不发 */
    private static void emitSpv(List<Map.Entry<String, String>> plan, String desc,
                                Map<String, String> values, List<Map<String, String>> detail) {
        List<Map<String, String>> valid = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            Map<String, String> one = new LinkedHashMap<>();
            one.put("name", e.getKey());
            one.put("value", e.getValue());
            one.put("type", "xsd:string");
            valid.add(one);
        }
        if (valid.isEmpty()) {
            detail.add(row("渲染 " + desc, "", "skip", "没有可下发的参数（叶子未匹配到，机型未适配？）"));
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("values", valid);
        plan.add(step(desc, taskJson("SetParameterValues", "", params)));
    }

    /** 只在设备参数树存在候选叶子之一时才写入渲染结果；都没有则记跳过 */
    private static String leaf(DeviceData data, String base, String... candidates) {
        for (String c : candidates) {
            String path = base + c;
            if (data.has(path)) {
                return path;
            }
        }
        return null;
    }

    private static String findLeafValue(DeviceData data, String instanceBase, String... candidates) {
        for (String c : candidates) {
            String path = instanceBase + "." + c;
            if (data.has(path)) {
                return data.value(path);
            }
        }
        return null;
    }

    private static void putNumber(Map<String, String> values, DeviceData data, String base,
                                  Map<String, Object> fields, String key, String... leafNames) {
        String v = text(fields, key);
        if (v == null || v.isBlank()) {
            return;
        }
        String path = leaf(data, base, leafNames);
        if (path != null) {
            values.put(path, v);
        }
    }

    /** 意图字段的字符串取值（数字等非字符串先转字符串），缺省返回 null */
    private static String text(Map<String, Object> fields, String key) {
        if (fields == null) {
            return null;
        }
        Object v = fields.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String textOrEmpty(Map<String, Object> fields, String key) {
        String v = text(fields, key);
        return v == null ? "" : v;
    }

    /** 拼一个计划步骤：desc -> 任务 JSON（__type/__path/__fallback + 业务参数） */
    private static Map.Entry<String, String> step(String desc, String json) {
        return Map.entry(desc, json);
    }

    private static String taskJson(String type, String path, Map<String, Object> params) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("__type", type);
        all.put("__path", path == null ? "" : path);
        if (params != null) {
            all.putAll(params);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(all);
        } catch (Exception e) {
            throw new BusinessException("任务参数序列化失败: " + e.getMessage());
        }
    }
}
