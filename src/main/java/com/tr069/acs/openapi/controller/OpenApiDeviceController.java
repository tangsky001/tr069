package com.tr069.acs.openapi.controller;

import com.tr069.acs.common.Result;
import com.tr069.acs.domain.entity.CpeDevice;
import com.tr069.acs.openapi.service.OpenApiQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 北向只读接口：设备台账、在线状态、能力树、业务快照、下发结果、工单结果。
 *
 * <p>返回结构与管理端刻意分开：这里字段名是给外部系统用的，一旦发布就不能随页面改，
 * 所以不能把管理端适配 Vue 组件的那套 Map 直接透出去。</p>
 */
@Slf4j
@RestController
@RequestMapping("${acs.openapi.path-prefix:/openapi/v1}/devices")
@RequiredArgsConstructor
public class OpenApiDeviceController {

    private final OpenApiQueryService queryService;

    /**
     * 设备台账分页列表。
     */
    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String isp,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.success(queryService.devices(keyword, isp, page, size));
    }

    /**
     * 设备详情：台账 + 能力汇总 + 数据模型。
     */
    @GetMapping("/{sn}")
    public Result<Map<String, Object>> detail(@PathVariable String sn) {
        Map<String, Object> data = queryService.device(sn);
        if (data == null) {
            return Result.failure("设备不存在: " + sn);
        }
        return Result.success(data);
    }

    /**
     * 在线状态。
     */
    @GetMapping("/{sn}/online")
    public Result<Map<String, Object>> online(@PathVariable String sn) {
        return Result.success(queryService.online(sn));
    }

    /**
     * 能力树某一层，传 parent 为空返回根节点层。
     */
    @GetMapping("/{sn}/capability/tree")
    public Result<List<Map<String, Object>>> tree(
            @PathVariable String sn,
            @RequestParam(required = false) String parent) {
        return Result.success(queryService.capabilityTree(sn, parent));
    }

    /**
     * 参数值清单，可按路径前缀过滤。
     */
    @GetMapping("/{sn}/capability/params")
    public Result<List<Map<String, Object>>> params(
            @PathVariable String sn,
            @RequestParam(required = false) String prefix) {
        return Result.success(queryService.params(sn, prefix));
    }

    /**
     * 业务快照：WAN / WiFi / SIP 分区取值，读的是能力树快照，不打搅设备。
     */
    @GetMapping("/{sn}/business")
    public Result<Map<String, Object>> business(@PathVariable String sn) {
        CpeDevice device = queryService.findDevice(sn);
        if (device == null) {
            return Result.failure("设备不存在: " + sn);
        }
        return Result.success(queryService.business(sn));
    }

    /**
     * 该设备的下发任务历史。
     */
    @GetMapping("/{sn}/deliveries")
    public Result<List<Map<String, Object>>> deliveries(
            @PathVariable String sn,
            @RequestParam(defaultValue = "20") int limit) {
        return Result.success(queryService.tasks(sn, limit));
    }

    /**
     * 工单执行结果。
     */
    @GetMapping("/{sn}/work-orders")
    public Result<List<Map<String, Object>>> workOrders(
            @PathVariable String sn,
            @RequestParam(defaultValue = "20") int limit) {
        return Result.success(queryService.workOrders(sn, null, limit));
    }
}
