package com.tr069.acs.acs.adaptation;

import com.tr069.acs.acs.model.Isp;
import org.springframework.stereotype.Component;

/** 通用档：未识别出运营商的设备走这里 */
@Component
public class StandardAdaptation implements IspAdaptation {

    @Override
    public Isp isp() {
        return Isp.STANDARD;
    }
}
