/**
 * @author: tangsky001
 * @date:   2026年11月18日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.cpe.model.DeviceData;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SIP 语音线路清单与按路直发 */
@Service
@RequiredArgsConstructor
public class DeviceSipService {

    /** SIP 默认端口 */
    public static final String DEFAULT_SIP_PORT = "5060";

    /** 申报能力文案里的语音路数 */
    private static final Pattern DECLARED_SIP = Pattern.compile("(\\d+)路SIP");

    /** 服务器参数候选叶子 */
    private static final String[] REGISTRAR_LEAVES = {"RegistrarServer", "SIPRegistrarServer"};
    private static final String[] REGISTRAR_PORT_LEAVES = {"RegistrarServerPort", "SIPRegistrarServerPort",
            "RegistrarPort"};
    private static final String[] PROXY_LEAVES = {"ProxyServer", "SIPProxyServer", "OutboundProxy"};
    private static final String[] PROXY_PORT_LEAVES = {"ProxyServerPort", "SIPProxyServerPort", "ProxyPort"};

    private final CpeStore store;
    private final TaskService taskService;
    private final CwmpService cwmpService;
    private final CpeProperties props;
    private final ModelProfileService modelProfileService;

    /** 可编辑的语音线路清单 */
    public List<Map<String, Object>> lines(String sn) {
        Map<String, Object> device = requireDevice(sn);
        DeviceData data = deviceData(sn);
        String root = BusinessPaths.detectRoot(data,
                props.getDefaultRootNode());

        Map<Integer, Map<String, Object>> actual = new LinkedHashMap<>();
        String base = BusinessPaths.voiceRoot(root);
        for (int svc : data.instances(base)) {
            String profileBase = base + svc + ".VoiceProfile.";
            for (int profile : data.instances(profileBase)) {
                String profilePrefix = profileBase + profile + ".";
                String lineBase = profilePrefix + "Line.";
                for (int line : data.instances(lineBase)) {
                    String prefix = lineBase + line + ".";
                    if (data.leaves(prefix).isEmpty()) {
                        continue;
                    }
                    actual.putIfAbsent(line, lineRow(data, prefix, profilePrefix, line));
                }
            }
        }

        int max = actual.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        int total = Math.max(Math.max(declaredSipCount(sn), max), 1);
        List<Map<String, Object>> result = new ArrayList<>(total);
        for (int line = 1; line <= total; line++) {
            Map<String, Object> option = actual.get(line);
            if (option != null) {
                result.add(option);
                continue;
            }
            Map<String, Object> blank = new LinkedHashMap<>();
            blank.put("line", line);
            blank.put("number", "");
            blank.put("statusText", "未配置");
            blank.put("registered", false);
            blank.put("authUser", "");
            blank.put("password", "");
            blank.put("registrar", "");
            blank.put("registrarPort", DEFAULT_SIP_PORT);
            blank.put("proxy", "");
            blank.put("proxyPort", DEFAULT_SIP_PORT);
            result.add(blank);
        }
        return result;
    }

    /** 一条线路的可编辑字段 */
    private Map<String, Object> lineRow(DeviceData data, String prefix, String profilePrefix, int line) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", prefix);
        out.put("line", line);
        String number = orElse(data.value(prefix + "DirectoryNumber"),
                orElse(data.value(prefix + "SIP.URI"),
                        orElse(data.value(prefix + "SIP.AuthUserName"),
                                orElse(vendorLeafValue(data, prefix, "uri"),
                                        vendorLeafValue(data, prefix, "authusername")))));
        if (isParamPath(number)) {
            number = null; // 绝不把参数路径当电话号码显示
        }
        if (number != null && number.contains("@")) {
            number = number.substring(0, number.indexOf('@'));
        }
        out.put("number", number);
        String status = orElse(data.value(prefix + "Status"), data.value(prefix + "SIPRegisterStatus"));
        status = orElse(status, data.value(prefix + "RegisterStatus"));
        String low = status == null ? "" : status.toLowerCase();
        boolean registered = low.equals("up") || low.contains("ok")
                || (low.contains("registered") && !low.contains("unregistered") && !low.contains("registering"));
        out.put("statusText", sipStatusText(status));
        out.put("registered", registered);
        out.put("authUser", orElse(data.value(prefix + "SIP.AuthUserName"),
                orElse(data.value(prefix + "AuthUserName"), vendorLeafValue(data, prefix, "authusername"))));
        out.put("password", orElse(data.value(prefix + "SIP.AuthPassword"),
                orElse(data.value(prefix + "AuthPassword"), vendorLeafValue(data, prefix, "authpassword"))));
        out.put("registrar", serverValue(data, profilePrefix, prefix, REGISTRAR_LEAVES));
        out.put("registrarPort", orElse(serverValue(data, profilePrefix, prefix, REGISTRAR_PORT_LEAVES),
                DEFAULT_SIP_PORT));
        out.put("proxy", serverValue(data, profilePrefix, prefix, PROXY_LEAVES));
        out.put("proxyPort", orElse(serverValue(data, profilePrefix, prefix, PROXY_PORT_LEAVES),
                DEFAULT_SIP_PORT));
        return out;
    }

    /** 按路下发 SIP 配置 */
    public Map<String, Object> submit(String sn, Map<String, Object> body) {
        Map<String, Object> device = requireDevice(sn);
        DeviceData data = deviceData(sn);
        String root = BusinessPaths.detectRoot(data, str(device.get("root_node")));

        String number = str(body.get("number"));
        if (number == null || number.isBlank() || isParamPath(number)) {
            throw new BusinessException(400, "电话号码 / URI 不能为空，且不能是参数路径");
        }
        List<Integer> lines = normalizedLines(body.get("lines"));
        String operator = str(body.get("username"));

        Map<Integer, String[]> prefixes = linePrefixes(data, root);

        List<Map<String, Object>> tasks = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        for (Integer line : lines) {
            String[] pre = prefixes.get(line);
            if (pre == null) {
                failures.add(failRow(line, "设备参数树中没有第 " + line + " 路语音线路，请先完成机型适配"));
                continue;
            }
            try {
                List<Map<String, Object>> values =
                        valuesOf(data, pre[0], pre[1], body, number);
                if (values.isEmpty()) {
                    failures.add(failRow(line, "设备参数树中没有可下发的语音参数节点，请先完成机型适配"));
                    continue;
                }
                long taskId = taskService.createTask(sn, "SetParameterValues",
                        Map.of("values", values),
                        operator == null || operator.isBlank() ? "console" : operator);
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("line", line);
                ok.put("taskId", taskId);
                ok.put("status", "queued");
                tasks.add(ok);
            } catch (Exception e) {
                failures.add(failRow(line, e.getMessage()));
                Logs.warn(Logs.Module.CPE_PUSH, "SIP 下发受理失败", "sn", sn, "线路", line, "原因", e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lines", lines);
        result.put("submitted", tasks.size());
        result.put("failed", failures.size());
        result.put("tasks", tasks);
        result.put("failures", failures);
        return result;
    }

    /** 业务字段 → 参数三元组 */
    private List<Map<String, Object>> valuesOf(DeviceData data, String prefix, String profilePrefix,
                                               Map<String, Object> body, String number) {
        List<Map<String, Object>> out = new ArrayList<>();
        String uriLeaf = existingLeaf(data, prefix, "SIP.URI", "URI", "PhoneNumber");
        String dnLeaf = existingLeaf(data, prefix, "DirectoryNumber");
        if (uriLeaf != null) {
            out.add(nv(uriLeaf, number));
        }
        if (dnLeaf != null) {
            String dn = number.contains("@") ? number.substring(0, number.indexOf('@')) : number;
            out.add(nv(dnLeaf, dn));
        }
        String authUser = text(str(body.get("authUser")), number);
        putIfPresent(out, data, prefix, authUser, "SIP.AuthUserName", "AuthUserName");
        putIfPresent(out, data, prefix, str(body.get("password")), "SIP.AuthPassword", "AuthPassword");
        putIfPresent(out, data, profilePrefix, str(body.get("registrar")), REGISTRAR_LEAVES);
        putPort(out, data, profilePrefix, str(body.get("registrarPort")), REGISTRAR_PORT_LEAVES);
        putIfPresent(out, data, profilePrefix, str(body.get("proxy")), PROXY_LEAVES);
        putPort(out, data, profilePrefix, str(body.get("proxyPort")), PROXY_PORT_LEAVES);
        return out;
    }

    private Map<String, Object> requireDevice(String sn) {
        try {
            return store.findDevice(sn);
        } catch (Exception e) {
            throw new BusinessException(404, "设备不存在");
        }
    }

    /** 路号 → [Line 前缀, VoiceProfile 前缀] */
    private Map<Integer, String[]> linePrefixes(DeviceData data, String root) {
        Map<Integer, String[]> out = new LinkedHashMap<>();
        String base = BusinessPaths.voiceRoot(root);
        for (int svc : data.instances(base)) {
            String profileBase = base + svc + ".VoiceProfile.";
            for (int profile : data.instances(profileBase)) {
                String profilePrefix = profileBase + profile + ".";
                String lineBase = profilePrefix + "Line.";
                for (int line : data.instances(lineBase)) {
                    if (data.leaves(lineBase + line + ".").isEmpty()) {
                        continue;
                    }
                    out.putIfAbsent(line, new String[]{lineBase + line + ".", profilePrefix});
                }
            }
        }
        return out;
    }

    /** 合并参数树：落库树打底，活跃会话的内存树覆盖 */
    private DeviceData deviceData(String sn) {
        DeviceData data = new DeviceData();
        try {
            data.loadAll(store.loadParams(sn));
        } catch (Exception ignore) {
        }
        CwmpSession session = cwmpService.findSessionBySn(sn);
        if (session != null && !session.isEnded()) {
            data.loadAll(session.getData().all());
        }
        return data;
    }

    /** 申报的语音路数 */
    private int declaredSipCount(String sn) {
        try {
            Object st = modelProfileService.adaptStatus(List.of(sn)).get(sn);
            if (!(st instanceof Map<?, ?> status)) {
                return 0;
            }
            Object text = ((Map<?, ?>) status).get("capabilityText");
            if (text == null) {
                return 0;
            }
            Matcher m = DECLARED_SIP.matcher(String.valueOf(text));
            int max = 0;
            while (m.find()) {
                max = Math.max(max, Integer.parseInt(m.group()));
            }
            return max;
        } catch (Exception e) {
            return 0;
        }
    }

    private List<Integer> normalizedLines(Object o) {
        if (!(o instanceof List<?> list) || list.isEmpty()) {
            return List.of(1);
        }
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        for (Object x : list) {
            try {
                int line = Integer.parseInt(String.valueOf(x));
                if (line > 0) {
                    out.add(line);
                }
            } catch (NumberFormatException ignore) {
            }
        }
        return out.isEmpty() ? List.of(1) : new ArrayList<>(out.stream().sorted().toList());
    }

    /** SIP 注册状态归一成中文文案 */
    private String sipStatusText(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String low = status.toLowerCase();
        if (low.equals("up") || low.contains("registered") && !low.contains("unregistered")
                && !low.contains("registering") && !low.contains("error") && !low.contains("fail")) {
            return "已注册";
        }
        if (low.contains("unregistered") || low.equals("down")) {
            return "未注册";
        }
        if (low.contains("registering")) {
            return "注册中";
        }
        if (low.contains("error") || low.contains("fail")) {
            return "注册失败";
        }
        return status;
    }

    private static Map<String, Object> failRow(int line, String message) {
        Map<String, Object> fail = new LinkedHashMap<>();
        fail.put("line", line);
        fail.put("message", message);
        return fail;
    }

    /** prefix 下第一个真实存在的候选叶子全路径，都没有返回 null */
    private static String existingLeaf(DeviceData data, String prefix, String... candidates) {
        for (String c : candidates) {
            String path = prefix + c;
            if (data.has(path)) {
                return path;
            }
        }
        return null;
    }

    /** 按叶子名匹配厂商私有叶子，返回参数值（不是路径） */
    private static String vendorLeafValue(DeviceData data, String prefix, String nameLower) {
        for (String p : data.leaves(prefix)) {
            String leaf = p.substring(p.lastIndexOf('.') + 1);
            if (leaf.equalsIgnoreCase(nameLower)
                    || leaf.toLowerCase(Locale.ROOT).endsWith(nameLower.toLowerCase(Locale.ROOT))) {
                String v = data.value(p);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        }
        return null;
    }

    /** 完整参数路径（早期 bug 曾把路径当值返回，这里兜底识别） */
    private static boolean isParamPath(String v) {
        return v != null && (v.startsWith("InternetGatewayDevice.") || v.startsWith("Device."));
    }

    /** 服务器参数：VoiceProfile 层候选优先，Line 层兜底，最后扫厂商私有叶子 */
    private static String serverValue(DeviceData data, String profilePrefix, String linePrefix,
                                      String... candidates) {
        String v = firstValue(data, profilePrefix, candidates);
        if (v != null) {
            return v;
        }
        v = firstValue(data, linePrefix, candidates);
        if (v != null) {
            return v;
        }
        for (String c : candidates) {
            String key = c.toLowerCase(Locale.ROOT);
            String found = vendorLeafContains(data, profilePrefix, key);
            if (found == null) {
                found = vendorLeafContains(data, linePrefix, key);
            }
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 叶子名包含关键字（如 RegistrarServer / X_CMCC_SIPRegistrarServer）时返回其值 */
    private static String vendorLeafContains(DeviceData data, String prefix, String keyword) {
        if (prefix == null) {
            return null;
        }
        for (String p : data.leaves(prefix)) {
            String leaf = p.substring(p.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (!leaf.contains(keyword)) {
                continue;
            }
            // 服务器地址不能拿端口值顶替，端口也不能拿地址顶替
            if (keyword.contains("port") != leaf.contains("port")) {
                continue;
            }
            String v = data.value(p);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String firstValue(DeviceData data, String prefix, String... candidates) {
        for (String c : candidates) {
            String v = data.value(prefix + c);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** 只在叶子存在且值非空时才写入下发清单 */
    private static void putIfPresent(List<Map<String, Object>> out, DeviceData data,
                                     String prefix, String value, String... candidates) {
        if (value == null || value.isBlank()) {
            return;
        }
        String leaf = existingLeaf(data, prefix, candidates);
        if (leaf != null) {
            out.add(nv(leaf, value.trim()));
        }
    }

    /** 端口没填按默认值下发 */
    private static void putPort(List<Map<String, Object>> out, DeviceData data,
                                String prefix, String value, String... candidates) {
        putIfPresent(out, data, prefix,
                value == null || value.isBlank() ? DEFAULT_SIP_PORT : value, candidates);
    }

    private static Map<String, Object> nv(String name, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        m.put("type", "string");
        return m;
    }

    private static String orElse(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }

    private static String text(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return "";
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
