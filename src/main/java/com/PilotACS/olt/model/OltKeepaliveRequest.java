package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
public class OltKeepaliveRequest {

    @Schema(description = "保活开关")
    private Boolean enabled;

    @Schema(description = "探活周期(毫秒)，不传保持当前值")
    private Long intervalMs;
}
