package com.tr069.acs.olt.service;

import com.tr069.acs.olt.model.OltAction;
import com.tr069.acs.olt.model.OltActionParam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令动作的下发前处理：视图切换、占位符渲染、可选段、参数校验。
 *
 * <p>这里错了就是往设备上发错命令，所以每个分支都得有用例。</p>
 */
class OltActionRenderTest {

    private static OltAction action(OltAction.View view, List<OltActionParam> params, String... commands) {
        return new OltAction("test", "章节", "功能", view, List.of(commands), params, false, false, null);
    }

    @Test
    void configActionShouldRenderDirectly() {
        OltAction action = action(OltAction.View.CONFIG,
                List.of(OltActionParam.number("vlanId", "VLAN ID", 1, 4094, null)),
                "vlan standard {vlanId}");
        assertEquals(List.of("vlan standard 100"),
                OltService.planCommands(action, Map.of("vlanId", "100")));
    }

    @Test
    void vlanViewShouldEnterAndExit() {
        OltAction action = action(OltAction.View.VLAN,
                List.of(OltActionParam.number("vlanId", "VLAN ID", 1, 4094, null),
                        OltActionParam.portType(),
                        OltActionParam.ports("ports", "端口列表", null),
                        OltActionParam.choice("mode", "方式", List.of("tagged", "untagged"), "untagged", null)),
                "port {portType} {ports} {mode}");
        assertEquals(List.of("vlan standard 100", "port epon 1,2 untagged", "exit"),
                OltService.planCommands(action, Map.of("vlanId", "100", "portType", "epon",
                        "ports", "1,2", "mode", "untagged")));
    }

    @Test
    void portViewShouldEnterInterface() {
        OltAction action = action(OltAction.View.PORT,
                List.of(OltActionParam.portType(), OltActionParam.portNo("端口号")),
                "port state enable");
        assertEquals(List.of("interface epon 1", "port state enable", "exit"),
                OltService.planCommands(action, Map.of("portType", "epon", "portNo", "1")));
    }

    @Test
    void onuViewShouldEnterWithPonAndOnu() {
        OltAction action = action(OltAction.View.ONU,
                List.of(OltActionParam.number("ponId", "PON", 1, 16, null),
                        OltActionParam.number("onuId", "ONU", 1, 64, null)),
                "name test");
        assertEquals(List.of("interface onu 1/2", "name test", "exit"),
                OltService.planCommands(action, Map.of("ponId", "1", "onuId", "2")));
    }

    @Test
    void optionalPartShouldBeDroppedWhenNotFilled() {
        OltAction action = action(OltAction.View.CONFIG,
                List.of(OltActionParam.text("serverIp", "主服务器", null),
                        OltActionParam.optionalText("backupIp", "备服务器", null)),
                "ntp enable ntp-server-ip {serverIp}[ backup-server-ip {backupIp}]");
        assertEquals(List.of("ntp enable ntp-server-ip 1.1.1.1"),
                OltService.planCommands(action, Map.of("serverIp", "1.1.1.1")));
    }

    @Test
    void optionalPartShouldBeKeptWhenFilled() {
        OltAction action = action(OltAction.View.CONFIG,
                List.of(OltActionParam.text("serverIp", "主服务器", null),
                        OltActionParam.optionalText("backupIp", "备服务器", null)),
                "ntp enable ntp-server-ip {serverIp}[ backup-server-ip {backupIp}]");
        assertEquals(List.of("ntp enable ntp-server-ip 1.1.1.1 backup-server-ip 2.2.2.2"),
                OltService.planCommands(action, Map.of("serverIp", "1.1.1.1", "backupIp", "2.2.2.2")));
    }

    @Test
    void twoOptionalPartsShouldBeIndependent() {
        // user add test test123 common reenter 4            （两个都填）
        // user add test test123 common                      （都不填）
        OltAction action = action(OltAction.View.CONFIG,
                List.of(OltActionParam.text("userName", "用户名", null),
                        OltActionParam.text("password", "密码", null),
                        OltActionParam.choice("level", "权限", List.of("admin", "common"), "common", null),
                        OltActionParam.optionalNumber("reenter", "同时登录数", 1, 16, null),
                        OltActionParam.optionalText("info", "描述", null)),
                "user add {userName} {password} {level}[ reenter {reenter}][ {info}]");
        assertEquals(List.of("user add test test123 common reenter 4"),
                OltService.planCommands(action, Map.of("userName", "test", "password", "test123",
                        "level", "common", "reenter", "4")));
        assertEquals(List.of("user add test test123 common"),
                OltService.planCommands(action, Map.of("userName", "test", "password", "test123",
                        "level", "common")));
    }

    @Test
    void missingRequiredParamShouldFail() {
        OltAction action = action(OltAction.View.CONFIG,
                List.of(OltActionParam.number("vlanId", "VLAN ID", 1, 4094, null)),
                "vlan standard {vlanId}");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OltService.resolveParams(action, Map.of()));
        assertTrue(error.getMessage().contains("VLAN ID"));
    }

    @Test
    void outOfRangeNumberShouldFail() {
        OltActionParam param = OltActionParam.number("vlanId", "VLAN ID", 1, 4094, null);
        assertThrows(IllegalArgumentException.class, () -> OltService.checkParam(param, "4095"));
        assertThrows(IllegalArgumentException.class, () -> OltService.checkParam(param, "abc"));
        assertEquals("100", OltService.checkParam(param, "100"));
    }

    @Test
    void commandInjectionShouldBeRejected() {
        // 命令是一条条发下去的，参数里混进换行等于多发一条命令
        OltActionParam text = OltActionParam.text("name", "名称", null);
        assertThrows(IllegalArgumentException.class, () -> OltService.checkParam(text, "a\nreboot"));
        assertThrows(IllegalArgumentException.class, () -> OltService.checkParam(text, "a;reboot"));
        assertThrows(IllegalArgumentException.class, () -> OltService.checkParam(text, "a b;c"));
        assertEquals("3栋2单元", OltService.checkParam(text, "3栋2单元"));

        OltActionParam ports = OltActionParam.ports("ports", "端口列表", null);
        assertThrows(IllegalArgumentException.class, () -> OltService.checkParam(ports, "1;reboot"));
        assertEquals("1,3,4-8", OltService.checkParam(ports, "1,3,4-8"));
    }

    @Test
    void enumShouldRejectUnknownOption() {
        OltActionParam mode = OltActionParam.choice("mode", "模式", List.of("enable", "disable"), "enable", null);
        assertThrows(IllegalArgumentException.class, () -> OltService.checkParam(mode, "reboot"));
        assertEquals("disable", OltService.checkParam(mode, "disable"));
    }

    @Test
    void renderShouldRejectUndeclaredPlaceholder() {
        OltAction action = action(OltAction.View.CONFIG, List.of(), "vlan standard {vlanId}");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OltService.planCommands(action, Map.of()));
        assertTrue(error.getMessage().contains("vlanId"));
    }

    @Test
    void defaultValueShouldBeApplied() {
        OltAction action = new OltAction("test", "章节", "功能", OltAction.View.CONFIG,
                List.of("vlan mode {mode}"),
                List.of(OltActionParam.choice("mode", "模式", List.of("access", "trunk"), "access", null)),
                false, false, null);
        Map<String, String> params = OltService.resolveParams(action, Map.of("mode", ""));
        assertEquals("access", params.get("mode"));
        assertFalse(params.isEmpty());
    }
}
