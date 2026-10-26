package com.tr069.acs.exec;

import com.tr069.acs.exec.model.IntentPlan;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯重启计划（OTHER / Device_Mgmt）。
 *
 * <p>重启是标准 TR-069 RPC，不依赖任何型号适配：模板勾了「下发业务完成后重启光猫」，
 * 就该渲染成一张合法的空计划，执行收尾时发 Reboot。以前这里直接报
 * 「该业务还没有适配模板」，用户只想重启设备却被适配流程挡死。</p>
 */
class RebootOnlyPlanTest {

    private final SqlitePlanRenderer renderer =
            new SqlitePlanRenderer(null, null, null, null, null, null);

    @Test
    void 只勾重启时渲染为可下发的空计划() {
        IntentPlan plan = renderer.render("Device_Mgmt", "OTHER", null,
                Map.of(), 1, Map.of("reboot_after", "true"));

        assertTrue(plan.errors().isEmpty(), "勾了重启就不该再报未适配: " + plan.errors());
        assertTrue(plan.parameters().isEmpty());
        assertTrue(plan.isRebootOnly());
        assertTrue(plan.deliverable());
    }

    @Test
    void 值来自本次下发变量时同样生效() {
        IntentPlan plan = renderer.render("Device_Mgmt", "OTHER", null,
                Map.of("reboot_after", "true"), 1, Map.of());

        assertTrue(plan.deliverable());
    }

    @Test
    void 没勾任何动作时给出明确错误() {
        IntentPlan plan = renderer.render("Device_Mgmt", "OTHER", null,
                Map.of(), 1, Map.of("multicast_mode", "ignore"));

        assertFalse(plan.deliverable());
        assertTrue(plan.errors().get(0).contains("没有启用任何动作"),
                "实际错误: " + plan.errors());
    }

    @Test
    void 未适配的厂商私有字段要报出来而不是静默丢弃() {
        IntentPlan plan = renderer.render("Device_Mgmt", "OTHER", null,
                Map.of(), 1, Map.of("telnet_enable", "true"));

        assertFalse(plan.deliverable());
        assertTrue(plan.errors().get(0).contains("telnet_enable"), "实际错误: " + plan.errors());
    }
}
