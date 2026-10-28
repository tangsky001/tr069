package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.model.OltAction;
import com.tr069.acs.olt.model.OltOnt;
import com.tr069.acs.olt.telnet.OltVendor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 鸿升光 GPON：型号口数识别、厂商识别、ONT 回显解析 */
class HsgqGponOltProfileTest {

    private final HsgqGponOltProfile profile = new HsgqGponOltProfile();

    @Test
    void shouldKnowItsVendor() {
        assertEquals(OltVendor.HSGQ_GPON, profile.vendor());
        assertTrue(profile.wholeDevice(), "GPON 也是整机一张卡");
        assertTrue(profile.boardCommands().isEmpty(), "GPON 没有板卡表，别发命令");
    }

    @Test
    void shouldOnlyMatchItsOwnBanner() {
        assertTrue(profile.matches("Neutral GPON OLT Operating System V1.0.0\r\nLogin: "));
        assertTrue(profile.matches("Product        : Neutral-G16\r\nSoftware Version : V3.0.6"));
        assertTrue(profile.matches("Product        : HSGQ-G08"));
        // 别家 GPON 也打 gpon 这个词，不能误判成鸿升光
        assertFalse(profile.matches("Gpon OLT Version: 2.1.3 build 20240618"));
        assertFalse(profile.matches("Product        : HSGQ-E04"));
    }

    @Test
    void ponPortCountShouldFollowModel() {
        assertEquals(16, profile.ponPortCount("Neutral-G16"));
        assertEquals(8, profile.ponPortCount("Neutral-G08"));
        assertEquals(4, profile.ponPortCount("HSGQ-G04"));
        assertEquals(8, profile.ponPortCount(null));
        assertEquals(8, profile.ponPortCount("不认识的型号"));
    }

    @Test
    void wholeDeviceShouldCarryGponPortMode() {
        OltBoard board = profile.buildWholeDevice("Neutral-G16");
        assertEquals("GPON", board.getPortMode());
        assertEquals(16, board.getPortCount());
        assertTrue(board.getDescription().contains("GPON"));
    }

    @Test
    void modelShouldBeReadFromVersionOutput() {
        String output = "Product        : Neutral-G16\r\n"
                + "Software Version : V3.0.6\r\n"
                + "SerialNumber   : H8GA202011230090\r\n";
        assertEquals("Neutral-G16", profile.extractModel(output));
    }

    @Test
    void registryShouldTellGponFromEpon() {
        assertEquals(OltVendor.HSGQ_GPON,
                OltVendorProfiles.detect("Neutral GPON OLT Operating System V1.0.0").vendor());
        assertEquals(OltVendor.HSGQ, OltVendorProfiles.detect("Product : HSGQ-E04").vendor());
        assertEquals(OltVendor.GENERIC, OltVendorProfiles.detect("Gpon OLT Version: 2.1.3").vendor());
    }

    @Test
    void shouldParseOntInfoRows() {
        String output = "  --------------------------------------------------\r\n"
                + "  ONT-ID  SN                  Run     Config  Match\r\n"
                + "  1       4857544341E3F1A8    Online  Normal  Match\r\n"
                + "  2       4857544341E3F1A9    Offline Normal  Match\r\n";
        List<OltOnt> onts = HsgqGponOltProfile.parseOntInfo(output);

        assertEquals(2, onts.size());
        assertEquals(Integer.valueOf(1), onts.get(0).getOntId());
        assertEquals("4857544341E3F1A8", onts.get(0).getSn());
        assertTrue(onts.get(0).getOnline());
        assertEquals(Integer.valueOf(2), onts.get(1).getOntId());
        assertFalse(onts.get(1).getOnline());
    }

    @Test
    void unrecognisedOntOutputShouldYieldNothing() {
        // 解析不出来就返回空，绝不拿错位的列凑数
        assertTrue(HsgqGponOltProfile.parseOntInfo(null).isEmpty());
        assertTrue(HsgqGponOltProfile.parseOntInfo("").isEmpty());
        assertTrue(HsgqGponOltProfile.parseOntInfo("  No ONT online.").isEmpty());
    }

    @Test
    void actionsShouldCoverTheManual() {
        List<OltAction> actions = profile.actions();
        assertFalse(actions.isEmpty());
        assertTrue(actions.size() > 250, "GPON 手册 15 章，动作数不该只有 " + actions.size());
        actions.forEach(action -> assertTrue(action.code().startsWith("gpon."),
                action.code() + " 编号应以 gpon. 开头，便于与 EPON 区分"));
    }
}
