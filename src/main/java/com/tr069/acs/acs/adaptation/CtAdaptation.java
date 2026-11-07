package com.tr069.acs.acs.adaptation;

import com.tr069.acs.acs.model.Isp;
import org.springframework.stereotype.Component;

import java.util.List;

/** 中国电信（CT）适配：电信定制机的差异参数收在这里 */
@Component
public class CtAdaptation implements IspAdaptation {

    @Override
    public Isp isp() {
        return Isp.CT;
    }

    @Override
    public List<String> adminAccountNodes() {
        return List.of("DeviceInfo.X_CT-COM_TeleComAccount.");
    }
}
