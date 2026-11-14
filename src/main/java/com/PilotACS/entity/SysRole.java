/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.386
 *
 */

package com.PilotACS.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

// 角色
@Data
@TableName("sys_role")
public class SysRole {
    @TableId(value = "code", type = IdType.INPUT)
    private String code;
    private String name;
    // 内置角色 1是 0否，内置禁止删除/改权限
    private Integer builtin;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
