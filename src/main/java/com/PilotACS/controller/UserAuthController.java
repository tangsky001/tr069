package com.PilotACS.controller;

import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.LoginUser;
import com.PilotACS.dto.LoginDTO;
import com.PilotACS.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 登录认证接口
@Tag(name = "认证管理")
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserAuthController {

    private final AuthService authService;

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        return Result.ok(authService.login(dto, request));
    }

    @Operation(summary = "当前用户信息")
    @GetMapping("/info")
    public Result<Map<String, Object>> info(HttpServletRequest request) {
        LoginUser lu = authService.currentUser(request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", lu.getNickname() == null || lu.getNickname().isBlank() ? lu.getUsername() : lu.getNickname());
        data.put("avatar", "");
        data.put("role", lu.getRole());
        data.put("permissions", lu.getPermissions() == null ? List.of() : lu.getPermissions());
        data.put("source", lu.getSource());
        return Result.ok(data);
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        authService.logout(request);
        return Result.ok();
    }
}
