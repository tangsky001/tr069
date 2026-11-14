package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
public class OltOntQueryRequest {

    @Schema(description = "动作：optical（光信息）/ version（版本）/ register（注册历史）")
    private String action;

    @Schema(description = "框号，一般是 0")
    private Integer frameNo;

    @Schema(description = "槽位（业务板所在槽）")
    private Integer slotNo;

    @Schema(description = "PON 端口号")
    private Integer portNo;

    @Schema(description = "ONT ID（端口内唯一）")
    private Integer ontId;
}
