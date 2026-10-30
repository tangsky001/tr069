package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 角色拥有的权限点。
 *
 * <p>存的是 code 而不是 id：权限点本身不落库（见 {@code PermissionCodes}），
 * 这里只记「这个角色勾了哪些」。</p>
 */
@Data
@TableName("sys_role_permission")
public class SysRolePermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String roleCode;

    private String permission;
}
