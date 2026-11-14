/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.380
 *
 */

package com.PilotACS.controller;

import com.PilotACS.common.constant.PermissionGroups;
import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.RequiresPermission;
import com.PilotACS.service.SysLoginLogService;
import com.PilotACS.service.SysOperLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 权限组 / 密码策略 / 登录日志
@Tag(name = "系统杂项")
@RestController
@RequestMapping("/system")
@RequiredArgsConstructor
public class SysMiscController {

    private final SysLoginLogService loginLogService;
    private final SysOperLogService operLogService;

    @Operation(summary = "权限组清单")
    @RequiresPermission("system:view")
    @GetMapping("/permissions")
    public Result<Map<String, Object>> permissions() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groups", PermissionGroups.groups());
        return Result.ok(data);
    }

    @Operation(summary = "账号密码策略")
    @GetMapping("/password-policy")
    public Result<Map<String, Object>> passwordPolicy() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("minLength", 6);
        data.put("usernamePattern", "3-32 位字母、数字或下划线");
        data.put("passwordHint", "至少 6 位");
        return Result.ok(data);
    }

    @Operation(summary = "登录日志")
    @RequiresPermission("system:view")
    @GetMapping("/login-logs")
    public Result<List<Map<String, Object>>> loginLogs(
            @Parameter(description = "返回条数上限，默认 200") @RequestParam(required = false) Long limit,
            @Parameter(description = "按 IP 模糊过滤") @RequestParam(required = false) String ip,
            @Parameter(description = "按登录标识过滤：WEB/CLIENT/MOBILE") @RequestParam(required = false) String source,
            @Parameter(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss") @RequestParam(required = false) String from,
            @Parameter(description = "结束时间，格式 yyyy-MM-dd HH:mm:ss") @RequestParam(required = false) String to) {
        return Result.ok(loginLogService.search(limit, ip, source, from, to));
    }

    @Operation(summary = "操作日志：修改密码/分组管理/移动设备/ACL 授权等关键动作")
    @RequiresPermission("system:view")
    @GetMapping("/oper-logs")
    public Result<List<Map<String, Object>>> operLogs(
            @Parameter(description = "返回条数上限，默认 200") @RequestParam(required = false) Long limit,
            @Parameter(description = "按模块过滤：用户/分组/设备权限/OLT") @RequestParam(required = false) String module,
            @Parameter(description = "按操作人过滤") @RequestParam(required = false) String username,
            @Parameter(description = "按动作或详情模糊搜索") @RequestParam(required = false) String keyword,
            @Parameter(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss") @RequestParam(required = false) String from,
            @Parameter(description = "结束时间，格式 yyyy-MM-dd HH:mm:ss") @RequestParam(required = false) String to) {
        return Result.ok(operLogService.search(limit, module, username, keyword, from, to));
    }
}
