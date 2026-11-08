package com.tr069.acs.acs.adaptation;

import com.tr069.acs.acs.model.Isp;
import org.springframework.stereotype.Component;

/**
 * 中国广电（CBN）适配：广电定制机的差异参数收在这里。
 * <p>
 * 广电网络 2019 年才起步，定制固件使用 {@code X_CBN_} 前缀的私有参数。
 * 目前还没有已验证的定制节点和私有参数名，全部走接口默认值（标准名兜底）。
 * 后续拿到广电真机适配后，在此补充。
 */
@Component
public class CbnAdaptation implements IspAdaptation {

    @Override
    public Isp isp() {
        return Isp.CBN;
    }
}
