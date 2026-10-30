package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端用户。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String nickname;

    /**
     * 角色编码，对应 sys_role.code。
     */
    private String role;

    /** 最近一次登录来源：WEB / CLIENT / MOBILE。 */
    private String lastLoginSource;

    /** 最近一次登录 IP。 */
    private String lastLoginIp;

    /**
     * 状态：1 启用，0 禁用
     */
    private Integer status;

    @TableLogic
    private Integer deleted;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
