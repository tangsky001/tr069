package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class OltGemport {

    @Schema(description = "GEM port 编号")
    private Integer gemportId;

    @Schema(description = "承载它的 T-CONT")
    private Integer tcontId;

    @Schema(description = "映射方式：VLAN / 802.1p / Port")
    private String mappingMode;

    @Schema(description = "GEM 内的映射序号（gem mapping vlan 的第二个数字）")
    private Integer mappingId;

    @Schema(description = "GEM 内的映射列表（明细回显的映射表格，一个 GEM port 可挂多条映射）")
    private List<OltGemMapping> mappings = new ArrayList<>();

    @Schema(description = "业务流里的内层 VLAN（service-port 的 user-vlan）")
    private String userVlan;

    @Schema(description = "映射到的 VLAN（映射方式是 VLAN 时才有）")
    private String vlan;
}
