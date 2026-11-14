package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
public class OltIpInterface {

    @Schema(description = "接口名，如 vlanif46")
    private String name;

    @Schema(description = "从接口名里带出来的 VLAN ID")
    private Integer vlanId;

    @Schema(description = "接口 IP，带掩码那段的左边部分")
    private String ipAddress;

    @Schema(description = "掩码位数，24")
    private Integer prefixLength;

    @Schema(description = "点分十进制掩码，255.255.255.0")
    private String mask;

    @Schema(description = "物理状态 up/down")
    private String physical;

    @Schema(description = "协议状态 up/down")
    private String protocol;
}
