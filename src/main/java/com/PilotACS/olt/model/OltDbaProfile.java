/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.780
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/** DBA 模板。 */
@Data
public class OltDbaProfile {

    @Schema(description = "模板 ID，线路模板靠它引用")
    private Integer profileId;

    @Schema(description = "模板名")
    private String name;

    @Schema(description = "类型：type1 ~ type5")
    private String type;

    @Schema(description = "固定带宽（kbps）")
    private Integer fixKbps;

    @Schema(description = "保证带宽（kbps）")
    private Integer assureKbps;

    @Schema(description = "最大带宽（kbps）")
    private Integer maxKbps;
}
