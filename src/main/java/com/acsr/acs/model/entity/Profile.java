package com.acsr.acs.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 配置模板（一组设备的默认目标值）。 */
@Data
@TableName("profile")
public class Profile {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long unitTypeId;
  private String name;
  private String description;
}
