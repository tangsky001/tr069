package com.acsr.acs.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 设备型号（运营元数据，能力树在 SQLite）。 */
@Data
@TableName("unit_type")
public class UnitType {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String name;
  private String vendor;
  private String description;
  private String protocol;
  private LocalDateTime createTime;
}
