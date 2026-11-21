/**
 * @author: tangsky001
 * @date:   2026年11月21日
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/**
 * 链路聚合组创建/删除请求。
 * 华为口径：创建 {@code link-aggregation 0/17 0-1 ingress workmode lacp-static}，
 * 删除 {@code undo link-aggregation 0/17}（masterFs 即命令里的主控槽位）。
 */
@Data
public class OltLinkAggregationRequest {

    @Schema(description = "操作：create / delete")
    private String op;

    @Schema(description = "主控端口（框/槽，例 0/17；删除时定位聚合组）")
    private String masterFs;

    @Schema(description = "成员端口列表（例 0-1 或 0,1），创建时必填")
    private String ports;

    @Schema(description = "聚合方向：ingress / egress，创建时必填")
    private String direction;

    @Schema(description = "工作模式（例 lacp-static），创建时缺省 lacp-static")
    private String workmode;
}
