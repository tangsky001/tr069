package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理端角色 */
@Data
@TableName("sys_role")
public class SysRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码，与 sys_user.role 一致 */
    private String code;

    private String name;

    /** 内置角色：不允许删除，也不允许改权限 */
    private Integer builtin;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
