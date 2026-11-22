/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.677
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/** T-CONT 信息 */
@Data
public class OltTcont {

    @Schema(description = "T-CONT 编号")
    private Integer tcontId;

    @Schema(description = "这条 T-CONT 引用的 DBA 模板 ID")
    private Integer dbaProfileId;

    @Schema(description = "DBA 模板名（detail 组装时按 ID 查好，查不到为空串），页面直接显示")
    private String dbaName;
}
