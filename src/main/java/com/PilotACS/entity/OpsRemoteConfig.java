/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.308
 *
 */

package com.PilotACS.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 装维 APP 远程运维配置 */
@Data
@TableName("ops_remote_config")
public class OpsRemoteConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** APP 登录二维码 Token */
    private String token;
    /** 操作权限码，逗号分隔 */
    private String permissions;
    /** 可见设备范围 */
    private String deviceScope;
    /** 穿透二级域名前缀 */
    private String subdomain;
    /** ACS 实例唯一标识 */
    private String instanceId;
    /** 是否启用内网穿透 */
    private Integer remoteEnabled;
    private String lanQrContent;
    private String cloudQrContent;
    private String apkQrContent;
    /** 上次生成 Token 的时间 */
    private LocalDateTime tokenCreatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
