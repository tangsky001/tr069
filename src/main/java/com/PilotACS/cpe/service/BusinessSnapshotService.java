/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.cpe.model.DeviceData;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务快照派生：从设备参数树按数据模型（TR-098/TR-181）整理出
 * WAN/LAN/WIFI/SIP 清单与台账派生字段。所有文案/格式化都在后端算好。
 */
@Service
public class BusinessSnapshotService {

    public Map<String, Object> snapshot(Map<String, Object> deviceRow, Map<String, CpeParam> params,
                                        String collectStatus, String message) {
        DeviceData data = new DeviceData();
        data.loadAll(params);
        String root = BusinessPaths.detectRoot(data, null);
        return snapshot(deviceRow, data, root, collectStatus, message);
    }

    public Map<String, Object> snapshot(Map<String, Object> deviceRow, DeviceData data, String root,
                                        String collectStatus, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        String sn = str(deviceRow.get("sn"));
        String oui = str(deviceRow.get("oui"));
        out.put("title", (oui == null || oui.isBlank() ? "" : oui + "-") + sn);
        out.put("mac", firstValue(data, "MACAddress"));
        out.put("ip", firstValue(data, "ExternalIPAddress"));
        out.put("ponType", ponType(data));
        out.put("opticText", opticText(data));
        String upTime = firstValue(data, "UpTime");
        out.put("uptimeText", uptimeText(upTime));
        Long bootMs = longOf(deviceRow.get("last_boot_ms"));
        out.put("registerTime", fmt(bootMs));
        out.put("lastContactTime", fmt(longOf(deviceRow.get("last_inform_ms"))));
        out.put("collectStatus", collectStatus == null ? "success" : collectStatus);
        out.put("message", message);

        out.put("wan", wanList(data, root));
        out.put("lan", lanList(data, root));
        out.put("wifi", wifiList(data, root));
        out.put("sip", sipList(data, root));
        return out;
    }

    // ---------------- WAN ----------------

    private List<Map<String, Object>> wanList(DeviceData data, String root) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int wanDev : data.instances(root + "WANDevice.")) {
            String connBase = root + "WANDevice." + wanDev + ".WANConnectionDevice.";
            for (int connDev : data.instances(connBase)) {
                String base = connBase + connDev + ".";
                for (String kind : List.of("WANIPConnection", "WANPPPConnection")) {
                    String obj = base + kind + ".";
                    for (int i : data.instances(obj)) {
                        out.add(wanEntry(data, obj + i + ".", kind.equals("WANIPConnection") ? "IP" : "PPP", i));
                    }
                }
            }
        }
        return out;
    }

    private Map<String, Object> wanEntry(DeviceData data, String prefix, String connMode, int instance) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", prefix);
        out.put("instance", instance);
        out.put("connMode", connMode);
        out.put("ipProtocol", data.value(prefix + "ConnectionType"));
        out.put("up", boolValue(data, prefix + "Enable", true));
        out.put("statusText", data.value(prefix + "ConnectionStatus"));
        out.put("uptimeText", uptimeText(data.value(prefix + "Uptime")));
        out.put("natEnabled", boolValue(data, prefix + "NATEnabled", null));
        out.put("mtu", data.value(prefix + "MaxMTUSize"));
        out.put("service", data.value(prefix + "Name"));
        out.put("bindPorts", "");
        Map<String, Object> params = new LinkedHashMap<>();
        for (String p : data.leaves(prefix)) {
            params.put(p.substring(prefix.length()), data.value(p));
        }
        out.put("params", params);
        return out;
    }

    // ---------------- LAN ----------------

    private List<Map<String, Object>> lanList(DeviceData data, String root) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (BusinessPaths.isTr181(root)) {
            String base = "Device.Ethernet.Interface.";
            for (int i : data.instances(base)) {
                String p = base + i + ".";
                String up = data.value(p + "Upstream");
                if (up != null && up.matches("(?i)1|true")) {
                    continue;
                }
                String st = data.value(p + "Status");
                boolean connected = "Up".equals(st) || Boolean.TRUE.equals(boolValue(data, p + "Up", null));
                out.add(lanEntry(p, i, connected, data.value(p + "MACAddress")));
            }
            return out;
        }
        // TR-098：LANEthernetInterface{状态} + LANEthernetInterfaceConfig{MAC}
        for (int lanDev : data.instances(root + "LANDevice.")) {
            String base = root + "LANDevice." + lanDev + ".LANEthernetInterface.";
            for (int i : data.instances(base)) {
                String p = base + i + ".";
                String status = data.value(p + "EthernetLinkStatus");
                String cfg = root + "LANDevice." + lanDev + ".LANEthernetInterfaceConfig." + i + ".";
                out.add(lanEntry(p, i, "Up".equals(status), data.value(cfg + "MACAddress")));
            }
        }
        return out;
    }

    private Map<String, Object> lanEntry(String path, int instance, boolean connected, String mac) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("instance", instance);
        out.put("connected", connected);
        out.put("mac", mac);
        return out;
    }

    // ---------------- WIFI ----------------

    private List<Map<String, Object>> wifiList(DeviceData data, String root) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (BusinessPaths.isTr181(root)) {
            String ssidBase = "Device.WiFi.SSID.";
            for (int i : data.instances(ssidBase)) {
                String ssidPath = ssidBase + i + ".";
                String apPath = findApForSsid(data, i);
                String radioBand = bandOfTr181(data, ssidPath);
                out.add(wifiEntry(ssidPath, i,
                        data.value(ssidPath + "SSID"),
                        boolValue(data, ssidPath + "Enable", null),
                        boolValue(data, ssidPath + "Enable", null),
                        "TR-181",
                        radioBand,
                        apPath == null ? null : data.value(apPath + "Security.ModeEnabled"),
                        apPath == null ? null : orElse(data.value(apPath + "Security.KeyPassphrase"),
                                data.value(apPath + "Security.PreSharedKey")),
                        data));
            }
            return out;
        }
        for (int lanDev : data.instances(root + "LANDevice.")) {
            String base = root + "LANDevice." + lanDev + ".LANWLANConfiguration.";
            for (int i : data.instances(base)) {
                String p = base + i + ".";
                out.add(wifiEntry(p, i,
                        data.value(p + "SSID"),
                        boolValue(data, p + "Enable", null),
                        "Up".equals(data.value(p + "Status")),
                        data.value(p + "Status"),
                        bandOfTr098(data.value(p + "Standard")),
                        orElse(data.value(p + "WPAEncryptionModes"), data.value(p + "WEPKeyIndex")),
                        orElse(data.value(p + "KeyPassphrase"), data.value(p + "PreSharedKey")),
                        data));
            }
        }
        return out;
    }

    private Map<String, Object> wifiEntry(String path, int instance, String ssid, Boolean enabled,
                                          boolean up, String statusText, String band, String encryptionValue,
                                          String keyValue, DeviceData data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("instance", instance);
        out.put("band", band);
        out.put("enabled", Boolean.TRUE.equals(enabled));
        out.put("up", up);
        out.put("statusText", statusText);
        out.put("ssid", ssid);
        out.put("encryptionValue", encryptionValue);
        out.put("encrypted", encryptionValue != null
                && !encryptionValue.isBlank()
                && !"NONE".equalsIgnoreCase(encryptionValue)
                && !"None".equals(encryptionValue));
        out.put("keyValue", keyValue);
        Map<String, Object> params = new LinkedHashMap<>();
        for (String p : data.leaves(path)) {
            params.put(p.substring(path.length()), data.value(p));
        }
        out.put("params", params);
        return out;
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

    private String bandOfTr181(DeviceData data, String ssidPath) {
        String lower = data.value(ssidPath + "LowerLayers");
        if (lower != null) {
            for (String ref : lower.split(",")) {
                String radio = "Device." + ref.trim();
                if (!radio.endsWith(".")) {
                    radio += ".";
                }
                String band = data.value(radio + "OperatingFrequencyBand");
                if (band != null) {
                    return band.contains("2.4") ? "2.4G" : band.contains("5") ? "5G" : band;
                }
            }
        }
        return null;
    }

    private String bandOfTr098(String standard) {
        if (standard == null || standard.isBlank()) {
            return null;
        }
        Set<String> toks = Set.of(standard.toLowerCase().split("[,;\\s]+"));
        boolean b5 = toks.contains("a") || toks.contains("ac") || toks.contains("ax");
        boolean b24 = toks.contains("b") || toks.contains("g") || toks.contains("n");
        if (b5 && b24) {
            return "2.4G+5G";
        }
        return b5 ? "5G" : b24 ? "2.4G" : standard;
    }

    // ---------------- SIP ----------------

    private List<Map<String, Object>> sipList(DeviceData data, String root) {
        List<Map<String, Object>> out = new ArrayList<>();
        String base = BusinessPaths.voiceRoot(root);
        for (int svc : data.instances(base)) {
            String profileBase = base + svc + ".VoiceProfile.";
            for (int profile : data.instances(profileBase)) {
                String lineBase = profileBase + profile + ".Line.";
                for (int line : data.instances(lineBase)) {
                    out.add(sipEntry(data, lineBase + line + ".", line));
                }
            }
        }
        return out;
    }

    private Map<String, Object> sipEntry(DeviceData data, String prefix, int line) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", prefix);
        out.put("line", line);
        out.put("number", data.value(prefix + "DirectoryNumber"));
        String server = orElse(data.value(prefix + "ProxyServer"), data.value(prefix + "SIPProxyServer"));
        server = orElse(server, data.value(prefix + "RegistrarServer"));
        out.put("server", server);
        String status = orElse(data.value(prefix + "Status"), data.value(prefix + "SIPRegisterStatus"));
        status = orElse(status, data.value(prefix + "RegisterStatus"));
        out.put("statusText", status);
        out.put("registered", status != null && status.matches("(?i).*(up|registered|ok).*"));
        Map<String, Object> params = new LinkedHashMap<>();
        for (String p : data.leaves(prefix)) {
            params.put(p.substring(prefix.length()), data.value(p));
        }
        out.put("params", params);
        return out;
    }

    // ---------------- 派生字段 ----------------

    private String ponType(DeviceData data) {
        for (String p : data.allPaths("")) {
            if (p.contains("PON") && p.endsWith(".Mode")) {
                return data.value(p);
            }
        }
        return null;
    }

    private String opticText(DeviceData data) {
        for (String p : data.allPaths("")) {
            String leaf = p.substring(p.lastIndexOf('.') + 1);
            if (leaf.contains("RxPower") && data.value(p) != null) {
                return "收光 " + data.value(p) + " dBm";
            }
        }
        return null;
    }

    // ---------------- 工具 ----------------

    private String firstValue(DeviceData data, String suffix) {
        for (String p : data.allPaths("")) {
            if (p.endsWith(suffix)) {
                String v = data.value(p);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        }
        return null;
    }

    private Boolean boolValue(DeviceData data, String path, Boolean def) {
        String v = data.value(path);
        if (v == null) {
            return def;
        }
        if (v.matches("(?i)1|true|enabled")) {
            return true;
        }
        if (v.matches("(?i)0|false|disabled")) {
            return false;
        }
        return def;
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

    private String orElse(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private Long longOf(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String fmt(Long ms) {
        if (ms == null) {
            return null;
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ms));
    }
}
