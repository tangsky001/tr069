package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
public class OltDbaProfileRequest {

    @Schema(description = "模板 ID，10-512，OLT 上不能重复")
    private Integer profileId;

    @Schema(description = "模板名，数字、字母及下划线")
    private String name;

    @Schema(description = "type1~type5")
    private String type;

    @Schema(description = "固定带宽（kbps），type1 / type5")
    private Integer fixKbps;

    @Schema(description = "保证带宽（kbps），type2 / type3 / type5")
    private Integer assureKbps;

    @Schema(description = "最大带宽（kbps），type3 / type4 / type5")
    private Integer maxKbps;
}
