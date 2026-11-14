package com.PilotACS.common.security;

import lombok.Data;

import java.util.Set;

// 登录用户会话信息
@Data
public class LoginUser {
    private Long userId;
    private String username;
    private String nickname;
    // 角色编码，如 SUPER_ADMIN
    private String role;
    private boolean superAdmin;
    // 登录来源 WEB/CLIENT/MOBILE
    private String source;
    private String jti;
    private Set<String> permissions;
}
