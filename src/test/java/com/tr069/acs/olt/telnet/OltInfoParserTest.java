package com.tr069.acs.olt.telnet;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.model.OltIpInterface;
import com.tr069.acs.olt.model.OltOnt;
import com.tr069.acs.olt.model.OltPort;
import com.tr069.acs.olt.model.OltPortVlan;
import com.tr069.acs.olt.model.OltStaticRoute;
import com.tr069.acs.olt.model.OltVlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回显解析测试，样例按现场设备（华为 MA5683T / 中兴 C320）的实际输出格式整理。
 */
class OltInfoParserTest {

    private static final String HUAWEI_VERSION = String.join("\n",
            "  Huawei Versatile Routing Platform Software",
            "  VRP (R) software, Version 5.180 (MA5600T V800R018C10)",
            "  Copyright (C) 2000-2020 HUAWEI TECH CO., LTD",
            "  Uptime is 68 days, 4 hours, 12 minutes");

    /** MA5683T 的 display version，含补丁、背板、MAC、运行时长 */
    private static final String MA5683T_VERSION = String.join("\n",
            "  VERSION : MA5600V800R015C00",
            "  PATCH   : SPC107",
            "",
            "  Huawei Versatile Routing Platform Software",
            "  VRP (R) software, Version 5.70 (MA5600V800R015C00)",
            "  Copyright (C) 2000-2012 HUAWEI TECH CO., LTD",
            "",
            "  Huawei MA5683T uptime is 59 day(s), 18 hour(s), 21 minute(s), 11 second(s)",
            "",
            "  Main board:",
            "    PCB    Version : H802MABO VER.B",
            "    Board  Type    : H802MABO",
            "    MAC address    : D4-6A-A8-6C-4E-96");

    private static final String HUAWEI_BOARDS = String.join("\n",
            "  -------------------------------------------------------------------------",
            "  SlotID  BoardName     Status          SubType0  SubType1    Online/Offline",
            "  -------------------------------------------------------------------------",
            "  0       H901MPLA      Normal",
            "  1       H901GPHF      Normal",
            "  2       H901GPHF      Abnormal",
            "  3       H901PILA      Normal");

    /** MA5683T 的 display board 0：空槽位只回槽位号，主控板回 Active_normal / Standby_normal */
    private static final String MA5683T_BOARDS = String.join("\n",
            "  -------------------------------------------------------------------------",
            "  SlotID  BoardName  Status          SubType0  SubType1    Online/Offline",
            "  -------------------------------------------------------------------------",
            "  0       H805GPFD   Normal",
            "  1",
            "  2",
            "  6       H802SCUN   Active_normal",
            "  7       H802SCUN   Standby_normal",
            "  8       H801GICF   Normal",
            "  9       H801X2CS   Normal",
            "  10",
            "  11",
            "  12");

    /** 现场 MA5683T（MA5683T> 用户视图）的真实回显，原样贴进来做回归 */
    private static final String REAL_MA5683T_BOARDS = String.join("\n",
            "MA5683T>display board 0",
            "-------------------------------------------------------------------------",
            "  SlotID  BoardName  Status          SubType0  SubType1    Online/Offline",
            "-------------------------------------------------------------------------",
            "  0",
            "  1       H807GPBD   Normal",
            "  2",
            "  3",
            "  4",
            "  5",
            "  6       H802SCUN   Active_normal",
            "  7",
            "  8       H801X2CS   Normal",
            "  9",
            "  10",
            "  11",
            "  12",
            "-------------------------------------------------------------------------",
            "MA5683T>");

    /** display temperature all 的真实格式：Slot Card Sensor Status Current(C) Lower(C) Upper(C) */
    private static final String MA5683T_TEMPERATURE = String.join("\n",
            "  ---------------------------------------------------------------",
            "  Slot  Card  Sensor  Status  Current(C)  Lower(C)  Upper(C)",
            "  ---------------------------------------------------------------",
            "  1     -     1       Normal  46          0         85",
            "  6     -     1       Normal  39          0         85",
            "  8     -     1       Normal  37          0         85",
            "  8     -     2       Normal  40          0         85");

    private static final String ZTE_VERSION = String.join("\n",
            "ZXAN#show version",
            "ZXAN Software, Version: V4.8.0",
            "Copyright (c) 1995-2018 ZTE Corporation",
            "ZXA10 C320");

    private static final String ZTE_BOARDS = String.join("\n",
            "Rack Shelf Slot  Cpu  BoardType  PortNum  Status",
            "1    1     1     0    CFC        0        active",
            "1    1     2     0    GTGO       8        inservice",
            "1    1     3     0    GTGO       8        standby");

    @Test
    void shouldDetectVendorFromVersionOutput() {
        assertEquals(OltVendor.HUAWEI, OltVendor.detect(HUAWEI_VERSION));
        assertEquals(OltVendor.HUAWEI, OltVendor.detect(MA5683T_VERSION));
        assertEquals(OltVendor.ZTE, OltVendor.detect(ZTE_VERSION));
        assertEquals(OltVendor.GENERIC, OltVendor.detect("unknown device"));
        assertEquals(OltVendor.GENERIC, OltVendor.detect(""));
    }

    @Test
    void shouldExtractHuaweiModelAndVersion() {
        assertEquals("MA5600T", OltInfoParser.extractModel(OltVendor.HUAWEI, HUAWEI_VERSION));
        assertEquals("V800R018C10", OltInfoParser.extractSoftwareVersion(HUAWEI_VERSION));
    }

    @Test
    void shouldExtractMa5683tVersionDetails() {
        assertEquals("MA5683T", OltInfoParser.extractModel(OltVendor.HUAWEI, MA5683T_VERSION));
        assertEquals("MA5600V800R015C00", OltInfoParser.extractSoftwareVersion(MA5683T_VERSION));
        assertEquals("SPC107", OltInfoParser.extractPatchVersion(MA5683T_VERSION));
        assertEquals("H802MABO", OltInfoParser.extractBackplane(MA5683T_VERSION));
        assertEquals("D4-6A-A8-6C-4E-96", OltInfoParser.extractMacAddress(MA5683T_VERSION));
        assertEquals("59天18小时21分11秒", OltInfoParser.extractUptime(MA5683T_VERSION));
    }

    @Test
    void shouldExtractZteModelAndVersion() {
        assertEquals("ZXA10 C320", OltInfoParser.extractModel(OltVendor.ZTE, ZTE_VERSION));
        assertEquals("V4.8.0", OltInfoParser.extractSoftwareVersion(ZTE_VERSION));
    }

    @Test
    void shouldParseHuaweiBoards() {
        List<OltBoard> boards = OltInfoParser.parseBoards(HUAWEI_BOARDS);
        assertEquals(4, boards.size());
        assertEquals(0, boards.get(0).getSlotNo());
        assertEquals("H901MPLA", boards.get(0).getBoardType());
        assertEquals("Normal", boards.get(0).getStatus());
        assertEquals("GPON", boards.get(1).getPortMode());
        assertEquals("Abnormal", boards.get(2).getStatus());
        assertEquals("POWER", boards.get(3).getBoardKind());
    }

    @Test
    void shouldParseMa5683tBoardsAndClassify() {
        List<OltBoard> boards = OltInfoParser.parseBoards(MA5683T_BOARDS);

        // 空槽位（只有槽位号）不该被当成板卡
        assertEquals(5, boards.size());

        OltBoard service = boards.get(0);
        assertEquals(0, service.getSlotNo());
        assertEquals("H805GPFD", service.getBoardType());
        assertEquals("SERVICE", service.getBoardKind());
        assertEquals(16, service.getPortCount());
        assertEquals("16口GPON（业务板）", service.getDescription());

        OltBoard activeControl = boards.get(1);
        assertEquals(6, activeControl.getSlotNo());
        assertEquals("Active_normal", activeControl.getStatus());
        assertEquals("CONTROL", activeControl.getBoardKind());
        assertEquals("超级控制单元板（主控板）", activeControl.getDescription());

        OltBoard standbyControl = boards.get(2);
        assertEquals(7, standbyControl.getSlotNo());
        assertEquals("Standby_normal", standbyControl.getStatus());
        assertEquals("CONTROL", standbyControl.getBoardKind());

        OltBoard gicf = boards.get(3);
        assertEquals(8, gicf.getSlotNo());
        assertEquals("H801GICF", gicf.getBoardType());
        assertEquals("UPLINK", gicf.getBoardKind());
        assertEquals("上联板", gicf.getDescription());

        OltBoard x2cs = boards.get(4);
        assertEquals("UPLINK", x2cs.getBoardKind());
        assertEquals("10GE", x2cs.getPortMode());
        assertEquals(2, x2cs.getPortCount());
        assertEquals("2路10GE光口（上联板）", x2cs.getDescription());
    }

    @Test
    void shouldParseRealMa5683tOutput() {
        List<OltBoard> boards = OltInfoParser.parseBoards(REAL_MA5683T_BOARDS);

        assertEquals(3, boards.size());

        OltBoard gpon = boards.get(0);
        assertEquals(1, gpon.getSlotNo());
        assertEquals("H807GPBD", gpon.getBoardType());
        assertEquals("Normal", gpon.getStatus());
        assertEquals("SERVICE", gpon.getBoardKind());
        assertEquals(8, gpon.getPortCount());
        assertEquals("8口GPON（业务板）", gpon.getDescription());

        OltBoard control = boards.get(1);
        assertEquals(6, control.getSlotNo());
        assertEquals("H802SCUN", control.getBoardType());
        assertEquals("Active_normal", control.getStatus());
        assertEquals("CONTROL", control.getBoardKind());
        assertEquals("超级控制单元板（主控板）", control.getDescription());

        OltBoard uplink = boards.get(2);
        assertEquals(8, uplink.getSlotNo());
        assertEquals("H801X2CS", uplink.getBoardType());
        assertEquals("UPLINK", uplink.getBoardKind());
        assertEquals("2路10GE光口（上联板）", uplink.getDescription());
    }

    @Test
    void shouldSkipBackplaneRow() {
        String withBackplane = String.join("\n",
                "  0       H802MABO   Normal",
                "  1       H807GPBD   Normal");
        List<OltBoard> boards = OltInfoParser.parseBoards(withBackplane);
        assertEquals(1, boards.size());
        assertEquals("H807GPBD", boards.get(0).getBoardType());
        assertEquals("H802MABO", OltInfoParser.extractBackplaneBoard(withBackplane));
    }

    /** display port state 的表格形态：Optic Status 取 normal / absence / abnormal */
    private static final String PORT_STATE_TABLE = String.join("\n",
            "  -------------------------------------------------------------------------",
            "  Port      PortType   PortState   OpticStatus   RXPower(dBm)",
            "  -------------------------------------------------------------------------",
            "  0/1/0     GPON       Up          normal        -8.12",
            "  0/1/1     GPON       Up          absence       -",
            "  0/1/2     GPON       Up          abnormal      -30.51",
            "  0/1/3     GPON       Up          normal        -9.33");

    /** 有些版本是键值块写法 */
    private static final String PORT_STATE_BLOCK = String.join("\n",
            "  Port ID : 0/1/0",
            "  Port type : GPON",
            "  Optic status : normal",
            "  RX power(dBm) : -8.12",
            "  Port ID : 0/1/1",
            "  Port type : GPON",
            "  Optic status : absence");

    @Test
    void shouldParsePortOpticStatus() {
        List<OltPort> ports = OltInfoParser.parsePorts(PORT_STATE_TABLE);

        assertEquals(4, ports.size());

        OltPort first = ports.get(0);
        assertEquals(0, first.getPortNo());
        assertEquals("P0", first.getPortName());
        assertEquals("normal", first.getOpticStatus());
        assertTrue(first.getOpticalPresent());
        assertEquals("-8.12 dBm", first.getRxPower());

        // 不在位：灰灯，没有收光功率
        OltPort absent = ports.get(1);
        assertEquals("absence", absent.getOpticStatus());
        assertFalse(absent.getOpticalPresent());
        assertNull(absent.getRxPower());

        // 故障：在位但异常，页面亮黄灯
        OltPort abnormal = ports.get(2);
        assertEquals("abnormal", abnormal.getOpticStatus());
        assertTrue(abnormal.getOpticalPresent());

        assertEquals(1, ports.get(3).getSlotNo());
    }

    @Test
    void shouldParsePortOpticStatusFromKeyValueBlock() {
        List<OltPort> ports = OltInfoParser.parsePorts(PORT_STATE_BLOCK);

        assertEquals(2, ports.size());
        assertEquals("normal", ports.get(0).getOpticStatus());
        assertTrue(ports.get(0).getOpticalPresent());
        assertEquals("-8.12 dBm", ports.get(0).getRxPower());
        assertFalse(ports.get(1).getOpticalPresent());
    }

    /** config 视图下 display ont info 0 1 0 all 的真实输出：末尾有汇总行 */
    private static final String ONT_INFO_GPON = String.join("\n",
            "  -----------------------------------------------------------------------------",
            "  F/S/P   ONT-ID   SN                  Control   Run       Config    Match",
            "                                       flag      state     state     state",
            "  -----------------------------------------------------------------------------",
            "  0/ 1/0   0       485754431B2CA496    active    online    normal    match",
            "  0/ 1/0   1       485754431B2CA497    active    offline   normal    match",
            "  0/ 1/0   2       485754431B2CA498    active    online    normal    match",
            "  -----------------------------------------------------------------------------",
            "  In port 0/1/0, the total of ONTs are: 3, online: 2");

    /** 部分版本不给汇总行，只能按明细行数；EPON 的在线状态写 up/down */
    private static final String ONT_INFO_ROWS_ONLY = String.join("\n",
            "  F/S/P   ONT-ID   MAC                Control   Run     Config   Match",
            "  0/ 1/0   0       0000-0000-3000     active    up      normal   match",
            "  0/ 1/0   1       0000-0000-3001     active    down    normal   match");

    /** 现场原文：分页提示 ( Press 'Q' to break ) 直接打在行中间，后面跟着几条 ONT */
    private static final String ONT_INFO_WITH_PAGING_JUNK = String.join("\n",
            "  -----------------------------------------------------------------------------",
            "  F/S/P   ONT         SN         Control     Run      Config   Match    Protect",
            "          ID                     flag        state    state    state    side",
            "  -----------------------------------------------------------------------------",
            "  0/ 1/0    0  534B5957A89FE02A  active      online   normal   match    no",
            "  0/ 1/0    1  534B5957A88C62DA  active      offline  initial  initial  no",
            " ( Press 'Q' to break ) ----                                       0/ 1/0   2  534B5957A88C9CCD  active      online   normal   match    no",
            "  -----------------------------------------------------------------------------",
            "  F/S/P   ONT-ID   Description",
            "  -----------------------------------------------------------------------------",
            "  0/ 1/0       0   SK-D748S",
            "  0/ 1/0       1   SK-D748S",
            "  0/ 1/0       2   SK-D748S",
            "  -----------------------------------------------------------------------------",
            "  In port 0/ 1/0 , the total of ONTs are: 3, online: 2");

    @Test
    void shouldCountOntsFromSummaryLine() {
        Map<Integer, int[]> counts = OltInfoParser.parseOntCounts(ONT_INFO_GPON);

        assertEquals(1, counts.size());
        int[] port0 = counts.get(0);
        assertEquals(3, port0[0]);
        assertEquals(2, port0[1]);
    }

    @Test
    void shouldCountOntsFromRowsWhenNoSummary() {
        Map<Integer, int[]> counts = OltInfoParser.parseOntCounts(ONT_INFO_ROWS_ONLY);

        assertEquals(2, counts.get(0)[0]);
        assertEquals(1, counts.get(0)[1]);
    }

    @Test
    void shouldParseOntDetailsIncludingPagingWrappedRows() {
        Map<Integer, List<OltOnt>> details = OltInfoParser.parseOntDetails(ONT_INFO_WITH_PAGING_JUNK);

        List<OltOnt> onts = details.get(0);
        assertEquals(3, onts.size(), "分页提示打在行中间的那条 ONT 也不能漏");
        assertEquals(Integer.valueOf(0), onts.get(0).getOntId());
        assertEquals("534B5957A89FE02A", onts.get(0).getSn());
        assertTrue(onts.get(0).getOnline());
        // 不在线的也要列出来，页面要区分离线设备
        assertFalse(onts.get(1).getOnline());
        assertEquals("SK-D748S", onts.get(2).getDescription());
    }

    @Test
    void shouldNotTreatDescriptionTableAsOntDetail() {
        // Description 表只有 F/S/P ONT-ID 型号三列，不能当成一条 ONT
        assertTrue(OltInfoParser.parseOntDetails("  0/ 1/0       0   SK-D748S\r\n").isEmpty());
    }

    // 现场 MA5683T 原文，用于校对 VLAN 解析
    private static final String VLAN_ALL = String.join("\n",
            "  Command:",
            "          display vlan all",
            "  -----------------------------------------------------------------------------",
            "  VLAN  Type   Attribute  STND-Port NUM  SERV-Port NUM  VLAN-Con NUM",
            "  -----------------------------------------------------------------------------",
            "     1   smart  common          6               0             -",
            "    41   smart  common          6              57             -",
            "    45   smart  common          6              57             -",
            "    46   smart  common          6              57             -",
            "  -----------------------------------------------------------------------------",
            "  Total: 4",
            "  Note : STND-Port--standard port, SERV-Port--service virtual port,");

    private static final String PORT_VLAN_UPLINK = String.join("\n",
            "MA5683T(config)#display port vlan 0/6/0",
            "  --------------",
            "   1    41    45    46",
            "  --------------",
            "  Total: 4",
            "  Native VLAN: 1");

    private static final String PORT_VLAN_PON = String.join("\n",
            "MA5683T(config)#display port vlan 0/1/0",
            "  --------------",
            "   41     46     45",
            "  --------------",
            "  Total: 3");

    @Test
    void shouldParseVlanList() {
        List<OltVlan> vlans = OltInfoParser.parseVlans(VLAN_ALL);

        assertEquals(4, vlans.size());
        assertEquals(Integer.valueOf(1), vlans.get(0).getVlanId());
        assertEquals("smart", vlans.get(0).getType());
        assertEquals(Integer.valueOf(0), vlans.get(0).getServPortNum());
        assertNull(vlans.get(0).getVlanConNum(), "回显是 -，不能当成 0");
        assertEquals(Integer.valueOf(57), vlans.get(1).getServPortNum());
        assertEquals(4, OltInfoParser.parseVlanTotal(VLAN_ALL));
    }

    @Test
    void shouldParsePortVlans() {
        OltPortVlan uplink = OltInfoParser.parsePortVlan(PORT_VLAN_UPLINK);
        assertEquals(List.of(1, 41, 45, 46), uplink.getVlans(), "乱序也要排好");
        assertEquals(Integer.valueOf(1), uplink.getNativeVlan());

        OltPortVlan pon = OltInfoParser.parsePortVlan(PORT_VLAN_PON);
        assertEquals(List.of(41, 45, 46), pon.getVlans());
        assertNull(pon.getNativeVlan(), "PON 口回显没有 Native VLAN");

        assertNull(OltInfoParser.parsePortVlan("  % Unknown command"), "命令不支持时返回 null");
        assertNull(OltInfoParser.parsePortVlan(""));
    }

    /** 现场原文：带外管理口 meth0 不算三层 VLAN 接口 */
    private static final String IP_INTERFACE_BRIEF = String.join("\n",
            "The number of interface that is UP in Physical is 3",
            "Interface        IP Address/Mask       Physical    Protocol",
            "meth0            10.11.104.2/24        up          up",
            "null0            unassigned            up          up(s)",
            "vlanif46         10.172.10.254/24      up          up");

    private static final String ROUTING_TABLE = String.join("\n",
            "Destination/Mask    Proto   Pre  Cost      Flags NextHop         Interface",
            "",
            "        0.0.0.0/0   Static  60   0          RD   10.172.10.1     vlanif46",
            "    10.11.104.0/24  Direct  0    0           D   10.11.104.2     meth0",
            "    10.172.10.0/24  Direct  0    0           D   10.172.10.254   vlanif46",
            "      127.0.0.0/8   Direct  0    0           D   127.0.0.1       InLoopBack0");

    @Test
    void shouldParseVlanIpInterfaces() {
        List<OltIpInterface> interfaces = OltInfoParser.parseIpInterfaces(IP_INTERFACE_BRIEF);

        assertEquals(1, interfaces.size(), "meth0/null0 不是三层 VLAN 接口");
        OltIpInterface vlanif = interfaces.get(0);
        assertEquals(Integer.valueOf(46), vlanif.getVlanId());
        assertEquals("vlanif46", vlanif.getName());
        assertEquals("10.172.10.254", vlanif.getIpAddress());
        assertEquals("255.255.255.0", vlanif.getMask(), "页面显示的是点分十进制掩码");
        assertEquals("up", vlanif.getProtocol());
    }

    @Test
    void shouldParseStaticRoutesOnly() {
        List<OltStaticRoute> routes = OltInfoParser.parseStaticRoutes(ROUTING_TABLE);

        assertEquals(1, routes.size(), "Direct 路由不是静态路由");
        assertEquals("0.0.0.0/0", routes.get(0).getDestination());
        assertEquals("10.172.10.1", routes.get(0).getNextHop());
        assertEquals("vlanif46", routes.get(0).getIface());
        assertEquals(Integer.valueOf(60), routes.get(0).getPreference());
    }

    @Test
    void shouldConvertPrefixToMask() {
        assertEquals("255.255.255.0", OltInfoParser.prefixToMask(24));
        assertEquals("255.255.255.252", OltInfoParser.prefixToMask(30));
        assertEquals("0.0.0.0", OltInfoParser.prefixToMask(0));
        assertEquals("255.255.255.255", OltInfoParser.prefixToMask(32));
    }

    @Test
    void shouldDetectUnsupportedOntCommand() {
        assertTrue(OltInfoParser.looksUnsupported("Failure: The command is not found"));
        assertTrue(OltInfoParser.looksUnsupported(""));
        assertFalse(OltInfoParser.looksUnsupported(ONT_INFO_GPON));
    }

    @Test
    void shouldIgnoreOutputWithoutPortOptic() {
        assertTrue(OltInfoParser.parsePorts("Failure: The command is not found").isEmpty());
        assertTrue(OltInfoParser.parsePorts("  In port 0/1, the total of ONTs are: 23, online: 22").isEmpty());
    }

    @Test
    void shouldParseTemperatures() {
        Map<Integer, Integer> temperatures = OltInfoParser.parseTemperatures(MA5683T_TEMPERATURE);
        assertEquals(3, temperatures.size());
        assertEquals(46, temperatures.get(1));
        assertEquals(39, temperatures.get(6));
        // 一块板卡两个传感器取最高温
        assertEquals(40, temperatures.get(8));
        assertTrue(OltInfoParser.parseTemperatures("Error: Unrecognized command").isEmpty());
    }

    @Test
    void shouldParseZteBoardsWithPortCount() {
        List<OltBoard> boards = OltInfoParser.parseBoards(ZTE_BOARDS);
        assertEquals(3, boards.size());
        assertEquals("CFC", boards.get(0).getBoardType());
        assertEquals(8, boards.get(1).getPortCount());
        assertEquals("inservice", boards.get(1).getStatus());
        assertEquals("GPON", boards.get(1).getPortMode());
        assertEquals("active", boards.get(0).getStatus());
    }

    @Test
    void shouldIgnoreHeaderAndUnknownLines() {
        assertTrue(OltInfoParser.parseBoards("SlotID  BoardName  Status\n----\n").isEmpty());
        assertNull(OltInfoParser.extractModel(OltVendor.GENERIC, "no model here"));
    }
}
