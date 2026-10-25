package com.tr069.acs.olt.telnet;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONT 按需查询的块状回显解析，样本取自现场真实回显：
 * display ont optical-info / version / register-info。
 */
class OntQueryParserTest {

    @Test
    void parsesOpticalInfoBlock() {
        String text = "MA5683T(config-if-gpon-0/1)#display ont optical-info 0 1\r\n"
                + "  ----------------------------------------------------------------------------\r\n"
                + "  ONU网络侧端口号             :  -\r\n"
                + "  光模块类型                  :  -\r\n"
                + "  接收功率(dbm)               :  -16.59\r\n"
                + "  接收功率告警门限值(dbm)     :  [-,-]\r\n"
                + "  发送功率(dbm)               :  3.10\r\n"
                + "  偏置电流(mA)                :  15\r\n"
                + "  温度 (℃)                   :  49\r\n"
                + "  电压(V)                     :  3.100\r\n"
                + "  ----------------------------------------------------------------------------\r\n";

        List<LinkedHashMap<String, String>> blocks = OltInfoParser.parseOntKeyValueBlocks(text);
        assertEquals(1, blocks.size());
        LinkedHashMap<String, String> block = blocks.get(0);
        assertEquals("-16.59", block.get("接收功率(dbm)"));
        assertEquals("3.10", block.get("发送功率(dbm)"));
        assertEquals("3.100", block.get("电压(V)"));
    }

    @Test
    void parsesRegisterInfoBlocks() {
        String text = "MA5683T(config-if-gpon-0/1)#display ont register-info 0 3\r\n"
                + "  ----------------------------------------------------------------------------\r\n"
                + "  索引编号                     :  2\r\n"
                + "  认证方式                     :  SN认证\r\n"
                + "  ONT序列号                    :  4648545483B8A2E0 (FHTT-83B8A2E0)\r\n"
                + "  上线时间                     :  2026-10-25 14:06:21+08:00\r\n"
                + "  下线时间                     :  -\r\n"
                + "  下线原因                     :  -\r\n"
                + "  ----------------------------------------------------------------------------\r\n"
                + "  索引编号                     :  1\r\n"
                + "  认证方式                     :  SN认证\r\n"
                + "  上线时间                     :  2026-10-24 01:27:12+08:00\r\n"
                + "  下线时间                     :  2026-10-25 14:05:28+08:00\r\n"
                + "  下线原因                     :  ONT的LOSi/LOBi告警\r\n"
                + "  ----------------------------------------------------------------------------\r\n"
                + "  有效认证信息条数   :  2\r\n";

        List<LinkedHashMap<String, String>> blocks = OltInfoParser.parseOntKeyValueBlocks(text);
        // 分隔线分段：两次注册各一块；末尾「有效认证信息条数」也自成一块
        assertEquals(3, blocks.size());
        assertEquals("2", blocks.get(0).get("索引编号"));
        assertEquals("SN认证", blocks.get(0).get("认证方式"));
        assertEquals("ONT的LOSi/LOBi告警", blocks.get(1).get("下线原因"));
    }

    @Test
    void skipsPromptAndCommandLines() {
        String text = "MA5683T(config)#display ont version 0 2\r\n"
                + "  ----------------------------------------------------------------------------\r\n"
                + "  框/槽/端口                   :  0/1/0\r\n"
                + "  ONT编号                      :  2\r\n"
                + "  设备编号                     :  HG5145F\r\n"
                + "  主用软件版本                 :  RP0500\r\n"
                + "  ----------------------------------------------------------------------------\r\n";

        List<LinkedHashMap<String, String>> blocks = OltInfoParser.parseOntKeyValueBlocks(text);
        assertEquals(1, blocks.size());
        assertEquals("HG5145F", blocks.get(0).get("设备编号"));
        assertEquals("RP0500", blocks.get(0).get("主用软件版本"));
        assertTrue(blocks.get(0).keySet().stream().noneMatch(key -> key.contains("MA5683T")));
    }
}
