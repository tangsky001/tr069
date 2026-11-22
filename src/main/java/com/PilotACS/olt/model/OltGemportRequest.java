/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.630
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/** GEM Port 新增请求 */
@Data
public class OltGemportRequest {

    @Schema(description = "线路模板 ID")
    private Integer profileId;

    @Schema(description = "gpon / epon，留空按 gpon")
    private String ponType;

    @Schema(description = "GEM Port 挂到哪条 T-CONT 上")
    private Integer tcontId;

    @Schema(description = "GEM Port 索引，华为 0-1023")
    private Integer gemportId;
}
