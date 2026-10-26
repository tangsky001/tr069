package com.acsr.acs.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 下发任务台账。 */
@Data
@TableName("dispatch_task")
public class DispatchTask {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String taskNo;
  private String unitId;
  private String serialNumber;
  /** GetParameterValues / SetParameterValues / Reboot / Download ... */
  private String rpcName;
  private String paramPayload;
  private String resultPayload;
  private String status;
  private String failReason;
  private Long costMs;
  private Long createdBy;
  private LocalDateTime createTime;
  private LocalDateTime finishTime;
}
