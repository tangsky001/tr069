/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.cpe.model.DeviceData;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前端下发 → 自动组装成 SetParameterValues 任务。
 * <p>不管前端给的是 WiFi 业务字段还是原始参数三元组，这里都按设备登记的
 * 数据模型（TR-098/TR-181，来自机型模板/参数树）自动拼出目标路径与类型，
 * 页面不需要知道任何路径差异。
 */
@Service
@RequiredArgsConstructor
public class DispatchService {

    private final CpeStore store;
    private final TaskService taskService;

    /**
     * POST /device/dispatch
     * 请求：{ sns[], category, values{}, templateId, wifi{instance,ssid,encryption,password,enabled,band}, username }
     */
    public Map<String, Object> dispatch(Map<String, Object> req) {
        List<String> sns = snsOf(req.get("sns"));
        String username = str(req.get("username"));
        Map<String, Object> wifi = castMap(req.get("wifi"));
        List<Map<String, Object>> values = plainValues(req.get("values"));

        List<Map<String, Object>> rows = new ArrayList<>();
        int submitted = 0;
        int failed = 0;
        for (String sn : sns) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sn", sn);
            try {
                long taskId;
                if (wifi != null && !wifi.isEmpty()) {
                    taskId = taskService.createTask(sn, "SetParameterValues",
                            Map.of("values", wifiValues(sn, wifi)), username);
                } else if (!values.isEmpty()) {
                    taskId = taskService.createTask(sn, "SetParameterValues", Map.of("values", values), username);
                } else if (req.get("templateId") != null) {
                    row.put("taskId", null);
                    row.put("status", "fail");
                    row.put("errorMsg", "配置模板模块未接入，请直接下发参数或 WiFi 字段");
                    failed++;
                    rows.add(row);
                    continue;
                } else {
                    row.put("taskId", null);
                    row.put("status", "fail");
                    row.put("errorMsg", "无可下发内容");
                    failed++;
                    rows.add(row);
                    continue;
                }
                row.put("taskId", taskId);
                row.put("status", "queued");
                row.put("errorMsg", null);
                submitted++;
            } catch (Exception e) {
                row.put("taskId", null);
                row.put("status", "fail");
                row.put("errorMsg", e.getMessage());
                failed++;
                Logs.warn(Logs.Module.CPE, "下发受理失败", "sn", sn, "原因", e.getMessage());
            }
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("submitted", submitted);
        out.put("failed", failed);
        out.put("rows", rows);
        return out;
    }

    // ---------------- WiFi 业务字段 → 参数三元组 ----------------

    /** 按设备数据模型把 {instance, ssid, encryption, password, enabled, band} 拼成 SPV 值对 */
    public List<Map<String, Object>> wifiValues(String sn, Map<String, Object> wifi) {
        Map<String, Object> device = store.findDevice(sn);
        Map<String, CpeParam> params = store.loadParams(sn);
        DeviceData data = new DeviceData();
        data.loadAll(params);
        String root = BusinessPaths.detectRoot(data, str(device.get("root_node")));
        String ssid = str(wifi.get("ssid"));
        String password = str(wifi.get("password"));
        String encryption = str(wifi.get("encryption"));
        Boolean enabled = wifi.get("enabled") == null ? null : Boolean.parseBoolean(str(wifi.get("enabled")));
        String band = str(wifi.get("band"));
        Integer instance = wifi.get("instance") == null ? null : Integer.parseInt(str(wifi.get("instance")));

        List<Map<String, Object>> out = new ArrayList<>();
        if (BusinessPaths.isTr181(root)) {
            int idx = instance == null ? findWifiInstance(data, root, band, 1) : instance;
            String ssidPath = "Device.WiFi.SSID." + idx + ".";
            String ap = findApForSsid(data, idx);
            if (ssid != null && data.has(ssidPath + "SSID")) {
                out.add(nv(ssidPath + "SSID", ssid, "string"));
            }
            if (enabled != null && data.has(ssidPath + "Enable")) {
                out.add(nv(ssidPath + "Enable", enabled ? "1" : "0", "boolean"));
            }
            if (ap != null) {
                String keyPath = data.has(ap + ".Security.KeyPassphrase")
                        ? ap + ".Security.KeyPassphrase" : ap + ".Security.PreSharedKey";
                if (password != null && data.has(keyPath)) {
                    out.add(nv(keyPath, password, "string"));
                }
                String modePath = ap + ".Security.ModeEnabled";
                if (encryption != null && data.has(modePath)) {
                    out.add(nv(modePath, tr181Mode(encryption), "string"));
                }
            }
        } else {
            int idx = instance == null ? findWifiInstance(data, root, band, 1) : instance;
            String prefix = wlanBase(data, root, idx);
            if (prefix == null) {
                throw new IllegalStateException("设备参数树中未发现 WLAN 配置节点");
            }
            if (ssid != null && data.has(prefix + "SSID")) {
                out.add(nv(prefix + "SSID", ssid, "string"));
            }
            if (enabled != null && data.has(prefix + "Enable")) {
                out.add(nv(prefix + "Enable", enabled ? "1" : "0", "boolean"));
            }
            String keyLeaf = data.has(prefix + "KeyPassphrase") ? "KeyPassphrase"
                    : data.has(prefix + "PreSharedKey") ? "PreSharedKey" : null;
            if (password != null && keyLeaf != null) {
                out.add(nv(prefix + keyLeaf, password, "string"));
            }
            if (encryption != null && data.has(prefix + "WPAEncryptionModes")) {
                out.add(nv(prefix + "WPAEncryptionModes", tr098Mode(encryption), "string"));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("未能按机型参数树组装出可下发参数，请先完成机型适配");
        }
        return out;
    }

    /** TR-098 WLAN 配置对象前缀：兼容标准名 LANWLANConfiguration 与私有变体 WLANConfiguration（如 CIOT） */
    private String wlanBase(DeviceData data, String root, int idx) {
        for (String seg : new String[]{"LANWLANConfiguration", "WLANConfiguration"}) {
            String marker = "." + seg + "." + idx + ".";
            for (String p : data.allPaths("")) {
                if (p.contains(marker)) {
                    return p.substring(0, p.indexOf(marker) + 1) + seg + "." + idx + ".";
                }
            }
        }
        return null;
    }

    /** 按频段找 WLAN 实例号，找不到退回默认值 */
    private int findWifiInstance(DeviceData data, String root, String band, int def) {
        if (band == null || band.isBlank()) {
            return def;
        }
        boolean want5 = band.contains("5");
        // TR-181：Radio.{i}.OperatingFrequencyBand 直接给频段
        for (String p : data.allPaths("Device.WiFi.Radio.")) {
            if (p.endsWith(".OperatingFrequencyBand")) {
                String v = data.value(p);
                if (v != null) {
                    boolean is5 = v.contains("5");
                    if (is5 == want5) {
                        return instanceOf(p, "Radio.");
                    }
                }
            }
        }
        // TR-098：WLANConfiguration.{i}.Standard 推频段（兼容 LANWLANConfiguration 与私有变体 WLANConfiguration）
        var pat = java.util.regex.Pattern.compile("\\.LANDevice\\.(\\d+)\\.(?:LAN)?WLANConfiguration\\.(\\d+)\\.");
        for (String p : data.allPaths("")) {
            var m = pat.matcher(p);
            if (m.find() && (p.endsWith(".Standard") || p.endsWith(".OperatingStandards"))) {
                String std = data.value(p);
                if (std == null) {
                    continue;
                }
                String s = std.toLowerCase();
                boolean is5 = s.contains("ac") || s.contains("ax") || s.matches(".*(^|[ ,])a($|[ ,]).*");
                if (is5 == want5) {
                    return Integer.parseInt(m.group(2));
                }
            }
        }
        return def;
    }

    /** 从路径里取某一段后的实例号，如 …Radio.2. → 2 */
    private int instanceOf(String path, String segment) {
        int i = path.indexOf(segment);
        if (i < 0) {
            return 1;
        }
        int start = i + segment.length();
        int end = path.indexOf('.', start);
        try {
            return Integer.parseInt(end < 0 ? path.substring(start) : path.substring(start, end));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String findApForSsid(DeviceData data, int ssidInstance) {
        String suffix = "SSID." + ssidInstance + ".";
        for (String p : data.allPaths("Device.WiFi.AccessPoint.")) {
            if (p.endsWith(".SSIDReference") && data.value(p) != null
                    && data.value(p).endsWith(suffix)) {
                return p.substring(0, p.lastIndexOf('.'));
            }
        }
        return null;
    }

    private String tr098Mode(String encryption) {
        String e = encryption == null ? "" : encryption.trim().toLowerCase();
        return switch (e) {
            case "", "none", "open" -> "NONE";
            case "wpa" -> "WPA-PSK";
            case "wpa2", "wpa2psk" -> "WPA2-PSK";
            case "wpa3" -> "WPA3-PSK";
            default -> "WPA2-PSK";
        };
    }

    private String tr181Mode(String encryption) {
        String e = encryption == null ? "" : encryption.trim().toLowerCase();
        return switch (e) {
            case "", "none", "open" -> "None";
            case "wpa" -> "WPA-Personal";
            case "wpa2", "wpa2psk" -> "WPA2-Personal";
            case "wpa3" -> "WPA3-Personal";
            case "wpa/wpa2", "wpawpa2" -> "WPAWPA2-Personal";
            default -> "WPA2-Personal";
        };
    }

    // ---------------- 工具 ----------------

    private Map<String, Object> nv(String name, String value, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        m.put("type", type);
        return m;
    }

    private List<Map<String, Object>> plainValues(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof Map<?, ?> m) {
            // {name: value} 形式 → 全 string
            m.forEach((k, v) -> out.add(nv(String.valueOf(k), v == null ? "" : String.valueOf(v), "string")));
        } else if (o instanceof List<?> list) {
            for (Object it : list) {
                if (it instanceof Map<?, ?> m) {
                    out.add(nv(str(m.get("name")),
                            m.get("value") == null ? "" : str(m.get("value")),
                            str(m.get("type"))));
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> snsOf(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                out.add(String.valueOf(x));
            }
        } else if (o instanceof String s && !s.isBlank()) {
            for (String x : s.split(",")) {
                out.add(x.trim());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
