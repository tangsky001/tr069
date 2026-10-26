package com.acsr.acs.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 型号参数元数据（读写标记）。 */
@Data
@TableName("unit_type_param")
public class UnitTypeParam {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long unitTypeId;
  private String name;
  private String flags;
}
