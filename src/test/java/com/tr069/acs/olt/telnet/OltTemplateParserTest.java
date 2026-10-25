package com.tr069.acs.olt.telnet;

import com.tr069.acs.olt.model.OltDbaProfile;
import com.tr069.acs.olt.model.OltLineProfile;
import com.tr069.acs.olt.model.OltSrvProfile;
import com.tr069.acs.olt.model.OltTr069Profile;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DBA 模板 / 线路模板解析：{@code display dba-profile all} 与
 * {@code display ont-lineprofile gpon all} 两种回显写法都要认。
 */
class OltTemplateParserTest {

    @Test
    void parsesDbaProfilesTabular() {
        String text = "MA5683T(config)#display dba-profile all\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  Profile-id   Profile-name   Type     Bandwidth(kbps)\r\n"
                + "                                       Fix    Assure    Max\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  1            DBA-1          type3    1024   2048      102400\r\n"
                + "  2            DBA-2          type5    0      0         102400\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  Total: 2";

        List<OltDbaProfile> profiles = OltInfoParser.parseDbaProfiles(text);
        assertEquals(2, profiles.size());
        assertEquals(1, profiles.get(0).getProfileId());
        assertEquals("DBA-1", profiles.get(0).getName());
        assertEquals("type3", profiles.get(0).getType());
        assertEquals(1024, profiles.get(0).getFixKbps());
        assertEquals(2048, profiles.get(0).getAssureKbps());
        assertEquals(102400, profiles.get(0).getMaxKbps());
        assertEquals(2, profiles.get(1).getProfileId());
        assertEquals(0, profiles.get(1).getFixKbps());
    }

    @Test
    void parsesDbaProfilesInline() {
        String text = "  Profile-id  Profile-name  Type  Bandwidth\r\n"
                + "  1           dba-1         type4  fix 0 assure 0 max 102400\r\n";

        List<OltDbaProfile> profiles = OltInfoParser.parseDbaProfiles(text);
        assertEquals(1, profiles.size());
        assertEquals("type4", profiles.get(0).getType());
        assertEquals(0, profiles.get(0).getAssureKbps());
        assertEquals(102400, profiles.get(0).getMaxKbps());
    }

    @Test
    void parsesLineProfilesWithTcontAndGem() {
        String text = "MA5683T(config)#display ont-lineprofile gpon all\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  Profile-ID  Profile-name  Profile-type  FEC  OMCI\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  1           line-1        ONT           dFEC  OMCI\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  Number of T-CONT: 2   Number of GEM port: 2\r\n"
                + "  T-CONT  1  DBA-profile  1\r\n"
                + "  T-CONT  2  DBA-profile  2\r\n"
                + "  GEM port 1  T-CONT 1  Mapping mode VLAN  41\r\n"
                + "  GEM port 2  T-CONT 2  Mapping mode VLAN  45\r\n"
                + "  2           line-2        ONT           dFEC  OMCI\r\n"
                + "  Number of T-CONT: 1   Number of GEM port: 1\r\n"
                + "  T-CONT  1  DBA-profile  1\r\n"
                + "  GEM port 1  T-CONT 1  Mapping mode VLAN  46\r\n";

        List<OltLineProfile> profiles = OltInfoParser.parseLineProfiles(text);
        assertEquals(2, profiles.size());

        OltLineProfile first = profiles.get(0);
        assertEquals(1, first.getProfileId());
        assertEquals("line-1", first.getName());
        assertEquals(2, first.getTcontCount());
        assertEquals(2, first.getGemportCount());
        assertEquals(2, first.getTconts().size());
        assertEquals(1, first.getTconts().get(0).getTcontId());
        // T-CONT → DBA 模板：这条线路模板的两档带宽就顺着这里对上
        assertEquals(1, first.getTconts().get(0).getDbaProfileId());
        assertEquals(2, first.getTconts().get(1).getDbaProfileId());
        assertEquals(2, first.getGemports().size());
        assertEquals(1, first.getGemports().get(0).getGemportId());
        assertEquals(1, first.getGemports().get(0).getTcontId());
        assertEquals("VLAN", first.getGemports().get(0).getMappingMode());
        assertEquals("41", first.getGemports().get(0).getVlan());

        // 第二条模板的 T-CONT / GEM port 不能串到第一条上
        OltLineProfile second = profiles.get(1);
        assertEquals(2, second.getProfileId());
        assertEquals(1, second.getTconts().size());
        assertEquals(1, second.getGemports().size());
        assertEquals("46", second.getGemports().get(0).getVlan());
    }

    @Test
    void parsesNothingWhenNoEcho() {
        assertTrue(OltInfoParser.parseDbaProfiles(null).isEmpty());
        assertTrue(OltInfoParser.parseLineProfiles("").isEmpty());
        assertTrue(OltInfoParser.parseDbaProfiles("% Unknown command").isEmpty());
    }

    /** 配置脚本兜底：明细回显认不出 GEM 映射时，current-configuration 的脚本行要能解析 */
    @Test
    void parsesLineProfilesFromConfigScript() {
        String text = " ont-lineprofile gpon profile-id 100 profile-name \"FTTH-GPON-100\"\r\n"
                + "  tr069-management enable\r\n"
                + "  tcont 1 dba-profile-id 100\r\n"
                + "  gem add 0 eth tcont 1\r\n"
                + "  gem add 2 eth tcont 1\r\n"
                + "  gem mapping 0 0 vlan 46\r\n"
                + "  gem mapping 2 1 vlan 4011\r\n"
                + "  commit\r\n"
                + "  quit\r\n";

        List<OltLineProfile> profiles = OltInfoParser.parseLineProfiles(text);
        assertEquals(1, profiles.size());
        OltLineProfile profile = profiles.get(0);
        assertEquals(100, profile.getProfileId());
        assertEquals("FTTH-GPON-100", profile.getName());
        assertEquals(1, profile.getTconts().size());
        assertEquals(100, profile.getTconts().get(0).getDbaProfileId());
        assertEquals(2, profile.getGemports().size());
        assertEquals(0, profile.getGemports().get(0).getGemportId());
        assertEquals(1, profile.getGemports().get(0).getTcontId());
        assertEquals(0, profile.getGemports().get(0).getMappingId());
        assertEquals("46", profile.getGemports().get(0).getVlan());
        assertEquals(2, profile.getGemports().get(1).getGemportId());
        assertEquals(1, profile.getGemports().get(1).getMappingId());
        assertEquals("4011", profile.getGemports().get(1).getVlan());
    }

    /** display ont-srvprofile gpon all 的概要表行（ID / 名字 / 绑定次数） */
    @Test
    void parsesSrvProfileSummaryRows() {
        String text = "MA5683T(config)#display ont-srvprofile gpon all\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  Profile-ID  Profile-name    Binding times\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  100         100             0\r\n";

        List<OltSrvProfile> profiles = OltInfoParser.parseSrvProfiles(text);
        assertEquals(1, profiles.size());
        assertEquals(100, profiles.get(0).getProfileId());
        assertEquals("100", profiles.get(0).getName());
    }

    /** 配置脚本里的能力模板：ont-srvprofile 行 + ont-port 行给出端口上限 */
    @Test
    void parsesSrvProfilesFromConfigScript() {
        String text = " ont-srvprofile gpon profile-id 100 profile-name \"100\"\r\n"
                + "  ont-port pots adaptive 32 eth adaptive 8\r\n"
                + "  commit\r\n";

        List<OltSrvProfile> profiles = OltInfoParser.parseSrvProfiles(text);
        assertEquals(1, profiles.size());
        assertEquals(100, profiles.get(0).getProfileId());
        assertEquals("100", profiles.get(0).getName());
        assertEquals(32, profiles.get(0).getPotsPorts());
        assertEquals(8, profiles.get(0).getEthPorts());
    }

    /** 配置脚本里的 TR069 服务器模板行 */
    @Test
    void parsesTr069ProfileFromConfigScript() {
        String text = " ont tr069-server-profile add profile-id 10 profile-name \"TR069_ACS\" "
                + "url \"http://10.172.10.1:9090/\" user \"acs123\" \"%#%#cipher%#%\"\r\n";

        List<OltTr069Profile> profiles = OltInfoParser.parseTr069Profiles(text);
        assertEquals(1, profiles.size());
        assertEquals(10, profiles.get(0).getProfileId());
        assertEquals("TR069_ACS", profiles.get(0).getName());
        assertEquals("http://10.172.10.1:9090/", profiles.get(0).getUrl());
        assertEquals("acs123", profiles.get(0).getUser());
    }

    /** 光信息是表格式回显：表头一行 + 数据一行，列名按连续空格切开对上 */
    @Test
    void parsesOntOpticalTable() {
        String text = "MA5683T(config-if-gpon-0/1)#display ont optical-info 0 20\r\n"
                + "  ----------------------------------------------------------------------------\r\n"
                + "  ONT  Rx power(dBm)  Tx power(dBm)  OLT Rx ONT power(dBm)  Temperature(°C)  Voltage(V)\r\n"
                + "  ----------------------------------------------------------------------------\r\n"
                + "   0    -22.14         2.15           -25.31                 41.20            3.25\r\n"
                + "  ----------------------------------------------------------------------------\r\n";

        assertTrue(OltInfoParser.parseOntKeyValueBlocks(text).isEmpty());
        List<LinkedHashMap<String, String>> blocks = OltInfoParser.parseOntTableBlocks(text);
        assertEquals(1, blocks.size());
        assertEquals("0", blocks.get(0).get("ONT"));
        assertEquals("-22.14", blocks.get(0).get("Rx power(dBm)"));
        assertEquals("2.15", blocks.get(0).get("Tx power(dBm)"));
        assertEquals("3.25", blocks.get(0).get("Voltage(V)"));
    }
}
