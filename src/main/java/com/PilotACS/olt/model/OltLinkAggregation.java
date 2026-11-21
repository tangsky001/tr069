/**
 * @author: tangsky001
 * @date:   2026年11月21日
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/**
 * 链路聚合组（{@code display link-aggregation} 表格的一行）。
 * 主端口在回显里可能带空格（{@code 0 /17/0}），入库前已归一成 {@code 0/17/0}。
 */
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
