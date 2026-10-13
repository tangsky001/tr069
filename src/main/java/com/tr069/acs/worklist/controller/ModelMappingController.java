package com.tr069.acs.worklist.controller;

import com.tr069.acs.common.Result;
import com.tr069.acs.domain.entity.CpeModel;
import com.tr069.acs.domain.entity.ModelObjectMapping;
import com.tr069.acs.domain.entity.ModelParamMapping;
import com.tr069.acs.worklist.service.ModelMappingService;
import lombok.Data;
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
 * 型号语义映射管理接口。
 *
 * <p>设备上线自动登记型号（待适配），人工在这里做一次「业务字段 ↔ CWMP 节点」映射；
 * 之后同型号同固件的设备直接复用映射，不再人工介入。</p>
 */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelMappingController {

    private final ModelMappingService service;

    /** 型号列表，可按状态与关键字过滤。 */
    @GetMapping
    public Result<List<CpeModel>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return Result.success(service.list(status, keyword));
    }

    /** 业务语义字典：固定的业务对象与字段，映射页左侧用它渲染。 */
    @GetMapping("/schema")
    public Result<List<Map<String, Object>>> schema() {
        return Result.success(service.schema());
    }

    /** 按型号固件查该设备应命中的型号，前端可据此提示「已适配 / 待适配」。 */
    @GetMapping("/match")
    public Result<Map<String, Object>> match(
            @RequestParam(required = false) String isp,
            @RequestParam(required = false) String manufacturer,
            @RequestParam(required = false) String productClass,
            @RequestParam(required = false) String softwareVersion,
            @RequestParam(required = false) String hardwareVersion,
            @RequestParam(required = false) String sn) {
        CpeModel model = service.resolveOrRegister(isp, manufacturer, productClass,
                softwareVersion, hardwareVersion, sn);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("model", model);
        data.put("ready", service.isReady(model));
        return Result.success(data);
    }

    /** 型号详情：档案 + 两类映射 + 在网设备数 + 未映射必填字段。 */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        Map<String, Object> data = service.detail(id);
        return data == null ? Result.failure("型号不存在: " + id) : Result.success(data);
    }

    /** 人工登记新型号。 */
    @PostMapping
    public Result<CpeModel> create(@RequestBody CpeModel model) {
        return Result.success(service.create(model));
    }

    /** 局部更新型号（状态、配置路径、备注等）。 */
    @PostMapping("/{id}/update")
    public Result<CpeModel> update(@PathVariable Long id, @RequestBody CpeModel patch) {
        CpeModel updated = service.update(id, patch);
        return updated == null ? Result.failure("型号不存在: " + id) : Result.success(updated);
    }

    /**
     * 保存映射：参数映射与对象映射整组替换，必填字段齐备后型号自动置为已适配。
     */
    @PostMapping("/{id}/mappings")
    public Result<CpeModel> saveMappings(@PathVariable Long id, @RequestBody MappingRequest request) {
        try {
            List<ModelParamMapping> params = request == null ? List.of()
                    : (request.getParamMappings() == null ? List.of() : request.getParamMappings());
            List<ModelObjectMapping> objects = request == null ? List.of()
                    : (request.getObjectMappings() == null ? List.of() : request.getObjectMappings());
            return Result.success(service.saveMappings(id, params, objects));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 映射候选节点：从样机能力树取参数节点，命中业务对象推荐关键字的排在前面。
     */
    @GetMapping("/candidates")
    public Result<List<Map<String, Object>>> candidates(
            @RequestParam String sn,
            @RequestParam(required = false) String bizObject,
            @RequestParam(required = false) String keyword) {
        return Result.success(service.candidates(sn, bizObject, keyword));
    }

    /**
     * 映射写请求。
     */
    @Data
    public static class MappingRequest {
        /** 业务字段 → 参数路径 */
        private List<ModelParamMapping> paramMappings;
        /** 业务对象 → 对象路径 */
        private List<ModelObjectMapping> objectMappings;
    }
}
