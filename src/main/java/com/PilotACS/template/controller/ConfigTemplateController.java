package com.PilotACS.template.controller;

import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.RequiresPermission;
import com.PilotACS.template.model.ConfigTemplateSaveRequest;
import com.PilotACS.template.service.ConfigTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/** 配置意图模板：新增 / 编辑 / 删除 / 查询 */
@Tag(name = "配置模板", description = "光猫 CWMP 单业务意图模板：类型/字段定义、增删改查")
@RestController
@RequestMapping("/config-templates")
@RequiredArgsConstructor
public class ConfigTemplateController {

    private final ConfigTemplateService service;

    /** 模板类型与各类别的意图字段定义 */
    @Operation(summary = "模板元数据：类型清单、各类别意图字段定义、绑定端口候选")
    @GetMapping("/meta")
    @RequiresPermission("template:view")
    public Result<Map<String, Object>> meta() {
        return Result.ok(service.meta());
    }

    /** 模板列表，可按类型筛选与关键字搜索 */
    @Operation(summary = "模板列表：可按类型筛选、按名称/备注/分组搜索")
    @GetMapping
    @RequiresPermission("template:view")
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String category,
                                                  @RequestParam(required = false) String keyword) {
        return Result.ok(service.list(category, keyword));
    }

    /** 单个模板详情，供编辑回填 */
    @Operation(summary = "模板详情")
    @GetMapping("/{id}")
    @RequiresPermission("template:view")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    /** 新增或更新模板（带 id 为更新） */
    @Operation(summary = "保存模板：id 为空新建，否则更新")
    @PostMapping
    @RequiresPermission("template:update")
    public Result<Map<String, Object>> save(@RequestBody ConfigTemplateSaveRequest request) {
        return Result.ok(service.save(request));
    }

    /** 删除模板 */
    @Operation(summary = "删除模板")
    @PostMapping("/{id}/delete")
    @RequiresPermission("template:delete")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
