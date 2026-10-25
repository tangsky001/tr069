package com.tr069.acs.olt.telnet;

import com.tr069.acs.olt.model.OltGemport;
import com.tr069.acs.olt.model.OltLineProfile;
import com.tr069.acs.olt.model.OltServicePort;
import com.tr069.acs.olt.model.OltSrvProfile;
import com.tr069.acs.olt.model.OltTr069Profile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按现场 display current-configuration 的真实语法校验解析：
 * 配置脚本里的写法（gem add / gem mapping / ont-port pots adaptive / service-port ...
 * / ont tr069-server-profile add）都能认出来。
 */
class OltCurrentConfigParserTest {

    private static final String LINE_PROFILE = " ont-lineprofile gpon profile-id 100 profile-name \"FTTH-GPON-100\"\n"
            + "  tr069-management enable\n"
            + "  tcont 1 dba-profile-id 100\n"
            + "  gem add 0 eth tcont 1\n"
            + "  gem add 1 eth tcont 1\n"
            + "  gem add 2 eth tcont 1\n"
            + "  gem mapping 0 0 vlan 46\n"
            + "  gem mapping 1 0 vlan 41\n"
            + "  gem mapping 2 0 vlan 45\n"
            + "  gem mapping 2 1 vlan 4011\n"
            + "  commit\n"
            + "  quit\n";

    private static final String SRV_PROFILE = " ont-srvprofile gpon profile-id 100 profile-name \"100\"\n"
            + "  ont-port pots adaptive 32 eth adaptive 8 \n"
            + "  commit\n"
            + " ont-srvprofile gpon profile-id 101 profile-name \"SIP\"\n"
            + "  ont-port pots adaptive 16 eth adaptive 4\n";

    private static final String TR069 = " ont tr069-server-profile add profile-id 10 profile-name \"TR069_ACS\""
            + " url \"http://10.172.10.1:9090/\" user \"acs123\" \"%#%#k6zRVfQ;\"\n";

    @Test
    void parsesLineProfileWithGemMapping() {
        List<OltLineProfile> profiles = OltInfoParser.parseLineProfiles(LINE_PROFILE);
        assertEquals(1, profiles.size());
        OltLineProfile profile = profiles.get(0);
        assertEquals(100, profile.getProfileId());
        assertEquals("FTTH-GPON-100", profile.getName());
        assertEquals(1, profile.getTconts().size());
        assertEquals(100, profile.getTconts().get(0).getDbaProfileId());
        assertEquals(3, profile.getGemports().size());
        OltGemport second = profile.getGemports().get(2);
        assertEquals(2, second.getGemportId());
        // gem mapping 2 1 vlan 4011：GEM 映射ID 就是第二个数字
        assertEquals(1, second.getMappingId());
        assertEquals("4011", second.getVlan());
        assertEquals(1, second.getTcontId());
    }

    @Test
    void parsesSrvProfilePortCounts() {
        List<OltSrvProfile> profiles = OltInfoParser.parseSrvProfiles(SRV_PROFILE);
        assertEquals(2, profiles.size());
        OltSrvProfile first = profiles.get(0);
        assertEquals(100, first.getProfileId());
        assertEquals("100", first.getName());
        assertEquals(32, first.getPotsPorts());
        assertEquals(8, first.getEthPorts());
        assertEquals(16, profiles.get(1).getPotsPorts());
        assertEquals(4, profiles.get(1).getEthPorts());
    }

    @Test
    void parsesTr069ServerProfile() {
        List<OltTr069Profile> profiles = OltInfoParser.parseTr069Profiles(TR069);
        assertEquals(1, profiles.size());
        OltTr069Profile profile = profiles.get(0);
        assertEquals(10, profile.getProfileId());
        assertEquals("TR069_ACS", profile.getName());
        assertEquals("http://10.172.10.1:9090/", profile.getUrl());
        assertEquals("acs123", profile.getUser());
    }

    /** display ont-lineprofile gpon profile-id 100 的真实明细回显（块状 + 映射表格） */
    private static final String LINE_DETAIL = "Profile-ID        :100\n"
            + "Profile-name      :FTTH-GPON-100\n"
            + "Access-type       :gpon\n"
            + "Mapping mode      :VLAN\n"
            + "TR069 management  :Enable\n"
            + "TR069 IP index    :0\n"
            + "------------------------------------------------------------------------------\n"
            + "<T-CONT  0>       DBA Profile-ID:1\n"
            + "<T-CONT  1>       DBA Profile-ID:100\n"
            + "<Gem Index:0>\n"
            + "Upstream priority-queue:0    Downstream priority-queue:-\n"
            + "Mapping VLAN Priority Port type Port ID Bundle ID Flow CAR Transparent\n"
            + "0       45   -       -         -       -        -    -\n"
            + "<Gem Index:1>\n"
            + "Mapping VLAN Priority Port type Port ID Bundle ID Flow CAR Transparent\n"
            + "0       41   -       -         -       -        -    -\n"
            + "<Gem Index:2>\n"
            + "Mapping VLAN Priority Port type Port ID Bundle ID Flow CAR Transparent\n"
            + "0       45   -       -         -       -        -    -\n"
            + "1       4011 -       -         -       -        -    -\n"
            + "Notes: Run the display traffic table ip command to query\n";

    @Test
    void parsesLineProfileDetailBlocks() {
        List<OltLineProfile> profiles = OltInfoParser.parseLineProfiles(LINE_DETAIL);
        assertEquals(1, profiles.size());
        OltLineProfile profile = profiles.get(0);
        assertEquals(100, profile.getProfileId());
        assertEquals("FTTH-GPON-100", profile.getName());
        assertEquals(2, profile.getTconts().size());
        assertEquals(100, profile.getTconts().get(1).getDbaProfileId());
        assertEquals(3, profile.getGemports().size());
        // GEM 0：单条映射 0 -> VLAN 45
        OltGemport gem0 = profile.getGemports().get(0);
        assertEquals(0, gem0.getGemportId());
        assertEquals(1, gem0.getMappings().size());
        assertEquals(0, gem0.getMappings().get(0).getIndex());
        assertEquals("45", gem0.getMappings().get(0).getVlan());
        // GEM 2：两条映射 0 -> 45、1 -> 4011
        OltGemport gem2 = profile.getGemports().get(2);
        assertEquals(2, gem2.getMappings().size());
        assertEquals("4011", gem2.getMappings().get(1).getVlan());
        // 单值列回填第一条
        assertEquals("45", gem2.getVlan());
        assertEquals(0, gem2.getMappingId());
    }

    /** 现场复现：display ont tr069-server-profile all 弹的无 <cr> 参数提示，要能识别并补回车 */
    @Test
    void tr069StyleParamPromptShouldNeedEnter() {
        String prompt = "display ont tr069-server-profile { all<K>|bound-info<K>|profile-id<K>|profile-name<K> }:";
        assertTrue(OltCliSession.PARAM_PROMPT.matcher(prompt).find(),
                "无 <cr> 的参数补全提示也要补回车");
        String crPrompt = " { <cr>|backplane<K>|frameid/slotid<S><Length 1-15> }:";
        assertTrue(OltCliSession.PARAM_PROMPT.matcher(crPrompt).find());
        // 正文里的普通内容不能被当成参数提示
        String normal = "ont-lineprofile gpon profile-id 100 profile-name \"FTTH-GPON-100\"";
        assertFalse(OltCliSession.PARAM_PROMPT.matcher(normal).find());
    }

    /** GEM 块头冒号后带空格（&lt;Gem Index: 0&gt;）、priority 列有值时也要能解析 */
    @Test
    void parsesLineProfileDetailSpacingVariants() {
        String text = "Profile-ID        :100\n"
                + "Profile-name      :FTTH-GPON-100\n"
                + "<T-CONT  1>       DBA Profile-ID:100\n"
                + "<Gem Index: 0>\n"
                + "Mapping VLAN Priority Port type Port ID Bundle ID Flow CAR Transparent\n"
                + "0       46   0       -         -       -        -    -\n"
                + "<Gem Index: 1>\n"
                + "Mapping VLAN Priority Port type Port ID Bundle ID Flow CAR Transparent\n"
                + "0       41   -       -         -       -        -    -\n";
        List<OltLineProfile> profiles = OltInfoParser.parseLineProfiles(text);
        assertEquals(1, profiles.size());
        OltLineProfile profile = profiles.get(0);
        assertEquals(2, profile.getGemports().size());
        assertEquals("46", profile.getGemports().get(0).getMappings().get(0).getVlan());
        assertEquals("41", profile.getGemports().get(1).getMappings().get(0).getVlan());
    }

    @Test
    void parsesServicePortFromConfig() {
        String text = " service-port 0 vlan 41 gpon 0/1/1 ont 21 gemport 1 multi-service user-vlan 41 "
                + "tag-transform translate\n"
                // 回显被终端折断的行
                + " service-port 4 vlan 41 gpon 0/1/2 ont 8 gemport 1 multi-service user-vlan 41 \n"
                + "tag-transform translate\n";
        List<OltServicePort> ports = OltInfoParser.parseServicePortsFromConfig(text);
        assertEquals(2, ports.size());
        OltServicePort first = ports.get(0);
        assertEquals(0, first.getIndex());
        assertEquals(41, first.getVlanId());
        assertEquals("0/1/1", first.iface());
        assertEquals(21, first.getOntId());
        assertEquals(1, first.getGemport());
        // 折断那一行的主要字段都在前半段，照样解析得出来
        assertNotNull(ports.get(1));
        assertEquals(8, ports.get(1).getOntId());
        assertTrue(OltInfoParser.parseServicePortsFromConfig("no service-port here").isEmpty());
    }
}
