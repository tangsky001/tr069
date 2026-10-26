package com.acsr.acs.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 协议交互过程日志。 */
@Data
@TableName("cwmp_log")
public class CwmpLog {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String unitId;
  private String sessionId;
  /** IN / OUT */
  private String direction;
  private String method;
  private String eventCodes;
  private String faultCode;
  private String faultString;
  private String ipAddress;
  private Long costMs;
  private String xml;
  private LocalDateTime createTime;
}
