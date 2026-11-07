package com.tr069.acs.olt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一键开局参数 */
@Data
@TableName("olt_quick_setup_config")
public class OltQuickSetupConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long oltId;

    /** 开局参数快照(JSON) */
    private String configJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
