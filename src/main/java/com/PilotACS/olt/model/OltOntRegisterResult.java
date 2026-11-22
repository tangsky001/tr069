/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.595
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/** ONT 注册结果 */
@Data
public class OltOntRegisterResult {

    @Schema(description = "框/槽/端口")
    private String fsp;

    @Schema(description = "ONT SN / MAC / LOID")
    private String sn;

    private String model;

    @Schema(description = "命中的模板名；没匹配到模板时是 null")
    private String templateName;

    private String authMode;

    private boolean success;

    @Schema(description = "失败原因或成功说明")
    private String message;

    @Schema(description = "实际下发（或预览）的命令")
    private String command;
}
