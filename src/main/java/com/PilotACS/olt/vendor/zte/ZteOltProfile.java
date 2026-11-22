/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.279
 *
 */

package com.PilotACS.olt.vendor.zte;

import com.PilotACS.olt.telnet.OltVendor;
import com.PilotACS.olt.vendor.AbstractOltVendorProfile;

import java.util.List;
import java.util.regex.Pattern;

/** 中兴 ZXA10 系列厂商适配 */
public class ZteOltProfile extends AbstractOltVendorProfile {

    private static final Pattern MODEL = Pattern.compile(
            "(?i)\\b(ZXA10\\s*[A-Z]\\d{3,4}[A-Z]?|ZXA10\\s*C\\d{3})\\b");

    @Override
    public OltVendor vendor() {
        return OltVendor.ZTE;
    }

    @Override
    public boolean matches(String output) {
        return containsAny(output, "zte", "zxan", "zxr10", "c320", "c300", "c350");
    }

    @Override
    protected List<Pattern> modelPatterns() {
        return List.of(MODEL, MODEL_LABEL);
    }
}
