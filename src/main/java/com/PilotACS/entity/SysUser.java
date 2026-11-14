/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.755
 *
 */

package com.PilotACS.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

// 系统用户
@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String salt;
    private String nickname;
    // 角色编码
    private String roleCode;
    private Integer status;
    private String lastLoginIp;
    private LocalDateTime lastLoginTime;
    // 最近登录来源 WEB/CLIENT/MOBILE
    private String lastLoginSource;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
