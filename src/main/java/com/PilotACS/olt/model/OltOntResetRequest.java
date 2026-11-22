/**
 * @author: tangsky001
 * @date:   2026年11月21日
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/** ONU 复位（重启）请求：字段缺省时按 ONT / PON 口 / 板卡分级复位 */
@Data
public class OltOntResetRequest {

    @Schema(description = "槽位号（0/槽，例 2）")
    private Integer slot;

    @Schema(description = "PON 端口号；缺省时复位整块板卡")
    private Integer port;

    @Schema(description = "ONT ID；缺省时复位整条 PON 口")
    private Integer ontId;
}
