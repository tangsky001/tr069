package com.tr069.acs.acs.adaptation;

import com.tr069.acs.acs.model.Isp;
import org.springframework.stereotype.Component;

import java.util.List;

/** 中国联通（CU）适配：联通定制机的差异参数收在这里 */
@Component
public class CuAdaptation implements IspAdaptation {

    @Override
    public Isp isp() {
        return Isp.CU;
    }

    @Override
    public List<String> adminAccountNodes() {
        return List.of("DeviceInfo.X_CU_TeleComAccount.");
    }
}
