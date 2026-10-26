package com.acsr.acs.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 设备参数（ACS 目标值 / 只读回写值）。 */
@Data
@TableName("unit_param")
public class UnitParam {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String unitId;
  private String name;
  private String value;
  private String flags;
}
