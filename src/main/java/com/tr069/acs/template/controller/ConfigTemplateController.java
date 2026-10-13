package com.tr069.acs.template.controller;

import com.tr069.acs.common.Result;
import com.tr069.acs.template.model.ConfigTemplateSaveRequest;
import com.tr069.acs.template.service.ConfigTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 配置意图模板：新增 / 编辑 / 删除 / 查询。
 *
 * <p>模板只描述开通意图（IP 协议、是否带 VLAN、是否绑定端口等）并入库存档，
 * 不含参数定义与执行步骤；下发时由执行器按设备型号映射生成真实 CWMP 参数。</p>
 */
@RestController
@RequestMapping("/api/config-templates")
@RequiredArgsConstructor
public class ConfigTemplateController {

    private final ConfigTemplateService service;

    /**
     * 模板类型与各类别的意图字段定义。
     */
    @GetMapping("/meta")
    public Result<Map<String, Object>> meta() {
        return Result.success(service.meta());
    }

    /**
     * 模板列表，可按类型筛选与关键字搜索。
     */
    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String category,
                                                  @RequestParam(required = false) String keyword) {
        return Result.success(service.list(category, keyword));
    }

    /**
     * 单个模板详情，供编辑回填。
     */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.success(service.get(id));
    }

    /**
     * 新增或更新模板。
     */
    @PostMapping
    public Result<Map<String, Object>> save(@RequestBody ConfigTemplateSaveRequest request) {
        return Result.success(service.save(request));
    }

    /**
     * 删除模板。
     */
    @PostMapping("/{id}/delete")
    public Result<?> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success(null);
    }
}
