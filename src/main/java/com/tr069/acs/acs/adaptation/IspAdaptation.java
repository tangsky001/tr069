package com.tr069.acs.acs.adaptation;

import com.tr069.acs.acs.model.Isp;

import java.util.List;

/** 运营商适配策略：每家运营商一个实现，新增运营商只需加实现类，私有前缀不再硬编码在业务代码里 */
public interface IspAdaptation {

    Isp isp();

    default String code() {
        return isp().getCode();
    }

    default String label() {
        return isp().getLabel();
    }

    /** 型号档案目录名：CM / CT / CU / CBN / standard */
    default String dir() {
        return isp().getDir();
    }

    /** 简短名，用于下拉选项：移动 / 电信 / 联通 / 广电 / 通用 */
    default String shortLabel() {
        return isp().getLabel();
    }

    /** CWMP 私有参数前缀，用于从能力树识别运营商 */
    default List<String> prefixes() {
        Isp isp = isp();
        if (isp.getPrefix().isEmpty()) {
            return List.of();
        }
        return isp.getAltPrefix().isEmpty()
                ? List.of(isp.getPrefix())
                : List.of(isp.getPrefix(), isp.getAltPrefix());
    }

    // ==================== 设备超管账号 ====================

    /** 定制固件的超管账号节点，相对根节点带尾点；没有定制节点返回空 */
    default List<String> adminAccountNodes() {
        return List.of();
    }

    // ==================== Telnet 使能参数 ====================

    /**
     * Telnet 使能参数所在的节点（相对根节点，带尾点）。
     * 业务代码会在这个节点下拼接 {@code TelnetEnable} 叶子路径进行读写。
     * 没有定制节点时返回空，由业务代码走通用标准路径兜底。
     */
    default List<String> telnetEnableNodes() {
        return List.of();
    }

    // ==================== WAN 连接私有参数 ====================

    /** WAN「业务绑定」参数名候选，按优先级逐个试；移动 X_CMCC_ServiceList、电信 X_CT-COM_ServiceList */
    default List<String> serviceListNames() {
        return List.of("ServiceList");
    }

    /** WAN「VLAN 模式」参数名候选，按优先级试；移动 X_CMCC_VLANMode、电信 X_CT-COM_VLANMode */
    default List<String> vlanModeNames() {
        return List.of("VLANMode");
    }

    /** WAN「IP 模式」参数名候选；移动 X_CMCC_IPMode */
    default List<String> ipModeNames() {
        return List.of("IPMode");
    }

    // ==================== LAN 绑定端口 ====================

    /** 绑定端口参数节点名：移动定制机叫 X_CMCC_LanInterface，标准值为 LanInterface / BindLANPort */
    default String lanInterfaceNode() {
        return "LanInterface";
    }

    /** 绑定端口参数名候选，读取当前绑定时按优先级逐个试 */
    default List<String> bindPortNames() {
        return List.of("LanInterface", "BindLANPort");
    }

    // ==================== IPv6 状态参数 ====================

    /** IPv6 连接状态参数名候选；移动 X_CMCC_IPv6ConnStatus → IPv6ConnectionStatus */
    default List<String> ipv6StatusNames() {
        return List.of("IPv6ConnectionStatus");
    }

    /** IPv6 地址参数名候选；移动 X_CMCC_IPv6Address / X_CMCC_IPv6IPAddress → IPv6Address */
    default List<String> ipv6AddressNames() {
        return List.of("IPv6Address");
    }

    // ==================== LAN 网口状态叶子 ====================

    /** LAN 网口要重读的状态叶子参数名（含厂商扩展的插线状态），标准值 Enable / Status */
    default List<String> lanStatusLeaves() {
        return List.of("Enable", "Status", "MACAddress", "MaxBitRate", "DuplexMode");
    }

    /** LAN 网口上行状态参数路径后缀：电信定制机有 X_CT-COM_UpLineStatus，其余返回空 */
    default List<String> lanUpLineStatusLeaves() {
        return List.of();
    }

    // ==================== 能力采集 ====================

    /** 能力采集时要跳过的私有参数前缀（遍历进去必抛 9002）：电信 X_CTC_ */
    default List<String> skipPrefixes() {
        return List.of();
    }

    /** 能力采集的额外探测路径：电信天翼网关需要 X_CT-COM_UserInfo.UserId 等业务标识 */
    default List<String> extraProbePaths() {
        return List.of();
    }

    /** 能力采集的业务子树（相对 TR-098 根节点）：电信天翼网关需要 X_CT-COM_UserInfo. */
    default List<String> capabilitySubtrees() {
        return List.of();
    }

    /** GetParameterNames 策略：默认递归优先；电信天翼网关用自适应（先试参数树，撞 9002 再降级） */
    default String gpnStrategy() {
        return "recursive-first";
    }
}
