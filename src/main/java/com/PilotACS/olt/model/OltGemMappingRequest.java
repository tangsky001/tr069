/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.616
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/** GEM 映射新增请求 */
@Data
public class OltGemMappingRequest {

    @Schema(description = "线路模板 ID")
    private Integer profileId;

    @Schema(description = "gpon / epon，留空按 gpon")
    private String ponType;

    @Schema(description = "哪个 GEM Port 上的映射")
    private Integer gemportId;

    @Schema(description = "GEM 映射序号，逗号分隔，如 1,2；留空表示从 0 开始顺延")
    private String mappingIds;

    @Schema(description = "业务 VLAN，逗号分隔，如 46,41，与映射序号一一对应")
    private String vlans;
}
