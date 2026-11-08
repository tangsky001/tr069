package com.tr069.acs.acs.adaptation;

import com.tr069.acs.acs.model.Isp;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 中国移动（CMCC）适配：移动定制机的差异参数收在这里。
 * <p>
 * 移动定制固件使用 {@code X_CMCC_} 前缀的私有参数，
 * 这是当前已验证过的全套逻辑——所有业务代码通过 {@link IspAdaptation} 取参，
 * 不再硬编码 {@code X_CMCC_*} 常量。
 */
@Component
public class CmAdaptation implements IspAdaptation {

    @Override
    public Isp isp() {
        return Isp.CMCC;
    }

    // ==================== 设备超管账号 ====================

    @Override
    public List<String> adminAccountNodes() {
        return List.of("DeviceInfo.X_CMCC_TeleComAccount.");
    }

    // ==================== WAN 连接私有参数 ====================

    /** 移动定制机上的业务绑定参数，标准名兜底 */
    @Override
    public List<String> serviceListNames() {
        return List.of("X_CMCC_ServiceList", "ServiceList");
    }

    /** 移动定制机上的 VLAN 模式参数 */
    @Override
    public List<String> vlanModeNames() {
        return List.of("X_CMCC_VLANMode", "VLANMode");
    }

    /** 移动定制机上的 IP 模式参数 */
    @Override
    public List<String> ipModeNames() {
        return List.of("X_CMCC_IPMode", "IPMode");
    }

    // ==================== LAN 绑定端口 ====================

    /** 移动定制机上绑定端口节点真叫这个名字，取值为 LAN 口的完整路径串 */
    @Override
    public String lanInterfaceNode() {
        return "X_CMCC_LanInterface";
    }

    /** 读取设备当前绑定时优先试移动私有名 */
    @Override
    public List<String> bindPortNames() {
        return List.of("X_CMCC_LanInterface", "LanInterface", "BindLANPort");
    }

    // ==================== IPv6 状态参数 ====================

    @Override
    public List<String> ipv6StatusNames() {
        return List.of("X_CMCC_IPv6ConnStatus", "IPv6ConnectionStatus");
    }

    @Override
    public List<String> ipv6AddressNames() {
        return List.of("X_CMCC_IPv6Address", "X_CMCC_IPv6IPAddress", "IPv6Address");
    }
}
