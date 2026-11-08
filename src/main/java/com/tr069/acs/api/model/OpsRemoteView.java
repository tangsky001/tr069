package com.tr069.acs.api.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** 远程运维配置的视图对象：前端直接渲染 */
@Data
public class OpsRemoteView {

    /** 装维 APP 登录 Token */
    private String token;

    /** Token 生成时间 */
    private String tokenCreatedAt;

    /** 已勾选的权限项 */
    private List<String> permissions;

    /** 可见设备范围 */
    private String deviceScope;

    /** 内网穿透分配的随机二级域名前缀 */
    private String subdomain;

    /** 是否启用内网穿透 */
    private Boolean remoteEnabled;

    /** 可选权限项：{value, label} */
    private List<Map<String, String>> permissionOptions;

    /** 可选设备范围：{value, label} */
    private List<Map<String, String>> deviceScopeOptions;

    /** 内网本地登录二维码内容 */
    private String lanQrContent;

    /** 外网云登录二维码内容 */
    private String cloudQrContent;

    /** APK 下载二维码内容 */
    private String apkQrContent;
}
