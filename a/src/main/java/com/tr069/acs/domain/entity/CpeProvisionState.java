package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 纳管下发快照：每台设备每个预设最近一次成功下发的目标参数值，用于跳过重复下发 */
@Data
@TableName("cpe_provision_state")
public class CpeProvisionState {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设备序列号（唯一标识） */
    private String sn;

    /** 预设 ID */
    private Long presetId;

    /** 下发快照：path=value 的 JSON */
    private String targetsJson;

    private LocalDateTime updatedAt;
}
