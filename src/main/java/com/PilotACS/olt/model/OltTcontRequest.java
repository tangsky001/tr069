/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.578
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/** T-CONT 新增请求 */
@Data
public class OltTcontRequest {

    @Schema(description = "线路模板 ID")
    private Integer profileId;

    @Schema(description = "gpon / epon，留空按 gpon")
    private String ponType;

    @Schema(description = "T-CONT 索引，华为 0-127")
    private Integer tcontId;

    @Schema(description = "引用的 DBA 模板 ID")
    private Integer dbaProfileId;
}
