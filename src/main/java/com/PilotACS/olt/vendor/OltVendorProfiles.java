/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.237
 *
 */

package com.PilotACS.olt.vendor;

import com.PilotACS.olt.telnet.OltVendor;
import com.PilotACS.olt.vendor.Fiberhome.FiberhomeOltProfile;
import com.PilotACS.olt.vendor.Hsgq.HsgqGponOltProfile;
import com.PilotACS.olt.vendor.Hsgq.HsgqOltProfile;
import com.PilotACS.olt.vendor.hw.HuaweiOltProfile;
import com.PilotACS.olt.vendor.zte.ZteOltProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 厂商 profile 注册表 */
public final class OltVendorProfiles {

    /** 按识别优先级排列的品牌 */
    private static final List<OltVendorProfile> BRANDS =
            List.of(new HuaweiOltProfile(), new ZteOltProfile(), new FiberhomeOltProfile(),
                    new HsgqGponOltProfile(), new HsgqOltProfile());

    private static final OltVendorProfile GENERIC = new GenericOltProfile();

    private static final Map<OltVendor, OltVendorProfile> PROFILES = register();

    private OltVendorProfiles() {
    }

    private static Map<OltVendor, OltVendorProfile> register() {
        Map<OltVendor, OltVendorProfile> profiles = new LinkedHashMap<>();
        for (OltVendorProfile profile : BRANDS) {
            profiles.put(profile.vendor(), profile);
        }
        profiles.put(OltVendor.GENERIC, GENERIC);
        return profiles;
    }

    /** 按厂商取 profile，认不出给兜底 */
    public static OltVendorProfile of(OltVendor vendor) {
        if (vendor == null) {
            return GENERIC;
        }
        OltVendorProfile profile = PROFILES.get(vendor);
        return profile == null ? GENERIC : profile;
    }

    /** 按回显识别厂商 profile */
    public static OltVendorProfile detect(String... texts) {
        if (texts != null) {
            for (String text : texts) {
                if (text == null || text.isBlank()) {
                    continue;
                }
                for (OltVendorProfile profile : BRANDS) {
                    if (profile.matches(text)) {
                        return profile;
                    }
                }
            }
        }
        return GENERIC;
    }

    /** 已注册的品牌列表 */
    public static List<OltVendorProfile> brands() {
        return new ArrayList<>(BRANDS);
    }
}
