package com.PilotACS.biz.service;

import com.PilotACS.template.service.ConfigTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 配置模板桥接 */
@Component
@RequiredArgsConstructor
public class ConfigTemplateResolver {

    private final ConfigTemplateService configTemplateService;

    /** 按 id 取配置模板视图 */
    public Map<String, Object> findView(Long id) {
        return configTemplateService.findView(id);
    }

    /** 取配置模板意图字段 */
    public Map<String, Object> fieldsOf(Long id) {
        return configTemplateService.fieldsOf(id);
    }

    /** 配置模板是否为增量业务 */
    public boolean incrementalOf(Long id) {
        return configTemplateService.incrementalOf(id);
    }

    /** 按业务类型列配置模板 */
    public List<Map<String, Object>> list(String category, String keyword) {
        return configTemplateService.list(category, keyword);
    }
}
