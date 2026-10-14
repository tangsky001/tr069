package com.tr069.acs.template.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.template.mapper.ConfigTemplateMapper;
import com.tr069.acs.worklist.model.SemanticSchema;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模板意图字段与语义字典的对齐校验。
 *
 * <p>模板字段 key 只有命中 {@code SemanticSchema} 的业务字段 code，下发渲染器才会
 * 把它翻译成真实参数。这里逐类型断言 key 全部落在对应业务对象里，防止再出现
 * 「页面上能填、下发生效不了」的摆设字段。</p>
 */
class ConfigTemplateMetaTest {

    /** 模板类型 -> 承载其语义字段的业务对象，与 IntentDeliveryService.BIZ_OBJECTS 一致。 */
    private static final Map<String, String> CATEGORY_OBJECTS = Map.of(
            "WAN_DHCP", "Internet_WAN",
            "WAN_PPPOE", "Internet_WAN",
            "WAN_STATIC", "Internet_WAN",
            "WAN_BRIDGE", "Bridge_WAN",
            "WIFI_24G", "WiFi_2G",
            "WIFI_58G", "WiFi_5G",
            "SIP", "Voice_Service",
            "OTHER", "Device_Mgmt");

    @Test
    void metaCoversAllCategories() {
        ConfigTemplateService service = new ConfigTemplateService(
                Mockito.mock(ConfigTemplateMapper.class), new ObjectMapper());
        Map<String, Object> meta = service.meta();

        List<?> categories = (List<?>) meta.get("categories");
        Set<String> codes = categories.stream()
                .map(c -> String.valueOf(((Map<?, ?>) c).get("code")))
                .collect(Collectors.toSet());
        assertThat(codes).containsExactlyInAnyOrderElementsOf(CATEGORY_OBJECTS.keySet());

        Map<String, List<Map<String, Object>>> fields = fieldsOf(meta);
        assertThat(fields.keySet()).containsExactlyInAnyOrderElementsOf(CATEGORY_OBJECTS.keySet());
    }

    @Test
    void everyFieldKeyAlignsWithSemanticSchema() {
        ConfigTemplateService service = new ConfigTemplateService(
                Mockito.mock(ConfigTemplateMapper.class), new ObjectMapper());
        Map<String, List<Map<String, Object>>> fields = fieldsOf(service.meta());

        for (Map.Entry<String, String> entry : CATEGORY_OBJECTS.entrySet()) {
            String category = entry.getKey();
            SemanticSchema.BizObject object = SemanticSchema.objectOf(entry.getValue());
            Set<String> known = object.fields().stream()
                    .map(SemanticSchema.BizField::code)
                    .collect(Collectors.toSet());

            List<String> unknown = fields.get(category).stream()
                    .map(def -> String.valueOf(def.get("key")))
                    .filter(key -> !known.contains(key))
                    .toList();
            assertThat(unknown)
                    .as("模板类型 %s 的字段 %s 不在业务对象 %s(%s) 里，下发时不会产生报文",
                            category, unknown, object.code(), object.label())
                    .isEmpty();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Map<String, Object>>> fieldsOf(Map<String, Object> meta) {
        return (Map<String, List<Map<String, Object>>>) meta.get("fields");
    }
}
