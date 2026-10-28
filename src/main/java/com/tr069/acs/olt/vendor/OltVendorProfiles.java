package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.telnet.OltVendor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 厂商 profile 注册表：会话要「这家设备该怎么问」就来这里取。
 *
 * <p><b>新增一个品牌的完整步骤：</b></p>
 * <ol>
 *   <li>写一个类继承 {@link AbstractOltVendorProfile}（照 {@code HsgqOltProfile} 抄），
 *       覆写 {@code vendor()} / {@code matches()} 和与众不同的那几条命令；</li>
 *   <li>在 {@link OltVendor} 枚举里加一个取值（页面下拉和库里存的是这个 code）；</li>
 *   <li>在下面 {@link #register()} 里加一行。</li>
 * </ol>
 * 会话主流程、 {@code OltInfoParser} 都不用动。
 */
public final class OltVendorProfiles {

    /** 识别优先级：排前面的先认，跟 {@code OltVendor.detect} 原先的顺序一致 */
    private static final List<OltVendorProfile> BRANDS =
            List.of(new HuaweiOltProfile(), new ZteOltProfile(), new FiberhomeOltProfile(),
                    // GPON 要排在 EPON 前面：同一家的回显里若同时出现 gpon 与 hsgq，先认 GPON
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

    /** 按厂商取 profile，认不出/null 一律给兜底那份 */
    public static OltVendorProfile of(OltVendor vendor) {
        if (vendor == null) {
            return GENERIC;
        }
        OltVendorProfile profile = PROFILES.get(vendor);
        return profile == null ? GENERIC : profile;
    }

    /**
     * 按回显认厂商：依次给到每份 profile 的 {@link OltVendorProfile#matches}，
     * 没人认得就给兜底那份。文本可以是版本回显、板卡回显、登录横幅或提示符。
     */
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

    /** 已注册的具体品牌（不含兜底那份），按识别优先级排 */
    public static List<OltVendorProfile> brands() {
        return new ArrayList<>(BRANDS);
    }
}
