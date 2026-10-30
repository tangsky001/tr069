package com.tr069.acs.acs.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CWMP 根数据模型：TR-098（{@code InternetGatewayDevice.}）与 TR-181（{@code Device.}）。
 */
public enum DataModel {

    /** TR-098：InternetGatewayDevice，电信企业网关的老三代标准。 */
    TR098("InternetGatewayDevice.", "TR-098"),

    /** TR-181：Device，BBF 现行数据模型，新设备普遍采用。 */
    TR181("Device.", "TR-181");

    /**
     * 业务分区 key → 相对根节点的后缀列表。这里刻意叠了两种模型的写法：
     */
    private static final java.util.Map<String, List<String>> SECTION_VARIANTS =
            java.util.Map.of(
                    "device", List.of("DeviceInfo."),
                    "wan", List.of("WANDevice.", "WAN."),
                    "wifi", List.of("LANDevice.", "LAN.", "WiFi."),
                    "lan", List.of("LANDevice.", "Ethernet.Interface."),
                    "sip", List.of("Services.VoiceService.", "VoiceService."));

    private final String root;

    private final String label;

    DataModel(String root, String label) {
        this.root = root;
        this.label = label;
    }

    /**
     * 根数据模型节点，如 {@code Device.}。
     */
    public String getRoot() {
        return root;
    }

    /**
     * 标准编号，用于日志与对外输出。
     */
    public String getLabel() {
        return label;
    }

    /**
     * 该参数路径是否属于本模型的子树。
     */
    public boolean owns(String path) {
        return path != null && path.startsWith(root);
    }

    /**
     * 按根节点字符串反查模型，识别不出返回 TR-098（当前绝大多数设备）。
     */
    public static DataModel ofRoot(String root) {
        if (root != null) {
            String text = root.trim().toLowerCase(Locale.ROOT);
            for (DataModel model : values()) {
                if (text.startsWith(model.root.toLowerCase(Locale.ROOT))) {
                    return model;
                }
            }
        }
        return TR098;
    }

    /**
     * 从设备上报的参数路径里判定数据模型。
     */
    public static DataModel detect(List<String> paths) {
        if (paths == null) {
            return TR098;
        }
        int tr181 = 0;
        int tr098 = 0;
        for (String path : paths) {
            if (path == null) {
                continue;
            }
            if (path.startsWith(TR181.root)) {
                tr181++;
            } else if (path.startsWith(TR098.root)) {
                tr098++;
            }
        }
        if (tr181 == 0 && tr098 == 0) {
            return TR098;
        }
        return tr181 > tr098 ? TR181 : TR098;
    }

    /**
     * 业务分区在各模型下的全部前缀，返回值形如 {@code [Device.WAN., InternetGatewayDevice.WANDevice.]}。
     */
    public static List<String> businessPrefixes(String sectionKey) {
        List<String> variants = SECTION_VARIANTS.get(sectionKey);
        if (variants == null || variants.isEmpty()) {
            return List.of();
        }
        List<String> prefixes = new ArrayList<>(variants.size() * values().length);
        for (DataModel model : values()) {
            for (String variant : variants) {
                prefixes.add(model.root + variant);
            }
        }
        return prefixes;
    }
}
