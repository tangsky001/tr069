package com.tr069.acs.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 装维 APP 远程运维配置（全局一份）。
 *
 * <p>当前是占位实现：Token、权限项、可见范围都存在这张表里，
 * 后续接真实数据（运维人员、登录会话、工单）时在这张表上扩展即可。
 */
@Data
@TableName("ops_remote_config")
public class OpsRemoteConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 装维 APP 登录二维码里的 Token（重新生成后旧 Token 立即失效） */
    private String token;

    /** 操作权限：录入工单 / 修改下发配置 / 重启复位，按位或逗号分隔存储 */
    @TableField("permissions")
    private String permissions;

    /** 可见设备范围：all / online / 5m / 10m / 15m / 1h / 2h / 24h */
    @TableField("device_scope")
    private String deviceScope;

    /** 内网本地登录二维码的内容 */
    @TableField("lan_qr_content")
    private String lanQrContent;

    /** 外网云登录二维码的内容 */
    @TableField("cloud_qr_content")
    private String cloudQrContent;

    /** APK 下载二维码的内容 */
    @TableField("apk_qr_content")
    private String apkQrContent;

    /** 上次生成 Token 的时间 */
    @TableField("token_created_at")
    private LocalDateTime tokenCreatedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
