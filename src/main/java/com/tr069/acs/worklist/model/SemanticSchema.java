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
    /** SIP 语音（VoiceService 配置，区别于承载它的 VOIP_WAN 连接）。 */
    public static final String VOICE = "Voice_Service";
    /** 设备管理类意图（Telnet / 超管 / 重启等），多为厂商私有节点。 */
    public static final String DEVICE_MGMT = "Device_Mgmt";

    /** WAN 类业务共用的字段。 */
    private static final List<BizField> WAN_FIELDS = List.of(
            new BizField("enable", "启用连接", "boolean", true, "Enable"),
            new BizField("name", "连接名称", "string", false, "Name"),
            new BizField("connection_type", "连接模式", "string", true, "ConnectionType"),
            new BizField("addressing_type", "地址获取方式", "string", false, "AddressingType"),
            new BizField("vlan_id", "VLAN ID", "unsignedInt", true, "VLAN", "VID"),
            new BizField("vlan_priority", "802.1p 优先级", "unsignedInt", false, "Priority", "8021p"),
            new BizField("service_list", "业务模式", "string", false, "ServiceList"),
            new BizField("bind_ports", "绑定端口", "string", false, "Bind", "LAN"),
            new BizField("multicast_vlan", "组播 VLAN ID", "unsignedInt", false, "MulticastVlan"),
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
            new BizField("wpa_algorithm", "WPA 加密算法", "string", false, "Algorithm", "Encryption"),
            new BizField("channel", "信道", "string", false, "Channel"),
            new BizField("bandwidth", "信道带宽", "string", false, "Bandwidth"),
            new BizField("hidden", "隐藏 SSID", "boolean", false, "HideSSID", "SSIDAdvertisement"),
            new BizField("tx_power", "发射功率", "string", false, "TransmitPower", "TxPower"),
            new BizField("radio_hardware_enable", "无线模块硬件开关", "boolean", false, "Module", "Hardware"),
            new BizField("wps_disable", "禁用 WPS", "boolean", false, "WPSDisable"));

    /** SIP 语音（VoiceService/VoiceProfile）字段。 */
    private static final List<BizField> VOICE_FIELDS = List.of(
            new BizField("protocol", "语音协议", "string", false, "SignalingProtocol"),
            new BizField("registrar", "SIP 注册服务器", "string", false, "RegistrarServer", "ProxyServer"),
            new BizField("registrar_port", "注册服务器端口", "unsignedInt", false, "RegistrarServerPort", "ProxyServerPort"),
            new BizField("proxy", "SIP 代理服务器", "string", false, "ProxyServer"),
            new BizField("proxy_port", "代理服务器端口", "unsignedInt", false, "ProxyServerPort"),
            new BizField("outbound_proxy", "Outbound 服务器", "string", false, "OutboundProxy"),
            new BizField("outbound_proxy_port", "Outbound 服务器端口", "unsignedInt", false, "OutboundProxyPort"),
            new BizField("backup_registrar", "备用注册服务器", "string", false, "RegistrarServer", "Backup"),
            new BizField("backup_registrar_port", "备用注册服务器端口", "unsignedInt", false, "Backup"),
            new BizField("min_digits", "最小拨号长度", "unsignedInt", false, "MinDigits"),
            new BizField("user_agent_domain", "用户域名", "string", false, "UserAgentDomain"),
            new BizField("digitmap_enable", "数图开关", "string", false, "DigitMap", "Digit"),
            new BizField("digitmap_rule", "数图规则", "string", false, "DigitMap"),
            new BizField("disable_special_dial", "禁用特殊号码拨号", "boolean", false, "Special", "Dial"),
            new BizField("sip_user_info", "SIP 用户信息", "string", false, "Account", "UserInfo", "Auth"),
            new BizField("prefer_existing_account", "优先用已存在账号", "boolean", false, "Account", "Exist"));

    /** 设备管理类意图字段，多为厂商私有节点，映射后才有报文。 */
    private static final List<BizField> DEVICE_MGMT_FIELDS = List.of(
            new BizField("unlock_limit", "解除上网限制", "boolean", false, "Limit", "Restrict"),
            new BizField("telnet_enable", "开启 Telnet", "boolean", false, "TelnetEnable", "Telnet"),
            new BizField("telnet_username", "Telnet 用户名", "string", false, "Telnet", "UserName"),
            new BizField("telnet_password", "Telnet 口令", "string", false, "Telnet", "Password"),
            new BizField("reboot_after", "下发完成后重启", "boolean", false, "Reboot"),
            new BizField("wan_access", "开启 WAN 侧访问", "boolean", false, "WANAccess", "RemoteAccess"),
            new BizField("igmp_support", "IGMP 组播支持", "string", false, "IGMP", "Multicast"),
            new BizField("multicast_mode", "组播工作模式", "string", false, "Multicast", "Mode"),
            new BizField("clear_loid", "清除逻辑 ID", "boolean", false, "LOID", "LogicalID"),
            new BizField("super_account", "超管账号", "string", false, "Admin", "UserName", "Super"),
            new BizField("super_password", "超管口令", "string", false, "Admin", "Password", "Super"),
            new BizField("firewall_disable", "关闭防火墙", "boolean", false, "Firewall", "Attack"),
            new BizField("log_disable", "禁用日志", "boolean", false, "Log", "Syslog"),
            new BizField("wifi_isolation_disable", "关闭 WiFi 共享隔离", "boolean", false, "Isolation"),
            new BizField("led_control", "面板 LED 控制", "string", false, "LED", "Light"),
            new BizField("wifi_roaming", "WiFi 漫游支持", "string", false, "Roaming", "80211r"),
            new BizField("roaming_threshold_24g", "2.4G 漫游阈值", "string", false, "Roaming", "RSSI"),
            new BizField("roaming_threshold_58g", "5G 漫游阈值", "string", false, "Roaming", "RSSI"));

    /**
     * 全部业务对象，顺序即下发顺序。
     */
    public static final List<BizObject> OBJECTS = List.of(
            new BizObject(INTERNET_WAN, "Internet 上网 WAN", true, WAN_FIELDS),
            new BizObject(VOIP_WAN, "语音 WAN", true, WAN_FIELDS),
            new BizObject(BRIDGE_WAN, "桥接 WAN", true, WAN_FIELDS),
            new BizObject(WIFI_2G, "2.4G WiFi", false, WIFI_FIELDS),
            new BizObject(WIFI_5G, "5G WiFi", false, WIFI_FIELDS),
            new BizObject(VOICE, "SIP 语音", false, VOICE_FIELDS),
            new BizObject(DEVICE_MGMT, "设备管理", false, DEVICE_MGMT_FIELDS));

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
