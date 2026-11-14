package com.PilotACS.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

// 角色权限关联（存权限码：模块:动作）
@Data
@TableName("sys_role_permission")
public class SysRolePermission {
    private String roleCode;
    private String permCode;
}
