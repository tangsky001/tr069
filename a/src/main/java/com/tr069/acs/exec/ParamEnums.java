package com.tr069.acs.exec;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 参数取值合法集：命中不了只告警 */
@Slf4j
final class ParamEnums {

    private ParamEnums() {
    }

    /** 叶子名（小写） -> 合法取值 */
    private static final Map<String, List<String>> VALUES = Map.of(
            "beacontype", List.of("None", "Basic", "WPA", "11i", "BasicandWPA",
                    "Basicand11i", "WPAand11i", "BasicandWPAand11i"),
            "ieee11iencryptionmodes", List.of("WEPEncryption", "TKIPEncryption",
                    "WEPandTKIPEncryption", "AESEncryption", "WEPandAESEncryption",
                    "TKIPandAESEncryption", "WEPandTKIPandAESEncryption"),
            "wpaencryptionmodes", List.of("WEPEncryption", "TKIPEncryption",
                    "WEPandTKIPEncryption", "AESEncryption", "WEPandAESEncryption",
                    "TKIPandAESEncryption", "WEPandTKIPandAESEncryption"),
            "basicencryptionmodes", List.of("None", "WEPEncryption"),
            "addressingtype", List.of("DHCP", "Static", "AutoIP", "IPCP", "IKEv2"),
            "transporttype", List.of("PPPoA", "PPPoE", "L2TP", "PPTP"),
            "connectiontype", List.of("Unconfigured", "IP_Routed", "IP_Bridged", "DHCP_Spoofed",
                    "PPPoE_Bridged", "PPPoE_Relay", "PPTP_Relay", "L2TP_Relay"));

    /** 写法对齐：忽略大小写命中未收录的参数原样返回 */
    static String align(String path, String value) {
        List<String> official = valuesOf(path);
        if (official == null || value == null || value.isBlank()) {
            return value;
        }
        if (official.contains(value)) {
            return value;
        }
        for (String candidate : official) {
            if (candidate.equalsIgnoreCase(value.trim())) {
                return candidate;
            }
        }
        log.warn("取值不在合法集里: {}={}，可选 {}", path, value, official);
        return value;
    }

    /** 该参数的合法取值集，未收录返回 null */
    static List<String> valuesOf(String path) {
        return path == null ? null : VALUES.get(leaf(path).toLowerCase(Locale.ROOT));
    }

    private static String leaf(String path) {
        int cut = path.lastIndexOf('.');
        return cut < 0 ? path : path.substring(cut + 1);
    }
}
