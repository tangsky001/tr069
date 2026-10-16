package com.tr069.acs.worklist.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tr069.acs.common.PageResult;
import com.tr069.acs.common.Result;
import com.tr069.acs.domain.entity.Worklist;
import com.tr069.acs.domain.entity.WorklistStepRecord;
import com.tr069.acs.mapper.WorklistMapper;
import com.tr069.acs.mapper.WorklistStepMapper;
import com.tr069.acs.worklist.service.WorklistDeliveryService;
import com.tr069.acs.worklist.tpl.TemplateVariables;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单下发与执行记录查询。
 */
@Slf4j
@RestController
@RequestMapping("/api/worklist")
@RequiredArgsConstructor
public class WorklistExecuteController {

    private final WorklistMapper worklistMapper;
    private final WorklistStepMapper worklistStepMapper;
    private final WorklistDeliveryService deliveryService;

    /**
     * 某类业务需要填写哪些变量，前端据此渲染下发表单。
     *
     * <p>变量清单由 {@link TemplateVariables} 统一定义，新增业务只需改那里，页面不用动。</p>
     */
    @GetMapping("/variables")
    public Result<List<TemplateVariables.Var>> variables(@RequestParam String category) {
        return Result.success(TemplateVariables.forCategory(category));
    }

    /**
     * 提交一张下发工单。
     *
     * <p>以 {@code worklistDesc} 做幂等键：重复提交只受理一次，返回首次创建的工单，
     * 用户手抖点两下或网络重试都不会给设备重复开业务。</p>
     */
    @PostMapping("/instances")
    public Result<Worklist> create(@RequestBody CreateRequest body) {
        Worklist created = deliveryService.create(
                body.getSn(),
                body.getCategory(),
                body.getWorklistDesc(),
                body.getValues(),
                body.getUsername(),
                body.getTemplateId());
        return Result.success(created);
    }

    /** 建单入参。 */
    @Data
    public static class CreateRequest {
        /** 设备序列号 */
        private String sn;
        /** 业务类型：WIFI_24G / WIFI_58G / SIP / WAN_DHCP / WAN_PPPOE / WAN_STATIC / WAN_BRIDGE */
        private String category;
        /** 调用方流水号，幂等键；不传由调用方保证不重复时可省略 */
        private String worklistDesc;
        /** 变量值，键为 %room% 这类占位符 */
        private Map<String, String> values;
        /** 操作人 */
        private String username;
        /** 开通模板 id；不传则按业务类型自动取第一条模板 */
        private Long templateId;
    }

    /**
     * 工单执行记录，按设备或状态查询。
     */
    @GetMapping("/instances")
    public Result<PageResult<Worklist>> instances(
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        LambdaQueryWrapper<Worklist> query = new LambdaQueryWrapper<>();
        if (sn != null && !sn.isBlank()) {
            query.eq(Worklist::getSn, sn);
        }
        if (status != null && !status.isBlank()) {
            query.eq(Worklist::getStatus, status);
        }
        query.orderByDesc(Worklist::getId);
        Page<Worklist> pager = Page.of(page, size);
        IPage<Worklist> result = worklistMapper.selectPage(pager, query);
        return Result.success(PageResult.of(result.getRecords(), result.getTotal(), page, size));
    }

    /**
     * 单张工单的执行进度。
     *
     * <p>工单是异步执行的，调用方提交后只拿到工单号，靠这个接口轮询结果，
     * 配合 {@code /instances/{id}/steps} 可以看到每一步的明细。</p>
     */
    @GetMapping("/instances/{id}")
    public Result<Worklist> instance(@PathVariable Long id) {
        Worklist record = worklistMapper.selectById(id);
        if (record == null) {
            return Result.failure("工单不存在: " + id);
        }
        return Result.success(record);
    }

    /**
     * 某个设备的工单历史。
     */
    @GetMapping("/instances/device/{sn}")
    public Result<List<Worklist>> listByDevice(@PathVariable String sn) {
        return Result.success(worklistMapper.selectList(
                new LambdaQueryWrapper<Worklist>()
                        .eq(Worklist::getSn, sn)
                        .orderByDesc(Worklist::getId)
                        .last("limit 100")));
    }

    /**
     * 单张工单的步骤明细。
     */
    @GetMapping("/instances/{id}/steps")
    public Result<List<WorklistStepRecord>> steps(@PathVariable Long id) {
        return Result.success(worklistStepMapper.selectList(
                new LambdaQueryWrapper<WorklistStepRecord>()
                        .eq(WorklistStepRecord::getWorklistId, id)
                        .orderByAsc(WorklistStepRecord::getStepIndex)));
    }

}
