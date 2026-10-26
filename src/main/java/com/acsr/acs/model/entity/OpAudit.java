package com.acsr.acs.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 操作审计。 */
@Data
@TableName("op_audit")
public class OpAudit {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long operatorId;
  private String action;
  private String target;
  private String detail;
  private String ip;
  private LocalDateTime createTime;
}
