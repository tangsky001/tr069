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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 业务快照派生 */
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

    /** 反序列化落库的快照 JSON */
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

    /** 用台账最新时间覆盖快照时间 */
    public void refreshTimes(Map<String, Object> snapshot, Map<String, Object> deviceRow) {
        if (snapshot == null) {
            return;
        }
        Long bootMs = longOf(deviceRow.get("last_boot_ms"));
        Long informMs = longOf(deviceRow.get("last_inform_ms"));
        snapshot.put("registerTime", fmt(bootMs));
        snapshot.put("lastContactTime", fmt(informMs));
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
        String upTime = data.value(root + "DeviceInfo.UpTime");
        if (upTime == null || upTime.isBlank()) {
            upTime = firstValue(data, "DeviceInfo.UpTime");
        }
        if (upTime == null || upTime.isBlank()) {
            upTime = firstValue(data, "UpTime");
        }
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
        Map<String, Object> params = new LinkedHashMap<>();
        for (String p : data.leaves(prefix)) {
            params.put(p.substring(prefix.length()), data.value(p));
        }
        String serviceList = orElse(data.value(prefix + "ServiceList"), vendorParam(params, "servicelist"));
        out.put("path", prefix);
        out.put("instance", instance);
        out.put("connMode", connMode);
        out.put("ipProtocol", ipProtocolOf(params));
        out.put("addrType", orElse(data.value(prefix + "AddressingType"), vendorParam(params, "addresstype")));
        out.put("up", boolValue(data, prefix + "Enable", true));
        out.put("statusText", data.value(prefix + "ConnectionStatus"));
        out.put("uptimeText", uptimeText(data.value(prefix + "Uptime")));
        out.put("natEnabled", boolValue(data, prefix + "NATEnabled", null));
        out.put("mtu", data.value(prefix + "MaxMTUSize"));
        out.put("title", orElse(name, orElse(serviceList, connMode + " " + instance)));
        out.put("service", orElse(serviceList, orElse(name, connMode)));
        out.put("bindPorts", "");
        out.put("vlanId", vendorParam(params, "vlanidmark") != null
                ? vendorParam(params, "vlanidmark") : vendorParam(params, "vlanid"));
        out.put("params", params);
        return out;
    }

    /**
     * 地址协议类型：IPv4 / IPv6 / IPv4+IPv6，判不出来返回 null（master DeviceBusinessService 口径）。
     * 只认设备申报的 IPMode 类字段（各厂商私有名：X_CMCC_IPMode / X_CT-COM_IPMode /
     * IPProtocolVersion / X_HUAWEI_IPMode / X_ZTE_IPMode ...），值 1/2/3 或标准写法；
     * 认不出绝不把 ConnectionType 的 IP_Routed/IP_Bridged 之类原样当协议显示。
     * 没上报 IPMode 时才按实锤证据推断：IPv4 有效外网地址 / IPv6 状态 Connected / IPv6 地址。
     */
    private static String ipProtocolOf(Map<String, Object> params) {
        String mode = firstIpMode(params);
        String declared = normalizeIpMode(mode);
        if (declared != null) {
            return declared;
        }
        boolean v4 = hasIpv4(params);
        boolean v6 = hasIpv6(params);
        if (v4 && v6) {
            return "IPv4+IPv6";
        }
        if (v4) {
            return "IPv4";
        }
        if (v6) {
            return "IPv6";
        }
        return null;
    }

    /** 取该连接上报的 IPMode 类字段值（键名匹配，覆盖任意厂商私有前缀） */
    private static String firstIpMode(Map<String, Object> params) {
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
            if (!k.contains("ipmode") && !k.contains("ipprotocol") && !k.contains("ipversion")) {
                continue;
            }
            String val = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            if (!val.isEmpty()) {
                return val;
            }
        }
        return null;
    }

    /** 协议值归一：1/2/3 → IPv4/IPv6/IPv4+IPv6；其它写法只认标准关键字，认不出返回 null */
    private static String normalizeIpMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        switch (v) {
            case "1":
                return "IPv4";
            case "2":
                return "IPv6";
            case "3":
                return "IPv4+IPv6";
            default:
                break;
        }
        String low = v.toLowerCase(Locale.ROOT);
        boolean v4 = low.contains("ipv4");
        boolean v6 = low.contains("ipv6");
        if ((v4 && v6) || low.contains("dual") || low.contains("both")) {
            return "IPv4+IPv6";
        }
        if (v6) {
            return "IPv6";
        }
        if (v4) {
            return "IPv4";
        }
        return null; // 不回显乱七八糟的原值
    }

    /** 是否拿到了可用的 IPv4 地址（master hasIpv4 口径） */
    private static boolean hasIpv4(Map<String, Object> params) {
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
            if (!k.endsWith("externalipaddress") && !k.endsWith("ipaddress")) {
                continue;
            }
            String val = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            if (!val.isEmpty() && !"0.0.0.0".equals(val) && !val.contains(":")) {
                return true;
            }
        }
        return false;
    }

    /** IPv6 侧是否真的起来了：连接状态 Connected/Up，或分配到合法 IPv6 地址（排除 MAC 形态） */
    private static boolean hasIpv6(Map<String, Object> params) {
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
            if (!k.contains("ipv6")) {
                continue;
            }
            String val = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            if (val.isEmpty()) {
                continue;
            }
            if ((k.contains("status") || k.contains("connectionstatus"))
                    && ("connected".equalsIgnoreCase(val) || "up".equalsIgnoreCase(val))) {
                return true;
            }
            if ((k.contains("address") || k.contains("prefix")) && looksLikeIpv6(val)) {
                return true;
            }
        }
        return false;
    }

    /** 值是否为 IPv6 地址形态；MAC（6 段两位十六进制）不算 */
    private static boolean looksLikeIpv6(String val) {
        if (val == null || !val.contains(":") || "::".equals(val)) {
            return false;
        }
        String[] parts = val.split(":", -1);
        boolean mac = parts.length == 6;
        if (mac) {
            for (String p : parts) {
                if (!p.matches("[0-9a-fA-F]{2}")) {
                    mac = false;
                    break;
                }
            }
        }
        return !mac && (parts.length >= 3 || val.contains("::"));
    }

    /** 在连接参数里找厂商自定义参数（键名后缀匹配，不区分大小写），取第一个非空值 */
    private String vendorParam(Map<String, Object> params, String suffix) {
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String k = e.getKey().toLowerCase();
            if (k.endsWith(suffix) && e.getValue() != null && !String.valueOf(e.getValue()).isBlank()) {
                return String.valueOf(e.getValue());
            }
        }
        return null;
    }

    /** LAN 口清单：统一走 LanPortCatalog（设备申报口径），不再各自枚举 */
    private List<Map<String, Object>> lanList(DeviceData data, String root) {
        return LanPortCatalog.enumerate(data, root);
    }

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
                base = root + "LANDevice." + lanDev + ".WLANConfiguration.";
                ids = data.instances(base);
            }
            for (int i : ids) {
                String p = base + i + ".";
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
        boolean b5 = toks.contains("a") || toks.contains("ac");
        boolean b24 = toks.contains("b") || toks.contains("g") || toks.contains("n");
        if (b5 && b24) {
            return "2.4G+5.8G";
        }
        return b5 ? "5.8G" : b24 ? "2.4G" : null;
    }

    /** CMCC 私有 RFBand 换算频段 */
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

    /** 按工作信道推断频段 */
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

    private List<Map<String, Object>> sipList(DeviceData data, String root) {
        List<Map<String, Object>> out = new ArrayList<>();
        String base = BusinessPaths.voiceRoot(root);
        for (int svc : data.instances(base)) {
            String profileBase = base + svc + ".VoiceProfile.";
            for (int profile : data.instances(profileBase)) {
                String lineBase = profileBase + profile + ".Line.";
                for (int line : data.instances(lineBase)) {
                    Map<String, Object> entry = sipEntry(data, lineBase + line + ".", line);
                    if (entry != null) {
                        out.add(entry);
                    }
                }
            }
        }
        return out;
    }

    private Map<String, Object> sipEntry(DeviceData data, String prefix, int line) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (String p : data.leaves(prefix)) {
            params.put(p.substring(prefix.length()), data.value(p));
        }
        boolean hasData = params.values().stream().anyMatch(v -> v != null && !String.valueOf(v).isBlank());
        if (!hasData) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", prefix);
        out.put("line", line);
        String number = orElse(data.value(prefix + "DirectoryNumber"),
                orElse(data.value(prefix + "SIP.URI"),
                        orElse(data.value(prefix + "SIP.AuthUserName"),
                                orElse(vendorParam(params, "uri"), vendorParam(params, "authusername")))));
        if (number != null && number.contains("@")) {
            number = number.substring(0, number.indexOf('@'));
        }
        out.put("number", number);
        String server = orElse(data.value(prefix + "ProxyServer"), data.value(prefix + "SIPProxyServer"));
        server = orElse(server, data.value(prefix + "RegistrarServer"));
        out.put("server", server);
        String status = orElse(data.value(prefix + "Status"), data.value(prefix + "SIPRegisterStatus"));
        status = orElse(status, data.value(prefix + "RegisterStatus"));
        String low = status == null ? "" : status.toLowerCase();
        boolean registered = low.equals("up") || low.contains("ok")
                || (low.contains("registered") && !low.contains("unregistered") && !low.contains("registering"));
        out.put("statusText", sipStatusText(status));
        out.put("registered", registered);
        out.put("params", params);
        return out;
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
        if (low.contains("unregistered")) {
            return "未注册";
        }
        if (low.contains("registering")) {
            return "注册中";
        }
        if (low.contains("disabled")) {
            return "未启用";
        }
        if (low.contains("error") || low.contains("fail")) {
            return "注册失败";
        }
        return status;
    }

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
