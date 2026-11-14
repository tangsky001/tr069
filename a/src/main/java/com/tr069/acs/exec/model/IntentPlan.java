package com.tr069.acs.exec.model;

import java.util.List;

/** 意图模板渲染出的下发计划 */
public record IntentPlan(String bizObject, int instance, List<String> objectsToAdd,
                         List<PlanItem> parameters, List<String> errors) {

    /** 是否可以直接下发：没有问题，且确实有要写的参数或明确的动作 */
    public boolean deliverable() {
        return errors.isEmpty() && (!parameters.isEmpty() || isRebootOnly());
    }

    /** 纯重启计划：Device_Mgmt 意图没有任何参数要写，动作就是执行收尾时发 Reboot */
    public boolean isRebootOnly() {
        return "Device_Mgmt".equals(bizObject) && parameters.isEmpty();
    }
}
