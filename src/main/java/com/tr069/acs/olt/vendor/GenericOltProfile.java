package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.telnet.OltVendor;

import java.util.List;

/**
 * 认不出厂商时的兜底 profile：两套命令都试。
 *
 * <p>型号识别也走这里——按已注册品牌的顺序挨家试一遍，新品牌注册进来自动生效，
 * 不用在这里补它们家的正则。</p>
 */
public class GenericOltProfile extends AbstractOltVendorProfile {

    @Override
    public OltVendor vendor() {
        return OltVendor.GENERIC;
    }

    @Override
    public OltVendor.CliStyle cliStyle() {
        // 认不出厂商就不认领任何风格，用探测时那条版本命令的风格
        return null;
    }

    @Override
    public boolean matches(String output) {
        return false;
    }

    @Override
    public List<String> pagingCommands() {
        return List.of("terminal length 0");
    }

    @Override
    public List<String> versionCommands() {
        return List.of("display version", "show version");
    }

    @Override
    public List<String> boardCommands() {
        return List.of("display board 0", "show card", "show slot");
    }

    @Override
    public String extractModel(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        // 挨家问一遍：谁认得这个型号就用谁的
        for (OltVendorProfile profile : OltVendorProfiles.brands()) {
            String model = profile.extractModel(text);
            if (model != null) {
                return model;
            }
        }
        return super.extractModel(text);
    }
}
