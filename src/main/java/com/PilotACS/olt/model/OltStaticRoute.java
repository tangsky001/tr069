package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
public class OltStaticRoute {

    @Schema(description = "目的网络/掩码，回显原样，如 0.0.0.0/0")
    private String destination;

    @Schema(description = "下一跳网关，如 10.172.10.1")
    private String nextHop;

    @Schema(description = "出接口，如 vlanif46")
    private String iface;

    @Schema(description = "优先级（Pre）")
    private Integer preference;

    @Schema(description = "开销（Cost）")
    private Integer cost;
}
