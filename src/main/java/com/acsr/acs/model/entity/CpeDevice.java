package com.acsr.acs.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 设备台账（cpe_device）。 */
@Data
@TableName("cpe_device")
public class CpeDevice {

  @TableId(type = IdType.AUTO)
  private Long id;

  /** OUI-ProductClass-SerialNumber */
  private String unitId;
  private String serialNumber;
  private String oui;
  private String manufacturer;
  private String productClass;
  private String model;
  private String firmwareVersion;
  private String account;
  private String secret;
  private Long unitTypeId;
  private Long profileId;
  private String ipAddress;
  private Integer vlanId;
  private String bindOlt;
  private String remark;
  private String provisioningMode;
  private Integer online;
  private LocalDateTime firstOnline;
  private LocalDateTime lastOnline;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
