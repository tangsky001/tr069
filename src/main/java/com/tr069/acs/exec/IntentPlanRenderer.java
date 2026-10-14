package com.tr069.acs.exec;

import com.tr069.acs.domain.entity.ModelObjectMapping;
import com.tr069.acs.domain.entity.ModelParamMapping;
import com.tr069.acs.exec.model.IntentPlan;
import com.tr069.acs.exec.model.PlanItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务意图 → CWMP 下发计划。
 *
 * <p>模板只描述「开通意图」（IP 协议、是否带 VLAN、是否隐藏 SSID），具体要写哪个节点、
 * 实例号是几、值是什么都由渲染器结合型号语义映射（{@code model_param_mapping}）算出。
 * 因此同一份模板在华为与中兴上写出的路径可以完全不同。</p>
 *
 * <p>路径里的 {@code {i}} 是实例号占位符，取值来自请求里的 {@code instance} 字段，缺省 1。
 * 一台设备上同一个业务可能有多个实例（多条 WAN 连接、多个 SSID），写哪一条由调用方决定。</p>
 */
@Slf4j
@Service
public class IntentPlanRenderer {

    /**
     * 渲染下发计划。
     *
     * @param bizObject     业务对象
     * @param paramMappings 该型号的参数映射
     * @param objectMappings 该型号的对象映射
     * @param intentFields  模板里的意图字段（快照用，也参与布尔开关类字段的值）
     * @param values        本次请求给出的业务字段值
     * @param createInstance 是否要求新建实例
     */
    public IntentPlan render(String bizObject,
                             List<ModelParamMapping> paramMappings,
                             List<ModelObjectMapping> objectMappings,
                             Map<String, Object> intentFields,
                             Map<String, Object> values,
                             boolean createInstance) {
        List<String> errors = new ArrayList<>();
        int instance = resolveInstance(values);

        List<PlanItem> items = new ArrayList<>();
        Set<String> usedFields = new LinkedHashSet<>();
        for (ModelParamMapping mapping : paramMappings) {
            if (mapping.getBizObject() == null || !mapping.getBizObject().equals(bizObject)) {
                continue;
            }
            String field = mapping.getBizField();
            if (field == null || field.isBlank()) {
                continue;
            }
            String value = valueOf(field, values, intentFields);
            if (value == null) {
                if (isTrue(mapping.getRequired())) {
                    errors.add("缺必填业务字段: " + field);
                }
                continue;
            }
            if (mapping.getCwmpPath() == null || mapping.getCwmpPath().isBlank()) {
                errors.add("业务字段未映射 CWMP 路径: " + field);
                continue;
            }
            // writable=0 表示该节点在该型号上只读，写下去必然失败，不如在校对阶段就拦住
            if (mapping.getWritable() != null && mapping.getWritable() == 0) {
                errors.add("映射到的节点只读，无法下发: " + field);
                continue;
            }
            String path = replaceInstance(mapping.getCwmpPath(), instance);
            if (path.contains("{i}")) {
                errors.add("参数路径仍有未替换的实例号: " + path);
                continue;
            }
            items.add(new PlanItem(bizObject, field, path, value,
                    valueTypeOf(mapping.getValueType(), value)));
            usedFields.add(field);
        }

        List<String> objectsToAdd = new ArrayList<>();
        if (createInstance) {
            for (ModelObjectMapping mapping : objectMappings) {
                if (mapping.getBizObject() == null || !mapping.getBizObject().equals(bizObject)) {
                    continue;
                }
                // 复用现有实例的模式不必创建
                if (ModelObjectMapping.MODE_EXISTING.equals(mapping.getInstanceMode())) {
                    continue;
                }
                String objectName = mapping.getObjectName() == null
                        ? "" : replaceInstance(mapping.getObjectName(), instance);
                if (objectName.isEmpty()) {
                    errors.add("业务对象未映射 AddObject 对象名: " + bizObject);
                    continue;
                }
                objectsToAdd.add(objectName);
            }
            if (objectsToAdd.isEmpty() && !paramMappings.isEmpty() && !items.isEmpty()) {
                errors.add("要求新建实例，但该型号未登记 AddObject 对象名: " + bizObject);
            }
        }

        if (items.isEmpty() && errors.isEmpty()) {
            errors.add("没有任何字段可以下发：请确认该型号已做语义映射，且请求带上了业务字段值");
        }
        log.debug("渲染下发计划: bizObject={}, instance={}, 参数={}, 对象={}",
                bizObject, instance, items.size(), objectsToAdd.size());
        return new IntentPlan(bizObject, instance, objectsToAdd, items, errors);
    }

    /**
     * 业务字段取值顺序：本次请求 > 模板意图默认值 > 布尔开关的通用写法。
     */
    private String valueOf(String field, Map<String, Object> values,
                           Map<String, Object> intentFields) {
        if (values != null) {
            Object raw = values.get(field);
            if (hasText(raw)) {
                return text(raw);
            }
        }
        if (intentFields != null) {
            Object raw = intentFields.get(field);
            if (hasText(raw)) {
                return text(raw);
            }
        }
        return null;
    }

    private static boolean hasText(Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof java.util.Collection<?> col) {
            return !col.isEmpty();
        }
        return !(raw instanceof String s && s.isBlank());
    }

    /**
     * 集合值以逗号拼接：业务模式、绑定端口这类多选字段，设备侧
     * （如 ServiceList）普遍按逗号分隔取值。
     */
    private static String text(Object raw) {
        if (raw instanceof java.util.Collection<?> col) {
            StringBuilder sb = new StringBuilder();
            for (Object o : col) {
                if (o == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(String.valueOf(o).trim());
            }
            return sb.toString();
        }
        return String.valueOf(raw).trim();
    }

    private static String replaceInstance(String path, int instance) {
        return path.replace("{i}", String.valueOf(instance));
    }

    private static int resolveInstance(Map<String, Object> values) {
        if (values == null) {
            return 1;
        }
        Object raw = values.get("instance");
        if (raw == null) {
            return 1;
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(raw).trim());
            return parsed > 0 ? parsed : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 没有标注类型时按值本身猜：数字给 unsignedInt，true/false 给 boolean。
     */
    private static String valueTypeOf(String declared, String value) {
        if (declared != null && !declared.isBlank()) {
            return declared;
        }
        if (value == null) {
            return "string";
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return "boolean";
        }
        if (value.matches("-?\\d+")) {
            return "unsignedInt";
        }
        return "string";
    }

    private static boolean isTrue(Integer flag) {
        return flag != null && flag == 1;
    }
}
