package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.telnet.OltVendor;

import java.util.List;
import java.util.regex.Pattern;

/** 中兴 ZXA10 C300 / C320 / C350：show 那一套，提示符 ZXAN# */
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
