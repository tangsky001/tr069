package com.acsr.acs.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 配置模板参数目标值。 */
@Data
@TableName("profile_param")
public class ProfileParam {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long profileId;
  private String name;
  private String value;
}
