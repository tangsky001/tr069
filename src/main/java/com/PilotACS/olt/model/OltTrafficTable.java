/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.517
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
public class OltTrafficTable {

    @Schema(description = "模板索引")
    private Integer index;

    @Schema(description = "模板名")
    private String name;

    @Schema(description = "承诺速率 CIR（kbps），无承诺时为 null")
    private Integer cir;

    @Schema(description = "峰值速率 PIR（kbps）")
    private Integer pir;
}
