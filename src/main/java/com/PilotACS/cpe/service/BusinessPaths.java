/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.model.DeviceData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** TR-098 / TR-181 数据模型路径差异收口 */
public final class BusinessPaths {

    public static final String ROOT_TR098 = "InternetGatewayDevice.";
    public static final String ROOT_TR181 = "Device.";

    /** 必探的管理路径 */
    private static final String[] KEY_PARAM_SUFFIXES = {
            "ManagementServer.ConnectionRequestURL",
            "DeviceInfo.SoftwareVersion",
            "DeviceInfo.HardwareVersion",
            "DeviceInfo.ModelName"
    };

    private BusinessPaths() {
    }

    /** 从参数树推断数据模型根节点 */
    public static String detectRoot(DeviceData data, String fallback) {
        if (data.has(ROOT_TR098)) {
            return ROOT_TR098;
        }
        if (data.has(ROOT_TR181)) {
            return ROOT_TR181;
        }
        return fallback == null || fallback.isBlank() ? ROOT_TR098 : fallback;
    }

    /** 会话 Inform 后必 GPV 的关键叶子 */
    public static List<String> keyPaths(String root) {
        List<String> out = new ArrayList<>();
        for (String suffix : KEY_PARAM_SUFFIXES) {
            out.add(root + suffix);
        }
        return out;
    }

    /** 业务域根路径（能力测试/业务采集扫描用） */
    public static List<String> businessRoots(String root) {
        List<String> out = new ArrayList<>();
        out.add(root + "DeviceInfo.");
        out.add(root + "ManagementServer.");
        out.add(root + "WANDevice.");
        boolean tr181 = ROOT_TR181.equals(root);
        if (tr181) {
            out.add(ROOT_TR181 + "Ethernet.Interface.");
            out.add(ROOT_TR181 + "WiFi.");
        } else {
            out.add(root + "LANDevice.");
        }
        out.add(root + "Services.VoiceService.");
        return out;
    }

    /** 路径是否带 WiFi/射频词汇 */
    public static boolean wifiHintPath(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase();
        return (lower.contains("wlan") || lower.contains("wifi") || lower.contains("radio"))
                && !lower.contains("neighborap");
    }

    /** 厂商私有 WiFi 子树叶子兜底 */
    public static List<String> vendorWifiLeaves(DeviceData data) {
        List<String> out = new ArrayList<>();
        for (String p : data.allPaths("")) {
            if (!p.endsWith(".") && wifiHintPath(p) && data.value(p) == null) {
                out.add(p);
            }
        }
        return out;
    }

    private static final String[] WIFI_BLIND_LEAVES = {"SSID", "Channel", "Standard", "WirelessMode"};

    /** 按实例盲探标准 WLAN 判据叶 */
    public static List<String> blindWifiLeaves(DeviceData data) {
        Set<String> out = new LinkedHashSet<>();
        Pattern obj = Pattern.compile("\\.WLANConfiguration\\.\\d+\\.");
        for (String p : data.allPaths("")) {
            Matcher m = obj.matcher(p);
            if (m.find()) {
                String base = p.substring(0, m.end());
                for (String leaf : WIFI_BLIND_LEAVES) {
                    out.add(base + leaf);
                }
            }
        }
        return new ArrayList<>(out);
    }

    public static String infoRoot(String root) {
        return root + "DeviceInfo.";
    }

    public static String voiceRoot(String root) {
        return root + "Services.VoiceService.";
    }

    public static boolean isTr181(String root) {
        return ROOT_TR181.equals(root);
    }

    /** 枚举 objectPrefix 下的实例 */
    public static List<Integer> instances(DeviceData data, String objectPrefix) {
        return data.instances(objectPrefix);
    }

    /** 匹配正则实例路径：把 {i} 段转成 (\d+) */
    public static Pattern patternOf(String template) {
        String regex = Pattern.quote(template).replace("{i}", "\\E\\d+\\Q")
                .replace("\\Q\\E", "");
        return Pattern.compile(regex);
    }

    /** 找第一个匹配前缀下指定后缀参数的值，如 find(data, "…LANWLANConfiguration.", "{i}.SSID") */
    public static String firstValue(DeviceData data, String prefix, String suffix) {
        for (String p : data.leaves(prefix)) {
            if (p.endsWith(suffix)) {
                return data.value(p);
            }
        }
        return null;
    }
}
