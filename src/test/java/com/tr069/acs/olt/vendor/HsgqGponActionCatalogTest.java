package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.model.OltAction;
import com.tr069.acs.olt.model.OltActionParam;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GPON 命令目录的体检，规则与 EPON 那份一致：编号唯一、章节不漏、模板占位符都能对上参数、
 * 视图切换所需参数齐全、破坏性操作有标记。
 */
class HsgqGponActionCatalogTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final List<OltAction> actions = HsgqGponActionCatalog.actions();

    @Test
    void codesShouldBeUnique() {
        Set<String> codes = new HashSet<>();
        actions.forEach(action -> assertTrue(codes.add(action.code()), "动作编号重复：" + action.code()));
    }

    @Test
    void shouldCoverEveryChapterOfTheManual() {
        // 手册 15 章：第 1 章是格式说明、第 15 章是缩写术语，其余每章都要有动作
        Set<String> chapters = actions.stream()
                .map(action -> action.chapter().replaceAll("^(\\d+).*", "$1"))
                .collect(Collectors.toSet());
        assertEquals(Set.of("2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14"), chapters);
    }

    @Test
    void commandPlaceholdersShouldBeDeclaredAsParams() {
        for (OltAction action : actions) {
            Set<String> keys = action.params().stream().map(OltActionParam::key).collect(Collectors.toSet());
            for (String command : action.commands()) {
                Matcher matcher = PLACEHOLDER.matcher(command);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    assertTrue(keys.contains(key),
                            action.code() + " 的命令 " + command + " 用了未声明的参数 " + key);
                }
            }
        }
    }

    @Test
    void paramKeysShouldNotRepeatInsideOneAction() {
        // 同一个 key 出现两次，页面上只会渲染一个输入框，命令里两处填同一个值——多半是抄错了
        for (OltAction action : actions) {
            Set<String> keys = new HashSet<>();
            for (OltActionParam param : action.params()) {
                assertTrue(keys.add(param.key()), action.code() + " 的参数 " + param.key() + " 重复");
            }
        }
    }

    @Test
    void viewSwitchShouldHaveItsParams() {
        for (OltAction action : actions) {
            Set<String> keys = action.params().stream().map(OltActionParam::key).collect(Collectors.toSet());
            switch (action.view()) {
                case VLAN, VLANIF, MCAST -> assertTrue(keys.contains("vlanId"),
                        action.code() + " 要进 VLAN 相关视图，缺 vlanId");
                case PORT -> {
                    assertTrue(keys.contains("portType"), action.code() + " 缺 portType");
                    assertTrue(keys.contains("portNo"), action.code() + " 缺 portNo");
                }
                case GPON, PON -> assertTrue(keys.contains("ponId"), action.code() + " 缺 ponId");
                case LINE_PROFILE, SRV_PROFILE -> assertTrue(keys.contains("profileId"),
                        action.code() + " 缺 profileId");
                case ACL -> assertTrue(keys.contains("aclId"), action.code() + " 缺 aclId");
                default -> {
                }
            }
        }
    }

    @Test
    void commandsShouldNotLeakAngleBrackets() {
        // 手册表格里的 <VLAN ID> 是占位说明，抄进模板就会原样发给设备
        for (OltAction action : actions) {
            for (String command : action.commands()) {
                assertFalse(command.contains("<") || command.contains(">"),
                        action.code() + " 的命令里混进了尖括号：" + command);
            }
        }
    }

    @Test
    void destructiveActionsShouldBeMarked() {
        List<String> dangerous = List.of("gpon.vlan.delete.all", "gpon.erase", "gpon.reboot",
                "gpon.user.default", "gpon.inband.vlanif.del", "gpon.dba.delete.all",
                "gpon.line.delete", "gpon.srv.delete", "gpon.sp.delete.all", "gpon.mac.flush.all",
                "gpon.arp.flush", "gpon.tr.clear", "gpon.ont.delete", "gpon.ont.delete.all",
                "gpon.ont.deactivate", "gpon.ont.reset");
        for (String code : dangerous) {
            OltAction action = actions.stream().filter(item -> code.equals(item.code())).findFirst()
                    .orElseThrow(() -> new AssertionError("缺少动作：" + code));
            assertTrue(action.danger(), code + " 是破坏性操作，要标 danger");
        }
    }

    @Test
    void queryActionsShouldNotBeDangerous() {
        actions.stream().filter(OltAction::query)
                .forEach(action -> assertFalse(action.danger(), action.code() + " 是查询，不该标 danger"));
    }

    @Test
    void ontsShouldUseGponView() {
        // 第 9 章 ONT 命令全在 GPON 口视图里下发，没有单独的 ONT 视图
        actions.stream().filter(action -> action.code().startsWith("gpon.ont."))
                .filter(action -> !action.query() && !action.code().contains("authorize"))
                .forEach(action -> assertEquals(OltAction.View.GPON, action.view(),
                        action.code() + " 应在 GPON 口视图执行"));
    }
}
