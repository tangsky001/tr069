package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.model.OltVlanPort;
import com.tr069.acs.olt.telnet.OltSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 鸿升光「创建 VLAN」弹窗背后的两件事：勾的端口怎么变成命令、可勾的端口从哪来。
 */
class HsgqVlanProvisionTest {

    private final HsgqOltProfile epon = new HsgqOltProfile();

    private final HsgqGponOltProfile gpon = new HsgqGponOltProfile();

    @Test
    void shouldBuildOneCommandPerPortType() {
        // 勾了 epon1、epon2、ge3 tagged 和 ge4 untagged：单 VLAN 不用先批量建一遍，进一次视图就够
        assertEquals(List.of(
                        "vlan standard 100",
                        "port epon 1-2 tagged",
                        "port ge 3 tagged",
                        "port ge 4 untagged",
                        "exit"),
                epon.createVlanCommands(100, 100, List.of("epon1", "epon2", "ge3"), List.of("ge4")));
    }

    @Test
    void shouldOnlyCreateVlansWhenNoPortPicked() {
        assertEquals(List.of("vlan standard 101 105", "exit"),
                epon.createVlanCommands(101, 105, List.of(), List.of()));
        assertEquals(List.of("vlan standard 101 105", "exit"),
                epon.createVlanCommands(101, 105, null, null));
    }

    @Test
    void shouldRepeatPortCommandsForEveryVlanInRange() {
        assertEquals(List.of(
                        "vlan standard 101 102",
                        "exit",
                        "vlan standard 101",
                        "port epon 1 tagged",
                        "exit",
                        "vlan standard 102",
                        "port epon 1 tagged",
                        "exit"),
                epon.createVlanCommands(101, 102, List.of("epon1"), List.of()));
    }

    @Test
    void shouldMergeContinuousPortNumbers() {
        // 1,3,4,5 这种连着的一串写 1,3-5，设备认，命令也短
        List<String> commands = epon.createVlanCommands(100, 100,
                List.of("epon1", "epon3", "epon4", "epon5"), List.of());
        assertTrue(commands.contains("port epon 1,3-5 tagged"), commands.toString());
    }

    @Test
    void gponShouldNotOfferPonPorts() {
        // GPON 的 PON 口不做二层成员，业务走 service-port，勾了也会被设备拒绝
        assertEquals(Set.of("ge", "xge"), gpon.vlanMemberPortTypes());
        assertEquals(Set.of("epon", "ge", "xge"), epon.vlanMemberPortTypes());
    }

    @Test
    void shouldParsePortStateList() {
        String text = "Port       Status    Speed\r\n"
                + "epon1      Up        1000\r\n"
                + "epon2      Down      1000\r\n"
                + "ge1        Up        1000\r\n"
                + "ge3        Up        auto\r\n";
        List<OltVlanPort> ports = HsgqOltProfile.parsePortStatePorts(text);

        assertEquals(List.of("PON01", "PON02", "GE01", "GE03"),
                ports.stream().map(OltVlanPort::getLabel).collect(Collectors.toList()));
    }

    @Test
    void shouldNotMistakeWordsForPorts() {
        // page / large 里也有个 ge，别把它当成端口
        assertTrue(HsgqOltProfile.parsePortStatePorts("no page size, large mtu 2000").isEmpty());
        assertTrue(HsgqOltProfile.parsePortStatePorts("").isEmpty());
        assertTrue(HsgqOltProfile.parsePortStatePorts(null).isEmpty());
    }

    @Test
    void shouldAlwaysOfferPonPortsEvenWhenPortStateDoesNotListThem() {
        // 现场这台 show port state all 只回上联电口，PON 口不在里面——弹窗不能因此少一排 PON
        OltSnapshot snapshot = new OltSnapshot();
        snapshot.setModel("HSGQ-E04");
        List<OltVlanPort> ports = epon.vlanMemberPorts(snapshot,
                "Port       Status\r\nge1        Up\r\nge2        Up\r\nge8        Down\r\n");

        assertEquals(List.of("PON01", "PON02", "PON03", "PON04", "GE01", "GE02", "GE08"), labels(ports));
    }

    @Test
    void shouldTakePonCountFromBoardWhenModelUnknown() {
        // 型号解析不出来时按板卡采到的 PON 口数给，别一律 4 个
        OltBoard board = epon.buildWholeDevice("HSGQ-E08");
        OltSnapshot snapshot = new OltSnapshot();
        snapshot.setBoards(List.of(board));

        List<OltVlanPort> ports = epon.vlanMemberPorts(snapshot, "");
        assertEquals(8, ports.size());
        assertEquals("PON08", ports.get(7).getLabel());

        // 上联口实在采不到时兜底 4 个 GE（collectVlans 里补，避免弹窗只剩 PON）
        assertEquals(4, epon.fallbackUplinkPorts().size());
    }

    @Test
    void gponShouldNotOfferPonPortsAtAll() {
        OltSnapshot snapshot = new OltSnapshot();
        snapshot.setModel("Neutral-G16");
        List<OltVlanPort> ports = gpon.vlanMemberPorts(snapshot, "gpon1 Up\r\ngpon2 Up\r\nge1 Up\r\n");

        assertEquals(List.of("GE01"), labels(ports));
        ports.forEach(port -> assertFalse("gpon".equals(port.getType()), "GPON 不该给出 PON 口"));
    }

    private List<String> labels(List<OltVlanPort> ports) {
        return ports.stream().map(OltVlanPort::getLabel).collect(Collectors.toList());
    }
}
