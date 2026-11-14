package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.List;

@Data
public class OltVlanRequest {

    @Schema(description = "VLAN ID 表达式，支持 , 与 - 连接，如 100、100-200,300")
    private String vlanIds;

    @Schema(description = "起止写法（兼容旧表单）：只建一个时 {@link #endVlanId} 留空")
    private Integer vlanId;

    @Schema(description = "起止写法（兼容旧表单）")
    private Integer endVlanId;

    @Schema(description = "打进 Tagged 的端口，写法 epon1 / ge3，与 {@link OltVlanPort} 的 type + no 对应")
    private List<String> tagged;

    @Schema(description = "打进 Untagged 的端口")
    private List<String> untagged;
}
