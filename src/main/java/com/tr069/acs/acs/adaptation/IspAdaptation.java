package com.tr069.acs.acs.adaptation;

import com.tr069.acs.acs.model.Isp;

import java.util.List;

/**
 * 运营商适配策略：每家运营商一个实现，各自的定制参数差异收在自己的类里。
 * <p>
 * 新增运营商只需加一个实现类——所有差异点都在这里声明，
 * 调用方通过 {@link AdaptationManager#of(String)} 拿到对应实现，
 * 不再把 {@code X_CMCC_} / {@code X_CT-COM_} 之类硬编码在业务代码里。
 */
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

    // ==================== WAN 连接私有参数 ====================

    /**
     * WAN 连接的「业务绑定」参数名候选列表，按优先级排序。
     * 调用方逐个去能力树上试，谁在树上用谁。
     * <p>
     * 移动: {@code X_CMCC_ServiceList} → {@code ServiceList}
     * 电信: {@code X_CT-COM_ServiceList} → {@code ServiceList}
     * 联通: {@code X_CU_ServiceList} → {@code ServiceList}
     */
    default List<String> serviceListNames() {
        return List.of("ServiceList");
    }

    /**
     * WAN 连接的「VLAN 模式」参数名候选列表，按优先级排序。
     * 移动: {@code X_CMCC_VLANMode} → {@code VLANMode}
     * 电信: {@code X_CT-COM_VLANMode} → {@code VLANMode}
     */
    default List<String> vlanModeNames() {
        return List.of("VLANMode");
    }

    /**
     * WAN 连接的「IP 模式」参数名候选列表，按优先级排序。
     * 移动: {@code X_CMCC_IPMode} → {@code IPMode}
     */
    default List<String> ipModeNames() {
        return List.of("IPMode");
    }

    // ==================== LAN 绑定端口 ====================

    /**
     * 绑定端口的参数节点名。移动定制机上叫 {@code X_CMCC_LanInterface}，
     * 取值为 LAN 口的完整路径串。标准值为 {@code LanInterface} / {@code BindLANPort}。
     */
    default String lanInterfaceNode() {
        return "LanInterface";
    }

    /**
     * 绑定端口参数名的候选列表，按优先级排序。
     * 读取设备当前绑定时逐个试，谁有值用谁。
     */
    default List<String> bindPortNames() {
        return List.of("LanInterface", "BindLANPort");
    }

    // ==================== IPv6 状态参数 ====================

    /**
     * IPv6 连接状态参数名候选列表，按优先级排序。
     * 移动: {@code X_CMCC_IPv6ConnStatus} → {@code IPv6ConnectionStatus}
     */
    default List<String> ipv6StatusNames() {
        return List.of("IPv6ConnectionStatus");
    }

    /**
     * IPv6 地址参数名候选列表，按优先级排序。
     * 移动: {@code X_CMCC_IPv6Address} / {@code X_CMCC_IPv6IPAddress} → {@code IPv6Address}
     */
    default List<String> ipv6AddressNames() {
        return List.of("IPv6Address");
    }

    // ==================== LAN 网口状态叶子 ====================

    /**
     * LAN 网口要重读的状态叶子参数名（含厂商扩展的插线状态）。
     * 标准值为 {@code Enable} / {@code Status}，各运营商可在前面加私有扩展。
     */
    default List<String> lanStatusLeaves() {
        return List.of("Enable", "Status", "MACAddress", "MaxBitRate", "DuplexMode");
    }

    /**
     * LAN 网口的上行状态参数路径后缀（相对 LAN 口实例）。
     * 电信定制机有 {@code X_CT-COM_UpLineStatus} / {@code X_CT-COM_LineStatus}，
     * 其他运营商一般没有，返回空。
     */
    default List<String> lanUpLineStatusLeaves() {
        return List.of();
    }

    // ==================== 能力采集 ====================

    /**
     * 能力采集时要跳过的私有参数前缀（遍历进去必抛 9002 的）。
     * 电信: {@code X_CTC_}
     */
    default List<String> skipPrefixes() {
        return List.of();
    }

    /**
     * 能力采集的额外探测路径，拼到通用探测路径前面。
     * 电信天翼网关需要 {@code X_CT-COM_UserInfo.UserId} 等业务标识。
     */
    default List<String> extraProbePaths() {
        return List.of();
    }

    /**
     * 能力采集的业务子树（相对 TR-098 根节点）。
     * 电信天翼网关需要 {@code X_CT-COM_UserInfo.}
     */
    default List<String> capabilitySubtrees() {
        return List.of();
    }

    /**
     * GetParameterNames 策略。默认递归优先；
     * 电信天翼网关用自适应（先试参数树，撞 9002 再降级）。
     * 取值见 {@code CapabilityProfiles.Profile.STRATEGY_*}
     */
    default String gpnStrategy() {
        return "recursive-first";
    }
}
