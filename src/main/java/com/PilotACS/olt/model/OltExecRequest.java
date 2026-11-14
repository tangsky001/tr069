package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.Map;

@Data
public class OltExecRequest {

    @Schema(description = "动作编号，如 vlan.create")
    private String code;

    @Schema(description = "表单参数，如 {”vlanId”: ”100”}")
    private Map<String, String> params;
}
