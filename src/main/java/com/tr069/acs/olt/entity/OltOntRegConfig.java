package com.tr069.acs.olt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一台 OLT 的 ONT 自动注册参数。
 *
 * <p>后台定时任务按 {@link #scanSeconds} 去设备上跑一次自动发现，把匹配上模板的
 * 光猫注册掉；注册成功后隔 {@link #saveSeconds} 把配置存盘——华为只写运行配置，
 * 不 save 掉电就回到注册前，光猫全部掉线。</p>
 */
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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
