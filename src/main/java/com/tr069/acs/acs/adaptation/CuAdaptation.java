package com.tr069.acs.acs.adaptation;

import com.tr069.acs.acs.model.Isp;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 中国联通（CU）适配：联通定制机的差异参数收在这里。
 * <p>
 * 联通定制固件使用 {@code X_CU_} 前缀的私有参数。
 * 联通的 WAN 连接参数命名规律与移动类似，但前缀不同。
 */
@Component
public class CuAdaptation implements IspAdaptation {

    @Override
    public Isp isp() {
        return Isp.CU;
    }

    // ==================== 设备超管账号 ====================

    @Override
    public List<String> adminAccountNodes() {
        return List.of("DeviceInfo.X_CU_TeleComAccount.");
    }

    // ==================== WAN 连接私有参数 ====================

    /** 联通定制机上的业务绑定参数 */
    @Override
    public List<String> serviceListNames() {
        return List.of("X_CU_ServiceList", "ServiceList");
    }

    /** 联通定制机上的 VLAN 模式参数 */
    @Override
    public List<String> vlanModeNames() {
        return List.of("X_CU_VLANMode", "VLANMode");
    }

    /** 联通定制机上的 IP 模式参数 */
    @Override
    public List<String> ipModeNames() {
        return List.of("X_CU_IPMode", "IPMode");
    }

    // ==================== LAN 绑定端口 ====================

    /** 联通定制机上的绑定端口参数名 */
    @Override
    public List<String> bindPortNames() {
        return List.of("X_CU_LanInterface", "LanInterface", "BindLANPort");
    }
}
