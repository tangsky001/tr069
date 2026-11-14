package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
public class OltPortVlanRequest {

    @Schema(description = "机框/板卡/端口，如 0/7/0；只写框和槽的会被拒（不知道该放通到哪个口）")
    private String iface;

    @Schema(description = "VLAN ID 表达式：41、41,45-46")
    private String vlanIds;

    @Schema(description = "true 表示按 Native（不打标签）下发，只对「放通」有意义")
    private Boolean nativeVlan;
}
