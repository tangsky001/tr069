package com.tr069.acs.worklist.model;

import java.util.List;

/**
 * 业务语义字典：固定的业务对象与业务字段定义。
 *
 * <p>业务模板只描述这几类业务对象和这些字段，不含任何 CWMP 节点路径；
 * 路径由型号映射表（{@code model_param_mapping} / {@code model_object_mapping}）给出，
 * 所以新增一种光猫不会动业务模板。</p>
 *
 * <p>{@code hints} 是节点名关键字，人工做映射时用来从设备能力树里推荐候选节点，
 * 只影响推荐顺序，不做自动绑定——节点命名各厂商差异大，最终由人工确认。</p>
 */
public final class SemanticSchema {

    private SemanticSchema() {
    }

    /**
     * 业务字段定义。
     */
    public record BizField(String code, String label, String type, boolean required, String... hints) {
    }

    /**
     * 业务对象定义。
     */
    public record BizObject(String code, String label, boolean needInstance, List<BizField> fields) {
    }

    /** Internet 上网 WAN（DHCP 或静态）。 */
    public static final String INTERNET_WAN = "Internet_WAN";
    /** 语音 WAN。 */
    public static final String VOIP_WAN = "VOIP_WAN";
    /** 桥接 WAN。 */
    public static final String BRIDGE_WAN = "Bridge_WAN";
    /** 2.4G 射频。 */
    public static final String WIFI_2G = "WiFi_2G";
    /** 5G 射频。 */
    public static final String WIFI_5G = "WiFi_5G";

    /** WAN 类业务共用的字段。 */
    private static final List<BizField> WAN_FIELDS = List.of(
            new BizField("enable", "启用连接", "boolean", true, "Enable"),
            new BizField("name", "连接名称", "string", false, "Name"),
            new BizField("connection_type", "连接模式", "string", true, "ConnectionType"),
            new BizField("addressing_type", "地址获取方式", "string", false, "AddressingType"),
            new BizField("vlan_id", "VLAN ID", "unsignedInt", true, "VLAN", "VID"),
            new BizField("vlan_priority", "802.1p 优先级", "unsignedInt", false, "Priority", "8021p"),
            new BizField("service_list", "业务模式", "string", false, "ServiceList"),
            new BizField("nat_enable", "NAT 开关", "boolean", false, "NATEnabled"),
            new BizField("mtu", "MTU", "unsignedInt", false, "MTU"),
            new BizField("ip_protocol", "地址族", "string", false, "IPProtocol"),
            new BizField("external_ip", "静态 IP", "string", false, "ExternalIPAddress"),
            new BizField("subnet_mask", "子网掩码", "string", false, "SubnetMask"),
            new BizField("default_gateway", "默认网关", "string", false, "DefaultGateway"),
            new BizField("username", "拨号账号", "string", false, "Username"),
            new BizField("password", "拨号口令", "string", false, "Password"));

    /** WiFi 射频共用的字段。 */
    private static final List<BizField> WIFI_FIELDS = List.of(
            new BizField("radio_enable", "射频开关", "boolean", true, "Enable", "Radio"),
            new BizField("ssid", "SSID 名称", "string", true, "SSID"),
            new BizField("wpa_key", "WiFi 密码", "string", false, "PreSharedKey", "KeyPassphrase", "WPAKey"),
            new BizField("encryption", "加密方式", "string", false, "BeaconType", "Encryption"),
            new BizField("channel", "信道", "string", false, "Channel"),
            new BizField("bandwidth", "信道带宽", "string", false, "Bandwidth"),
            new BizField("hidden", "隐藏 SSID", "boolean", false, "HideSSID", "SSIDAdvertisement"),
            new BizField("tx_power", "发射功率", "string", false, "TransmitPower", "TxPower"),
            new BizField("wps_disable", "禁用 WPS", "boolean", false, "WPSDisable"));

    /**
     * 全部业务对象，顺序即下发顺序。
     */
    public static final List<BizObject> OBJECTS = List.of(
            new BizObject(INTERNET_WAN, "Internet 上网 WAN", true, WAN_FIELDS),
            new BizObject(VOIP_WAN, "语音 WAN", true, WAN_FIELDS),
            new BizObject(BRIDGE_WAN, "桥接 WAN", true, WAN_FIELDS),
            new BizObject(WIFI_2G, "2.4G WiFi", false, WIFI_FIELDS),
            new BizObject(WIFI_5G, "5G WiFi", false, WIFI_FIELDS));

    /**
     * 按业务对象编码取定义，未定义返回 null。
     */
    public static BizObject objectOf(String code) {
        for (BizObject object : OBJECTS) {
            if (object.code().equals(code)) {
                return object;
            }
        }
        return null;
    }

    /**
     * 全部业务对象编码。
     */
    public static List<String> objectCodes() {
        return OBJECTS.stream().map(BizObject::code).toList();
    }
}
