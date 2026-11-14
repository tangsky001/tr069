package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
public class OltLineMappingRow {

    @Schema(description = "T-CONT 编号")
    private Integer tcontId;

    @Schema(description = "这条 T-CONT 引用的 DBA 模板 ID")
    private Integer dbaProfileId;

    @Schema(description = "DBA 模板名（查不到为空串，页面显示「DBA N（未采到）」由 dbaProfileId 兜）")
    private String dbaName;

    @Schema(description = "GEM port 编号；模板还没配 GEM 时为 null")
    private Integer gemportId;

    @Schema(description = "映射序号，多个用逗号合并")
    private String mappingId;

    @Schema(description = "映射 VLAN，多个用逗号合并")
    private String vlan;

    @Schema(description = "映射方式：VLAN / 802.1p / Port")
    private String mappingMode;
}
