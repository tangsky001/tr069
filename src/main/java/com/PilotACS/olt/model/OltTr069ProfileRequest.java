package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
public class OltTr069ProfileRequest {

    @Schema(description = "模板 ID，1-32")
    private Integer profileId;

    @Schema(description = "模板名")
    private String profileName;

    @Schema(description = "ACS 服务器 URL，如 http://192.168.1.10:9090")
    private String url;

    @Schema(description = "ACS 服务器用户名")
    private String acsUsername;

    @Schema(description = "ACS 服务器密码")
    private String acsPassword;

    @Schema(description = "定期通知：true 使能（下发 inform-interval），false 去使能")
    private Boolean informEnable;

    @Schema(description = "定期通知间隔（秒），1-86400")
    private Integer informInterval;

    @Schema(description = "连接请求用户名")
    private String username;

    @Schema(description = "连接请求密码")
    private String password;

    @Schema(description = "连接请求端口，1-65535")
    private Integer port;
}
