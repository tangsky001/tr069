package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

public class OltTerminalUserRequest {

    @Schema(description = "用户名：设备要求 6~15 位")
    private String username;

    @Schema(description = "密码：设备要求 6~15 位")
    private String password;

    @Schema(description = "权限级别：1=Common User（只能看），2=Operator（能改配置）")
    private Integer level;

    @Schema(description = "用户模板（profile）；留空走设备的默认值，一般是 root")
    private String profile;

    @Schema(description = "允许重复登录的次数，0~4")
    private Integer reenter;

    @Schema(description = "备注，<=30 个字符")
    private String appendedInfo;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public Integer getReenter() {
        return reenter;
    }

    public void setReenter(Integer reenter) {
        this.reenter = reenter;
    }

    public String getAppendedInfo() {
        return appendedInfo;
    }

    public void setAppendedInfo(String appendedInfo) {
        this.appendedInfo = appendedInfo;
    }
}
