package com.acsr.acs.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 系统用户（登录账号，MySQL）。 */
@Data
@TableName("sys_user")
public class SysUser {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String username;
  private String passwordHash;
  private String displayName;
  private Integer status;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
