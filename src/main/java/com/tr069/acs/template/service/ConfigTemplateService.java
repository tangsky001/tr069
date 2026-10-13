package com.tr069.acs.template.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.common.exception.BizException;
import com.tr069.acs.template.entity.ConfigTemplate;
import com.tr069.acs.template.mapper.ConfigTemplateMapper;
import com.tr069.acs.template.model.ConfigTemplateSaveRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 配置意图模板：只描述「要开通成什么样」，不下发任何数据。
 *
 * <p>模板里的意图字段（IP 协议、是否带 VLAN、是否绑定端口……）与型号语义映射里的
 * {@code biz_field} 对齐；下发时由执行器按设备型号取出真实 CWMP 参数路径，
 * 再组合成 SetParameterValues 报文。新增意图字段只改 {@link #FIELD_DEFS}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigTemplateService {

    /** 模板类型：代码 -> 展示名。 */
    private static final List<Map<String, String>> CATEGORIES = List.of(
            cat("WAN_DHCP", "WAN DHCP"),
            cat("WAN_PPPOE", "WAN PPPoE"),
            cat("WAN_STATIC", "WAN 静态IP"),
            cat("WAN_BRIDGE", "WAN 桥接"),
            cat("WIFI_24G", "2.4G WiFi"),
            cat("WIFI_58G", "5.8G WiFi"),
            cat("SIP", "SIP 语音"));

    /**
     * 各类型的意图字段定义：只描述意图，不写参数路径（路径由型号映射决定）。
     */
    private static final List<Map<String, Object>> FIELD_DEFS = List.of(
            def("WAN_DHCP", "ip_protocol", "IP 协议", "enum", "IPv4",
                    List.of(opt("IPv4", "IPv4"), opt("IPv6", "IPv6"), opt("IPv4+IPv6", "IPv4 + IPv6"))),
            def("WAN_DHCP", "vlan_used", "WAN VLAN ID", "enum", "false",
                    List.of(opt("false", "不使用"), opt("true", "使用"))),
            def("WAN_DHCP", "port_bind", "绑定端口", "enum", "false",
                    List.of(opt("false", "不绑定"), opt("true", "绑定"))),

            def("WAN_PPPOE", "ip_protocol", "IP 协议", "enum", "IPv4",
                    List.of(opt("IPv4", "IPv4"), opt("IPv6", "IPv6"), opt("IPv4+IPv6", "IPv4 + IPv6"))),
            def("WAN_PPPOE", "vlan_used", "WAN VLAN ID", "enum", "false",
                    List.of(opt("false", "不使用"), opt("true", "使用"))),
            def("WAN_PPPOE", "port_bind", "绑定端口", "enum", "false",
                    List.of(opt("false", "不绑定"), opt("true", "绑定"))),

            def("WAN_STATIC", "ip_protocol", "IP 协议", "enum", "IPv4",
                    List.of(opt("IPv4", "IPv4"), opt("IPv6", "IPv6"), opt("IPv4+IPv6", "IPv4 + IPv6"))),
            def("WAN_STATIC", "vlan_used", "WAN VLAN ID", "enum", "false",
                    List.of(opt("false", "不使用"), opt("true", "使用"))),
            def("WAN_STATIC", "port_bind", "绑定端口", "enum", "false",
                    List.of(opt("false", "不绑定"), opt("true", "绑定"))),

            def("WAN_BRIDGE", "ip_protocol", "IP 协议", "enum", "IPv4",
                    List.of(opt("IPv4", "IPv4"), opt("IPv6", "IPv6"), opt("IPv4+IPv6", "IPv4 + IPv6"))),
            def("WAN_BRIDGE", "vlan_used", "WAN VLAN ID", "enum", "false",
                    List.of(opt("false", "不使用"), opt("true", "使用"))),
            def("WAN_BRIDGE", "port_bind", "绑定端口", "enum", "false",
                    List.of(opt("false", "不绑定"), opt("true", "绑定"))),

            def("WIFI_24G", "wifi_enable", "启用无线 SSID", "enum", "true",
                    List.of(opt("true", "是"), opt("false", "否"))),
            def("WIFI_24G", "wifi_encryption", "加密方式", "enum", "11i",
                    List.of(opt("Basic", "开放（无密码）"), opt("WPA", "WPA-PSK"),
                            opt("11i", "WPA2-PSK"), opt("WPAand11i", "WPA/WPA2 混合"))),
            def("WIFI_24G", "wifi_hidden", "隐藏 SSID", "enum", "false",
                    List.of(opt("false", "否"), opt("true", "是"))),

            def("WIFI_58G", "wifi_enable", "启用无线 SSID", "enum", "true",
                    List.of(opt("true", "是"), opt("false", "否"))),
            def("WIFI_58G", "wifi_encryption", "加密方式", "enum", "11i",
                    List.of(opt("Basic", "开放（无密码）"), opt("WPA", "WPA-PSK"),
                            opt("11i", "WPA2-PSK"), opt("WPAand11i", "WPA/WPA2 混合"))),
            def("WIFI_58G", "wifi_hidden", "隐藏 SSID", "enum", "false",
                    List.of(opt("false", "否"), opt("true", "是"))),

            def("SIP", "sip_protocol", "语音协议", "enum", "SIP",
                    List.of(opt("SIP", "SIP"), opt("H248", "H.248"))),
            def("SIP", "sip_outbound", "启用 Outbound", "enum", "false",
                    List.of(opt("false", "否"), opt("true", "是"))),
            def("SIP", "sip_digitmap", "下发数图", "enum", "false",
                    List.of(opt("false", "否"), opt("true", "是"))));

    private final ConfigTemplateMapper mapper;
    private final ObjectMapper objectMapper;

    // ==================== 元数据 ====================

    /**
     * 模板类型与各类别的意图字段定义，供页面按类型渲染表单。
     */
    public Map<String, Object> meta() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("categories", CATEGORIES);
        Map<String, List<Map<String, Object>>> fieldsByType = new LinkedHashMap<>();
        for (Map<String, Object> def : FIELD_DEFS) {
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
            throw new BizException("模板不存在: " + id);
        }
        return toView(template);
    }

    /**
     * 按 id 取模板视图，不存在返回 null（业务模板成员引用时用）。
     */
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
            throw new BizException("提交内容不能为空");
        }
        String name = request.getName() == null ? "" : request.getName().trim();
        String category = request.getCategory() == null ? "" : request.getCategory().trim();
        if (name.isEmpty()) {
            throw new BizException("模板名称不能为空");
        }
        if (category.isEmpty()) {
            throw new BizException("模板类型不能为空");
        }

        ConfigTemplate template = request.getId() == null ? new ConfigTemplate() : mapper.selectById(request.getId());
        if (template == null) {
            throw new BizException("模板不存在: " + request.getId());
        }

        boolean dup = mapper.selectCount(new LambdaQueryWrapper<ConfigTemplate>()
                .eq(ConfigTemplate::getCategory, category)
                .eq(ConfigTemplate::getName, name)
                .ne(request.getId() != null, ConfigTemplate::getId, request.getId())) > 0;
        if (dup) {
            throw new BizException("同类型下已存在同名模板: " + name);
        }

        template.setName(name);
        template.setCategory(category);
        template.setGroupName(request.getGroupName() == null ? "" : request.getGroupName().trim());
        template.setEnabled(request.getEnabled() == null || request.getEnabled());
        template.setRemark(request.getRemark() == null ? "" : request.getRemark().trim());
        template.setFields(writeFields(request.getFields()));

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
            throw new BizException("模板不存在: " + id);
        }
        mapper.deleteById(id);
    }

    public int count() {
        return mapper.selectCount(null).intValue();
    }

    /**
     * 取模板的意图字段，供下发时按型号映射翻译成真实参数。
     */
    public Map<String, Object> fieldsOf(Long id) {
        ConfigTemplate template = mapper.selectById(id);
        if (template == null) {
            return new LinkedHashMap<>();
        }
        return readFields(template.getFields());
    }

    // ==================== 内部实现 ====================

    private Map<String, Object> toView(ConfigTemplate template) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", template.getId());
        view.put("name", template.getName());
        view.put("category", template.getCategory());
        view.put("categoryLabel", labelOf(template.getCategory()));
        view.put("groupName", template.getGroupName());
        view.put("enabled", template.getEnabled() != null && template.getEnabled());
        view.put("remark", template.getRemark());
        view.put("fields", readFields(template.getFields()));
        view.put("createdAt", template.getCreatedAt());
        view.put("updatedAt", template.getUpdatedAt());
        return view;
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
            log.warn("模板意图字段解析失败，已忽略: {}", e.getMessage());
        }
        return fields;
    }

    private String writeFields(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            throw new BizException("意图字段序列化失败: " + e.getMessage());
        }
    }

    private static String labelOf(String category) {
        if (category == null) {
            return "";
        }
        for (Map<String, String> c : CATEGORIES) {
            if (Objects.equals(c.get("code"), category)) {
                return c.get("label");
            }
        }
        return category;
    }

    private static Map<String, String> cat(String code, String label) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("label", label);
        return item;
    }

    private static Map<String, Object> def(String category, String key, String label,
                                           String type, String defaultValue,
                                           List<Map<String, String>> options) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("category", category);
        item.put("key", key);
        item.put("label", label);
        item.put("type", type);
        item.put("defaultValue", defaultValue);
        item.put("options", options);
        return item;
    }

    private static Map<String, String> opt(String value, String label) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("value", value);
        item.put("label", label);
        return item;
    }
}
