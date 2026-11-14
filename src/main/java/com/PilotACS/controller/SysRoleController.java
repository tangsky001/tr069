/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.149
 *
 */

package com.PilotACS.controller;

import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.RequiresPermission;
import com.PilotACS.dto.RoleCreateDTO;
import com.PilotACS.service.SysRoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

// 角色管理接口
@Tag(name = "角色管理")
@RestController
@RequestMapping("/system/roles")
@RequiredArgsConstructor
public class SysRoleController {

    private final SysRoleService roleService;

    @Operation(summary = "角色列表")
    @RequiresPermission("system:view")
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(roleService.listRoles());
    }

    @Operation(summary = "新增角色")
    @RequiresPermission("system:update")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody RoleCreateDTO dto) {
        roleService.createRole(dto);
        return Result.ok();
    }

    @Operation(summary = "覆盖保存角色权限")
    @RequiresPermission("system:update")
    @PutMapping("/{code}/permissions")
    public Result<Void> savePermissions(@Parameter(description = "角色编码") @PathVariable String code,
                                        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "权限码数组，全量覆盖，如 {\"permissions\":[\"system:view\",\"system:update\"]}")
                                        @RequestBody Map<String, List<String>> body) {
        roleService.savePermissions(code, body.get("permissions"));
        return Result.ok();
    }

    @Operation(summary = "删除角色")
    @RequiresPermission("system:update")
    @DeleteMapping("/{code}")
    public Result<Void> delete(@Parameter(description = "角色编码") @PathVariable String code) {
        roleService.deleteRole(code);
        return Result.ok();
    }
}
