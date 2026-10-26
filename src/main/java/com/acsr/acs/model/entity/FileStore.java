package com.acsr.acs.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 固件 / 配置文件（供 Download RPC 使用）。 */
@Data
@TableName("file_store")
public class FileStore {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long unitTypeId;
  private String name;
  private String type;
  private String version;
  private String description;
  private String targetName;
  private Long length;
  private byte[] content;
  private LocalDateTime createTime;
}
