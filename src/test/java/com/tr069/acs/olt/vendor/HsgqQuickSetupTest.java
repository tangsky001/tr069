package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.model.OltQuickSetupRequest;
import com.tr069.acs.olt.model.OltTr069ProfileRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一键开局的命令怎么编：建 VLAN、配端口 VLAN、TR-069 模板，三步可以任意组合，
 * 最后统一补一条 {@code save} 存盘。
 *
 * <p>顺序是设备逼出来的：{@code vlan trunk} 要求 VLAN 已存在，而 VLAN 视图下的
 * {@code port ... tagged} 只对 hybrid 端口有效，所以必须「先建 VLAN → 再配端口」，
 * 端口成员也只能在端口视图里配。</p>
 */
class HsgqQuickSetupTest {

    private final HsgqOltProfile epon = new HsgqOltProfile();

    private final HsgqGponOltProfile gpon = new HsgqGponOltProfile();

    @Test
    void shouldSetAllPonAndGeToTrunk() {
        OltQuickSetupRequest request = new OltQuickSetupRequest();
        request.setMode("trunk");
        request.setPorts(List.of(port("epon", 1), port("epon", 2), port("ge", 1)));

        assertEquals(List.of(
                "interface epon 1",
                "vlan mode trunk",
                "exit",
                "interface epon 2",
                "vlan mode trunk",
                "exit",
                "interface ge 1",
                "vlan mode trunk",
                "exit",
                "save"), epon.quickSetupCommands(request));
    }

    @Test
    void shouldAppendTrunkVlanListWhenGiven() {
        OltQuickSetupRequest request = new OltQuickSetupRequest();
        request.setMode("trunk");
        request.setVlanList("100-200,300");
        request.setPorts(List.of(port("ge", 1)));

        assertEquals(List.of(
                "interface ge 1",
                "vlan mode trunk",
                "vlan trunk 100-200,300",
                "exit",
                "save"), epon.quickSetupCommands(request));
    }

    @Test
    void shouldUseVlanAccessForAccessMode() {
        OltQuickSetupRequest request = new OltQuickSetupRequest();
        request.setMode("access");
        request.setVlanList("100");
        request.setPorts(List.of(port("ge", 1)));

        // access 端口只属于一个 VLAN，命令是 vlan access <id>，不是 vlan trunk
        assertEquals(List.of("interface ge 1", "vlan mode access", "vlan access 100", "exit", "save"),
                epon.quickSetupCommands(request));
    }

    @Test
    void shouldUseVlanHybridForHybridMode() {
        OltQuickSetupRequest request = new OltQuickSetupRequest();
        request.setMode("hybrid");
        request.setVlanList("41,45");
        request.setPorts(List.of(port("epon", 1)));

        assertEquals(List.of("interface epon 1", "vlan mode hybrid", "vlan hybrid 41,45 tagged", "exit", "save"),
                epon.quickSetupCommands(request));
    }

    @Test
    void shouldCreateVlanBatchWhenNoMemberPorts() {
        OltQuickSetupRequest request = new OltQuickSetupRequest();
        request.setCreateVlanIds("100-102");

        // 连续段一条命令建完
        assertEquals(List.of("vlan standard 100 102", "exit", "save"), epon.quickSetupCommands(request));
    }

    @Test
    void shouldCreateVlansBeforeTouchingPorts() {
        OltQuickSetupRequest request = new OltQuickSetupRequest();
        request.setCreateVlanIds("100,200");
        request.setAddCreatedToPorts(true);
        request.setPorts(List.of(port("epon", 1), port("epon", 4), port("ge", 2)));

        // VLAN 必须先建出来，否则端口上的 vlan trunk 会被设备以
        // "Specified VLANs not exsits" 顶回来
        assertEquals(List.of(
                "vlan standard 100", "exit",
                "vlan standard 200", "exit",
                "interface epon 1", "vlan mode trunk", "vlan trunk 100,200", "exit",
                "interface epon 4", "vlan mode trunk", "vlan trunk 100,200", "exit",
                "interface ge 2", "vlan mode trunk", "vlan trunk 100,200", "exit",
                "save"), epon.quickSetupCommands(request));
    }

    @Test
    void shouldNotBindMembersInsideVlanView() {
        // trunk 端口在 VLAN 视图下加成员会被设备拒绝：port mode is not hybrid
        OltQuickSetupRequest request = new OltQuickSetupRequest();
        request.setCreateVlanIds("100");
        request.setAddCreatedToPorts(true);
        request.setPorts(List.of(port("epon", 1)));

        List<String> commands = epon.quickSetupCommands(request);
        assertFalse(commands.stream().anyMatch(command -> command.startsWith("port ")), commands.toString());
    }

    @Test
    void shouldMergeManualAndCreatedVlansIntoTrunkList() {
        OltQuickSetupRequest request = new OltQuickSetupRequest();
        request.setMode("trunk");
        request.setVlanList("200-210");
        request.setCreateVlanIds("41,45-46");
        request.setAddCreatedToPorts(true);
        request.setPorts(List.of(port("ge", 1)));

        // 页面这两个字段共用一份清单，合并后要排序去重，不能出现 41,45-46,41,45-46
        assertEquals(List.of(
                "vlan standard 41", "exit",
                "vlan standard 45 46", "exit",
                "interface ge 1", "vlan mode trunk", "vlan trunk 41,45-46,200-210", "exit",
                "save"), epon.quickSetupCommands(request));
    }

    @Test
    void shouldNotRepeatVlansWhenAllowAndCreateShareOneList() {
        // 页面上「放通」和「顺带创建」共用同一个输入框，传上来就是同一份清单
        OltQuickSetupRequest request = new OltQuickSetupRequest();
        request.setMode("trunk");
        request.setVlanList("41,45-46");
        request.setCreateVlanIds("41,45-46");
        request.setAddCreatedToPorts(true);
        request.setPorts(List.of(port("ge", 1)));

        List<String> commands = epon.quickSetupCommands(request);
        assertEquals(List.of("vlan trunk 41,45-46"), commands.stream()
                .filter(command -> command.startsWith("vlan trunk")).toList(), commands.toString());
    }

    @Test
    void shouldCombineThreeStepsInOrder() {
        OltQuickSetupRequest request = new OltQuickSetupRequest();
        request.setMode("trunk");
        request.setPorts(List.of(port("ge", 1)));
        request.setCreateVlanIds("100");
        request.setTr069(tr069Request());

        assertEquals(List.of(
                "vlan standard 100", "exit",
                "interface ge 1",
                "vlan mode trunk",
                "exit",
                "onu-tr069-profile add profile-id 20 profile-name acs"
                        + " url http://192.168.1.10:9090 acs-password acs acs-username acs"
                        + " port 9090 password acs username acs",
                "save"), epon.quickSetupCommands(request));
    }

    @Test
    void shouldReturnEmptyWhenNothingSelected() {
        assertTrue(epon.quickSetupCommands(new OltQuickSetupRequest()).isEmpty());
        assertTrue(epon.quickSetupCommands(null).isEmpty());
    }

    @Test
    void shouldConfigureGponPonPortsAsPortsNotVlanMembers() {
        OltQuickSetupRequest request = new OltQuickSetupRequest();
        request.setCreateVlanIds("100");
        request.setAddCreatedToPorts(true);
        // GPON 的 PON 口不做 VLAN 成员，但端口视图里照样可以配 vlan trunk
        request.setPorts(List.of(port("gpon", 1), port("ge", 1)));

        List<String> commands = gpon.quickSetupCommands(request);

        assertEquals(List.of(
                "vlan standard 100", "exit",
                "interface gpon 1", "vlan mode trunk", "vlan trunk 100", "exit",
                "interface ge 1", "vlan mode trunk", "vlan trunk 100", "exit",
                "save"), commands);
        assertFalse(commands.stream().anyMatch(command -> command.contains("gpon 1 tagged")), commands.toString());
    }

    private static OltQuickSetupRequest.PortRef port(String type, int no) {
        OltQuickSetupRequest.PortRef ref = new OltQuickSetupRequest.PortRef();
        ref.setType(type);
        ref.setNo(no);
        return ref;
    }

    private static OltTr069ProfileRequest tr069Request() {
        OltTr069ProfileRequest tr069 = new OltTr069ProfileRequest();
        tr069.setProfileId(20);
        tr069.setProfileName("acs");
        tr069.setUrl("http://192.168.1.10:9090");
        tr069.setAcsUsername("acs");
        tr069.setAcsPassword("acs");
        tr069.setUsername("acs");
        tr069.setPassword("acs");
        tr069.setPort(9090);
        return tr069;
    }
}
