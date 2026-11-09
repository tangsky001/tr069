package com.tr069.acs.acs.adaptation;

import com.tr069.acs.acs.model.Isp;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 中国电信（CT）适配：电信天翼网关的差异参数收在这里。
 * <p>
 * 电信定制固件使用 {@code X_CT-COM_} / {@code X_CTC_} 两套私有前缀。
 * 天翼网关的能力树遍历策略与移动一致（递归优先），
 * 且 LAN 网口有私有的插线状态叶子。
 */
@Component
public class CtAdaptation implements IspAdaptation {

    @Override
    public Isp isp() {
        return Isp.CT;
    }

    // ==================== 设备超管账号 ====================

    @Override
    public List<String> adminAccountNodes() {
        return List.of("DeviceInfo.X_CT-COM_TeleComAccount.");
    }

    // ==================== WAN 连接私有参数 ====================

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

    // ==================== LAN 网口状态叶子 ====================

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

    // ==================== 能力采集 ====================

    /** 天翼那套私有分支，遍历进去必抛 9002：逐层展开到此为止 */
    @Override
    public List<String> skipPrefixes() {
        return List.of("X_CTC_");
    }

    /**
     * 天翼网关的业务子树：与移动一致，先全量递归扫参数树。
     * <p>
     * 之前用 adaptive 策略——GPN 撞 9002 就降级为只读 probePaths，
     * 但 probePaths 里 WAN 路径太少，导致光猫拿不到完整 WAN 信息。
     * 现在改为默认 recursive-first，第一次就全量递归，和移动一个流程。
     * X_CT-COM_UserInfo 是电信私有的业务标识子树，一并扫进来。
     */
    @Override
    public List<String> capabilitySubtrees() {
        return List.of(
                "DeviceInfo.",
                "WANDevice.",
                "LANDevice.",
                "Services.VoiceService.",
                "X_CT-COM_UserInfo.");
    }

    /** 天翼网关的额外探测路径：设备身份 + 天翼业务标识 */
    @Override
    public List<String> extraProbePaths() {
        return List.of(
                "DeviceInfo.ModelName",
                "DeviceInfo.OUI",
                "DeviceInfo.SerialNumber",
                "DeviceInfo.ProvisioningCode",
                "ManagementServer.ConnectionRequestURL",
                "WANDeviceNumberOfEntries",
                "WANDevice.1.WANCommonInterfaceConfig.WANAccessType",
                "X_CT-COM_UserInfo.UserId",
                "X_CT-COM_UserInfo.UserName");
    }
}
