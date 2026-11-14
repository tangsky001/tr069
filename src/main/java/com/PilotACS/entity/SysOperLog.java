/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.901
 *
 */

package com.PilotACS.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

// 操作审计日志（谁在什么时候做了什么关键操作）
@Data
@TableName("sys_oper_log")
public class SysOperLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    // 操作人登录名；系统内部动作为 system
    private String username;
    // 模块：用户 / 分组 / 设备权限 / OLT ...
    private String module;
    // 动作：修改密码 / 新建分组 / 删除分组 / 移动设备 ...
    private String action;
    // 详情：对象名、目标分组、授权动作等
    private String detail;
    private String ip;
    private LocalDateTime createdAt;
}
