/**
 * @author: tangsky001
 * @date:   2026年11月21日
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/** 链路聚合组信息 */
@Data
public class OltLinkAggregation {

    @Schema(description = "主端口（框/槽/口，例 0/17/0）")
    private String masterPort;

    @Schema(description = "聚合方向：ingress / egress")
    private String mode;

    @Schema(description = "成员端口数（Port NUM）")
    private Integer portNum;

    @Schema(description = "工作模式，例 lacp-static")
    private String workmode;

    @Schema(description = "最大链路数（设备回 - 时为 null）")
    private String maxLinkNumber;

    @Schema(description = "最少链路数（设备回 - 时为 null）")
    private String leastLinkNumber;
}
