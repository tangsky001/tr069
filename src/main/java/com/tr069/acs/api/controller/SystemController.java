package com.tr069.acs.api.controller;

import com.tr069.acs.api.auth.PermissionCodes;
import com.tr069.acs.api.service.LoginLogService;
import com.tr069.acs.api.service.RoleService;
import com.tr069.acs.api.service.UserAdminService;
import com.tr069.acs.common.Result;
import com.tr069.acs.config.AdminAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 系统管理：角色、权限、用户。
 *
 * <p>自己也要被权限管：看要 {@code system:view}，改要 {@code system:manage}，
 * 系统管理员例外（它在 {@link RoleService#has} 里直接放行）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final RoleService roleService;

    private final UserAdminService userAdminService;

    private final LoginLogService loginLogService;

    /** 权限点清单，按模块分组，给页面勾选用。 */
    @GetMapping("/permissions")
    public Result<Map<String, Object>> permissions() {
        Map<String, Object> data = Map.of("groups", PermissionCodes.groups());
        return Result.success(data);
    }

    @GetMapping("/roles")
    public Result<List<Map<String, Object>>> roles() {
        return Result.success(roleService.list());
    }

    /** 建角色。新角色一个权限都没有，建完去勾。 */
    @PostMapping("/roles")
    public Result<Void> createRole(@RequestBody(required = false) RoleRequest request) {
        if (request == null) {
            return Result.failure("缺少参数");
        }
        roleService.create(request.getCode(), request.getName(), request.getRemark());
        return Result.success();
    }

    /** 设置角色权限，整份覆盖。系统管理员会被拒。 */
    @PutMapping("/roles/{code}/permissions")
    public Result<Void> setPermissions(@PathVariable String code,
                                       @RequestBody(required = false) PermissionsRequest request) {
        roleService.setPermissions(code, request == null ? List.of() : request.getPermissions());
        return Result.success();
    }

    @DeleteMapping("/roles/{code}")
    public Result<Void> deleteRole(@PathVariable String code) {
        roleService.delete(code);
        return Result.success();
    }

    @GetMapping("/users")
    public Result<List<Map<String, Object>>> users() {
        return Result.success(userAdminService.list());
    }

    /** 登录日志。传 userId 看某一个人的，不传看全部人的。 */
    @GetMapping("/login-logs")
    public Result<List<Map<String, Object>>> loginLogs(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        if (userId != null) {
            return Result.success(loginLogService.ofUser(userId, limit));
        }
        return Result.success(loginLogService.recent(limit));
    }

    @PostMapping("/users")
    public Result<Void> createUser(@RequestBody(required = false) UserRequest request) {
        if (request == null) {
            return Result.failure("缺少参数");
        }
        userAdminService.create(request.getUsername(), request.getPassword(),
                request.getNickname(), request.getRole());
        return Result.success();
    }

    /**
     * 改用户。当前操作者的用户名从拦截器里取，用来拦住「删自己」，
     * 以及改密码时验他自己的密码。
     */
    @PutMapping("/users/{id}")
    public Result<Void> updateUser(@PathVariable Long id,
                                   @RequestBody(required = false) UserRequest request,
                                   HttpServletRequest http) {
        if (request == null) {
            return Result.failure("缺少参数");
        }
        Object username = http.getAttribute(AdminAuthInterceptor.USERNAME_ATTR);
        userAdminService.update(id, request.getNickname(), request.getRole(),
                request.getStatus(), request.getPassword(), request.getCurrentPassword(),
                username == null ? null : String.valueOf(username));
        return Result.success();
    }

    @DeleteMapping("/users/{id}")
    public Result<Void> deleteUser(@PathVariable Long id, HttpServletRequest request) {
        Object username = request.getAttribute(AdminAuthInterceptor.USERNAME_ATTR);
        userAdminService.delete(id, username == null ? null : String.valueOf(username));
        return Result.success();
    }

    @Data
    public static class RoleRequest {
        private String code;
        private String name;
        private String remark;
    }

    @Data
    public static class PermissionsRequest {
        private List<String> permissions;
    }

    @Data
    public static class UserRequest {
        private String username;
        private String password;
        /** 改密码时必填：操作者自己的当前密码，二次验证用。 */
        private String currentPassword;
        private String nickname;
        private String role;
        private Integer status;
    }
}
