package com.tr069.acs.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.biz.entity.BizTemplate;
import com.tr069.acs.biz.entity.BizTemplateItem;
import com.tr069.acs.biz.mapper.BizTemplateItemMapper;
import com.tr069.acs.biz.mapper.BizTemplateMapper;
import com.tr069.acs.biz.model.BizTemplateDetail;
import com.tr069.acs.biz.model.BizTemplateItemInput;
import com.tr069.acs.biz.model.BizTemplateItemView;
import com.tr069.acs.biz.model.BizTemplateSaveRequest;
import com.tr069.acs.common.exception.BizException;
import com.tr069.acs.template.service.ConfigTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务模板的增删改查。
 *
 * <p>业务模板本身只存「名称 + 分组 + 备注」，真正的业务内容是其成员指向的配置模板，
 * 因此成员保存采用整体替换：页面提交多少条就落多少条，省去逐条 diff 的复杂度。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizTemplateService {

    private final BizTemplateMapper templateMapper;
    private final BizTemplateItemMapper itemMapper;
    private final ConfigTemplateService configTemplateService;
    private final ObjectMapper objectMapper;

    /**
     * 业务模板列表，含成员。
     *
     * @param keyword 名称 / 分组模糊匹配
     */
    public List<BizTemplateDetail> list(String keyword) {
        LambdaQueryWrapper<BizTemplate> query = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like(BizTemplate::getName, kw).or().like(BizTemplate::getGroupName, kw));
        }
        query.orderByDesc(BizTemplate::getId);
        List<BizTemplateDetail> result = new ArrayList<>();
        for (BizTemplate template : templateMapper.selectList(query)) {
            result.add(detail(template));
        }
        return result;
    }

    /**
     * 单个业务模板详情。
     */
    public BizTemplateDetail get(Long id) {
        BizTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw new BizException("业务模板不存在: " + id);
        }
        return detail(template);
    }

    /**
     * 新增或更新业务模板及其成员。
     */
    @Transactional(rollbackFor = Exception.class)
    public BizTemplateDetail save(BizTemplateSaveRequest request) {
        if (request == null || request.getTemplate() == null) {
            throw new BizException("业务模板内容不能为空");
        }
        BizTemplate template = request.getTemplate();
        if (template.getName() == null || template.getName().isBlank()) {
            throw new BizException("业务模板名称不能为空");
        }
        if (template.getGroupName() == null || template.getGroupName().isBlank()) {
            throw new BizException("分组不能为空");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BizException("至少选择一个配置模板成员");
        }
        if (template.getEnabled() == null) {
            template.setEnabled(Boolean.TRUE);
        }
        if (template.getContinueOnError() == null) {
            template.setContinueOnError(Boolean.FALSE);
        }
        LocalDateTime now = LocalDateTime.now();

        if (template.getId() == null) {
            int dup = templateMapper.selectCount(new LambdaQueryWrapper<BizTemplate>()
                    .eq(BizTemplate::getName, template.getName())).intValue();
            if (dup > 0) {
                throw new BizException("同名业务模板已存在: " + template.getName());
            }
            template.setCreatedAt(now);
            template.setUpdatedAt(now);
            templateMapper.insert(template);
        } else {
            BizTemplate existing = templateMapper.selectById(template.getId());
            if (existing == null) {
                throw new BizException("业务模板不存在: " + template.getId());
            }
            template.setCreatedAt(existing.getCreatedAt());
            template.setUpdatedAt(now);
            templateMapper.updateById(template);
        }

        replaceItems(template.getId(), request.getItems());
        return detail(template);
    }

    /**
     * 删除业务模板及其成员。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (templateMapper.selectById(id) == null) {
            throw new BizException("业务模板不存在: " + id);
        }
        itemMapper.delete(new LambdaQueryWrapper<BizTemplateItem>()
                .eq(BizTemplateItem::getTemplateId, id));
        templateMapper.deleteById(id);
    }

    /**
     * 校验业务模板能否下发：启用、有成员、成员模板都在且已迁移。
     *
     * @return 不可下发的原因列表，为空表示可以下发
     */
    public List<String> validate(Long id) {
        BizTemplate template = templateMapper.selectById(id);
        List<String> issues = new ArrayList<>();
        if (template == null) {
            issues.add("业务模板不存在: " + id);
            return issues;
        }
        if (!Boolean.TRUE.equals(template.getEnabled())) {
            issues.add("业务模板已停用");
        }
        List<BizTemplateItem> items = itemsOf(id);
        if (items.isEmpty()) {
            issues.add("业务模板未配置任何配置模板成员");
        }
        for (BizTemplateItem item : items) {
            BizTemplateItemView view = toView(item);
            if (!view.isAvailable() || !view.isExecutable()) {
                issues.add(item.getTemplateKey() + " : " + view.getIssue());
            }
        }
        return issues;
    }

    /**
     * 取成员实体，按执行顺序。
     */
    public List<BizTemplateItem> itemsOf(Long templateId) {
        return itemMapper.selectList(new LambdaQueryWrapper<BizTemplateItem>()
                .eq(BizTemplateItem::getTemplateId, templateId)
                .orderByAsc(BizTemplateItem::getItemOrder)
                .orderByAsc(BizTemplateItem::getId));
    }

    // ==================== 内部实现 ====================

    private void replaceItems(Long templateId, List<BizTemplateItemInput> inputs) {
        itemMapper.delete(new LambdaQueryWrapper<BizTemplateItem>()
                .eq(BizTemplateItem::getTemplateId, templateId));
        if (inputs == null || inputs.isEmpty()) {
            return;
        }
        int order = 1;
        for (BizTemplateItemInput input : inputs) {
            if (input == null || input.getTemplateKey() == null || input.getTemplateKey().isBlank()) {
                continue;
            }
            BizTemplateItem item = new BizTemplateItem();
            item.setTemplateId(templateId);
            item.setTemplateKey(input.getTemplateKey().trim());
            item.setItemOrder(input.getItemOrder() == null ? order : input.getItemOrder());
            item.setParams(writeParams(input.getParams()));
            item.setRemark(input.getRemark());
            itemMapper.insert(item);
            order++;
        }
    }

    private BizTemplateDetail detail(BizTemplate template) {
        BizTemplateDetail detail = BizTemplateDetail.of(template);
        for (BizTemplateItem item : itemsOf(template.getId())) {
            detail.getItems().add(toView(item));
        }
        return detail;
    }

    private BizTemplateItemView toView(BizTemplateItem item) {
        BizTemplateItemView view = new BizTemplateItemView();
        view.setId(item.getId());
        view.setTemplateId(item.getTemplateId());
        view.setTemplateKey(item.getTemplateKey());
        view.setItemOrder(item.getItemOrder());
        view.setRemark(item.getRemark());
        view.setParams(parseParams(item.getParams()));

        // 成员 templateKey 存的是配置意图模板 id
        Map<String, Object> tpl = configTemplateService.findView(parseId(item.getTemplateKey()));
        if (tpl == null) {
            view.setAvailable(false);
            view.setExecutable(false);
            view.setTemplateName(lastSegment(item.getTemplateKey()));
            view.setIssue("配置模板不存在");
            return view;
        }
        view.setAvailable(true);
        view.setTemplateName(String.valueOf(tpl.get("name")));
        view.setCategory(String.valueOf(tpl.get("category")));
        view.setCategoryLabel(String.valueOf(tpl.get("categoryLabel")));
        view.setDoc(String.valueOf(tpl.get("remark")));
        view.setExecutable(Boolean.TRUE.equals(tpl.get("enabled")));
        if (!view.isExecutable()) {
            view.setIssue("配置模板已停用");
        }
        return view;
    }

    /**
     * 成员引用的配置模板 id；存量数据存的是旧的文件 key，解析不出数字即视为失效。
     */
    private static Long parseId(String templateKey) {
        if (templateKey == null || templateKey.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(templateKey.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String writeParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new BizException("成员参数序列化失败: " + e.getMessage());
        }
    }

    /**
     * 解析成员上配置的入参覆盖（JSON 对象字符串）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> parseParams(String json) {
        Map<String, String> params = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return params;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            parsed.forEach((k, v) -> params.put(k, v == null ? null : String.valueOf(v)));
        } catch (Exception e) {
            log.warn("业务模板成员参数解析失败，已忽略: {}", json);
        }
        return params;
    }

    private static String lastSegment(String key) {
        if (key == null) {
            return null;
        }
        int idx = key.lastIndexOf('/');
        return idx < 0 ? key : key.substring(idx + 1);
    }
}
