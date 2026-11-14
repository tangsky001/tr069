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

// 装维 APP 远程运维配置（全局一份）
@Data
@TableName("ops_remote_config")
public class OpsRemoteConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    // 装维 APP 登录二维码里的 Token，重新生成后旧 Token 立即失效
    private String token;
    // 操作权限：ticket/config/reboot，逗号分隔存储
    private String permissions;
    // 可见设备范围：all/online/5m/10m/15m/1h/2h/24h
    private String deviceScope;
    // 内网穿透分配的随机二级域名前缀
    private String subdomain;
    // ACS 实例唯一标识（UUID）
    private String instanceId;
    // 是否启用内网穿透：0否 1是
    private Integer remoteEnabled;
    private String lanQrContent;
    private String cloudQrContent;
    private String apkQrContent;
    // 上次生成 Token 的时间
    private LocalDateTime tokenCreatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
