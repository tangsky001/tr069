package com.PilotACS.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

// 远程运维配置视图：前端直接渲染
@Data
public class OpsRemoteView {
    private String token;
    private String tokenCreatedAt;
    private List<String> permissions;
    private String deviceScope;
    private String subdomain;
    private Boolean remoteEnabled;
    private List<Map<String, String>> permissionOptions;
    private List<Map<String, String>> deviceScopeOptions;
    private String lanQrContent;
    private String cloudQrContent;
    private String apkQrContent;
}
