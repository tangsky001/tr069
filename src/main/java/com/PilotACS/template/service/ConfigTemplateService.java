package com.PilotACS.template.service;

import com.PilotACS.common.exception.BusinessException;
import com.PilotACS.common.log.Logs;
import com.PilotACS.template.entity.ConfigTemplate;
import com.PilotACS.template.mapper.ConfigTemplateMapper;
import com.PilotACS.template.model.ConfigTemplateSaveRequest;
import com.PilotACS.template.model.TemplateFieldDefs;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 配置意图模板：只描述「要开通成什么样」，不下发任何数据。 */
@Service
@RequiredArgsConstructor
public class ConfigTemplateService {

    private final ConfigTemplateMapper mapper;
    private final ObjectMapper objectMapper;

    /** 模板类型与意图字段定义 */
    public Map<String, Object> meta() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("categories", TemplateFieldDefs.CATEGORIES);
        data.put("incrementalCategories", TemplateFieldDefs.CATEGORIES.stream()
                .map(c -> c.get("code"))
                .filter(code -> code != null && code.toUpperCase(Locale.ROOT).startsWith("WAN_"))
                .toList());
        data.put("bindPortGroups", TemplateFieldDefs.BIND_PORT_GROUPS);
        Map<String, List<Map<String, Object>>> fieldsByType = new LinkedHashMap<>();
        for (Map<String, Object> def : TemplateFieldDefs.FIELD_DEFS) {
            fieldsByType.computeIfAbsent(String.valueOf(def.get("category")), k -> new ArrayList<>())
                    .add(def);
        }
        data.put("fields", fieldsByType);
        return data;
    }

    /** 模板列表 */
    public List<Map<String, Object>> list(String category, String keyword) {
        LambdaQueryWrapper<ConfigTemplate> query = new LambdaQueryWrapper<>();
        if (category != null && !category.isBlank()) {
            query.eq(ConfigTemplate::getCategory, category.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like(ConfigTemplate::getName, kw)
                    .or().like(ConfigTemplate::getRemark, kw)
                    .or().like(ConfigTemplate::getGroupName, kw));
        }
        query.orderByAsc(ConfigTemplate::getCategory).orderByAsc(ConfigTemplate::getId);
        List<Map<String, Object>> views = new ArrayList<>();
        for (ConfigTemplate template : mapper.selectList(query)) {
            views.add(toView(template));
        }
        return views;
    }

    /** 模板详情 */
    public Map<String, Object> get(Long id) {
        ConfigTemplate template = mapper.selectById(id);
        if (template == null) {
            throw new BusinessException("模板不存在: " + id);
        }
        return toView(template);
    }

    /** 按模板 ID 取视图，不存在返回 null */
    public Map<String, Object> findView(Long id) {
        if (id == null) {
            return null;
        }
        ConfigTemplate template = mapper.selectById(id);
        return template == null ? null : toView(template);
    }

    /** 保存模板 */
    public Map<String, Object> save(ConfigTemplateSaveRequest request) {
        if (request == null) {
            throw new BusinessException("提交内容不能为空");
        }
        String name = request.getName() == null ? "" : request.getName().trim();
        String category = request.getCategory() == null ? "" : request.getCategory().trim();
        if (name.isEmpty()) {
            throw new BusinessException("模板名称不能为空");
        }
        if (category.isEmpty()) {
            throw new BusinessException("模板类型不能为空");
        }

        ConfigTemplate template = request.getId() == null ? new ConfigTemplate() : mapper.selectById(request.getId());
        if (template == null) {
            throw new BusinessException("模板不存在: " + request.getId());
        }

        boolean dup = mapper.selectCount(new LambdaQueryWrapper<ConfigTemplate>()
                .eq(ConfigTemplate::getCategory, category)
                .eq(ConfigTemplate::getName, name)
                .ne(request.getId() != null, ConfigTemplate::getId, request.getId())) > 0;
        if (dup) {
            throw new BusinessException("同类型下已存在同名模板: " + name);
        }

        template.setName(name);
        template.setCategory(category);
        template.setGroupName(request.getGroupName() == null ? "" : request.getGroupName().trim());
        template.setEnabled(request.getEnabled() == null || request.getEnabled());
        template.setRemark(request.getRemark() == null ? "" : request.getRemark().trim());
        normalizeServiceList(request.getFields());
        template.setFields(writeFields(request.getFields()));
        template.setIncremental(request.getIncremental() != null && request.getIncremental());

        LocalDateTime now = LocalDateTime.now();
        if (request.getId() == null) {
            template.setCreatedAt(now);
            template.setUpdatedAt(now);
            mapper.insert(template);
        } else {
            template.setUpdatedAt(now);
            mapper.updateById(template);
        }
        return toView(template);
    }

    /** 删除模板 */
    public void delete(Long id) {
        if (mapper.selectById(id) == null) {
            throw new BusinessException("模板不存在: " + id);
        }
        mapper.deleteById(id);
    }

    /** 取模板的意图字段 */
    public Map<String, Object> fieldsOf(Long id) {
        ConfigTemplate template = mapper.selectById(id);
        if (template == null) {
            return new LinkedHashMap<>();
        }
        return readFields(template.getFields());
    }

    /** 是否为增量业务模板 */
    public boolean incrementalOf(Long id) {
        if (id == null) {
            return false;
        }
        ConfigTemplate template = mapper.selectById(id);
        return template != null && template.getIncremental() != null && template.getIncremental();
    }

    private Map<String, Object> toView(ConfigTemplate template) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", template.getId());
        view.put("name", template.getName());
        view.put("category", template.getCategory());
        view.put("categoryLabel", TemplateFieldDefs.labelOf(template.getCategory()));
        view.put("groupName", template.getGroupName());
        view.put("enabled", template.getEnabled() != null && template.getEnabled());
        view.put("remark", template.getRemark());
        Map<String, Object> fields = readFields(template.getFields());
        normalizeServiceList(fields);
        view.put("fields", fields);
        view.put("intentSummary", intentSummaryOf(template.getCategory(), fields));
        view.put("categoryLevel", TemplateFieldDefs.categoryLevelOf(template.getCategory()));
        view.put("incremental", template.getIncremental() != null && template.getIncremental());
        view.put("createdAt", template.getCreatedAt());
        view.put("updatedAt", template.getUpdatedAt());
        return view;
    }

    /** 生成「开通意图」摘要 */
    private static String intentSummaryOf(String category, Map<String, Object> fields) {
        if (category == null || fields == null || fields.isEmpty()) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> def : TemplateFieldDefs.FIELD_DEFS) {
            if (!category.equals(String.valueOf(def.get("category")))) {
                continue;
            }
            Object value = fields.get(String.valueOf(def.get("key")));
            if (value == null) {
                continue;
            }
            if (value instanceof String text && text.isBlank()) {
                continue;
            }
            if (value instanceof List<?> list && list.isEmpty()) {
                continue;
            }
            String text = optionTextOf(value, def);
            if (!text.isBlank()) {
                parts.add(def.get("label") + "：" + text);
            }
        }
        return parts.isEmpty() ? "-" : String.join("　", parts);
    }

    /** 字段取值转展示文本 */
    private static String optionTextOf(Object value, Map<String, Object> def) {
        if (value instanceof List<?> list) {
            List<String> texts = new ArrayList<>();
            for (Object item : list) {
                texts.add(optionLabelOf(item, def));
            }
            return String.join("、", texts);
        }
        return optionLabelOf(value, def);
    }

    private static String optionLabelOf(Object value, Map<String, Object> def) {
        Object options = def.get("options");
        if (options instanceof List<?> list) {
            for (Object option : list) {
                if (option instanceof Map<?, ?> map
                        && map.get("value") != null
                        && String.valueOf(map.get("value")).equals(String.valueOf(value))
                        && map.get("label") != null) {
                    return String.valueOf(map.get("label"));
                }
            }
        }
        return String.valueOf(value);
    }

    private Map<String, Object> readFields(String json) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return fields;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            if (parsed != null) {
                parsed.forEach((k, v) -> {
                    if (k != null && v != null) {
                        fields.put(k, v);
                    }
                });
            }
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE, "模板意图字段解析失败，已忽略", "原因", e.getMessage());
        }
        return fields;
    }

    /** 业务模式归一成设备口径 */
    private static void normalizeServiceList(Map<String, Object> fields) {
        if (fields == null) {
            return;
        }
        Object raw = fields.get("service_list");
        if (!(raw instanceof String value) || value.isBlank()) {
            return;
        }
        List<String> tokens = java.util.Arrays.stream(value.split("[,，、+/\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        if (!tokens.isEmpty()) {
            fields.put("service_list", String.join(",", tokens));
        }
    }

    private String writeFields(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            throw new BusinessException("意图字段序列化失败: " + e.getMessage());
        }
    }
}
