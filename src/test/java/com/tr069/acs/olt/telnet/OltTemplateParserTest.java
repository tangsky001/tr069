package com.tr069.acs.olt.telnet;

import com.tr069.acs.olt.model.OltDbaProfile;
import com.tr069.acs.olt.model.OltLineProfile;
import org.junit.jupiter.api.Test;

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
}
