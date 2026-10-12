package com.tr069.acs.worklist.controller;

import com.tr069.acs.common.Result;
import com.tr069.acs.worklist.model.TemplateCategory;
import com.tr069.acs.worklist.model.WorklistTemplate;
import com.tr069.acs.worklist.registry.WorklistRegistry;
import com.tr069.acs.worklist.service.WorklistTemplateStore;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 配置模板（工单模板）的新建 / 编辑 / 复制 / 删除。
 *
 * <p>模板就是配置根目录下的文件，这里只负责落盘与重载，读仍然走
 * {@code GET /api/worklist/templates}。</p>
 *
 * <p>用 POST + body 传 key 而不是路径参数：模板 key 形如
 * {@code CT/v4.0/business/ADSL_2LAN/WAN_DHCP}，含多级斜杠，塞进路径要额外转义。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/worklist/templates")
@RequiredArgsConstructor
public class WorklistTemplateController {

    private final WorklistTemplateStore store;
    private final WorklistRegistry worklistRegistry;

    /**
     * 模板类型枚举，供页面渲染分类按钮。
     */
    @GetMapping("/categories")
    public Result<List<Map<String, String>>> categories() {
        List<Map<String, String>> list = new ArrayList<>();
        for (TemplateCategory category : TemplateCategory.values()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("code", category.getCode());
            item.put("label", category.getLabel());
            list.add(item);
        }
        return Result.success(list);
    }

    /**
     * 新建模板时可选择的层级取值，从现有模板聚合，避免现场手打错目录名。
     */
    @GetMapping("/options")
    public Result<Map<String, Object>> options() {
        Set<String> isps = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> versions = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> domains = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> deviceTypes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (WorklistTemplate t : worklistRegistry.validValues()) {
            isps.add(t.getIsp());
            versions.add(t.getVersion());
            domains.add(t.getDomain());
            deviceTypes.add(t.getDeviceType());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("isps", new ArrayList<>(isps));
        data.put("versions", new ArrayList<>(versions));
        data.put("domains", new ArrayList<>(domains));
        data.put("deviceTypes", new ArrayList<>(deviceTypes));
        return Result.success(data);
    }

    /**
     * 单个模板详情（含参数定义与执行步骤），供编辑页回填。
     */
    @GetMapping("/detail")
    public Result<WorklistTemplate> detail(@RequestParam String key) {
        return worklistRegistry.get(key)
                .map(item -> item.isValid()
                        ? Result.success(item.getValue())
                        : Result.<WorklistTemplate>failure("该模板解析失败: " + item.getError()))
                .orElseGet(() -> Result.failure("模板不存在: " + key));
    }

    /**
     * 新建模板。
     */
    @PostMapping
    public Result<WorklistTemplate> create(@RequestBody TemplateRequest request) {
        requireTemplate(request);
        return Result.success(store.create(request.getTemplate(), request.getFormat()));
    }

    /**
     * 编辑 / 复制 / 删除，由 action 分派。
     */
    @PostMapping("/{action}")
    public Result<?> modify(@PathVariable String action, @RequestBody TemplateRequest request) {
        switch (action) {
            case "update" -> {
                requireTemplate(request);
                requireKey(request.getKey());
                return Result.success(store.update(request.getKey(), request.getTemplate()));
            }
            case "copy" -> {
                requireTemplate(request);
                requireKey(request.getSourceKey());
                return Result.success(store.copyAs(request.getSourceKey(),
                        request.getTemplate(), request.getFormat()));
            }
            case "delete" -> {
                requireKey(request.getKey());
                store.delete(request.getKey());
                return Result.success(null);
            }
            default -> {
                return Result.failure("未知操作: " + action);
            }
        }
    }

    private void requireTemplate(TemplateRequest request) {
        if (request == null || request.getTemplate() == null) {
            throw new IllegalArgumentException("模板内容不能为空");
        }
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("模板 key 不能为空");
        }
    }

    /**
     * 模板写请求。
     */
    @Data
    public static class TemplateRequest {
        /** 目标模板 key，更新 / 删除时使用。 */
        private String key;
        /** 源模板 key，复制时使用。 */
        private String sourceKey;
        /** 落盘格式：yaml / xml，为空时自动选择。 */
        private String format;
        /** 模板内容。 */
        private WorklistTemplate template;
    }
}
