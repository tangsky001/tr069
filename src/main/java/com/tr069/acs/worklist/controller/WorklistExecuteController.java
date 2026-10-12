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
import com.tr069.acs.worklist.engine.WorklistEngine;
import com.tr069.acs.worklist.model.WorklistOutcome;
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
 * 工单下发与执行记录查询。
 */
@Slf4j
@RestController
@RequestMapping("/api/worklist")
@RequiredArgsConstructor
public class WorklistExecuteController {

    private final WorklistEngine worklistEngine;
    private final WorklistMapper worklistMapper;
    private final WorklistStepMapper worklistStepMapper;

    /**
     * 向设备下发一张工单。
     */
    /**
     * 向设备下发一张工单。
     *
     * <p>提交后立即返回工单号，执行在后台线程池推进：
     * 一张工单要等多次设备空 POST 往返，同步等待会把请求线程挂住几分钟。</p>
     *
     * <p>进度查询：{@code GET /api/worklist/instances/{id}} 与
     * {@code GET /api/worklist/instances/{id}/steps}。</p>
     */
    @PostMapping("/execute")
    public Result<Map<String, Object>> execute(@RequestBody ExecuteRequest request) {
        if (request == null || request.getSn() == null || request.getSn().isBlank()) {
            return Result.failure("设备序列号不能为空");
        }
        if (request.getTemplateKey() == null || request.getTemplateKey().isBlank()) {
            return Result.failure("工单模板不能为空");
        }
        WorklistEngine.WorklistSubmission submission = worklistEngine.submit(
                request.getSn(), request.getTemplateKey(), request.getParams(), request.getUsername());
        if (!submission.isAccepted()) {
            return Result.failure(submission.getError());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("worklistId", submission.getWorklistId());
        data.put("status", "running");
        return Result.success(data);
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

    /**
     * 工单下发请求体。
     */
    @Data
    public static class ExecuteRequest {
        /** 设备序列号。 */
        private String sn;
        /** 工单模板 key。 */
        private String templateKey;
        /** 工单入参。 */
        private Map<String, String> params;
        /** 操作人。 */
        private String username;
    }
}
