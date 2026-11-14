package com.PilotACS.dashboard;

import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 首页概览：左 OLT / 右光猫，配合 /ws/dashboard 实时推送 */
@RestController
@RequestMapping("/dashboard")
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
