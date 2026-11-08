package com.tr069.acs.acs.adaptation;

import com.tr069.acs.acs.model.Isp;
import org.springframework.stereotype.Component;

/** 中国广电（CBN）适配：广电定制机的差异参数收在这里 */
@Component
public class CbnAdaptation implements IspAdaptation {

    @Override
    public Isp isp() {
        return Isp.CBN;
    }
}
