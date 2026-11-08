package com.tr069.acs.acs.adaptation;

import com.tr069.acs.acs.model.Isp;
import org.springframework.stereotype.Component;

import java.util.List;

/** 中国移动（CM）适配：移动定制机的差异参数收在这里 */
@Component
public class CmAdaptation implements IspAdaptation {

    @Override
    public Isp isp() {
        return Isp.CMCC;
    }

    @Override
    public List<String> adminAccountNodes() {
        return List.of("DeviceInfo.X_CMCC_TeleComAccount.");
    }
}
