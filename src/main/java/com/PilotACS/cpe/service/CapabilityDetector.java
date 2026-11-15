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

/**
 * 机型能力探测：从参数树里实测 网口数/网口速率/WIFI 频段/SIP 路数，
 * 与前端申报值（isp/lanPorts/lanSpeed/sipCount/wifiSpec）逐项比对。
 */
@Component
public class CapabilityDetector {

    private static final Pattern LAN_IF_TR098 =
            Pattern.compile("\\.LANDevice\\.(\\d+)\\.LANEthernetInterface(?:Config)?\\.(\\d+)\\.");
    private static final Pattern LAN_IF_TR181 = Pattern.compile("\\.Ethernet\\.Interface\\.(\\d+)\\.");
    private static final Pattern WIFI_TR098 =
            Pattern.compile("\\.LANDevice\\.(\\d+)\\.LANWLANConfiguration\\.(\\d+)\\.");
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
            if (v == null) {
                continue;
            }
            Matcher m = Pattern.compile("(\\d+)").matcher(v);
            if (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (n > max) {
                    max = n;
                }
            } else if (v.matches("(?i).*2\\.?5G.*")) {
                max = Math.max(max, 2500);
            } else if (v.matches("(?i).*1G|1000.*")) {
                max = Math.max(max, 1000);
            }
        }
        return max;
    }

    private List<String> detectWifiBands(DeviceData data, String root) {
        Set<String> bands = new LinkedHashSet<>();
        for (String p : data.allPaths("")) {
            String leaf = p.substring(p.lastIndexOf('.') + 1);
            String v = data.value(p);
            if (v == null || v.isBlank()) {
                continue;
            }
            switch (leaf) {
                case "OperatingFrequencyBand" -> {
                    if (v.contains("2.4")) {
                        bands.add("2.4G");
                    }
                    if (v.contains("5")) {
                        bands.add("5G");
                    }
                }
                case "OperatingStandards", "Standard" -> {
                    Set<String> toks = new LinkedHashSet<>(List.of(v.toLowerCase().split("[,;\\s]+")));
                    if (toks.contains("b") || toks.contains("g") || toks.contains("n")) {
                        bands.add("2.4G");
                    }
                    if (toks.contains("a") || toks.contains("ac") || toks.contains("ax")) {
                        bands.add("5G");
                    }
                }
                default -> {
                }
            }
        }
        return new ArrayList<>(bands);
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

    /**
     * 实测 vs 申报 逐项比对。
     * 申报：isp/lanPorts/lanSpeed/sipCount/wifiSpec（来自 capability-options 的口径）
     */
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
        // WIFI 频段
        String wifiSpec = str(declared.get("wifiSpec"));
        if (wifiSpec != null && !wifiSpec.isBlank()) {
            Set<String> declaredBands = bandsOf(wifiSpec);
            List<String> missing = declaredBands.stream().filter(b -> !dBands.contains(b)).toList();
            addCheck(checks, reasons, "WIFI 频段", String.join("+", dBands), missing.isEmpty(),
                    String.join("+", declaredBands));
        }
        // SIP 路数
        Integer sipCount = intOf(declared.get("sipCount"));
        if (sipCount != null) {
            addCheck(checks, reasons, "SIP 路数", String.valueOf(dSip), dSip >= sipCount,
                    sipCount + " 路");
        }

        // 探针记录（给前端展示探测到了哪些参数）
        addProbe(probes, "lanPortCount", findProbePath(data, LAN_IF_TR098, root), String.valueOf(dLan));
        addProbe(probes, "lanSpeedMbps", findNamed(data, "MaxBitRate"), String.valueOf(dSpeed));
        addProbe(probes, "wifiBands", findNamed(data, "OperatingFrequencyBand", "Standard"),
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

    /** "2.4G" / "2.4G+5G" / "双频" → 频段集合 */
    private Set<String> bandsOf(String spec) {
        Set<String> out = new LinkedHashSet<>();
        String s = spec.toLowerCase();
        if (s.contains("2.4") || s.contains("单频")) {
            out.add("2.4G");
        }
        if (s.contains("5") || s.contains("双") || s.contains("三")) {
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
