package com.tr069.acs.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tr069.acs.acs.service.DeviceCapabilityService;
import com.tr069.acs.common.PageResult;
import com.tr069.acs.common.Result;
import com.tr069.acs.domain.entity.CpeParameter;
import com.tr069.acs.mapper.CpeParameterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 设备能力树查询与采集触发。
 *
 * <p>数据来自 {@code cpe_parameter}：由 {@code GetParameterNames} 采集路径与读写权限，
 * {@code GetParameterValues} 采集参数值。模板匹配与 {@code SetParameterValues} 下发前，
 * 都应先用这里的接口确认设备是否支持该路径、以及是否可写。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/device/{sn}/capability")
@RequiredArgsConstructor
public class DeviceCapabilityController {

    private final CpeParameterMapper parameterMapper;
    private final DeviceCapabilityService capabilityService;
    private final com.tr069.acs.worklist.service.ModelTreeStore modelTreeStore;

    /**
     * 能力汇总：支持多少路径、对象数、参数数、可写数、最近采集时间与当前采集进度。
     */
    @GetMapping
    public Result<Map<String, Object>> summary(@PathVariable String sn) {
        return Result.success(capabilityService.summary(sn));
    }

    /**
     * 手动触发一次采集。
     *
     * @param force true 时忽略「距上次采集时间」的判断，立即重采
     */
    @PostMapping("/collect")
    public Result<DeviceCapabilityService.CollectStatus> collect(
            @PathVariable String sn,
            @RequestParam(defaultValue = "false") boolean force) {
        return Result.success(capabilityService.collect(sn, force));
    }

    /**
     * 能力树分页查询。
     *
     * @param parent   只看该路径下的子树，前缀匹配
     * @param type     object / param
     * @param writable 只筛可写(1)或只读(0)参数
     * @param keyword  路径关键字
     */
    @GetMapping("/params")
    public Result<PageResult<CpeParameter>> params(
            @PathVariable String sn,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String parent,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer writable,
            @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<CpeParameter> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CpeParameter::getSn, sn)
                .likeRight(notBlank(parent), CpeParameter::getParamPath, parent)
                .eq(notBlank(type), CpeParameter::getNodeType, type)
                .eq(writable != null, CpeParameter::getWritable, writable)
                .like(notBlank(keyword), CpeParameter::getParamPath, keyword)
                .orderByAsc(CpeParameter::getParamPath);
        IPage<CpeParameter> result = parameterMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(PageResult.of(result.getRecords(), result.getTotal(), page, size));
    }

    /**
     * 取某个对象的下一层子节点，parent 为空时返回根节点，用于前端树形展开。
     */
    @GetMapping("/tree")
    public Result<List<CpeParameter>> tree(@PathVariable String sn,
                                           @RequestParam(required = false) String parent) {
        LambdaQueryWrapper<CpeParameter> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CpeParameter::getSn, sn)
                .eq(CpeParameter::getParentPath, parent == null ? "" : parent)
                .orderByAsc(CpeParameter::getParamPath);
        return Result.success(parameterMapper.selectList(wrapper));
    }

    /**
     * 判断设备是否支持指定路径，并回带读写权限与当前值，供下发前校验。
     */
    @GetMapping("/param")
    public Result<Map<String, Object>> param(@PathVariable String sn,
                                             @RequestParam String path) {
        CpeParameter node = parameterMapper.selectOne(
                new LambdaQueryWrapper<CpeParameter>()
                        .eq(CpeParameter::getSn, sn)
                        .eq(CpeParameter::getParamPath, path));
        if (node == null) {
            return Result.failure("设备不支持该路径: " + path);
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("path", node.getParamPath());
        data.put("nodeType", node.getNodeType());
        data.put("writable", node.getWritable() != null && node.getWritable() == 1);
        data.put("value", node.getParamValue());
        data.put("valueType", node.getValueType());
        return Result.success(data);
    }

    /**
     * 把该设备的参数树导出为型号 XML 文件，路径回写 cpe_model.config_path。
     *
     * @param force true 时覆盖已由其它样机导出的同名型号文件
     */
    @PostMapping("/export-tree")
    public Result<com.tr069.acs.worklist.service.ModelTreeStore.ExportResult> exportTree(
            @PathVariable String sn,
            @RequestParam(defaultValue = "false") boolean force) {
        try {
            return Result.success(modelTreeStore.export(sn, force));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }

    /**
     * 该设备型号的能力树文件位置，即下发前「去库里查路径」这一步。
     */
    @GetMapping("/model-file")
    public Result<Map<String, Object>> modelFile(@PathVariable String sn) {
        return Result.success(modelTreeStore.locate(sn));
    }

    /**
     * 从型号 XML 查某个业务项对应的参数节点，如 item=wan_sip_vlan。
     *
     * @param parent 可选，限定在该对象路径下查找
     */
    @GetMapping("/biz-params")
    public Result<List<Map<String, Object>>> bizParams(
            @PathVariable String sn,
            @RequestParam String item,
            @RequestParam(required = false) String parent,
            @RequestParam(defaultValue = "200") int limit) {
        try {
            return Result.success(modelTreeStore.lookup(sn, item, parent, limit));
        } catch (IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
