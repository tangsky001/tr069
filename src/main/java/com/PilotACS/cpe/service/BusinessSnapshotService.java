/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.cpe.model.DeviceData;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper JSON = new ObjectMapper();

    public Map<String, Object> snapshot(Map<String, Object> deviceRow, Map<String, CpeParam> params,
                                        String collectStatus, String message) {
        DeviceData data = new DeviceData();
        data.loadAll(params);
        String root = BusinessPaths.detectRoot(data, null);
        return snapshot(deviceRow, data, root, collectStatus, message);
    }

    /** 快照序列化为 JSON（落库用）；失败返回 null */
    public String toJson(Map<String, Object> snap) {
        try {
            return JSON.writeValueAsString(snap);
        } catch (Exception e) {
            return null;
        }
    }

    /** 反序列化落库的快照 JSON；解析失败返回 null（调用方现算兜底） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取落库快照后，用台账最新时间覆盖（快照生成时刻的"注册/最后联系/运行时长"会过时） */
    public void refreshTimes(Map<String, Object> snapshot, Map<String, Object> deviceRow) {
        if (snapshot == null) {
            return;
        }
        Long bootMs = longOf(deviceRow.get("last_boot_ms"));
        Long informMs = longOf(deviceRow.get("last_inform_ms"));
        snapshot.put("registerTime", fmt(bootMs));
        snapshot.put("lastContactTime", fmt(informMs));
        // 运行时长动态推算：采集时刻的 UpTime + 距最近一次上报经过的时间
        if (informMs != null && snapshot.get("upTimeSeconds") instanceof Number n) {
            long up = n.longValue() + Math.max(0, (System.currentTimeMillis() - informMs) / 1000);
            snapshot.put("uptimeText", uptimeText(String.valueOf(up)));
        }
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
        out.put("upTimeSeconds", secondsOf(upTime));
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
                        String prefix = obj + i + ".";
                        // 幽灵实例过滤：GPN 能列出但 GPV 采不到任何值（设备实际不存在该连接）
                        String ctype = data.value(prefix + "ConnectionType");
                        String cstat = data.value(prefix + "ConnectionStatus");
                        if ((ctype == null || ctype.isBlank()) && (cstat == null || cstat.isBlank())) {
                            continue;
                        }
                        out.add(wanEntry(data, prefix, kind.equals("WANIPConnection") ? "IP" : "PPP", i));
                    }
                }
            }
        }
        return out;
    }

    private Map<String, Object> wanEntry(DeviceData data, String prefix, String connMode, int instance) {
        Map<String, Object> out = new LinkedHashMap<>();
        String name = data.value(prefix + "Name");
        String serviceList = data.value(prefix + "ServiceList");
        out.put("path", prefix);
        out.put("instance", instance);
        out.put("connMode", connMode);
        out.put("ipProtocol", data.value(prefix + "ConnectionType"));
        out.put("up", boolValue(data, prefix + "Enable", true));
        out.put("statusText", data.value(prefix + "ConnectionStatus"));
        out.put("uptimeText", uptimeText(data.value(prefix + "Uptime")));
        out.put("natEnabled", boolValue(data, prefix + "NATEnabled", null));
        out.put("mtu", data.value(prefix + "MaxMTUSize"));
        // 卡片标题：连接名（设备网页里叫 Internet连接 / 1_TR069_R_VID 这类）→ 业务清单 → 模式+实例号
        out.put("title", orElse(name, orElse(serviceList, connMode + " " + instance)));
        // 业务模式：ServiceList（INTERNET/TR069/VOIP）→ 连接名 → 连接模式
        out.put("service", orElse(serviceList, orElse(name, connMode)));
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
            java.util.List<Integer> ids = data.instances(base);
            if (ids.isEmpty()) {
                // 厂商变体：部分固件（如 SKYW/移动定制）少写 LAN 前缀 → WLANConfiguration
                base = root + "LANDevice." + lanDev + ".WLANConfiguration.";
                ids = data.instances(base);
            }
            for (int i : ids) {
                String p = base + i + ".";
                // 频段判据优先级：X_CMCC_RFBand(0=2.4G,1=5G) → Standard → Channel。
                // 不能拿 ax 当 5G 专属：Wi-Fi 6 的 ax 在 2.4G/5G 上同名
                String band = bandOfRfBand(data.value(p + "X_CMCC_RFBand"));
                if (band == null) {
                    band = bandOfTr098(data.value(p + "Standard"));
                }
                if (band == null) {
                    band = bandOfChannel(data.value(p + "Channel"));
                }
                out.add(wifiEntry(p, i,
                        data.value(p + "SSID"),
                        boolValue(data, p + "Enable", null),
                        "Up".equals(data.value(p + "Status")),
                        data.value(p + "Status"),
                        band,
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
                    return band.contains("2.4") ? "2.4G" : band.contains("5") ? "5.8G" : band;
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
        // 严格口径：a/ac 只在 5G，b/g/n 只在 2.4G；ax 两边都有 → 不作判据（留给 RFBand/Channel）
        boolean b5 = toks.contains("a") || toks.contains("ac");
        boolean b24 = toks.contains("b") || toks.contains("g") || toks.contains("n");
        if (b5 && b24) {
            return "2.4G+5.8G";
        }
        return b5 ? "5.8G" : b24 ? "2.4G" : null;
    }

    /** CMCC 私有 X_CMCC_RFBand：0=2.4G，1=5G（2 预留 6G）；其余返回 null */
    private String bandOfRfBand(String rfBand) {
        if (rfBand == null || rfBand.isBlank()) {
            return null;
        }
        try {
            int v = (int) Double.parseDouble(rfBand.trim());
            return switch (v) {
                case 0 -> "2.4G";
                case 1 -> "5.8G";
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 按工作信道推断频段：1~14 → 2.4G，>14 → 5.8G；0(自动)/解析失败返回 null */
    private String bandOfChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        try {
            int ch = (int) Double.parseDouble(channel.trim());
            if (ch <= 0) {
                return null;
            }
            return ch <= 14 ? "2.4G" : "5.8G";
        } catch (NumberFormatException e) {
            return null;
        }
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

    private Long secondsOf(String seconds) {
        if (seconds == null || seconds.isBlank()) {
            return null;
        }
        try {
            return (long) Double.parseDouble(seconds.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
