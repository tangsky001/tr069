package com.tr069.acs.biz.controller;

import com.tr069.acs.biz.entity.BizTemplateExecution;
import com.tr069.acs.biz.model.BizTemplateDetail;
import com.tr069.acs.biz.model.BizTemplateSaveRequest;
import com.tr069.acs.biz.service.BizTemplateExecutionService;
import com.tr069.acs.biz.service.BizTemplateService;
import com.tr069.acs.common.Result;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务模板管理：把多张配置模板编排成一个业务，对设备整包下发。
 */
@Slf4j
@RestController
@RequestMapping("/api/biz-template")
@RequiredArgsConstructor
public class BizTemplateController {

    private final BizTemplateService bizTemplateService;
    private final BizTemplateExecutionService executionService;

    /**
     * 业务模板列表。
     */
    @GetMapping("/list")
    public Result<List<BizTemplateDetail>> list(@RequestParam(required = false) String keyword) {
        return Result.success(bizTemplateService.list(keyword));
    }

    /**
     * 单个业务模板详情。
     */
    @GetMapping("/{id}")
    public Result<BizTemplateDetail> detail(@PathVariable Long id) {
        return Result.success(bizTemplateService.get(id));
    }

    /**
     * 新增或更新业务模板。
     */
    @PostMapping("/save")
    public Result<BizTemplateDetail> save(@RequestBody BizTemplateSaveRequest request) {
        return Result.success(bizTemplateService.save(request));
    }

    /**
     * 删除业务模板。
     */
    @PostMapping("/delete")
    public Result<Void> delete(@RequestBody IdRequest request) {
        if (request == null || request.getId() == null) {
            return Result.failure("业务模板 id 不能为空");
        }
        bizTemplateService.delete(request.getId());
        return Result.success(null);
    }

    /**
     * 下发前自检：模板是否启用、成员是否齐全、引用的配置模板是否都可下发。
     */
    @GetMapping("/{id}/validate")
    public Result<Map<String, Object>> validate(@PathVariable Long id) {
        List<String> issues = bizTemplateService.validate(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pass", issues.isEmpty());
        data.put("issues", issues);
        return Result.success(data);
    }

    /**
     * 对指定设备整包下发业务模板。
     *
     * <p>立即返回执行记录 id，页面用 {@code GET /api/biz-template/executions/{id}} 轮询进度。</p>
     */
    @PostMapping("/execute")
    public Result<Map<String, Object>> execute(@RequestBody ExecuteRequest request) {
        if (request == null || request.getTemplateId() == null) {
            return Result.failure("业务模板不能为空");
        }
        Long executionId = executionService.execute(
                request.getTemplateId(), request.getSn(), request.getParams(), request.getUsername());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionId", executionId);
        data.put("status", "running");
        return Result.success(data);
    }

    /**
     * 下发记录列表。
     */
    @GetMapping("/executions")
    public Result<List<BizTemplateExecution>> executions(
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) Long templateId,
            @RequestParam(defaultValue = "20") int limit) {
        return Result.success(executionService.list(sn, templateId, limit));
    }

    /**
     * 单条下发记录，含每个成员的执行结果。
     */
    @GetMapping("/executions/{id}")
    public Result<BizTemplateExecution> execution(@PathVariable Long id) {
        return Result.success(executionService.get(id));
    }

    /**
     * 仅含 id 的请求体。
     */
    @Data
    public static class IdRequest {
        private Long id;
    }

    /**
     * 整包下发请求体。
     */
    @Data
    public static class ExecuteRequest {
        /** 业务模板 id。 */
        private Long templateId;
        /** 设备序列号。 */
        private String sn;
        /** 下发入参，覆盖成员上配置的默认值。 */
        private Map<String, String> params;
        /** 操作人。 */
        private String username;
    }
}
