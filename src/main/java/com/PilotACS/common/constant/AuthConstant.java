package com.PilotACS.common.constant;

// 认证相关常量
public interface AuthConstant {
    String TOKEN_HEADER = "X-Token";
    String TOKEN_PREFIX = "Bearer ";
    String CLIENT_HEADER = "X-Client-Type";
    String TOKEN_KEY_PREFIX = "acs:auth:token:";
    long SUPER_ADMIN_ID = 1L;
    String SUPER_ADMIN_CODE = "SUPER_ADMIN";
    // ITMS 对接账号（不可删除，仅超级管理员可编辑）
    String ITMS_USERNAME = "itms";
    String ITMS_ROLE_CODE = "ITMS";
    int UNAUTHORIZED = 401;
    int FORBIDDEN = 403;
}
