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

/** 操作审计日志 */
@Data
@TableName("sys_oper_log")
public class SysOperLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 操作人登录名 */
    private String username;
    /** 操作模块 */
    private String module;
    /** 操作动作 */
    private String action;
    /** 操作详情 */
    private String detail;
    private String ip;
    private LocalDateTime createdAt;
}
