package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端角色。
 *
 * <p>权限不是角色上的字段，单独放 {@code sys_role_permission}——
 * 一个角色能配十几个权限点，拼成逗号字段就只能模糊匹配了。</p>
 */
@Data
@TableName("sys_role")
public class SysRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码，与 sys_user.role 一致。 */
    private String code;

    private String name;

    /** 内置角色：不允许删除，也不允许改权限。 */
    private Integer builtin;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
