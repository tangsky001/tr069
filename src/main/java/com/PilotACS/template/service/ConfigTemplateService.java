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

/**
 * 配置意图模板：只描述「要开通成什么样」，不下发任何数据。
 * 字段定义（meta）由本服务统一下发，前端按它渲染表单；
 * 下发链路在 IntentDeliveryService，按设备数据模型把意图翻译成真实参数。
 */
@Service
@RequiredArgsConstructor
public class ConfigTemplateService {

    private final ConfigTemplateMapper mapper;
    private final ObjectMapper objectMapper;

    // ==================== 元数据 ====================

    /** 模板类型与各类别的意图字段定义，供页面按类型渲染表单 */
    public Map<String, Object> meta() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("categories", TemplateFieldDefs.CATEGORIES);
        // 只有 WAN 类业务有「先清理存量再新建」的覆盖语义，增量开关只对它们有意义；
        // 清单由这里统一下发，前端不做前缀推断
        data.put("incrementalCategories", TemplateFieldDefs.CATEGORIES.stream()
                .map(c -> c.get("code"))
                .filter(code -> code != null && code.toUpperCase(Locale.ROOT).startsWith("WAN_"))
                .toList());
        // 绑定端口候选：与 bind_ports 意图字段的取值同源，页面照它渲染勾选组
        data.put("bindPortGroups", TemplateFieldDefs.BIND_PORT_GROUPS);
        Map<String, List<Map<String, Object>>> fieldsByType = new LinkedHashMap<>();
        for (Map<String, Object> def : TemplateFieldDefs.FIELD_DEFS) {
            fieldsByType.computeIfAbsent(String.valueOf(def.get("category")), k -> new ArrayList<>())
                    .add(def);
        }
        data.put("fields", fieldsByType);
        return data;
    }

    // ==================== 查询 ====================

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

    public Map<String, Object> get(Long id) {
        ConfigTemplate template = mapper.selectById(id);
        if (template == null) {
            throw new BusinessException("模板不存在: " + id);
        }
        return toView(template);
    }

    /** 按 id 取模板视图，不存在返回 null（业务模板成员引用时用） */
    public Map<String, Object> findView(Long id) {
        if (id == null) {
            return null;
        }
        ConfigTemplate template = mapper.selectById(id);
        return template == null ? null : toView(template);
    }

    // ==================== 保存 / 删除 ====================

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

    public void delete(Long id) {
        if (mapper.selectById(id) == null) {
            throw new BusinessException("模板不存在: " + id);
        }
        mapper.deleteById(id);
    }

    /** 取模板的意图字段，供下发时按机型数据模型翻译成真实参数 */
    public Map<String, Object> fieldsOf(Long id) {
        ConfigTemplate template = mapper.selectById(id);
        if (template == null) {
            return new LinkedHashMap<>();
        }
        return readFields(template.getFields());
    }

    /** 模板是否勾了「增量业务」 */
    public boolean incrementalOf(Long id) {
        if (id == null) {
            return false;
        }
        ConfigTemplate template = mapper.selectById(id);
        return template != null && template.getIncremental() != null && template.getIncremental();
    }

    // ==================== 内部实现 ====================

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
        // 读出口也归一一次：存量模板里的历史脏值（Internet / Internet,TR069 混合大小写）
        // 不用等重新保存就按设备口径显示
        normalizeServiceList(fields);
        view.put("fields", fields);
        // 列表页的「开通意图」摘要与类型标签色：字段定义在服务端，取值中文也只在这边查得到
        view.put("intentSummary", intentSummaryOf(template.getCategory(), fields));
        view.put("categoryLevel", TemplateFieldDefs.categoryLevelOf(template.getCategory()));
        view.put("incremental", template.getIncremental() != null && template.getIncremental());
        view.put("createdAt", template.getCreatedAt());
        view.put("updatedAt", template.getUpdatedAt());
        return view;
    }

    /** 「开通意图」摘要：按该类型的字段定义逐个拼「标签：取值」，取值翻成中文 */
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
        // 全角空格分隔：一行里几个字段能看出分组，又不至于挤成一片
        return parts.isEmpty() ? "-" : String.join("　", parts);
    }

    /** 多选拼「a、b」，单选翻中文；选项表里没有的原样显示 */
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

    /** 业务模式归一成设备口径（INTERNET / TR069 / VOIP / IPTV，大写、逗号分隔、去重） */
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
