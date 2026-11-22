package com.PilotACS.biz.service;

import com.PilotACS.biz.entity.BizTemplate;
import com.PilotACS.biz.entity.BizTemplateItem;
import com.PilotACS.biz.mapper.BizTemplateExecutionMapper;
import com.PilotACS.biz.mapper.BizTemplateItemMapper;
import com.PilotACS.biz.mapper.BizTemplateMapper;
import com.PilotACS.biz.model.BizTemplateDetail;
import com.PilotACS.biz.model.BizTemplateItemInput;
import com.PilotACS.biz.model.BizTemplateItemView;
import com.PilotACS.biz.model.BizTemplateSaveRequest;
import com.PilotACS.common.exception.BusinessException;
import com.PilotACS.template.model.TemplateFieldDefs;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 业务模板服务 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizTemplateService {

    private final BizTemplateMapper templateMapper;
    private final BizTemplateItemMapper itemMapper;
    private final BizTemplateExecutionMapper executionMapper;
    private final ConfigTemplateResolver templateResolver;
    private final ObjectMapper objectMapper;

    /** 业务模板列表，可按分组筛选与关键字搜索 */
    public List<Map<String, Object>> list(String groupName, String keyword) {
        LambdaQueryWrapper<BizTemplate> query = new LambdaQueryWrapper<>();
        if (groupName != null && !groupName.isBlank()) {
            query.eq(BizTemplate::getGroupName, groupName.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like(BizTemplate::getName, kw)
                    .or().like(BizTemplate::getRemark, kw));
        }
        query.orderByDesc(BizTemplate::getUpdatedAt);
        List<Map<String, Object>> views = new ArrayList<>();
        for (BizTemplate template : templateMapper.selectList(query)) {
            List<BizTemplateItemView> items = toItemViews(itemsOf(template.getId()));
            Map<String, Object> tpl = new LinkedHashMap<>();
            tpl.put("id", template.getId());
            tpl.put("name", template.getName());
            tpl.put("groupName", template.getGroupName());
            tpl.put("remark", template.getRemark());
            tpl.put("enabled", template.getEnabled() != null && template.getEnabled());
            tpl.put("continueOnError", template.getContinueOnError() != null && template.getContinueOnError());
            tpl.put("createdAt", template.getCreatedAt());
            tpl.put("updatedAt", template.getUpdatedAt());
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("template", tpl);
            view.put("items", items);
            views.add(view);
        }
        return views;
    }

    /** 业务模板详情 */
    public BizTemplateDetail get(Long id) {
        BizTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw new BusinessException("业务模板不存在: " + id);
        }
        BizTemplateDetail detail = new BizTemplateDetail();
        detail.setId(template.getId());
        detail.setName(template.getName());
        detail.setGroupName(template.getGroupName());
        detail.setRemark(template.getRemark());
        detail.setEnabled(template.getEnabled());
        detail.setContinueOnError(template.getContinueOnError());
        detail.setCreatedAt(template.getCreatedAt());
        detail.setUpdatedAt(template.getUpdatedAt());
        detail.setItems(toItemViews(itemsOf(id)));
        return detail;
    }

    /** 业务模板的成员列表（原始行） */
    public List<BizTemplateItem> itemsOf(Long templateId) {
        return itemMapper.selectList(new LambdaQueryWrapper<BizTemplateItem>()
                .eq(BizTemplateItem::getTemplateId, templateId)
                .orderByAsc(BizTemplateItem::getItemOrder)
                .orderByAsc(BizTemplateItem::getId));
    }

    /** 成员视图 */
    public List<BizTemplateItemView> toItemViews(List<BizTemplateItem> items) {
        List<BizTemplateItemView> views = new ArrayList<>();
        for (BizTemplateItem item : items) {
            BizTemplateItemView view = new BizTemplateItemView();
            view.setId(item.getId());
            view.setTemplateId(item.getTemplateId());
            view.setTemplateKey(item.getTemplateKey());
            view.setItemOrder(item.getItemOrder());
            view.setRemark(item.getRemark());
            view.setParams(readParams(item.getParams()));

            Map<String, Object> cfg = templateResolver.findView(parseKey(item.getTemplateKey()));
            if (cfg == null) {
                view.setAvailable(false);
                view.setExecutable(false);
                view.setIssue("配置模板不存在或已删除: " + item.getTemplateKey());
            } else {
                view.setTemplateName(String.valueOf(cfg.get("name")));
                view.setCategory(String.valueOf(cfg.get("category")));
                view.setCategoryLabel(String.valueOf(cfg.get("categoryLabel")));
                view.setIntentSummary(String.valueOf(cfg.get("intentSummary")));
                boolean enabled = cfg.get("enabled") instanceof Boolean b && b;
                view.setAvailable(enabled);
                view.setExecutable(enabled);
                if (!enabled) {
                    view.setIssue("配置模板已停用");
                }
            }
            views.add(view);
        }
        return views;
    }

    /** 保存业务模板 */
    @Transactional
    public Map<String, Object> save(BizTemplateSaveRequest request) {
        if (request == null) {
            throw new BusinessException("提交内容不能为空");
        }
        String name = request.getName() == null ? "" : request.getName().trim();
        if (name.isEmpty()) {
            throw new BusinessException("业务模板名称不能为空");
        }
        boolean dup = templateMapper.selectCount(new LambdaQueryWrapper<BizTemplate>()
                .eq(BizTemplate::getName, name)
                .ne(request.getId() != null, BizTemplate::getId, request.getId())) > 0;
        if (dup) {
            throw new BusinessException("已存在同名业务模板: " + name);
        }

        BizTemplate template = request.getId() == null ? new BizTemplate() : templateMapper.selectById(request.getId());
        if (template == null) {
            throw new BusinessException("业务模板不存在: " + request.getId());
        }
        template.setName(name);
        template.setGroupName(request.getGroupName() == null ? "" : request.getGroupName().trim());
        template.setRemark(request.getRemark() == null ? "" : request.getRemark().trim());
        template.setEnabled(request.getEnabled() == null || request.getEnabled());
        template.setContinueOnError(request.getContinueOnError() != null && request.getContinueOnError());
        LocalDateTime now = LocalDateTime.now();
        if (request.getId() == null) {
            template.setCreatedAt(now);
            template.setUpdatedAt(now);
            templateMapper.insert(template);
        } else {
            template.setUpdatedAt(now);
            templateMapper.updateById(template);
            itemMapper.delete(new LambdaQueryWrapper<BizTemplateItem>()
                    .eq(BizTemplateItem::getTemplateId, template.getId()));
        }

        if (request.getItems() != null) {
            int order = 0;
            for (BizTemplateItemInput input : request.getItems()) {
                order++;
                BizTemplateItem item = new BizTemplateItem();
                item.setTemplateId(template.getId());
                item.setTemplateKey(input.getTemplateKey() == null ? "" : input.getTemplateKey().trim());
                if (item.getTemplateKey().isEmpty()) {
                    throw new BusinessException("成员 " + order + " 未选择配置模板");
                }
                item.setItemOrder(input.getItemOrder() == null ? order : input.getItemOrder());
                item.setRemark(input.getRemark() == null ? "" : input.getRemark().trim());
                item.setParams(writeParams(input.getParams() == null ? new HashMap<>() : input.getParams()));
                itemMapper.insert(item);
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", template.getId());
        view.put("name", template.getName());
        view.put("itemCount", itemsOf(template.getId()).size());
        return view;
    }

    /** 删除业务模板 */
    @Transactional
    public void delete(Long id) {
        if (templateMapper.selectById(id) == null) {
            throw new BusinessException("业务模板不存在: " + id);
        }
        itemMapper.delete(new LambdaQueryWrapper<BizTemplateItem>()
                .eq(BizTemplateItem::getTemplateId, id));
        templateMapper.deleteById(id);
    }

    /** 下发前校验 */
    public List<String> validate(Long id) {
        List<String> problems = new ArrayList<>();
        BizTemplate template = templateMapper.selectById(id);
        if (template == null) {
            problems.add("业务模板不存在");
            return problems;
        }
        if (template.getEnabled() == null || !template.getEnabled()) {
            problems.add("业务模板已停用，不允许下发");
        }
        List<BizTemplateItem> items = itemsOf(id);
        if (items.isEmpty()) {
            problems.add("业务模板没有任何成员");
        }
        for (BizTemplateItem item : items) {
            Long key = parseKey(item.getTemplateKey());
            Map<String, Object> cfg = key == null ? null : templateResolver.findView(key);
            if (cfg == null) {
                problems.add("成员引用的配置模板不存在: " + item.getTemplateKey());
                continue;
            }
            if (!(cfg.get("enabled") instanceof Boolean b) || !b) {
                problems.add("成员配置模板已停用: " + cfg.get("name"));
            }
        }
        return problems;
    }

    private Long parseKey(String templateKey) {
        if (templateKey == null || templateKey.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(templateKey.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, String> readParams(String json) {
        Map<String, String> params = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return params;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            if (parsed != null) {
                parsed.forEach((k, v) -> {
                    if (k != null && v != null) {
                        params.put(k, String.valueOf(v));
                    }
                });
            }
        } catch (Exception e) {
            log.warn("业务模板成员入参解析失败，已忽略: {}", e.getMessage());
        }
        return params;
    }

    private String writeParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new BusinessException("成员入参序列化失败: " + e.getMessage());
        }
    }
}
