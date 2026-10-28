package com.tr069.acs.olt.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 页面上填的 VLAN ID（100 / 100-200,300）怎么解析 */
class VlanIdExpressionTest {

    @Test
    void shouldReadSingleId() {
        assertEquals(List.of(100), VlanIdExpression.expand("100"));
        assertArrayEquals(new int[]{100, 100}, VlanIdExpression.segments("100").get(0));
    }

    @Test
    void shouldReadCommaAndRange() {
        assertEquals(List.of(100, 101, 102, 300), VlanIdExpression.expand("100-102,300"));
        List<int[]> segments = VlanIdExpression.segments("100-102,300");
        assertEquals(2, segments.size());
        assertArrayEquals(new int[]{100, 102}, segments.get(0));
        assertArrayEquals(new int[]{300, 300}, segments.get(1));
    }

    @Test
    void shouldSortAndMergeWhatUserTyped() {
        // 顺序写反了也认，连续的数字合成一段
        List<int[]> segments = VlanIdExpression.segments("200-201,100,101,102");
        assertEquals(2, segments.size());
        assertArrayEquals(new int[]{100, 102}, segments.get(0));
        assertArrayEquals(new int[]{200, 201}, segments.get(1));
    }

    @Test
    void shouldIgnoreRepeatedIds() {
        assertEquals(List.of(100, 101), VlanIdExpression.expand("100,100-101,100"));
    }

    @Test
    void shouldTolerateChineseComma() {
        assertEquals(List.of(100, 300), VlanIdExpression.expand("100，300"));
    }

    @Test
    void shouldRejectBadInput() {
        assertMessage("", "请填写 VLAN ID");
        assertMessage("  ", "请填写 VLAN ID");
        assertMessage("abc", "VLAN ID 只能是数字");
        assertMessage("0", "VLAN ID 必须在 1-4094 之间");
        assertMessage("4095", "VLAN ID 必须在 1-4094 之间");
        assertMessage("200-100", "前后写反了");
        assertMessage("100-", "范围写法不对");
    }

    private void assertMessage(String text, String expected) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> VlanIdExpression.expand(text));
        assertTrue(error.getMessage().contains(expected),
                "输入 " + text + " 的提示应是「" + expected + "」，实际是「" + error.getMessage() + "」");
    }
}
