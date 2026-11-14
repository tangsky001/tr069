package com.PilotACS.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

// 登录日志
@Data
@TableName("sys_login_log")
public class SysLoginLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String ip;
    // WEB/CLIENT/MOBILE
    private String source;
    // 1成功 0失败
    private Integer success;
    private String userAgent;
    private LocalDateTime createdAt;
}
