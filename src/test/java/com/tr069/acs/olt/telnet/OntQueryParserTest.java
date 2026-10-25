package com.tr069.acs.olt.telnet;

import com.tr069.acs.olt.model.OltOntInfo;
import com.tr069.acs.olt.model.OltServicePort;
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

    /**
     * 单条 ONT 详情（display ont info 0 1）里的关键字段：整板 all 表没有模板号和 IP，
     * 页面点 SN 时靠这一份显示线路/业务模板、IP、最后一次掉线原因。
     */
    @Test
    void parsesOntInfoDetail() {
        String text = "MA5683T(config)#display ont info 0 1\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  框/槽/端口               : 0/1/0\r\n"
                + "  ONT编号                  : 1\r\n"
                + "  运行标志                 : 在线\r\n"
                + "  配置状态                 : 正常\r\n"
                + "  匹配状态                 : 匹配\r\n"
                + "  序列号                   : 4648545474095C80 (FHTT-74095C80)\r\n"
                + "  ONT IP 0 地址/掩码       : 10.172.10.17/24\r\n"
                + "  描述                     : HG5145F\r\n"
                + "  最后一次下线原因         : LOSi/LOBi【光质量差/光纤断】\r\n"
                + "  最后一次上线时间         : 2026-10-24 14:06:41+08:00\r\n"
                + "  ONT在线时长              : 1天, 8小时, 43分, 4秒\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  线路模板编号          : 100\r\n"
                + "  线路模板名称          : FTTH-GPON-100\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  业务模板编号          : 100\r\n"
                + "  业务模板名称          : 100\r\n"
                + "  -----------------------------------------------------------------------------\r\n";

        OltOntInfo info = OltInfoParser.parseOntInfoDetail(text);
        assertEquals(Integer.valueOf(1), info.getOntId());
        assertEquals("4648545474095C80 (FHTT-74095C80)", info.getSn());
        assertEquals("HG5145F", info.getDesc());
        assertEquals("10.172.10.17/24", info.getIp());
        assertEquals(Integer.valueOf(100), info.getLineProfileId());
        assertEquals("FTTH-GPON-100", info.getLineProfileName());
        assertEquals(Integer.valueOf(100), info.getSrvProfileId());
        assertEquals("在线", info.getRunState());
        assertEquals("LOSi/LOBi【光质量差/光纤断】", info.getLastDownReason());
        assertEquals("2026-10-24 14:06:41+08:00", info.getLastUpTime());
        assertEquals("1天, 8小时, 43分, 4秒", info.getOnlineDuration());
    }

    /** 现场真实回显是英文标签（enable 视图敲的 display ont info 0 1 0 1），两套标签都要认 */
    @Test
    void parsesOntInfoDetailWithEnglishLabels() {
        String text = "MA5683T#display ont info 0 1 0 1\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  F/S/P                   : 0/1/0\r\n"
                + "  ONT-ID                  : 1\r\n"
                + "  Control flag            : active\r\n"
                + "  Run state               : online\r\n"
                + "  Config state            : normal\r\n"
                + "  Match state             : match\r\n"
                + "  Authentic type          : SN-auth\r\n"
                + "  SN                      : 4648545474095C80 (FHTT-74095C80)\r\n"
                + "  ONT IP 0 address/mask   : 10.172.10.17/24\r\n"
                + "  Description             : HG5145F\r\n"
                + "  Last down cause         : LOSi/LOBi\r\n"
                + "  Last up time            : 2026-10-24 14:06:41+08:00\r\n"
                + "  Last down time          : 2026-10-24 14:05:34+08:00\r\n"
                + "  ONT online duration     : 1 day(s), 9 hour(s), 24 minute(s), 45 second(s)\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  Line profile ID      : 100\r\n"
                + "  Line profile name    : FTTH-GPON-100\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  Service profile ID   : 100\r\n"
                + "  Service profile name : 100\r\n"
                + "  -----------------------------------------------------------------------------\r\n";

        OltOntInfo info = OltInfoParser.parseOntInfoDetail(text);
        assertEquals(Integer.valueOf(1), info.getOntId());
        assertEquals("4648545474095C80 (FHTT-74095C80)", info.getSn());
        assertEquals("HG5145F", info.getDesc());
        assertEquals("10.172.10.17/24", info.getIp());
        assertEquals(Integer.valueOf(100), info.getLineProfileId());
        assertEquals("FTTH-GPON-100", info.getLineProfileName());
        assertEquals(Integer.valueOf(100), info.getSrvProfileId());
        assertEquals("online", info.getRunState());
        assertEquals("LOSi/LOBi", info.getLastDownReason());
        assertEquals("1 day(s), 9 hour(s), 24 minute(s), 45 second(s)", info.getOnlineDuration());
    }

    /**
     * 点 SN 查的是配置摘录（display current-configuration ont 0/1/0 1）：
     * ont add 授权行经常被终端折断（ont-srvprofile-id 掉到下一行），业务流行要能全部解出。
     */
    @Test
    void parsesOntConfigExcerpt() {
        String text = "MA5683T#display current-configuration ont 0/1/0 1\r\n"
                + "  [gpon]\r\n"
                + "   <gpon-0/1>\r\n"
                + "    interface gpon 0/1\r\n"
                + " ont add 0 1 sn-auth \"4648545474095C80\" omci ont-lineprofile-id 100\r\n"
                + " ont-srvprofile-id 100 desc \"HG5145F\"\r\n"
                + " ont fec 0 1 enable\r\n"
                + "  #\r\n"
                + "  [bbs-config]\r\n"
                + "   <bbs-config>\r\n"
                + " service-port 64 vlan 41 gpon 0/1/0 ont 1 gemport 1 multi-service user-vlan 41 tag-transform translate\r\n"
                + " service-port 65 vlan 46 gpon 0/1/0 ont 1 gemport 0 multi-service user-vlan 46 tag-transform translate\r\n"
                + " service-port 66 vlan 45 gpon 0/1/0 ont 1 gemport 2 multi-service user-vlan 45 tag-transform translate\r\n"
                + " service-port 67 vlan 46 gpon 0/1/0 ont 1 gemport 2 multi-service user-vlan 4011 tag-transform translate\r\n"
                + "  #\r\n"
                + " return\r\n";

        OltOntInfo info = OltInfoParser.parseOntBindInfo(text);
        assertEquals("4648545474095C80", info.getSn());
        assertEquals("HG5145F", info.getDesc());
        assertEquals(Integer.valueOf(100), info.getLineProfileId());
        assertEquals(Integer.valueOf(100), info.getSrvProfileId());

        // 业务流行 4 条全部解出，带索引 / VLAN / GEM port
        List<OltServicePort> ports = OltInfoParser.parseServicePortsFromConfig(text);
        assertEquals(4, ports.size());
        assertEquals(Integer.valueOf(64), ports.get(0).getIndex());
        assertEquals(Integer.valueOf(41), ports.get(0).getVlanId());
        assertEquals(Integer.valueOf(1), ports.get(0).getGemport());
    }

    /** 现场光信息英文回显：键最长 33 个字符（Supply voltage alarm threshold(V)），不能被键长截断 */
    @Test
    void parsesOpticalInfoEnglishBlock() {
        String text = "MA5683T(config)  display ont optical-info 0 1\r\n"
                + "  -------------------------------------------------------------------\r\n"
                + "  ONU NNI port ID              : -\r\n"
                + "  Laser bias current(mA)                : 15\r\n"
                + "  Tx bias current warning threshold(mA)  : [-,-]\r\n"
                + "  Temperature(C)                : 50\r\n"
                + "  Supply voltage alarm threshold(V)  : [-,-]\r\n"
                + "  OLT Rx ONT optical power(dBm)   : -11.98\r\n"
                + "  CATV Rx optical power(dBm)      : -\r\n"
                + "  -------------------------------------------------------------------\r\n"
                + "MA5683T(config-if-gpon-0/1)#\r\n";

        List<LinkedHashMap<String, String>> blocks = OltInfoParser.parseOntKeyValueBlocks(text);
        assertEquals(1, blocks.size(), "光信息英文回显必须解出至少一块");
        assertEquals("15", blocks.get(0).get("Laser bias current(mA)"));
        assertEquals("50", blocks.get(0).get("Temperature(C)"));
        assertEquals("-11.98", blocks.get(0).get("OLT Rx ONT optical power(dBm)"));
    }

    /** 现场业务流表格回显：FLOW PARA 是用户侧 VLAN，VCI 是 GEM port，行尾 STATE */
    @Test
    void parsesServicePortTableEcho() {
        String text = "MA5683T(config)#display service-port port 0/1/0 ont 1\r\n"
                + "  Switch-Oriented Flow List\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  INDEX  VLAN  VLAN      PORT F/ S/ P VPI  VCI   FLOW  FLOW       RX  TX  STATE\r\n"
                + "         ID    ATTR      TYPE                  TYPE  PARA\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "    64    41 common    gpon 0/1/ 0   1    1    vlan  41     -   -   up\r\n"
                + "    65    46 common    gpon 0/1/ 0   1    0    vlan  46     -   -   up\r\n"
                + "    66    45 common    gpon 0/1/ 0   1    2    vlan  45     -   -   up\r\n"
                + "    67    46 common    gpon 0/1/ 0   1    2    vlan  4011   -   -   up\r\n"
                + "  -----------------------------------------------------------------------------\r\n"
                + "  Total : 4  (Up/Down :  4/0)\r\n";

        List<OltServicePort> ports = OltInfoParser.parseServicePorts(text);
        assertEquals(4, ports.size());
        OltServicePort first = ports.get(0);
        assertEquals(Integer.valueOf(64), first.getIndex());
        assertEquals(Integer.valueOf(41), first.getVlanId());
        assertEquals("common", first.getVlanAttr());
        assertEquals(Integer.valueOf(1), first.getGemport());
        assertEquals(Integer.valueOf(1), first.getOntId());
        assertEquals(Integer.valueOf(41), first.getUserVlan());
        assertEquals("up", first.getState());
        assertEquals(Integer.valueOf(4011), ports.get(3).getUserVlan());
    }

    /** 硬件能力回显（display ont capability 0/1 0 1）：key:value 块状，T-CONT 的 PQ 行是动态键 */
    @Test
    void parsesCapabilityBlock() {
        String text = "MA5683T(config)#display ont capability 0/1 0 1\r\n"
                + "  --------------------------------------------------------------------------\r\n"
                + "  F/S/P:                 0/1/0\r\n"
                + "  ONT description:       HG5145F\r\n"
                + "  ONT ID:                1\r\n"
                + "  Equipment ID:          HG5145F\r\n"
                + "  Number of uplink PON ports:  1\r\n"
                + "  Number of ETH ports:   4\r\n"
                + "  Number of GEM ports:   64\r\n"
                + "  IP configuration:      support\r\n"
                + "  Number of Traffic Schedulers:  7\r\n"
                + "  Number of T-CONTs:     8\r\n"
                + "  The type of flow control:  GEMPORT CAR and PQ\r\n"
                + "  ONT optical module power\r\n"
                + "  control capability:      the TX and RX power can be\r\n"
                + "  controlled separately\r\n"
                + "  ONT type:              HGU\r\n"
                + "  --------------------------------------------------------------------------\r\n"
                + "  Number of PQs in T-CONT   0:              -\r\n"
                + "  Number of PQs in T-CONT   1:              8\r\n"
                + "  --------------------------------------------------------------------------\r\n";

        List<LinkedHashMap<String, String>> blocks = OltInfoParser.parseOntKeyValueBlocks(text);
        assertEquals(2, blocks.size());
        assertEquals("HG5145F", blocks.get(0).get("ONT description"));
        assertEquals("support", blocks.get(0).get("IP configuration"));
        assertEquals("GEMPORT CAR and PQ", blocks.get(0).get("The type of flow control"));
        // 标签折行 + 值折行要拼回一条：ONT optical module power control capability
        assertEquals("the TX and RX power can be controlled separately",
                blocks.get(0).get("ONT optical module power control capability"));
        assertEquals("HGU", blocks.get(0).get("ONT type"));
        assertEquals("-", blocks.get(1).get("Number of PQs in T-CONT   0"));
        assertEquals("8", blocks.get(1).get("Number of PQs in T-CONT   1"));
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
