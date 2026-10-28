package com.tr069.acs.exec.model;

import java.util.List;

/**
 * 意图模板渲染出的下发计划。
 *
 * <p>意图只说「开通 2.4G WiFi，SSID=xxx」；渲染器翻译成具体的 CWMP 路径与值，
 * 计划本身可直接用于下发，也便于失败后复查「当时到底想写什么」。</p>
 *
 * @param bizObject   业务对象：Internet_WAN / Bridge_WAN / WiFi_2G / WiFi_5G / VOIP_WAN
 * @param instance    实例号，替换参数路径里的 {@code {i}}
 * @param objectsToAdd 需要先 AddObject 创建的对象（父路径 + 对象名）
 * @param parameters  待写入的参数（路径、值、xsi:type）
 * @param errors      渲染阶段发现的问题：缺必填字段、路径还没替换实例号等
 */
public record IntentPlan(String bizObject, int instance, List<String> objectsToAdd,
                         List<PlanItem> parameters, List<String> errors) {

    /**
     * 是否可以直接下发：没有问题，且确实有要写的参数或明确的动作。
     */
    public boolean deliverable() {
        return errors.isEmpty() && (!parameters.isEmpty() || isRebootOnly());
    }

    /**
     * 纯重启计划：Device_Mgmt 意图没有任何参数要写，动作就是执行收尾时发 Reboot。
     *
     * <p>重启是标准 TR-069 RPC，不依赖任何型号适配——配置模板里勾了
     * 「下发业务完成后重启光猫」，这就是一张合法的空计划。</p>
     */
    public boolean isRebootOnly() {
        return "Device_Mgmt".equals(bizObject) && parameters.isEmpty();
    }
}
