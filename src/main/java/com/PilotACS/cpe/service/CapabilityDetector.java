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

    /**
     * 实测 WIFI 频段。
     * <p>判定口径：
     * <ul>
     * <li>每个射频实例独立判定，再取并集；</li>
     * <li>标准 b/g → 2.4G，a/ac → 5G；n/ax/be（WiFi4/6/7）在 2.4G 和 5G 上都存在，
     *     单独出现时只能算"模糊证据"——WiFi6 的 2.4G 射频经常只报 AX，不能当 5G；</li>
     * <li>模糊射频靠另一颗射频的清晰证据补齐（双频机必是一颗 2.4G 一颗 5G）；</li>
     * <li>兜底：实测到 5G 的家用光猫必然带 2.4G 频段，不存在"只有 5G"的机型。</li>
     * </ul>
     */
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
            switch (leaf) {
                case "OperatingFrequencyBand" -> {
                    String s = v.toLowerCase();
                    if (s.contains("2.4") || (s.contains("2") && !s.contains("5"))) {
                        bands(clear, radio).add("2.4G");
                    }
                    if (s.contains("5")) {
                        bands(clear, radio).add("5G");
                    }
                }
                case "OperatingStandards", "Standard", "RadioStandard" -> {
                    // 有的设备整串报 802.11b/g/n、802.11ax，先剥前缀再拆 token
                    for (String raw : v.toLowerCase().split("[,;/、\\s]+")) {
                        String t = raw.replace("802.11", "").trim();
                        switch (t) {
                            case "b", "g", "d" -> bands(clear, radio).add("2.4G");
                            case "a", "ac" -> bands(clear, radio).add("5G");
                            // WiFi4/6/7 双频通吃，单独出现定不了频段
                            case "n", "ax", "be" -> {
                                bands(ambiguous, radio).add("2.4G");
                                bands(ambiguous, radio).add("5G");
                            }
                            default -> {
                            }
                        }
                    }
                }
                default -> {
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

    /** 参数路径所属的射频实例标识，不属于任何射频返回 null */
    private String wifiRadioId(String path) {
        Matcher m = WIFI_TR098.matcher(path);
        if (m.find()) {
            return "t98:" + m.group(1) + '.' + m.group(2);
        }
        m = WIFI_RADIO_TR181.matcher(path);
        if (m.find()) {
            return "t181:" + m.group(1);
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

    /** "2.4G" / "2.4G+5G" / "双频" → 频段集合（口径只有 2.4G / 5G，与 detectWifiBands 对齐） */
    private Set<String> bandsOf(String spec) {
        Set<String> out = new LinkedHashSet<>();
        String s = spec.toLowerCase();
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
