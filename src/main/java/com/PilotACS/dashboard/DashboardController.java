/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.843
 *
 */

package com.PilotACS.dashboard;

import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 首页概览 */
@RestController
@RequestMapping("/dashboard")
@Tag(name = "首页大屏", description = "概览数据：OLT 统计、设备列表、分组统计与设备分布")
@RequiresPermission("dashboard:view")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "概览数据：OLT 统计与设备列表 + 光猫统计与按设备分布")
    @GetMapping("/overview")
    public Result<Object> overview() {
        return Result.ok(dashboardService.overview());
    }
}
