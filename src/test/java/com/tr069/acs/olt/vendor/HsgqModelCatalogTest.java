package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.model.OltFrontPanel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 鸿升光整机型号规格：型号决定前面板上有几个 PON 口、几个上联 GE / XGE。
 */
class HsgqModelCatalogTest {

    @Test
    void xe08rHasEightPonFourGeFourXge() {
        HsgqModelCatalog.Spec spec = HsgqModelCatalog.match("HSGQ-XE08R");

        assertNotNull(spec);
        assertEquals("GPON", spec.ponType());
        assertEquals(8, spec.ponCount());
        assertEquals(4, spec.geCount());
        assertEquals(4, spec.xgeCount());
        assertTrue(spec.hasNms());
        assertTrue(spec.hasConsole());
        assertEquals("8 × GPON + 4 × GE + 4 × XGE + NMS + CONSOLE", spec.summary());
    }

    @Test
    void eponLineHasFourGeUplink() {
        HsgqModelCatalog.Spec spec = HsgqModelCatalog.match("HSGQ-E04");

        assertNotNull(spec);
        assertEquals("EPON", spec.ponType());
        assertEquals(4, spec.ponCount());
        assertEquals(4, spec.geCount());
        assertEquals(0, spec.xgeCount());
    }

    @Test
    void g04lHasTwoXge() {
        HsgqModelCatalog.Spec spec = HsgqModelCatalog.match("HSGQ-G04L");

        assertNotNull(spec);
        assertEquals(4, spec.ponCount());
        assertEquals(4, spec.geCount());
        assertEquals(2, spec.xgeCount());
    }

    @Test
    void frontPanelOfBuildsWholeDevicePanel() {
        OltFrontPanel panel = HsgqModelCatalog.frontPanelOf("HSGQ-XE08R");

        assertNotNull(panel);
        assertEquals("HSGQ-XE08R", panel.getModel());
        assertEquals(8, panel.getPonCount());
        assertEquals(4, panel.getXgeCount());
        assertTrue(panel.isHasNms());
    }

    @Test
    void frontPanelOfIgnoresHuaweiBoard() {
        // 华为插框设备的板卡型号不是整机，别给它套整机面板
        assertNull(HsgqModelCatalog.frontPanelOf("H802SCUN"));
        assertNull(HsgqModelCatalog.frontPanelOf("H801XGBD"));
        assertNull(HsgqModelCatalog.frontPanelOf(null));
        assertNull(HsgqModelCatalog.frontPanelOf(""));
    }

    @Test
    void unknownHsgqModelFallsBackToPonCount() {
        HsgqModelCatalog.Spec spec = HsgqModelCatalog.fallback("GPON", 8);

        assertEquals("GPON", spec.ponType());
        assertEquals(8, spec.ponCount());
        assertEquals("8 × GPON + 4 × GE + 2 × XGE + CONSOLE", spec.summary());
    }
}
