/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.model.DeviceData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 机型能力探测：从参数树里实测 网口数/网口速率/WIFI 频段/SIP 路数， */
@Component
public class CapabilityDetector {

    private static final Pattern LAN_IF_TR098 =
            Pattern.compile("\\.LANDevice\\.(\\d+)\\.LANEthernetInterface(?:Config)?\\.(\\d+)\\.");
    private static final Pattern LAN_IF_TR181 = Pattern.compile("\\.Ethernet\\.Interface\\.(\\d+)\\.");
    private static final Pattern WIFI_TR098 =
            // 兼容标准名 LANWLANConfiguration 与私有名 WLANConfiguration（如 CIOT）
            Pattern.compile("\\.LANDevice\\.(\\d+)\\.(?:LAN)?WLANConfiguration\\.(\\d+)\\.");
    private static final Pattern WIFI_RADIO_TR181 = Pattern.compile("\\.WiFi\\.Radio\\.(\\d+)\\.");
    private static final Pattern SIP_LINE =
            Pattern.compile("\\.VoiceService\\.(\\d+)\\.VoiceProfile\\.(\\d+)\\.Line\\.(\\d+)\\.");

    /** 实测能力 */
    public Map<String, Object> detect(DeviceData data, String root) {
        Map<String, Object> detected = new LinkedHashMap<>();
        detected.put("lanPortCount", detectLanPorts(data, root));
        detected.put("lanSpeedMbps", detectLanSpeed(data, root));
        detected.put("wifiBands", detectWifiBands(data, root));
        detected.put("sipCount", detectSipLines(data));
        return detected;
    }

    private int detectLanPorts(DeviceData data, String root) {
        Set<String> keys = new LinkedHashSet<>();
        for (String p : data.allPaths("")) {
            Matcher m = LAN_IF_TR098.matcher(p);
            if (m.find()) {
                keys.add(m.group(1) + '.' + m.group(2));
                continue;
            }
            m = LAN_IF_TR181.matcher(p);
            if (m.find()) {
                // TR-181：排除上行口（Upstream）
                String up = data.value(p.substring(0, m.end()) + "Upstream");
                if (up == null || !up.matches("(?i)1|true")) {
                    keys.add(m.group(1));
                }
            }
        }
        return keys.size();
    }

    private int detectLanSpeed(DeviceData data, String root) {
        int max = 0;
        for (String p : data.allPaths("")) {
            String leaf = p.substring(p.lastIndexOf('.') + 1);
            if (!leaf.equals("MaxBitRate") && !leaf.equals("EthernetLinkStatus")) {
                continue;
            }
            String v = data.value(p);
            if (v == null || v.isBlank()) {
                continue;
            }
            max = Math.max(max, parseMbps(v));
        }
        return max;
    }

    /** 口径换算："1000000"/"1000Mbps"/"1Gbps"/"2.5G" → Mbps；解析不了返回 0 */
    private int parseMbps(String v) {
        String up = v.trim().toUpperCase();
        try {
            // G 级单位必须先于纯数字匹配：否则 "1Gbps" 会被 (\d+) 抓成 1Mbps
            Matcher g = Pattern.compile("([0-9.]+)\\s*G(BPS|BIT)?").matcher(up);
            if (g.find()) {
                return (int) Math.round(Double.parseDouble(g.group(1)) * 1000);
            }
            Matcher m = Pattern.compile("([0-9.]+)\\s*(K(BPS|BIT)?|MBPS|M(BIT)?)?").matcher(up);
            if (m.find()) {
                double n = Double.parseDouble(m.group(1));
                String unit = m.group(2);
                if (unit != null && unit.startsWith("K")) {
                    n /= 1000;
                }
                return (int) Math.round(n);
            }
        } catch (NumberFormatException ignore) {
            // 语义不明的字符串按 0 处理
        }
        return 0;
    }

    /** 实测 WIFI 频段。 */
    private List<String> detectWifiBands(DeviceData data, String root) {
        Map<String, Set<String>> clear = new LinkedHashMap<>();
        Map<String, Set<String>> ambiguous = new LinkedHashMap<>();
        Set<String> radios = new LinkedHashSet<>();
        for (String p : data.allPaths("")) {
            String radio = wifiRadioId(p);
            if (radio == null) {
                continue;
            }
            radios.add(radio);
            String leaf = p.substring(p.lastIndexOf('.') + 1);
            String v = data.value(p);
            if (v == null || v.isBlank()) {
                continue;
            }
            // 证据优先级（从最基础的判据往上）：
            // 频段字段（FrequencyBand/RFBand）→ 802.11 代际（b/g/n=4、ac=5、ax=6、be=7）→ 信道 → SSID 命名
            if (leaf.endsWith("FrequencyBand") || leaf.endsWith("FrequencyBands")
                    || leaf.toLowerCase().endsWith("wifiband")) {
                String s = v.toLowerCase();
                if (s.contains("2.4") || (s.contains("2") && !s.contains("5"))) {
                    bands(clear, radio).add("2.4G");
                }
                if (s.contains("5")) {
                    bands(clear, radio).add("5G");
                }
            } else if (leaf.endsWith("RFBand")) {
                // 厂商 RF 频段标记：X_HW_RFBand="2.4GHz"/"5GHz"，X_CMCC_RFBand="0"=2.4G/"1"=5G
                String s = v.trim().toLowerCase();
                if (s.startsWith("2.4") || "0".equals(s)) {
                    bands(clear, radio).add("2.4G");
                } else if (s.startsWith("5") || "1".equals(s)) {
                    bands(clear, radio).add("5G");
                }
            } else if (leaf.endsWith("Standard") || leaf.endsWith("Standards")
                    || leaf.equals("WirelessMode") || leaf.equals("WiFiMode")) {
                // 有的设备整串报 802.11b/g/n、bgn 连写、802.11ax，先剥前缀再按代际拆；
                // WirelessMode/WiFiMode 也常报 11bgn 这类代际串，一并当证据
                for (String raw : v.toLowerCase().split("[,;/、\\s]+")) {
                    parseStandard(raw, radio, clear, ambiguous);
                }
            } else if (leaf.equals("Channel")) {
                // 信道判据：1~14 在 2.4G，>14 必在 5G；0=自动不作判据
                try {
                    int ch = Integer.parseInt(v.trim());
                    if (ch > 14) {
                        bands(clear, radio).add("5G");
                    } else if (ch >= 1) {
                        bands(clear, radio).add("2.4G");
                    }
                } catch (NumberFormatException ignore) {
                    // 非数字信道（如 Auto）不作判据
                }
            } else if (leaf.equals("SSID")) {
                // SSID 命名线索：以 5G 结尾（如 ChinaNet-z533-5G、CMCC-xxx-5G）→ 该射频 5G；
                // 其余命名不作判据（"z533" 里的 5 是编号不是频段）
                if (v.trim().toLowerCase().endsWith("5g")) {
                    bands(clear, radio).add("5G");
                }
            }
        }

        Set<String> bands = new LinkedHashSet<>();
        clear.values().forEach(bands::addAll);
        boolean has2 = bands.contains("2.4G");
        boolean has5 = bands.contains("5G");
        for (Set<String> amb : ambiguous.values()) {
            if (has2 && !has5) {
                bands.add("5G");
            } else if (has5 && !has2) {
                bands.add("2.4G");
            } else if (!has2 && !has5 && radios.size() >= 2) {
                // 两颗射频都只有模糊证据：双射频机按双频算
                bands.addAll(amb);
            }
        }
        if (!has2 && !has5 && radios.size() == 1 && !ambiguous.isEmpty()) {
            // 单射频只有模糊证据（如仅报 AX/n）：家用机型默认按 2.4G
            bands.add("2.4G");
        }
        // 兜底：能跑 5G 的家用光猫必然也有 2.4G 频段
        if (bands.contains("5G")) {
            bands.add("2.4G");
        }
        return new ArrayList<>(bands);
    }

    private Set<String> bands(Map<String, Set<String>> map, String radio) {
        return map.computeIfAbsent(radio, k -> new LinkedHashSet<>());
    }

    /**
     * 解析一个 802.11 标准 token（如 bgn / an / 11ax / be）。
     * 按 WiFi 代际从基础往上判，不预设设备是高代际：
     * WiFi4（b/g/d → 明确 2.4G；n → 双频模糊）→ WiFi5（a/ac → 明确 5G）→
     * WiFi6（ax → 双频模糊）→ WiFi7（be → 双频模糊）。
     * ax/be 单靠代际判不出频段，只记模糊证据；b/g/a/ac 是明确频段证据。
     */
    private void parseStandard(String token, String radio,
                               Map<String, Set<String>> clear, Map<String, Set<String>> ambiguous) {
        String t = token.replace("802.11", "").trim().toLowerCase();
        boolean fuzzy = false;
        for (int i = 0; i < t.length(); ) {
            if (t.startsWith("ax", i)) {
                fuzzy = true; // WiFi6
                i += 2;
            } else if (t.startsWith("be", i)) {
                fuzzy = true; // WiFi7
                i += 2;
            } else if (t.startsWith("ac", i)) {
                bands(clear, radio).add("5G"); // WiFi5
                i += 2;
            } else {
                switch (t.charAt(i)) {
                    case 'b', 'g', 'd' -> bands(clear, radio).add("2.4G"); // WiFi4 及以下
                    case 'a' -> bands(clear, radio).add("5G");
                    case 'n' -> fuzzy = true; // WiFi4：2.4G/5G 都可能
                    default -> {
                    }
                }
                i++;
            }
        }
        if (fuzzy) {
            bands(ambiguous, radio).add("2.4G");
            bands(ambiguous, radio).add("5G");
        }
    }

    /** 参数路径所属的射频实例标识，不属于任何射频返回 null */
    private String wifiRadioId(String path) {
        // 邻居 AP 扫描子树是周边环境，不是本机射频能力，必须排除（否则邻居的 Channel/SSID 会污染判据）
        if (path.contains("NeighborAP")) {
            return null;
        }
        Matcher m = WIFI_TR098.matcher(path);
        if (m.find()) {
            return "t98:" + m.group(1) + '.' + m.group(2);
        }
        m = WIFI_RADIO_TR181.matcher(path);
        if (m.find()) {
            return "t181:" + m.group(1);
        }
        // 厂商私有 WiFi 子树兜底（如 X_RAISECOM_WiFi.1.）：带 wlan/wifi/radio 词汇的路径
        // 也当射频看，频段证据叶（FrequencyBand/RFBand/Standard/Channel/SSID）一并读取
        if (BusinessPaths.wifiHintPath(path)) {
            int cut = path.lastIndexOf('.');
            return "x:" + (cut > 0 ? path.substring(0, cut) : path);
        }
        return null;
    }

    private int detectSipLines(DeviceData data) {
        Set<String> lines = new LinkedHashSet<>();
        for (String p : data.allPaths("")) {
            Matcher m = SIP_LINE.matcher(p);
            if (m.find()) {
                lines.add(m.group(1) + '.' + m.group(2) + '.' + m.group(3));
            }
        }
        return lines.size();
    }

    /** 实测 vs 申报 逐项比对。 */
    public Map<String, Object> check(Map<String, Object> declared, Map<String, Object> detected,
                                     DeviceData data, String root) {
        List<Map<String, Object>> checks = new ArrayList<>();
        List<Map<String, Object>> probes = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        int dLan = num(detected.get("lanPortCount"));
        int dSpeed = num(detected.get("lanSpeedMbps"));
        int dSip = num(detected.get("sipCount"));
        List<String> dBands = (List<String>) detected.getOrDefault("wifiBands", List.of());

        // 网口数量
        Integer lanPorts = intOf(declared.get("lanPorts"));
        if (lanPorts != null) {
            addCheck(checks, reasons, "网口数量", String.valueOf(dLan), dLan >= lanPorts,
                    lanPorts + " 口");
        }
        // 网口速率（申报口径如 10/100 或 10/100/1000，取最大值比对）
        String lanSpeed = str(declared.get("lanSpeed"));
        if (lanSpeed != null && !lanSpeed.isBlank()) {
            int declaredMax = maxNum(lanSpeed);
            addCheck(checks, reasons, "网口速率", speedText(dSpeed), dSpeed >= declaredMax,
                    speedText(declaredMax));
        }
        // WIFI 频段（申报「无」= 用户确认没有，跳过比对）
        String wifiSpec = str(declared.get("wifiSpec"));
        if (wifiSpec != null && !wifiSpec.isBlank()) {
            Set<String> declaredBands = bandsOf(wifiSpec);
            String actual = dBands.isEmpty() ? "无" : String.join("+", dBands);
            if (declaredBands.isEmpty()) {
                addSkip(checks, "WIFI 频段", "申报无，不比对");
            } else {
                List<String> missing = declaredBands.stream().filter(b -> !dBands.contains(b)).toList();
                addCheck(checks, reasons, "WIFI 频段", actual, missing.isEmpty(),
                        String.join("+", declaredBands));
                // 实测为空但树里有 WiFi 相关节点：多半是厂商私有命名的频段字段没被认识，
                // 把采样到的路径写进原因，便于定位补判据
                if (!missing.isEmpty() && dBands.isEmpty()) {
                    List<String> hints = findWifiHintPaths(data, 5);
                    if (!hints.isEmpty()) {
                        reasons.add("WIFI 频段：设备参数树里有 WiFi 相关节点但读不出频段（"
                                + String.join("；", hints) + "），请重新测试；仍为空请把参数名反馈给开发补判据");
                    }
                }
            }
        }
        // SIP 路数（申报「无」= 用户确认没有，跳过比对）
        String sipStr = str(declared.get("sipCount"));
        if (sipStr != null && !sipStr.isBlank()) {
            boolean none = sipStr.contains("无");
            Integer sipCount = none ? null : intOf(sipStr);
            if (none) {
                addSkip(checks, "SIP 路数", "申报无，不比对");
            } else if (sipCount != null) {
                addCheck(checks, reasons, "SIP 路数", String.valueOf(dSip), dSip >= sipCount,
                        sipCount + " 路");
            }
        }

        // 探针记录（给前端展示探测到了哪些参数）
        addProbe(probes, "lanPortCount", findProbePath(data, LAN_IF_TR098, root), String.valueOf(dLan));
        addProbe(probes, "lanSpeedMbps", findNamed(data, "MaxBitRate"), String.valueOf(dSpeed));
        addProbe(probes, "wifiBands", findNamed(data,
                "OperatingFrequencyBand", "RFBand", "OperatingStandards", "Standard",
                "FrequencyBand", "WiFiBand"),
                String.join("+", dBands));
        addProbe(probes, "sipCount", findProbePath(data, SIP_LINE, root), String.valueOf(dSip));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", checks);
        out.put("probes", probes);
        out.put("reasons", reasons);
        out.put("detected", detected);
        out.put("supported", reasons.isEmpty());
        return out;
    }

    // ---------------- 工具 ----------------

    private void addCheck(List<Map<String, Object>> checks, List<String> reasons,
                          String label, String detectedText, boolean pass, String declaredText) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("label", label);
        c.put("checked", true);
        c.put("pass", pass);
        c.put("detail", "实测 " + detectedText + "，申报 " + declaredText);
        checks.add(c);
        if (!pass) {
            reasons.add(label + "不达标：实测 " + detectedText + "，低于申报 " + declaredText);
        }
    }

    /** 申报「无」的能力项：跳过比对，仅占位展示（checked=false，前端渲染为 —） */
    private void addSkip(List<Map<String, Object>> checks, String label, String detail) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("label", label);
        c.put("checked", false);
        c.put("pass", false);
        c.put("detail", detail);
        checks.add(c);
    }

    private void addProbe(List<Map<String, Object>> probes, String capability,
                          String path, String value) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("capability", capability);
        p.put("path", path);
        p.put("value", value);
        p.put("failed", path == null);
        p.put("status", path == null ? "miss" : "ok");
        probes.add(p);
    }

    /** 找一个匹配实例模式的代表参数路径 */
    private String findProbePath(DeviceData data, Pattern pat, String root) {
        for (String p : new TreeMap<>(data.all()).keySet()) {
            if (pat.matcher(p).find()) {
                return p;
            }
        }
        return null;
    }

    private String findNamed(DeviceData data, String... names) {
        for (String n : names) {
            for (String p : data.allPaths("")) {
                if (p.endsWith("." + n) && data.value(p) != null) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * 树里 WiFi 相关节点采样（诊断用）：只取有值的叶子、滤掉 WMM/统计/关联设备等噪声子树，
     * 输出 {@code 路径 = 值}（值截 40 字符），一眼能看出频段字段叫什么、值长什么样。
     * 一个有值的都没有时退回展示原始路径（标无值）——说明 WiFi 批次没采到值。
     */
    private List<String> findWifiHintPaths(DeviceData data, int limit) {
        List<String> out = new ArrayList<>();
        List<String> raw = new ArrayList<>();
        for (String p : data.allPaths("")) {
            if (p.endsWith(".") || !BusinessPaths.wifiHintPath(p)) {
                continue;
            }
            String lower = p.toLowerCase();
            boolean noise = lower.contains("wmmparameter") || lower.contains("associateddevice")
                    || lower.contains("stats.") || lower.contains("blocked");
            String v = data.value(p);
            if (!noise && v != null && !v.isBlank()) {
                v = v.trim();
                out.add(p + " = " + (v.length() > 40 ? v.substring(0, 40) + "…" : v));
                if (out.size() >= limit) {
                    return out;
                }
            } else if (!noise) {
                raw.add(p + " = (无值)");
            }
        }
        if (out.isEmpty()) {
            return raw.subList(0, Math.min(raw.size(), limit));
        }
        return out;
    }

    /** 树里是否有 WiFi 相关节点（叶子，无论有没有值）——存量树 WiFi 值缺失时判断要不要在线补采 */
    public boolean hasWifiNodes(DeviceData data) {
        for (String p : data.allPaths("")) {
            if (!p.endsWith(".") && BusinessPaths.wifiHintPath(p)) {
                return true;
            }
        }
        return false;
    }

    /** "2.4G" / "2.4G+5G" / "双频" → 频段集合；「无」返回空集表示要求实测也没有 */
    private Set<String> bandsOf(String spec) {
        Set<String> out = new LinkedHashSet<>();
        String s = spec.toLowerCase();
        if (s.contains("无") || s.contains("none")) {
            return out;
        }
        for (String token : s.split("[+/、,，\\s]+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.contains("2.4")) {
                out.add("2.4G");
            }
            if (token.contains("5")) {
                out.add("5G");
            }
        }
        if (s.contains("单频")) {
            out.add("2.4G");
        }
        if (s.contains("双频")) {
            out.add("2.4G");
            out.add("5G");
        }
        if (out.isEmpty()) {
            out.add(s.toUpperCase());
        }
        return out;
    }

    public String speedText(int mbps) {
        return switch (mbps) {
            case 0 -> "未知";
            case 100 -> "百兆";
            case 1000 -> "千兆";
            case 2500 -> "2.5G";
            case 10000 -> "万兆";
            default -> mbps + "M";
        };
    }

    private int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private Integer intOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private int maxNum(String s) {
        Matcher m = Pattern.compile("(\\d+)").matcher(s);
        int max = 0;
        while (m.find()) {
            max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return max;
    }
}
