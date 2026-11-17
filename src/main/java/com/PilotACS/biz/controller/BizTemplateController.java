package com.PilotACS.biz.controller;

import com.PilotACS.biz.model.BizTemplateDetail;
import com.PilotACS.biz.model.BizTemplateSaveRequest;
import com.PilotACS.biz.service.BizTemplateExecutionService;
import com.PilotACS.biz.service.BizTemplateService;
import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.RequiresPermission;
import com.PilotACS.common.security.UserContext;
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

/** 业务模板：CRUD / 校验 / 整包下发 / 执行记录 */
@Tag(name = "业务模板", description = "多张配置模板组成的整包业务，按序串行下发到光猫")
@RestController
@RequestMapping("/biz-template")
@RequiredArgsConstructor
public class BizTemplateController {

    private final BizTemplateService templateService;
    private final BizTemplateExecutionService executionService;

    /** 业务模板列表，可按分组筛选与关键字搜索 */
    @Operation(summary = "业务模板列表：可按分组筛选、按名称/备注搜索")
    @GetMapping("/list")
    @RequiresPermission("biz:view")
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String groupName,
                                                  @RequestParam(required = false) String keyword) {
        return Result.ok(templateService.list(groupName, keyword));
    }

    /** 业务模板详情（含成员视图） */
    @Operation(summary = "业务模板详情：含成员列表与每个成员引用的配置模板摘要")
    @GetMapping("/{id}")
    @RequiresPermission("biz:view")
    public Result<BizTemplateDetail> detail(@PathVariable Long id) {
        return Result.ok(templateService.get(id));
    }

    /** 保存业务模板（带 id 为更新，成员整体替换） */
    @Operation(summary = "保存业务模板：id 为空新建，否则整体替换成员")
    @PostMapping("/save")
    @RequiresPermission("biz:update")
    public Result<Map<String, Object>> save(@RequestBody BizTemplateSaveRequest request) {
        return Result.ok(templateService.save(request));
    }

    /** 删除业务模板 */
    @Operation(summary = "删除业务模板（历史执行记录保留）")
    @PostMapping("/delete")
    @RequiresPermission("biz:delete")
    public Result<Void> delete(@RequestBody Map<String, Long> body) {
        Long id = body == null ? null : body.get("id");
        if (id == null) {
            return Result.fail("缺少 id");
        }
        templateService.delete(id);
        return Result.ok();
    }

    /** 下发前校验：返回问题清单，空列表表示可下发 */
    @Operation(summary = "下发前校验：模板存在/启用、成员配置模板齐全且启用")
    @GetMapping("/{id}/validate")
    @RequiresPermission("biz:view")
    public Result<List<String>> validate(@PathVariable Long id) {
        return Result.ok(templateService.validate(id));
    }

    /** 整包下发：串行执行成员，返回执行记录 id 供轮询 */
    @Operation(summary = "整包下发到指定光猫（sn）：串行执行成员，返回执行记录 id")
    @PostMapping("/execute")
    @RequiresPermission("biz:update")
    public Result<Map<String, Object>> execute(@RequestBody Map<String, Object> body) {
        Long templateId = toLong(body.get("templateId"));
        String sn = body.get("sn") == null ? "" : String.valueOf(body.get("sn")).trim();
        if (templateId == null || sn.isEmpty()) {
            return Result.fail("缺少 templateId 或 sn");
        }
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> itemParams =
                (Map<String, Map<String, String>>) body.get("itemParams");
        String roomNo = body.get("roomNo") == null ? "" : String.valueOf(body.get("roomNo")).trim();
        Long execId = executionService.execute(templateId, sn, itemParams, roomNo,
                UserContext.get().getUsername());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("executionId", execId);
        return Result.ok(out);
    }

    /** 执行记录列表（可按 sn 过滤） */
    @Operation(summary = "执行记录列表：可按 sn 过滤，最多 100 条")
    @GetMapping("/executions")
    @RequiresPermission("biz:view")
    public Result<List<Map<String, Object>>> executions(@RequestParam(required = false) String sn,
                                                        @RequestParam(defaultValue = "20") int limit) {
        return Result.ok(executionService.executions(sn, limit));
    }

    /** 单次执行详情（含每成员步骤明细，前端轮询进度用） */
    @Operation(summary = "执行记录详情：含每个成员的执行结果与步骤明细")
    @GetMapping("/executions/{id}")
    @RequiresPermission("biz:view")
    public Result<Map<String, Object>> execution(@PathVariable Long id) {
        return Result.ok(executionService.execution(id));
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
