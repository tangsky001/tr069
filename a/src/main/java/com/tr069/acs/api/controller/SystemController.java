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

/** 系统管理：角色、权限、用户 */
@Slf4j
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final RoleService roleService;

    private final UserAdminService userAdminService;

    private final LoginLogService loginLogService;

    /** 权限点清单，按模块分组，给页面勾选用 */
    @GetMapping("/permissions")
    public Result<Map<String, Object>> permissions() {
        Map<String, Object> data = Map.of("groups", PermissionCodes.groups());
        return Result.success(data);
    }

    @GetMapping("/roles")
    public Result<List<Map<String, Object>>> roles() {
        return Result.success(roleService.list());
    }

    /** 建角色。新角色一个权限都没有，建完去勾 */
    @PostMapping("/roles")
    public Result<Void> createRole(@RequestBody(required = false) RoleRequest request) {
        if (request == null) {
            return Result.failure("缺少参数");
        }
        roleService.create(request.getCode(), request.getName(), request.getRemark());
        return Result.success();
    }

    /** 设置角色权限，整份覆盖。系统管理员会被拒 */
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

    /** 用户列表：每行带 canDelete / canToggleStatus / canChangeRole */
    @GetMapping("/users")
    public Result<List<Map<String, Object>>> users(HttpServletRequest request) {
        Object username = request.getAttribute(AdminAuthInterceptor.USERNAME_ATTR);
        return Result.success(userAdminService.list(username == null ? null : String.valueOf(username)));
    }

    /** 账号与密码策略：新建/改密弹窗的提示与校验值都由后端给 */
    @GetMapping("/password-policy")
    public Result<Map<String, Object>> passwordPolicy() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("minLength", 6);
        data.put("maxLength", 64);
        data.put("usernamePattern", "字母、数字与下划线，4-32 位");
        data.put("passwordHint", "至少 6 位");
        data.put("needCurrentPassword", true);
        return Result.success(data);
    }

    /** 登录日志。传 userId 看某一个人的，不传看全部人的 */
    @GetMapping("/login-logs")
    public Result<List<Map<String, Object>>> loginLogs(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "ip", required = false) String ip,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "from", required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.LocalDateTime to,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        boolean filtered = (ip != null && !ip.isBlank()) || (source != null && !source.isBlank())
                || from != null || to != null;
        if (!filtered && userId != null) {
            return Result.success(loginLogService.ofUser(userId, limit));
        }
        return Result.success(loginLogService.search(userId, ip, source, from, to, limit));
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

    /** 改用户。当前操作者的用户名从拦截器里取，用来拦住「删自己」 */
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
        /** 改密码时必填：操作者自己的当前密码，二次验证用 */
        private String currentPassword;
        private String nickname;
        private String role;
        private Integer status;
    }
}
