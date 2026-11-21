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

/** TR-098 / TR-181 两个数据模型的路径差异收口： */
public final class BusinessPaths {

    public static final String ROOT_TR098 = "InternetGatewayDevice.";
    public static final String ROOT_TR181 = "Device.";

    /** 会话开始时必探的管理路径（ConnectionRequestURL / 版本） */
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

    /** 会话 Inform 后必 GPV 的关键叶子（补 Inform 没带的管理参数） */
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

    /** 路径是否带 WiFi/射频词汇（厂商私有子树识别用，不区分大小写；邻居 AP 扫描子树除外） */
    public static boolean wifiHintPath(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase();
        return (lower.contains("wlan") || lower.contains("wifi") || lower.contains("radio"))
                && !lower.contains("neighborap");
    }

    /**
     * 厂商私有 WiFi 子树的叶子兜底：标准业务域（LANDevice./WiFi.）之外还有自己的 WiFi 树
     *（如 X_RAISECOM_WiFi.）的机型，GPN 扫得到路径但固定业务域补采不会取值，
     * 机型适配的 WIFI 频段实测就会一直是"无"。适配探测/业务采集要把这些叶子一并补 GPV。
     */
    public static List<String> vendorWifiLeaves(DeviceData data) {
        List<String> out = new ArrayList<>();
        for (String p : data.allPaths("")) {
            if (!p.endsWith(".") && wifiHintPath(p) && data.value(p) == null) {
                out.add(p);
            }
        }
        return out;
    }

    /** 标准 WLAN 判据叶：GPN 没吐这些叶子（WiFi 关闭/精简实现）时按实例盲探 GPV */
    private static final String[] WIFI_BLIND_LEAVES = {"SSID", "Channel", "Standard", "WirelessMode"};

    /**
     * 按树里已发现的 WLANConfiguration 实例，盲探标准判据叶（SSID/Channel/Standard/WirelessMode）。
     * 有的机型 GPN 精简实现不吐这些叶子，但 GPV 能取到值；设备不认的名字由 9005 拆分机制自然丢弃。
     */
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

    /** 枚举 objectPrefix 下的实例（跨中间层），pattern 如 InternetGatewayDevice.LANDevice.1.LANWLANConfiguration. */
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
