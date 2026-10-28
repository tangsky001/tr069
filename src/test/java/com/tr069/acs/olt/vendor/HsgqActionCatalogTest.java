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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令动作目录的体检：编号别重复、章节别漏、参数与命令模板对得上。
 *
 * <p>这份目录是照着 141 页手册手工录的，最容易出的错就是模板里写了个 {@code {vlanid}}、
 * 参数里却叫 {@code vlanId}——那种错要到现场下发时才炸，所以在这儿先卡住。</p>
 */
class HsgqActionCatalogTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final List<OltAction> actions = HsgqActionCatalog.actions();

    @Test
    void codesShouldBeUnique() {
        Set<String> codes = new HashSet<>();
        actions.forEach(action -> assertTrue(codes.add(action.code()), "动作编号重复：" + action.code()));
    }

    @Test
    void shouldCoverEveryChapterOfTheManual() {
        // 手册 13 章：第 1 章是格式说明、第 13 章是缩写术语，其余每章都要有动作
        Set<String> chapters = actions.stream()
                .map(action -> action.chapter().replaceAll("^(\\d+).*", "$1"))
                .collect(Collectors.toSet());
        assertEquals(Set.of("2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"), chapters);
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
                case PON -> assertTrue(keys.contains("ponId"), action.code() + " 缺 ponId");
                case ONU -> {
                    assertTrue(keys.contains("ponId"), action.code() + " 缺 ponId");
                    assertTrue(keys.contains("onuId"), action.code() + " 缺 onuId");
                }
                case ACL -> assertTrue(keys.contains("aclId"), action.code() + " 缺 aclId");
                default -> {
                }
            }
        }
    }

    @Test
    void destructiveActionsShouldBeMarked() {
        List<String> dangerous = List.of("vlan.deleteAll", "sys.erase", "sys.reboot", "sys.user.default",
                "mac.flush.all", "arp.flush", "onu.deregister", "timerange.clear");
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
    void everyActionShouldHaveLabelAndNote() {
        for (OltAction action : actions) {
            assertNotNull(action.label(), action.code() + " 缺功能名");
            assertFalse(action.commands().isEmpty(), action.code() + " 没有命令");
        }
    }
}
