/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.785
 *
 */

package com.PilotACS.controller;

import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.RequiresPermission;
import com.PilotACS.dto.UserCreateDTO;
import com.PilotACS.dto.UserUpdateDTO;
import com.PilotACS.service.SysUserService;
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

/** 用户管理接口 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/system/users")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserService userService;

    @Operation(summary = "用户列表")
    @RequiresPermission("system:view")
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(userService.listUsers());
    }

    @Operation(summary = "新增用户")
    @RequiresPermission("system:update")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody UserCreateDTO dto) {
        userService.createUser(dto);
        return Result.ok();
    }

    @Operation(summary = "编辑用户：角色/状态/密码")
    @RequiresPermission("system:update")
    @PutMapping("/{id}")
    public Result<Void> update(@Parameter(description = "用户 ID") @PathVariable Long id, @RequestBody UserUpdateDTO dto) {
        userService.updateUser(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除用户")
    @RequiresPermission("system:update")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "用户 ID") @PathVariable Long id) {
        userService.deleteUser(id);
        return Result.ok();
    }
}
