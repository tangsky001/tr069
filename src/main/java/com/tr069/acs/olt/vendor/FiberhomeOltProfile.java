package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.telnet.OltVendor;

import java.util.List;
import java.util.regex.Pattern;

/** 烽火 AN5516 / AN6000：show 那一套 */
public class FiberhomeOltProfile extends AbstractOltVendorProfile {

    private static final Pattern MODEL = Pattern.compile(
            "(?i)\\b(AN5\\d{3}-\\d{2}|AN6000-\\d+|AN5516-\\d{2})\\b");

    @Override
    public OltVendor vendor() {
        return OltVendor.FIBERHOME;
    }

    @Override
    public boolean matches(String output) {
        return containsAny(output, "fiberhome", "烽火", "an5516", "an6000");
    }

    @Override
    protected List<Pattern> modelPatterns() {
        return List.of(MODEL, MODEL_LABEL);
    }
}
