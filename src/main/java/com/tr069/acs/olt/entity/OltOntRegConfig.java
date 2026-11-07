package com.tr069.acs.olt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一台 OLT 的 ONT 自动注册参数 */
@Data
@TableName("olt_ont_reg_config")
public class OltOntRegConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long oltId;

    /** 是否启用自动注册 */
    private Integer enabled;

    /** 自动发现扫描间隔(秒) */
    private Integer scanSeconds;

    /** 注册成功后隔多久存盘(秒) */
    private Integer saveSeconds;

    /** 一轮最多注册几个 */
    private Integer concurrency;

    /** ONT 换 PON 口上线时自动迁移注册位置 */
    private Integer autoMove;

    /** 注册表满时自动删除离线 ONT */
    private Integer autoDeleteOffline;

    private LocalDateTime lastScanAt;

    private LocalDateTime lastRegisterAt;

    private LocalDateTime lastSaveAt;

    /** 最近扫描时间文本：当天只写时分秒，跨天带上月日；没扫过是「—」 */
    @TableField(exist = false)
    private String lastScanText;

    /** 最近注册时间文本，同上 */
    @TableField(exist = false)
    private String lastRegisterText;

    /** 最近保存（读上来的 ONT 落库）时间文本，同上 */
    @TableField(exist = false)
    private String lastSaveText;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
