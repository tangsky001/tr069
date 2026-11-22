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

/** 意图下发服务：把配置模板的意图字段翻译成设备真实参数并执行。 */
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

    /** 下发单个意图 */
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
            log.info("[下发] 计划生成 sn={} 类别={} 增量={} 步骤={} 条: {}", sn, category, incremental,
                    plan.size(), plan.stream().map(Map.Entry::getKey).toList());

            boolean allOk = true;
            String newInstance = null;
            Map<String, String> expected = new LinkedHashMap<>();
            for (Map.Entry<String, String> step : plan) {
                String desc = step.getKey();
                Map<String, Object> taskParams = parseParams(step.getValue());
                String type = String.valueOf(taskParams.remove("__type"));
                String path = String.valueOf(taskParams.remove("__path"));
                String fallback = String.valueOf(taskParams.remove("__fallback"));
                final String newOrFallback = newInstance != null ? newInstance : fallback;
                String realPath = path.contains("__NEW__") ? path.replace("__NEW__", newOrFallback) : path;
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
                List<Map<String, String>> effective = null;
                if ("SetParameterValues".equals(type)
                        && taskParams.get("values") instanceof List<?> vlist) {
                    effective = new ArrayList<>();
                    for (Object item : vlist) {
                        if (item instanceof Map<?, ?> mv) {
                            Map<String, String> one = new LinkedHashMap<>();
                            mv.forEach((k, v) -> one.put(String.valueOf(k), String.valueOf(v)));
                            effective.add(one);
                        }
                    }
                }
                Map<String, Object> stepResult = runTask(sn, type, realPath, taskParams, username);
                boolean ok = stepResult != null && Boolean.TRUE.equals(stepResult.get("ok"));
                String fault = stepResult == null ? "无结果"
                        : String.valueOf(stepResult.getOrDefault("fault", ""));
                log.info("[下发] 步骤 sn={} 任务={} 类型={} 路径={} 结果={}", sn, desc, type, realPath,
                        ok ? "成功" : "失败(" + fault + ")");
                if (!ok && "DeleteObject".equals(type)) {
                    // 9005 表示存量对象不在了，属可继续下发的正常情形
                    if (fault.contains("9005")) {
                        detail.add(row("跳过: " + desc, realPath, "skip", fault));
                        continue;
                    }
                }
                if ("AddObject".equals(type) && ok) {
                    String result = String.valueOf(stepResult.getOrDefault("result_content", ""));
                    Matcher m = Pattern.compile("(?i)instance[^0-9]{0,6}(\\d+)").matcher(result);
                    newInstance = m.find() ? m.group(1) : fallback;
                    log.info("[下发] AddObject 新实例号={}（设备返回={}，兜底={}）", newInstance, result, fallback);
                }
                // 整批 SPV 参数级被拒时逐条重发定位
                if (!ok && effective != null && SPV_REJECT.matcher(fault).find()) {
                    LocateResult located = new LocateResult();
                    locateRejected(sn, effective, detail, located);
                    if (!located.rejected.isEmpty()) {
                        detail.add(row("执行: " + desc, realPath, "fail",
                                "整批被拒，逐条重发（含换写法）后仍有 " + located.rejected.size()
                                        + " 个参数被拒: " + String.join("; ", located.rejected)));
                        allOk = false;
                        break; // 核心参数写不进去，继续发没意义
                    }
                    ok = true;
                    fault = "";
                    effective = located.accepted;
                    detail.add(row("执行: " + desc, realPath, "ok",
                            "整批被拒，逐条定位后全部落定（含换写法重试）"));
                }
                detail.add(row("执行: " + desc, realPath, ok ? "ok" : "fail", ok ? "" : fault));
                if (ok && effective != null) {
                    for (Map<String, String> mv : effective) {
                        String name = String.valueOf(mv.get("name"));
                        String val = String.valueOf(mv.get("value"));
                        if (name.contains("Password") || name.contains("PreSharedKey")
                                || name.contains("KeyPassphrase")) {
                            continue;
                        }
                        expected.put(name, val);
                    }
                }
                if (!ok) {
                    allOk = false;
                    break;
                }
            }
            out.put("success", allOk);
            if (allOk) {
                verify(sn, expected, detail, out);
            }
            out.put("error", allOk ? "" : "存在失败步骤，详见 detail");
            log.info("[下发] 结束 sn={} 类别={} 成功={} verified={} 步骤={} 条",
                    sn, category, allOk, out.get("verified"), detail.size());
            return out;
        } catch (BusinessException e) {
            log.warn("[下发] 计划失败 sn={} 类别={} 原因={}", sn, category, e.getMessage());
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

    /** 同步执行一条 RPC 任务，超时视为失败 */
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

    /** 下发成功后回读比对确认生效 */
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
            log.info("[下发] 回读确认 sn={} 比对={} 个 差异={} 个", sn, expected.size(), diff);
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

    /** 渲染 WAN 类意图：清理存量、AddObject、SPV */
    private void renderWan(String root, DeviceData data, String category, Map<String, Object> fields,
                           boolean incremental,
                           List<Map.Entry<String, String>> plan, List<Map<String, String>> detail) {
        String wanDevicePrefix = root + "WANDevice.";
        List<Integer> wanDevices = data.instances(wanDevicePrefix);
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
        String serviceList = normalizeServiceList(text(fields, "service_list"));

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

        // 增量模式若已有实例则复用第一条
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
        putValue(values, data, base, detail, "ConnectionType",
                routed ? "IP_Routed" : (pppoe ? "PPPoE_Bridged" : "IP_Bridged"),
                "ConnectionType");
        String name = "WAN_" + (text(fields, "vlan_id") == null ? "" : text(fields, "vlan_id"))
                + (bridge ? "_BRIDGE" : "") + "_" + category.replace("WAN_", "");
        putValue(values, data, base, detail, "Name", name, "Name");
        if (routed && !pppoe) {
            putValue(values, data, base, detail, "NATEnabled", "1", "NATEnabled");
        }
        putValue(values, data, base, detail, "业务绑定(ServiceList)", serviceList,
                "ServiceList", "X_CMCC_ServiceList", "X_CT-COM_ServiceList");
        String vlanId = text(fields, "vlan_id");
        if (vlanId != null && !vlanId.isBlank()) {
            String prio = text(fields, "vlan_priority");
            String combined = vlanId + " " + (prio == null || prio.isBlank() ? "0" : prio);
            putValue(values, data, base, detail, "VLAN 标记(X_CMCC_VLANIDMark)", combined,
                    "X_CMCC_VLANIDMark");
            putValue(values, data, base, detail, "VLAN ID(VLANIDMark)", vlanId,
                    "VLANIDMark", "X_CT-COM_VLANIDMark");
            putValue(values, data, base, detail, "VLAN 模式(VLANMode)", "2",
                    "X_CMCC_VLANMode", "X_CT-COM_VLANMode", "VLANMode");
        }
        String proto = text(fields, "ip_protocol");
        if (proto != null && !proto.isBlank() && !"IPv4".equalsIgnoreCase(proto)) {
            putValue(values, data, base, detail, "IP 协议版本(X_CMCC_IPMode)", proto, "X_CMCC_IPMode");
        }
        String mtu = text(fields, "mtu");
        if (mtu != null && !mtu.isBlank()) {
            putValue(values, data, base, detail, "MaxMTUSize", mtu, "MaxMTUSize");
        }
        String multicastVlan = text(fields, "multicast_vlan");
        if (multicastVlan != null && !multicastVlan.isBlank() && !"0".equals(multicastVlan)) {
            putValue(values, data, base, detail, "组播 VLAN", multicastVlan,
                    "X_CMCC_MulticastVlan", "MulticastVlan");
        }
        if ("WAN_PPPOE".equals(category)) {
            putValue(values, data, base, detail, "PPPoE 账号", text(fields, "username"), "Username");
            putValue(values, data, base, detail, "PPPoE 密码", text(fields, "password"), "Password");
        }
        if ("WAN_STATIC".equals(category)) {
            putValue(values, data, base, detail, "寻址方式", "Static", "AddressingType");
            putValue(values, data, base, detail, "静态 IP", text(fields, "external_ip"), "ExternalIPAddress");
            putValue(values, data, base, detail, "子网掩码", text(fields, "subnet_mask"), "SubnetMask");
            putValue(values, data, base, detail, "默认网关", text(fields, "default_gateway"), "DefaultGateway");
        }
        String bindPorts = text(fields, "bind_ports");
        if (bindPorts != null && !bindPorts.isBlank()) {
            String bound = lanInterfaceValue(root, data, bindPorts);
            if (bound == null) {
                detail.add(row("跳过参数", "绑定端口(" + bindPorts + ")", "skip",
                        "勾选端口无法映射到设备参数树上的 LAN 口路径（口不存在？）"));
            } else {
                putValue(values, data, base, detail, "绑定端口(" + bindPorts + ")", bound,
                        "X_CMCC_BindLANPort", "X_CMCC_LanInterface", "LanInterface",
                        "X_CT-COM_LANInterfaceBind", "BindLANPort");
            }
        }
        emitSpv(plan, "WAN 参数包", values, detail);
    }

    /** 渲染 WiFi 类意图 */
    private void renderWifi(String root, DeviceData data, String category, Map<String, Object> fields,
                            List<Map.Entry<String, String>> plan, List<Map<String, String>> detail) {
        boolean band5 = "WIFI_58G".equals(category);
        String cfgBase = root + "LANDevice.1.LANWLANConfiguration.";
        if (data.instances(cfgBase).isEmpty()) {
            cfgBase = root + "LANDevice.1.WLANConfiguration.";
        }
        List<Integer> instances = data.instances(cfgBase);
        if (instances.isEmpty()) {
            throw new BusinessException("设备参数树无 WLAN 配置实例，无法下发 WiFi");
        }
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
            putValue(values, data, base, detail, "射频总开关(radio_hardware_enable)",
                    "true".equalsIgnoreCase(hw) ? "1" : "0", "Enable");
        }
        if (text(fields, "radio_enable") != null) {
            putValue(values, data, base, detail, "无线开关(radio_enable)",
                    "true".equalsIgnoreCase(text(fields, "radio_enable")) ? "1" : "0", "Enable");
        }
        putValue(values, data, base, detail, "SSID", text(fields, "ssid"), "SSID");
        String channel = text(fields, "channel");
        if (channel != null && !channel.isBlank() && !"auto".equalsIgnoreCase(channel)) {
            putValue(values, data, base, detail, "信道(Channel)", channel, "Channel");
        }
        String hidden = text(fields, "hidden");
        if (hidden != null) {
            putValue(values, data, base, detail, "隐藏 SSID",
                    "true".equalsIgnoreCase(hidden) ? "1" : "0", "HideSSID", "X_CMCC_HideSSID");
        }
        String wps = text(fields, "wps_disable");
        if ("true".equalsIgnoreCase(wps)) {
            putValue(values, data, base, detail, "WPS 关闭", "1",
                    "X_CMCC_WpsDisable", "X_CT-COM_WPSDisable");
        }
        String tx = text(fields, "tx_power");
        if (tx != null && !tx.isBlank() && !"auto".equalsIgnoreCase(tx)) {
            putValue(values, data, base, detail, "发射功率", tx, "TransmitPower");
        }
        String encryption = text(fields, "encryption");
        if (encryption != null && !encryption.isBlank()) {
            putValue(values, data, base, detail, "BeaconType", encryption, "BeaconType");
        }
        String algo = text(fields, "wpa_algorithm");
        if (algo != null && !algo.isBlank()) {
            putValue(values, data, base, detail, "加密算法", algo,
                    "WPAEncryptionModes", "BasicEncryptionModes", "IEEE11iEncryptionModes");
        }
        String key = text(fields, "wpa_key");
        if (key != null && !key.isBlank()) {
            putValue(values, data, base, detail, "WiFi 密码", key, "KeyPassphrase",
                    "PreSharedKey.1.KeyPassphrase", "PreSharedKey.1.PreSharedKey", "PreSharedKey");
        }
        String bandwidth = text(fields, "bandwidth");
        if (bandwidth != null && !"auto".equalsIgnoreCase(bandwidth)) {
            putValue(values, data, base, detail, "频宽", bandwidth,
                    "X_CMCC_ChannelWidth", "X_CT-COM_ChannelWidth");
        }
        emitSpv(plan, "WiFi 参数包 [" + (band5 ? "5.8G" : "2.4G") + "]", values, detail);
    }

    /** 渲染 SIP 类意图 */
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
            putValue(values, data, base, detail, "注册服务器", registrar, "RegistrarServer");
        }
        putNumber(values, data, base, detail, "注册服务器端口", fields, "registrar_port", "RegistrarServerPort");
        String proxy = text(fields, "proxy");
        if (proxy == null || proxy.isBlank()) {
            proxy = registrar;
        }
        if (proxy != null && !proxy.isBlank()) {
            putValue(values, data, base, detail, "代理服务器", proxy, "ProxyServer");
        }
        putNumber(values, data, base, detail, "代理服务器端口", fields, "proxy_port", "ProxyServerPort");
        putNumber(values, data, base, detail, "出代理端口", fields, "outbound_proxy_port", "OutboundProxyPort");
        String outbound = text(fields, "outbound_proxy");
        if (outbound != null && !outbound.isBlank()) {
            putValue(values, data, base, detail, "出代理", outbound, "OutboundProxy");
        }
        String backup = text(fields, "backup_registrar");
        if (backup != null && !backup.isBlank()) {
            putValue(values, data, base, detail, "备用注册服务器", backup,
                    "X_CMCC_RegistrarServer2", "X_CT-COM_RegistrarServer2");
        }
        putNumber(values, data, base, detail, "备用注册端口", fields, "backup_registrar_port",
                "X_CMCC_RegistrarServerPort2");
        putNumber(values, data, base, detail, "最短位数", fields, "min_digits", "X_CMCC_MinDigits");
        String uaDomain = text(fields, "user_agent_domain");
        if (uaDomain != null && !uaDomain.isBlank()) {
            putValue(values, data, base, detail, "UserAgent 域", uaDomain,
                    "UserAgentDomain", "X_CMCC_UserAgentDomain");
        }
        String dmEnable = text(fields, "digitmap_enable");
        String dmRule = text(fields, "digitmap_rule");
        if ("enable".equalsIgnoreCase(dmEnable) && dmRule != null && !dmRule.isBlank()) {
            putValue(values, data, base, detail, "拨号计划(DigitMap)", dmRule, "DigitMap");
        }
        if ("true".equalsIgnoreCase(text(fields, "disable_special_dial"))) {
            putValue(values, data, base, detail, "禁用特殊拨号", "1", "X_CMCC_DisableSpecialDial");
        }

        // SIP 账号逐行写到 Line.{i}
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
                putValue(values, data, lineBase, detail, "SIP 账号启用(" + parts[0] + ")", "1", "Enable");
                putValue(values, data, lineBase, detail, "SIP URI(" + parts[0] + ")",
                        "sip:" + parts[0] + "@" + (registrar == null ? "" : registrar), "URI", "SIP_URI");
                putValue(values, data, lineBase, detail, "SIP 账号名(" + parts[0] + ")", parts[0], "AuthUserName");
                putValue(values, data, lineBase, detail, "SIP 密码(" + parts[0] + ")", parts[1], "AuthPassword");
            }
        }
        emitSpv(plan, "SIP 参数包", values, detail);
    }

    /** 渲染设备管理类意图 */
    private void renderOther(String root, DeviceData data, Map<String, Object> fields,
                             List<Map.Entry<String, String>> plan, List<Map<String, String>> detail) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean reboot = "true".equalsIgnoreCase(text(fields, "reboot_after"));

        String telnet = text(fields, "telnet_enable");
        if ("true".equalsIgnoreCase(telnet)) {
            putValue(values, data, root, detail, "Telnet 开关", "1",
                    "X_CMCC_TelnetEnable", "TelnetEnable", "X_CT-COM_TelnetEnable");
        }
        putValue(values, data, root, detail, "Telnet 账号", text(fields, "telnet_username"),
                "X_CMCC_TelnetUserName", "TelnetUserName");
        putValue(values, data, root, detail, "Telnet 密码", text(fields, "telnet_password"),
                "X_CMCC_TelnetPassword", "TelnetPassword");
        putValue(values, data, root, detail, "超管账号", text(fields, "super_account"),
                "X_CMCC_AdminUserName", "SuperUserName", "AdminUserName");
        putValue(values, data, root, detail, "超管密码", text(fields, "super_password"),
                "X_CMCC_AdminPassword", "SuperPassword", "AdminPassword");
        if ("true".equalsIgnoreCase(text(fields, "firewall_disable"))) {
            putValue(values, data, root, detail, "防火墙关闭", "0",
                    "X_CMCC_FirewallEnable", "FirewallEnabled");
        }
        String igmp = text(fields, "igmp_support");
        if ("enable".equalsIgnoreCase(igmp) || "disable".equalsIgnoreCase(igmp)) {
            putValue(values, data, root, detail, "IGMP 开关",
                    "enable".equalsIgnoreCase(igmp) ? "1" : "0",
                    "X_CMCC_IGMPEnable", "IGMPEnabled", "X_CT-COM_IGMPEnable");
        }
        String mode = text(fields, "multicast_mode");
        if (mode != null && !"auto".equalsIgnoreCase(mode) && !mode.isBlank()) {
            putValue(values, data, root, detail, "组播模式", mode.toLowerCase(Locale.ROOT),
                    "X_CMCC_IGMPMode", "IGMPMode", "X_CT-COM_IGMPMode");
        }
        String led = text(fields, "led_control");
        if ("on".equalsIgnoreCase(led) || "off".equalsIgnoreCase(led)) {
            putValue(values, data, root, detail, "指示灯",
                    "on".equalsIgnoreCase(led) ? "1" : "0",
                    "X_CMCC_LedEnable", "LEDControl", "X_CT-COM_LedControl");
        }
        String roaming = text(fields, "wifi_roaming");
        if ("enable".equalsIgnoreCase(roaming) || "disable".equalsIgnoreCase(roaming)) {
            putValue(values, data, root, detail, "WiFi 漫游",
                    "enable".equalsIgnoreCase(roaming) ? "1" : "0",
                    "X_CMCC_WiFiRoamingEnable", "X_CT-COM_WiFiRoamingEnable");
        }
        putNumber(values, data, root, detail, "漫游阈值 2.4G", fields, "roaming_threshold_24g",
                "X_CMCC_RoamingThreshold24G");
        putNumber(values, data, root, detail, "漫游阈值 5.8G", fields, "roaming_threshold_58g",
                "X_CMCC_RoamingThreshold58G");

        emitSpv(plan, "设备管理参数包", values, detail);
        if (reboot) {
            plan.add(step("重启光猫", taskJson("Reboot", "", Map.of())));
        }
    }

    /** 拼 SPV 步骤，全空不发 */
    private static void emitSpv(List<Map.Entry<String, String>> plan, String desc,
                                Map<String, String> values, List<Map<String, String>> detail) {
        List<Map<String, String>> valid = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                detail.add(row("渲染 " + desc, "", "skip", "叶子未匹配到设备参数树，已跳过"));
                continue;
            }
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

    /** 写入一个参数：只在设备参数树存在候选叶子之一时才写，否则显式记 skip */
    private static void putValue(Map<String, String> values, DeviceData data, String base,
                                 List<Map<String, String>> detail, String fieldDesc,
                                 String value, String... candidates) {
        if (value == null || value.isBlank()) {
            return;
        }
        String path = leaf(data, base, candidates);
        if (path == null) {
            detail.add(row("跳过参数", fieldDesc, "skip",
                    "设备参数树上没有叶子 " + String.join(" / ", candidates) + "（机型未适配？）"));
            return;
        }
        values.put(path, value);
    }

    /** 探测候选叶子路径，都不存在返回 null；__NEW__ 基础下用同类型已有实例做存在性参照 */
    private static String leaf(DeviceData data, String base, String... candidates) {
        String refBase = null;
        if (base.contains("__NEW__")) {
            String parent = base.substring(0, base.indexOf("__NEW__"));
            List<Integer> refs = data.instances(parent);
            if (!refs.isEmpty()) {
                refBase = parent + refs.get(0) + ".";
            }
        }
        for (String c : candidates) {
            String path = base + c;
            if (data.has(path)) {
                return path;
            }
            if (refBase != null && data.has(refBase + c)) {
                return path;
            }
        }
        if (refBase == null && base.contains("__NEW__") && candidates.length > 0) {
            return base + candidates[0];
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

    /** 数值类字段写入 */
    private static void putNumber(Map<String, String> values, DeviceData data, String base,
                                  List<Map<String, String>> detail, String fieldDesc,
                                  Map<String, Object> fields, String key, String... leafNames) {
        String v = text(fields, key);
        if (v == null || v.isBlank()) {
            return;
        }
        putValue(values, data, base, detail, fieldDesc, v, leafNames);
    }

    /** 把勾选端口翻成设备认的 LAN 口路径串 */
    private static String lanInterfaceValue(String root, DeviceData data, String value) {
        StringBuilder sb = new StringBuilder();
        for (String part : value.split("[,;\\s]+")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String port = lanPortPath(root, data, p);
            if (port == null) {
                return null;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(port);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** LAN{n} → 设备参数树上真实存在的 LAN 口路径，映射不出来返回 null */
    private static String lanPortPath(String root, DeviceData data, String port) {
        String p = port.trim();
        if (p.startsWith(root)) {
            return p;
        }
        if (!p.toUpperCase(Locale.ROOT).matches("LAN\\d+")) {
            return null;
        }
        int no = Integer.parseInt(p.substring(3));
        for (Integer ld : data.instances(root + "LANDevice.")) {
            String path = root + "LANDevice." + ld + ".LANEthernetInterfaceConfig." + no;
            if (data.has(path)) {
                return path;
            }
        }
        int idx = 0;
        for (Integer i : data.instances(root + "Ethernet.Interface.")) {
            String base = root + "Ethernet.Interface." + i;
            String upstream = findLeafValue(data, base, "Upstream");
            if ("true".equalsIgnoreCase(upstream) || "1".equals(upstream)) {
                continue;
            }
            if (++idx == no) {
                return base;
            }
        }
        return null;
    }

    /** SPV 参数级被拒的错误码 */
    private static final Pattern SPV_REJECT = Pattern.compile("\\b900[3-8]\\b");

    private static class LocateResult {
        final List<Map<String, String>> accepted = new ArrayList<>();
        final List<String> rejected = new ArrayList<>();
    }

    /** 整批 SPV 被拒时逐条重发定位，设备不认的开关类记 skip */
    private void locateRejected(String sn, List<Map<String, String>> values,
                                List<Map<String, String>> detail, LocateResult out) {
        for (Map<String, String> v : values) {
            String name = String.valueOf(v.get("name"));
            String raw = v.get("value") == null ? "" : v.get("value");
            if (values.size() > 1 && tryWriteOne(sn, v)) {
                out.accepted.add(v);
                continue;
            }
            Map<String, String> accepted = null;
            for (Map<String, String> alt : alternates(v)) {
                if (tryWriteOne(sn, alt)) {
                    accepted = alt;
                    break;
                }
            }
            if (accepted != null) {
                log.info("[下发] 参数换写法后被设备接受: {}={}（type {} -> {}）",
                        name, accepted.get("value"), v.get("type"), accepted.get("type"));
                out.accepted.add(accepted);
                continue;
            }
            if (isSwitch(name)) {
                log.warn("[下发] 启用开关设备不接受，跳过不下发: {}", name);
                detail.add(row("跳过参数", name, "skip",
                        "开关类参数设备不接受（其余参数已写进去），不影响本次下发"));
                continue;
            }
            out.rejected.add(name + "=" + raw + "（type=" + v.get("type") + "）");
            detail.add(row("参数被拒", name, "fail", "逐条重发（含换写法）仍被设备拒绝，值=" + raw));
        }
    }

    /** 单参数 SPV 试写 */
    private boolean tryWriteOne(String sn, Map<String, String> item) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("values", List.of(new LinkedHashMap<>(item)));
        Map<String, Object> r = runTask(sn, "SetParameterValues", "", params, "locate");
        return r != null && Boolean.TRUE.equals(r.get("ok"));
    }

    /** 生成同一参数的多种写法备选 */
    private static List<Map<String, String>> alternates(Map<String, String> v) {
        List<Map<String, String>> alts = new ArrayList<>();
        String name = String.valueOf(v.get("name"));
        String raw = v.get("value") == null ? "" : v.get("value");
        String origType = baseType(v.get("type"));
        if (!"string".equals(origType)) {
            alts.add(item(name, raw, "string"));
        }
        String bool = booleanValue(raw);
        if (!bool.equals(raw) && !"boolean".equals(origType)) {
            alts.add(item(name, bool, "boolean"));
        }
        if (raw.matches("\\d+") && !"unsignedInt".equals(origType)) {
            alts.add(item(name, raw, "unsignedInt"));
        }
        return alts;
    }

    /** xsi:type 去前缀 */
    private static String baseType(String t) {
        if (t == null) {
            return "string";
        }
        String s = t.trim();
        int i = s.indexOf(':');
        return i < 0 ? s : s.substring(i + 1);
    }

    private static Map<String, String> item(String name, String value, String type) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        m.put("type", type);
        return m;
    }

    /** 布尔取值归一 */
    private static String booleanValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        if ("1".equals(value) || "true".equalsIgnoreCase(value)) {
            return "true";
        }
        if ("0".equals(value) || "false".equalsIgnoreCase(value)) {
            return "false";
        }
        return raw;
    }

    /** 判断是否启用开关类参数 */
    private static boolean isSwitch(String path) {
        int idx = path.lastIndexOf('.');
        String leafName = idx < 0 ? path : path.substring(idx + 1);
        return leafName.matches("(?i).*(enable|enabled|disable|disabled)$");
    }

    private static final String SERVICE_INTERNET = "INTERNET";

    private static final List<String> SERVICE_TOKENS =
            List.of("INTERNET", "TR069", "VOIP", "IPTV", "OTHER");

    /** 业务模式归一成设备口径 */
    private static String normalizeServiceList(String raw) {
        if (raw == null || raw.isBlank()) {
            return SERVICE_INTERNET;
        }
        List<String> out = new ArrayList<>();
        for (String token : raw.split("[,，、+/\\s]+")) {
            String v = token.trim().toUpperCase(Locale.ROOT);
            if (v.isEmpty()) {
                continue;
            }
            if (!SERVICE_TOKENS.contains(v)) {
                log.warn("业务模式取值不在设备口径内，按 INTERNET 处理: {}", token);
                v = SERVICE_INTERNET;
            }
            if (!out.contains(v)) {
                out.add(v);
            }
        }
        return out.isEmpty() ? SERVICE_INTERNET : String.join(",", out);
    }

    /** 意图字段的字符串取值 */
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

    /** 拼一个计划步骤 */
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
