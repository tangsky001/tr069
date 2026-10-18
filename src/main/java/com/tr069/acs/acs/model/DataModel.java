package com.tr069.acs.acs.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CWMP 根数据模型：TR-098（{@code InternetGatewayDevice.}）与 TR-181（{@code Device.}）。
 *
 * <p>两种模型的业务挂载点完全不同：TR-098 的 WAN 在 {@code WANDevice.}、WiFi 在
 * {@code LANDevice.i.WLANConfiguration.i}；TR-181 则在 {@code WAN.}、{@code WiFi.SSID.i}、
 * 以太网口在 {@code Ethernet.Interface.}。凡是按「业务」而不是按「路径」工作的代码，
 * 都必须同时认这两个挂载点，否则另一种模型的设备会查到空数据——而且不会报错，只是静默为空。</p>
 *
 * <p>根节点由设备的 Inform 参数判定后落到 {@code cpe_device.root_node}，但这一列并不可靠：
 * 设备从 TR-098 固件升到 TR-181、或者旧版本 ACS 写入过的值都可能残留，
 * 一条脏数据就能让业务快照查到空。所以这里取前缀时默认返回<strong>两种模型的全部变体</strong>，
 * 宁可多几个 LIKE 条件，也不让错误的判定把业务拖空。</p>
 */
public enum DataModel {

    /** TR-098：InternetGatewayDevice，电信企业网关的老三代标准。 */
    TR098("InternetGatewayDevice.", "TR-098"),

    /** TR-181：Device，BBF 现行数据模型，新设备普遍采用。 */
    TR181("Device.", "TR-181");

    /**
     * 业务分区 key → 相对根节点的后缀列表。这里刻意叠了两种模型的写法：
     * {@code wan} 同时包含 TR-098 的 {@code WANDevice.} 与 TR-181 的 {@code WAN.}。
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
     *
     * <p>只看第一条参数是不够的：部分设备的首个参数是 {@code Device.DeviceInfo.} 之外的
     * 扩展节点，甚至有网关会先报 OUI 之类的私有参数。这里遍历全部参数取多数，
     * 一条都认不出来时按 TR-098 处理。</p>
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
     *
     * <p>{@link #SECTION_VARIANTS} 里同时列了两种模型的写法，这里做笛卡尔积，
     * 调用方不必关心设备是哪种模型。</p>
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
