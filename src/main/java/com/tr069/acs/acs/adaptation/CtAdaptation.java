package com.tr069.acs.acs.adaptation;

import com.tr069.acs.acs.model.Isp;
import org.springframework.stereotype.Component;

import java.util.List;

/** 中国电信（CT）适配：天翼网关的差异参数收在这里，私有前缀为 X_CT-COM_ / X_CTC_ */
@Component
public class CtAdaptation implements IspAdaptation {

    @Override
    public Isp isp() {
        return Isp.CT;
    }

    // 设备超管账号

    @Override
    public List<String> adminAccountNodes() {
        return List.of("DeviceInfo.X_CT-COM_TeleComAccount.");
    }

    // WAN 连接私有参数

    /** 电信定制机上的业务绑定参数 */
    @Override
    public List<String> serviceListNames() {
        return List.of("X_CT-COM_ServiceList", "ServiceList");
    }

    /** 电信定制机上的 VLAN 模式参数 */
    @Override
    public List<String> vlanModeNames() {
        return List.of("X_CT-COM_VLANMode", "VLANMode");
    }

    /** 电信定制机上的 IP 模式参数 */
    @Override
    public List<String> ipModeNames() {
        return List.of("X_CT-COM_IPMode", "IPMode");
    }

    // LAN 绑定端口

    /** 电信定制机上绑定端口节点 */
    @Override
    public String lanInterfaceNode() {
        return "X_CT-COM_LanInterface";
    }

    /** 读取设备当前绑定时优先试电信私有名 */
    @Override
    public List<String> bindPortNames() {
        return List.of("X_CT-COM_LanInterface", "LanInterface", "BindLANPort");
    }

    // IPv6 状态参数

    @Override
    public List<String> ipv6StatusNames() {
        return List.of("X_CT-COM_IPv6ConnStatus", "IPv6ConnectionStatus");
    }

    @Override
    public List<String> ipv6AddressNames() {
        return List.of("X_CT-COM_IPv6Address", "X_CT-COM_IPv6IPAddress", "IPv6Address");
    }

    // LAN 网口状态叶子

    /** 电信定制机有私有的上行/线路状态叶子，插线状态优先看这些 */
    @Override
    public List<String> lanUpLineStatusLeaves() {
        return List.of(
                "X_CT-COM-UpLineStatus", "X_CT-COM_UpLineStatus",
                "X_CT-COM-LineStatus", "X_CT-COM_LineStatus");
    }

    @Override
    public List<String> lanStatusLeaves() {
        // 电信的 LAN 状态叶子 = 标准叶子 + 私有上行状态
        return List.of("Enable", "Status",
                "X_CT-COM-UpLineStatus", "X_CT-COM_upLineStatus",
                "X_CT-COM-LineStatus", "X_CT-COM_LineStatus",
                "MACAddress", "MaxBitRate", "DuplexMode");
    }

    // 能力采集

    /** 天翼那套私有分支，遍历进去必抛 9002：逐层展开到此为止 */
    @Override
    public List<String> skipPrefixes() {
        return List.of("X_CTC_");
    }

    /** 天翼业务子树：与移动一致 recursive-first 全量递归；之前 adaptive 降级 probePaths 太少会丢 WAN 信息 */
    @Override
    public List<String> capabilitySubtrees() {
        return List.of(
                "DeviceInfo.",
                "WANDevice.",
                "LANDevice.",
                "Services.VoiceService.",
                "X_CT-COM_UserInfo.");
    }

    /**
     * 天翼额外探测路径：设备身份 + 天翼业务标识 + WAN/LAN 完整叶子。
     * 电信定制机 GPN 对父对象常返回 9002，能力树扫不出的参数靠这里 GPV 兜底；
     * 每条写死实例号 1，多实例由 expandInstances 按设备自报条目数补采。
     */
    @Override
    public List<String> extraProbePaths() {
        return List.of(
                // 设备身份
                "DeviceInfo.ModelName",
                "DeviceInfo.OUI",
                "DeviceInfo.SerialNumber",
                "DeviceInfo.ProvisioningCode",
                "ManagementServer.ConnectionRequestURL",
                "WANDeviceNumberOfEntries",
                "WANDevice.1.WANCommonInterfaceConfig.WANAccessType",

                // WAN 连接条目数（供 expandInstances 补采多实例）
                "WANDevice.1.WANConnectionDeviceNumberOfEntries",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnectionNumberOfEntries",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnectionNumberOfEntries",

                // GPON 链路层：新固件把 VLAN 挂在这里且常不进 GPN 树，不登记会抹掉 Inform 带来的 VLAN
                "WANDevice.1.WANConnectionDevice.1.X_CT-COM_WANGponLinkConfig.VLANIDMark",
                "WANDevice.1.WANConnectionDevice.1.X_CT-COM_WANGponLinkConfig.VLANMode",
                "WANDevice.1.WANConnectionDevice.1.X_CT-COM_WANGponLinkConfig.IPMode",

                // WAN IP 连接完整叶子（实例 1）
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.Enable",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.Name",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.ConnectionType",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.ConnectionStatus",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.AddressingType",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.ExternalIPAddress",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.DefaultGateway",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.DNSServers",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.NATEnabled",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.MaxMTUSize",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.X_CT-COM_ServiceList",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.X_CT-COM_VLANMode",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.X_CT-COM_VLANID",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.X_CT-COM_IPMode",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.X_CT-COM_LanInterface",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.X_CT-COM_IPv6ConnStatus",
                "WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.X_CT-COM_IPv6Address",

                // WAN PPP 连接完整叶子（实例 1）
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.Enable",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.Name",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.ConnectionType",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.ConnectionStatus",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.AddressingType",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.Username",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.ExternalIPAddress",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.DefaultGateway",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.DNSServers",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.NATEnabled",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.MaxMTUSize",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.X_CT-COM_ServiceList",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.X_CT-COM_VLANMode",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.X_CT-COM_VLANID",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.X_CT-COM_IPMode",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.X_CT-COM_LanInterface",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.X_CT-COM_IPv6ConnStatus",
                "WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.X_CT-COM_IPv6Address",

                // LAN 网口完整叶子（实例 1）
                "LANDevice.1.LANEthernetInterfaceNumberOfEntries",
                "LANDevice.1.LANEthernetInterfaceConfig.1.Enable",
                "LANDevice.1.LANEthernetInterfaceConfig.1.Status",
                "LANDevice.1.LANEthernetInterfaceConfig.1.MACAddress",
                "LANDevice.1.LANEthernetInterfaceConfig.1.MaxBitRate",
                "LANDevice.1.LANEthernetInterfaceConfig.1.DuplexMode",
                "LANDevice.1.LANEthernetInterfaceConfig.1.X_CT-COM-UpLineStatus",
                "LANDevice.1.LANEthernetInterfaceConfig.1.X_CT-COM-LineStatus",

                // 天翼业务标识
                "X_CT-COM_UserInfo.UserId",
                "X_CT-COM_UserInfo.UserName");
    }
}
