/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.model.DeviceData;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TR-098 / TR-181 两个数据模型的路径差异收口：
 * 识别根节点、给出各业务域（WAN/LAN/WIFI/SIP/设备信息）的路径前缀。
 * 下发组装只认这里给出的前缀，前端不需要关心数据模型差异。
 */
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
