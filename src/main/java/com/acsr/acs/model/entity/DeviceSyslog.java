package com.acsr.acs.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 设备 syslog 日志。 */
@Data
@TableName("device_syslog")
public class DeviceSyslog {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String unitId;
  private String ipAddress;
  private Integer facility;
  private Integer severity;
  private String deviceTime;
  private String hostname;
  private String tag;
  private String content;
  private LocalDateTime createTime;
}
