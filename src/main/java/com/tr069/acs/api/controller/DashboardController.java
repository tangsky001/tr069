package com.tr069.acs.api.controller;

import com.tr069.acs.api.service.DashboardService;
import com.tr069.acs.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 首页概览数据。
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 概览：设备总量 / 在线 / 离线、品牌分布、已下发 / 未下发设备数、工单与模板统计。
     */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.success(dashboardService.overview());
    }
}
