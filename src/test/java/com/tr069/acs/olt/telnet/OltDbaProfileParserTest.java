package com.tr069.acs.olt.telnet;

import com.tr069.acs.olt.model.OltDbaProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DBA 模板解析，样本取自现场 MA5683T 真实回显：
 * 总表没有名字列（Profile-ID/type/Bandwidth compensation/Fix/Assure/Max/Bind times），
 * 名字在 profile-id 详情的块状回显里。
 */
class OltDbaProfileParserTest {

    private static final String SUMMARY = "MA5683T(config)#display DBA-profile all\r\n"
            + "  { <cr>|detail<K> }:\r\n"
            + "  Command:\r\n"
            + "          display DBA-profile all\r\n"
            + "\r\n"
            + "  Profile-ID  type  Bandwidth compensation  Fix(kbps)  Assure(kbps)  Max(kbps)  Bind times\r\n"
            + "  ----------------------------------------------------------------------------\r\n"
            + "       0        3          No                  0         8192        20480        1\r\n"
            + "       4        1          No             102400            0            0        0\r\n"
            + "     100        4          No                  0        32768       102400        1\r\n"
            + "  ----------------------------------------------------------------------------\r\n"
            + "  Total : 3\r\n";

    private static final String DETAIL = "MA5683T(config)#display DBA-profile profile-id 100\r\n"
            + "  -------------------------\r\n"
            + "  Profile-name       :    100M\r\n"
            + "  Profile-ID         :    100\r\n"
            + "  type               :    4\r\n"
            + "  Bandwidth compensation: No\r\n"
            + "  Fix-delay          :    No\r\n"
            + "  Fix(kbps)          :    0\r\n"
            + "  Assure(kbps)       :    32768\r\n"
            + "  Max(kbps)          :    102400\r\n"
            + "  Additional-bandwidth:   -\r\n"
            + "  Best-effort-priority:    0\r\n"
            + "  Best-effort-weight:      128\r\n"
            + "  bind-times         :    1\r\n"
            + "  -------------------------\r\n";

    @Test
    void parsesMa5600SummaryWithoutName() {
        List<OltDbaProfile> profiles = OltInfoParser.parseDbaProfiles(SUMMARY);
        assertEquals(3, profiles.size());
        OltDbaProfile self = profiles.get(2);
        assertEquals(100, self.getProfileId());
        assertEquals("type4", self.getType());
        assertEquals(0, self.getFixKbps());
        assertEquals(32768, self.getAssureKbps());
        assertEquals(102400, self.getMaxKbps());
        // 总表就是没有名字，不能拿「No」之类的列冒充
        assertNull(profiles.get(0).getName());
    }

    @Test
    void parsesDetailBlockWithName() {
        List<OltDbaProfile> details = OltInfoParser.parseDbaDetails(DETAIL);
        assertEquals(1, details.size());
        OltDbaProfile detail = details.get(0);
        assertEquals(100, detail.getProfileId());
        assertEquals("100M", detail.getName());
        assertEquals("type4", detail.getType());
        assertEquals(0, detail.getFixKbps());
        assertEquals(32768, detail.getAssureKbps());
        assertEquals(102400, detail.getMaxKbps());
    }

    @Test
    void oldTabularFormatStillWorks() {
        String text = "  ----------------------------------------------------------------------------\r\n"
                + "   1  DBA-1  type3  1024  2048  102400\r\n"
                + "  ----------------------------------------------------------------------------\r\n";
        List<OltDbaProfile> profiles = OltInfoParser.parseDbaProfiles(text);
        assertEquals(1, profiles.size());
        assertEquals("DBA-1", profiles.get(0).getName());
        assertEquals("type3", profiles.get(0).getType());
    }
}
