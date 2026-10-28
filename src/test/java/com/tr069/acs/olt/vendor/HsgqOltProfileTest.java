package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.model.OltOnt;
import com.tr069.acs.olt.model.OltPort;
import com.tr069.acs.olt.model.OltVlan;
import com.tr069.acs.olt.telnet.OltSnapshot;
import com.tr069.acs.olt.telnet.OltVendor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 鸿升光一体化 OLT：没有板卡概念，整机卡片按型号生成，PON/ONU 数据在配置视图里补采。
 *
 * <p>这块以前散落在 {@code OltInfoParser} 和 {@code OltCliSession} 里，现在都在
 * {@link HsgqOltProfile} 里——以后出 E08 / E16 之类的新型号也只动这个类。</p>
 */
class HsgqOltProfileTest {

    private final HsgqOltProfile profile = new HsgqOltProfile();

    @Test
    void shouldRecognizeHsgq() {
        assertEquals(OltVendor.HSGQ, profile.vendor());
        assertEquals(OltVendor.CliStyle.SHOW, profile.cliStyle());
        assertTrue(profile.matches(" Product : HSGQ-E04"));
        assertTrue(profile.matches("HSGQ-E04_I_V3.5.14C_Rel"));
        assertFalse(profile.matches("ZXAN# show version"));
        // 整机设备：没有板卡可以采，也没有必要关分页（回显都很短）
        assertTrue(profile.wholeDevice());
        assertTrue(profile.pagingCommands().isEmpty());
    }

    @Test
    void shouldBuildWholeDeviceBoardFromModel() {
        OltBoard board = profile.buildWholeDevice("HSGQ-E04");
        assertEquals("HSGQ-E04", board.getBoardType());
        assertEquals(4, board.getPortCount());
        assertEquals("EPON", board.getPortMode());
        assertEquals(4, board.getPorts().size());
        assertEquals("PON01", board.getPorts().get(0).getPortName());
        assertEquals("PON04", board.getPorts().get(3).getPortName());

        // 新型号按名字里的口数来：HSGQ-E08A → 8 个 PON 口，不用改代码
        OltBoard eight = profile.buildWholeDevice("HSGQ-E08A");
        assertEquals(8, eight.getPortCount());
        assertEquals(8, eight.getPorts().size());
        assertEquals("PON08", eight.getPorts().get(7).getPortName());

        // 认不出型号时给现场最常见的那台，端口照样能画出来
        OltBoard unknown = profile.buildWholeDevice(null);
        assertEquals("HSGQ-E04", unknown.getBoardType());
        assertEquals(4, unknown.getPorts().size());
    }

    @Test
    void shouldParsePonInfo() {
        String text = "PON information:\r\n"
                + "PON01   Up        5/5\r\n"
                + "PON02   Up        3/4\r\n"
                + "PON03   Down      0/0\r\n";
        Map<Integer, int[]> counts = HsgqOltProfile.parsePonInfo(text);
        assertEquals(3, counts.size());
        assertEquals(5, counts.get(1)[0]);
        assertEquals(5, counts.get(1)[1]);
        assertEquals(3, counts.get(2)[0]);
        assertEquals(4, counts.get(2)[1]);
        assertTrue(HsgqOltProfile.parsePonInfo(null).isEmpty());
        assertTrue(HsgqOltProfile.parsePonInfo("").isEmpty());
    }

    @Test
    void shouldParseOnuInfoWithMacAsSn() {
        String text = "1/1   c8:21:da:f8:2f:70   Online   TRUE   TRUE   2026/10/25 00:46:10   ONU01/01   NO-DESCRIPTI\r\n"
                + "1/2   38:3a:21:2c:79:14   Offline  TRUE   TRUE   2026/10/25 00:46:10   ONU01/02   NO-DESCRIPTI\r\n"
                + "Total: 8  Online:7  Offline:1\r\n";
        Map<Integer, List<OltOnt>> onts = HsgqOltProfile.parseOnuInfo(text);
        assertEquals(1, onts.size());
        List<OltOnt> pon1 = onts.get(1);
        assertEquals(2, pon1.size());
        // EPON 没有 SN，唯一标识就是 MAC，页面那列写的也是「SN/MAC」
        assertEquals("C8:21:DA:F8:2F:70", pon1.get(0).getSn());
        assertEquals(1, pon1.get(0).getOntId());
        assertTrue(pon1.get(0).getOnline());
        // 名称是 ONU-Name 列，描述是最后一列，两者别混
        assertEquals("ONU01/01", pon1.get(0).getName());
        assertEquals("NO-DESCRIPTI", pon1.get(0).getDescription());
        // Auth / Reg-time 也要带出来：页面「认证状态」「注册时间」两列靠它
        assertEquals(Boolean.TRUE, pon1.get(0).getAuthorized());
        assertEquals("2026/10/25 00:46:10", pon1.get(0).getRegisterTime());
        assertFalse(pon1.get(1).getOnline());
    }

    /**
     * 手册 6.4 / 6.5：进 PON 口视图采 ONU 版本与光功率，补出设备类型、ONU 类型、接收光功率。
     */
    @Test
    void shouldFillOnuVersionAndOpticalInfo() {
        FakeCli cli = new FakeCli(Map.of(
                "configure", "OLT(config)#",
                "show pon-info", "PON01   Up        1/1\r\n",
                "show onu-info all", "1/1   c8:21:da:f8:2f:70   Online   TRUE   TRUE"
                        + "   2026/10/25 00:46:10   ONU01/01   NO-DESCRIPTI\r\n",
                "interface epon 1", "OLT(config-epon-1)#",
                "show onu-version all", "PON/ONU ONU-Name  Mac-Address   Vendor Serial DevType Type"
                        + "   Version   ONU-DESC\r\n"
                        + " 1/1 ONU01/01 38:3a:21:28:8d:ad HSGQ 0000 HGU 1ge V1.9.0-200326 NO-DESCRIPTION\r\n",
                "show optical-info", "PON/ONU ONU-Name Mac-address Temperature Voltage Bias"
                        + "   Tx power  Rx power\r\n"
                        + " 1/1 ONU01/01 38:3a:21:28:8d:ad 47 C 3.30 V 14 mA 2.0194 dBm -7.3119 dBm\r\n"));
        OltBoard board = profile.buildWholeDevice("HSGQ-E04");
        OltSnapshot snapshot = new OltSnapshot();
        List<String> notes = new ArrayList<>();

        profile.collectDetails(cli, snapshot, List.of(board), notes, 5000);

        OltOnt ont = board.getPorts().get(0).getOnts().get(0);
        assertEquals("HGU", ont.getDeviceType());
        assertEquals("1ge", ont.getOnuType());
        // 一行两个 dBm，页面「接收光功率」要的是后一个
        assertEquals("-7.3119 dBm", ont.getRxPower());
        // 采完要退回来，不然后面 VLAN 那几条命令会打在 epon 视图里
        assertTrue(cli.sent.contains("exit"));
        assertTrue(notes.stream().noneMatch(note -> note.contains("光功率")), notes.toString());
    }

    @Test
    void shouldFillPortsAndVlansFromConfigView() {
        FakeCli cli = new FakeCli(Map.of(
                "enable", "OLT#",
                "configure", "OLT(config)#",
                "show pon-info", "PON01   Up        5/5\r\nPON02   Down      0/2\r\n",
                "show onu-info all", "1/1   c8:21:da:f8:2f:70   Online   TRUE   TRUE   ONU01/01\r\n",
                "show running-config", "!\r\n"
                        + "vlan standard 100\r\n"
                        + " port epon 1,2 untagged\r\n"
                        + "!\r\n"
                        + "vlan standard 200 202\r\n"
                        + " port ge 1 tagged\r\n"
                        + "!\r\n",
                "show port state all", "Port       Status    Speed\r\n"
                        + "epon1      Up        1000\r\n"
                        + "epon2      Down      1000\r\n"
                        + "ge1        Up        1000\r\n",
                "interface epon 1", "OLT(config-epon-1)#",
                "show onu-version all", " 1/1 ONU01/01 38:3a:21:28:8d:ad HSGQ 0000 HGU 1ge V1.9.0 NO-DESC\r\n",
                "show optical-info", " 1/1 ONU01/01 38:3a:21:28:8d:ad 47 C 3.30 V 14 mA 2.0194 dBm -7.3119 dBm\r\n"));
        OltBoard board = profile.buildWholeDevice("HSGQ-E04");
        OltSnapshot snapshot = new OltSnapshot();
        List<String> notes = new ArrayList<>();

        profile.collectDetails(cli, snapshot, List.of(board), notes, 5000);

        // enable → config/configure，逐口补 ONU 版本与光功率后退出，再采 VLAN，顺序不能错
        assertEquals(List.of("enable", "config", "configure", "show pon-info", "show onu-info all",
                "interface epon 1", "show onu-version all", "show optical-info", "exit",
                "show running-config", "show port state all", "quit"), cli.sent);
        assertTrue(notes.isEmpty());
        OltPort pon1 = board.getPorts().get(0);
        assertEquals(5, pon1.getOnuOnline());
        assertEquals(5, pon1.getOnuTotal());
        assertEquals(1, pon1.getOnts().size());
        OltPort pon2 = board.getPorts().get(1);
        assertEquals(0, pon2.getOnuOnline());
        assertEquals(2, pon2.getOnuTotal());
        assertNull(pon2.getOnts(), "这条 PON 口没 ONU 明细就留空，页面显示「-」而不是 0");

        // VLAN 快照：一条 show running-config 同时带回 VLAN 与成员端口
        assertNotNull(snapshot.getVlanInfo());
        List<OltVlan> vlans = snapshot.getVlanInfo().getVlans();
        assertEquals(4, vlans.size());
        assertEquals(Integer.valueOf(100), vlans.get(0).getVlanId());
        assertEquals("EPON01,EPON02", vlans.get(0).getUntaggedPorts());
        // 一段连续 ID（vlan standard 200 202）展开成 3 个 VLAN，成员端口共用
        assertEquals(List.of(200, 201, 202),
                vlans.subList(1, 4).stream().map(OltVlan::getVlanId).toList());
        assertEquals("GE01", vlans.get(1).getTaggedPorts());

        // 可加 VLAN 的端口清单：PON 口按型号补（show port state all 只回上联口），上联口按采集结果
        assertEquals(List.of("PON01", "PON02", "PON03", "PON04", "GE01"),
                snapshot.getVlanInfo().getMemberPorts().stream()
                        .map(port -> port.getLabel()).toList());
    }

    @Test
    void shouldNoteWhenConfigViewUnreachable() {
        FakeCli cli = new FakeCli(Map.of("enable", "OLT#", "config", "?", "configure", "?"));
        List<String> notes = new ArrayList<>();

        profile.collectDetails(cli, new OltSnapshot(), List.of(profile.buildWholeDevice("HSGQ-E04")), notes, 5000);

        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("进不了配置视图"));
    }

    @Test
    void shouldNoteWhenNothingCollected() {
        FakeCli cli = new FakeCli(Map.of("configure", "OLT(config)#"));
        List<String> notes = new ArrayList<>();

        profile.collectDetails(cli, new OltSnapshot(), List.of(profile.buildWholeDevice("HSGQ-E04")), notes, 5000);

        // ONU、VLAN、上联口各一条：设备什么都没回，页面要能看出是哪几样没采到
        assertEquals(3, notes.size());
        assertTrue(notes.get(0).contains("ONU"));
        assertTrue(notes.get(1).contains("VLAN"));
        assertTrue(notes.get(2).contains("上联口"));
    }

    @Test
    void shouldFallBackToShowVlanTable() {
        // running-config 采不到（老固件 / 权限不够）时退回 vlan 视图里的 Attribute/Value 表
        FakeCli cli = new FakeCli(Map.of(
                "configure", "OLT(config)#",
                "show vlan", "Attribute       Value\r\n"
                        + "VLAN ID         :100\r\n"
                        + "Tagged Ports    :\r\n"
                        + "Untagged Ports  :GE03\r\n"
                        + "Default Vlan Ports :\r\n"));
        OltSnapshot snapshot = new OltSnapshot();
        List<String> notes = new ArrayList<>();

        profile.collectDetails(cli, snapshot, List.of(profile.buildWholeDevice("HSGQ-E04")), notes, 5000);

        assertNotNull(snapshot.getVlanInfo());
        OltVlan vlan = snapshot.getVlanInfo().getVlans().get(0);
        assertEquals(Integer.valueOf(100), vlan.getVlanId());
        assertEquals("GE03", vlan.getUntaggedPorts());
        assertNull(vlan.getTaggedPorts());
    }

    @Test
    void shouldParseVlanCommands() {
        // 建单个 / 建一段：vlan standard 之后要 exit 回全局视图，免得后面的命令发在 vlan 视图里
        assertEquals(List.of("vlan standard 100", "exit"), profile.createVlanCommands(100, 100));
        assertEquals(List.of("vlan standard 101 110", "exit"), profile.createVlanCommands(101, 110));

        // 删：连续的合成一条，断开的各一条
        assertEquals(List.of("no vlan 100"), profile.deleteVlanCommands(List.of(100)));
        assertEquals(List.of("no vlan 101 103"), profile.deleteVlanCommands(List.of(101, 102, 103)));
        assertEquals(List.of("no vlan 101 103", "no vlan 110"),
                profile.deleteVlanCommands(List.of(101, 102, 103, 110)));
        assertEquals(List.of("no vlan 100", "no vlan 300"), profile.deleteVlanCommands(List.of(300, 100)));
        assertTrue(profile.deleteVlanCommands(List.of()).isEmpty());
        assertTrue(profile.supportsVlanConfig());
    }

    @Test
    void shouldGiveWholeDeviceVlanCommands() {
        assertEquals("vlan standard 100", HsgqOltProfile.cmdCreateVlan(100));
        assertEquals("vlan standard 101 110", HsgqOltProfile.cmdCreateVlanBatch(101, 110));
        assertEquals("no vlan 100", HsgqOltProfile.cmdDeleteVlan(100));
        assertEquals("no vlan all", HsgqOltProfile.cmdDeleteAllVlan());
        assertEquals("port ge 3 untagged", HsgqOltProfile.cmdAddVlanPort("ge", "3", "untagged"));
        assertEquals("no port ge 3", HsgqOltProfile.cmdRemoveVlanPort("ge", "3"));
        assertEquals("save", HsgqOltProfile.cmdSave());
    }

    /** 假会话：按命令给回显，顺便把发过的命令记下来（用来校验顺序） */
    private static class FakeCli implements OltCli {

        private final Map<String, String> replies;
        final List<String> sent = new ArrayList<>();

        FakeCli(Map<String, String> replies) {
            this.replies = new HashMap<>(replies);
        }

        @Override
        public String run(String command, int timeoutMs) {
            sent.add(command);
            return replies.getOrDefault(command, "");
        }
    }
}
