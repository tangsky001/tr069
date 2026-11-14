/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.491
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
public class OltLineProfilePreviewRequest {

    @Schema(description = "tcont / gemport / mapping")
    private String kind;

    private Integer profileId;

    private String ponType;

    @Schema(description = "T-CONT 索引（tcont、gemport 用）")
    private String tcontId;

    @Schema(description = "DBA 模板 ID（tcont 用）")
    private String dbaProfileId;

    @Schema(description = "GEM Port 索引（gemport、mapping 用）")
    private String gemportId;

    @Schema(description = "GEM 映射序号，逗号分隔（mapping 用；留空按 VLAN 顺序从 0 起）")
    private String mappingIds;

    @Schema(description = "业务 VLAN，逗号分隔，与映射序号一一对应（mapping 用）")
    private String vlans;
}
