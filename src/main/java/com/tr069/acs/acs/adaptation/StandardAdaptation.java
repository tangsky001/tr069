package com.tr069.acs.acs.adaptation;

import com.tr069.acs.acs.model.Isp;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通用档：未识别出运营商的设备走这里。
 * <p>
 * 全部使用 TR-069 标准参数名，不带任何厂商私有前缀。
 * 能力采集走递归优先策略，不跳过任何前缀。
 */
@Component
public class StandardAdaptation implements IspAdaptation {

    @Override
    public Isp isp() {
        return Isp.STANDARD;
    }

    // ==================== Telnet 使能参数 ====================

    @Override
    public List<String> telnetEnableNodes() {
        return List.of("UserInterface.TelnetServer.", "DeviceInfo.X_HW_Telnet.");
    }
}
