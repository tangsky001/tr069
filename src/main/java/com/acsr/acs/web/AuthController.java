package com.acsr.acs.web;

import com.acsr.acs.common.Result;
import com.acsr.acs.model.entity.SysUser;
import com.acsr.acs.security.JwtUtil;
import com.acsr.acs.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 登录 / 账号管理。 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final UserService userService;
  private final JwtUtil jwtUtil;
  private final WebSupport webSupport;

  @Data
  public static class LoginRequest {
    private String username;
    private String password;
  }

  @PostMapping("/login")
  public Result<Map<String, Object>> login(@RequestBody LoginRequest req, HttpServletRequest request) {
    SysUser user = userService.login(req.getUsername(), req.getPassword());
    String token = jwtUtil.generate(user.getUsername(), Map.of("uid", user.getId()));
    webSupport.audit(request, "LOGIN", user.getUsername(), null);
    Map<String, Object> data = new HashMap<>();
    data.put("token", token);
    return Result.ok(data);
  }

  @GetMapping("/info")
  public Result<Map<String, Object>> info() {
    String username = webSupport.currentUsername();
    if (username == null) {
      return Result.fail(401, "未登录");
    }
    SysUser user = userService.findByUsername(username);
    if (user == null) {
      return Result.fail(401, "未登录");
    }
    Map<String, Object> data = new HashMap<>();
    data.put("name", user.getDisplayName() == null ? user.getUsername() : user.getDisplayName());
    data.put("username", user.getUsername());
    data.put("avatar", "https://wpimg.wallstcn.com/f778738c-e4f8-4870-b634-56703b4acafe.gif");
    data.put("roles", userService.rolesOf(user.getId()));
    return Result.ok(data);
  }

  @PostMapping("/logout")
  public Result<?> logout() {
    return Result.ok();
  }

  @GetMapping("/users")
  public Result<List<SysUser>> users() {
    return Result.ok(userService.list());
  }

  @Data
  public static class CreateUserRequest {
    private String username;
    private String password;
    private String displayName;
  }

  @PostMapping("/users")
  public Result<SysUser> createUser(@RequestBody CreateUserRequest req, HttpServletRequest request) {
    SysUser user = userService.create(req.getUsername(), req.getPassword(), req.getDisplayName());
    webSupport.audit(request, "CREATE_USER", req.getUsername(), null);
    return Result.ok(user);
  }

  @PostMapping("/users/{id}/password")
  public Result<?> changePassword(@PathVariable Long id, @RequestBody Map<String, String> body,
                                  HttpServletRequest request) {
    userService.changePassword(id, body.get("password"));
    webSupport.audit(request, "CHANGE_PASSWORD", String.valueOf(id), null);
    return Result.ok();
  }

  @PostMapping("/users/{id}/status")
  public Result<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
    userService.updateStatus(id, body.get("status"));
    return Result.ok();
  }

  @DeleteMapping("/users/{id}")
  public Result<?> deleteUser(@PathVariable Long id, HttpServletRequest request) {
    userService.delete(id);
    webSupport.audit(request, "DELETE_USER", String.valueOf(id), null);
    return Result.ok();
  }
}
