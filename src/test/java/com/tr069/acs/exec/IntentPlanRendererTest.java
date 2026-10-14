package com.tr069.acs.exec;

import com.tr069.acs.domain.entity.ModelObjectMapping;
import com.tr069.acs.domain.entity.ModelParamMapping;
import com.tr069.acs.exec.model.IntentPlan;
import com.tr069.acs.exec.model.PlanItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图 → CWMP 下发计划。
 *
 * <p>渲染是下发链路里唯一「算错就写坏设备」的一环：实例号没替换、只读节点被写、
 * 必填字段静默丢弃，都会变成一次错误的开通，所以逐条对着判一遍。</p>
 */
class IntentPlanRendererTest {

    private final IntentPlanRenderer renderer = new IntentPlanRenderer();

    /**
     * 两种数据模型的路径差异全在这层消化：同一份意图，TR-181 应当写出 Device.WAN. 的路径。
     */
    @Test
    void rendersTr181Paths() {
        IntentPlan plan = renderer.render("Internet_WAN",
                List.of(mapping("Internet_WAN", "vlan_id", "Device.WAN.{i}.VLANID", 1, 0),
                        mapping("Internet_WAN", "enable", "Device.WAN.{i}.Enable", 1, 1)),
                List.of(), Map.of(), Map.of("vlan_id", "100", "enable", "true", "instance", "2"), false);

        assertThat(plan.errors()).isEmpty();
        assertThat(plan.deliverable()).isTrue();
        assertThat(plan.instance()).isEqualTo(2);
        assertThat(plan.parameters()).extracting(PlanItem::path)
                .containsExactly("Device.WAN.2.VLANID", "Device.WAN.2.Enable");
        // 布尔值要按 boolean 下发，字符串下去设备会拒绝
        assertThat(plan.parameters()).filteredOn(item -> item.bizField().equals("enable"))
                .extracting(PlanItem::valueType).containsExactly("boolean");
    }

    /**
     * 缺必填字段必须拦住：与其下发半套配置，不如明确报错。
     */
    @Test
    void rejectsMissingRequiredField() {
        IntentPlan plan = renderer.render("Internet_WAN",
                List.of(mapping("Internet_WAN", "vlan_id", "Device.WAN.{i}.VLANID", 1, 1)),
                List.of(), Map.of(), Map.of(), false);

        assertThat(plan.errors()).anyMatch(text -> text.contains("缺必填业务字段: vlan_id"));
        assertThat(plan.deliverable()).isFalse();
    }

    /**
     * 模板里的意图默认值也能成为下发值，调用方不必每次都带全字段。
     */
    @Test
    void usesTemplateIntentAsDefault() {
        IntentPlan plan = renderer.render("Internet_WAN",
                List.of(mapping("Internet_WAN", "vlan_used", "Device.WAN.{i}.VLANID", 1, 1)),
                List.of(), Map.of("vlan_used", "false"), Map.of(), false);

        assertThat(plan.parameters()).extracting(PlanItem::value).containsExactly("false");
    }

    /**
     * 只读节点会被拦下。
     */
    @Test
    void rejectsReadOnlyNode() {
        IntentPlan plan = renderer.render("Internet_WAN",
                List.of(mapping("Internet_WAN", "serial", "Device.WAN.{i}.SerialNumber", 0, 1)),
                List.of(), Map.of(), Map.of("serial", "ABC"), false);

        assertThat(plan.errors()).anyMatch(text -> text.contains("只读"));
    }

    /**
     * 要求新建实例时必须能给出 AddObject 的对象名，否则设备侧没有可写的节点。
     */
    @Test
    void requiresObjectNameWhenCreatingInstance() {
        ModelObjectMapping object = new ModelObjectMapping();
        object.setBizObject("Internet_WAN");
        object.setParentPath("Device.WAN.");
        object.setObjectName("Device.WAN.Device.{i}.");
        object.setInstanceMode(ModelObjectMapping.MODE_ADD);

        IntentPlan plan = renderer.render("Internet_WAN",
                List.of(mapping("Internet_WAN", "enable", "Device.WAN.{i}.Enable", 1, 1)),
                List.of(object), Map.of(), Map.of("enable", "true"), true);

        assertThat(plan.objectsToAdd()).containsExactly("Device.WAN.Device.1.");
        assertThat(plan.errors()).isEmpty();
    }

    /**
     * 多选意图字段（业务模式 / 绑定端口）以逗号拼接下发；空选不产生报文也不报错。
     */
    @Test
    void joinsMultiSelectIntentFieldsWithComma() {
        IntentPlan plan = renderer.render("Internet_WAN",
                List.of(mapping("Internet_WAN", "service_list", "Device.WAN.{i}.X_ServiceList", 1, 0),
                        mapping("Internet_WAN", "bind_ports", "Device.WAN.{i}.X_LANBind", 1, 0)),
                List.of(),
                Map.of("service_list", List.of("Internet", "TR069"),
                        "bind_ports", List.of("LAN1", "SSID1", "PON")),
                Map.of(), false);

        assertThat(plan.errors()).isEmpty();
        assertThat(plan.parameters()).extracting(PlanItem::value)
                .containsExactly("Internet,TR069", "LAN1,SSID1,PON");

        // 全部多选字段清空 -> 无可下发内容，渲染器按「空计划」拦截，不允许发空报文
        IntentPlan empty = renderer.render("Internet_WAN",
                List.of(mapping("Internet_WAN", "service_list", "Device.WAN.{i}.X_ServiceList", 1, 0)),
                List.of(), Map.of("service_list", List.of()), Map.of(), false);
        assertThat(empty.deliverable()).isFalse();
        assertThat(empty.parameters()).isEmpty();
    }

    private static ModelParamMapping mapping(String bizObject, String bizField, String path,
                                             Integer writable, Integer required) {
        ModelParamMapping mapping = new ModelParamMapping();
        mapping.setBizObject(bizObject);
        mapping.setBizField(bizField);
        mapping.setCwmpPath(path);
        mapping.setWritable(writable);
        mapping.setRequired(required);
        return mapping;
    }
}
