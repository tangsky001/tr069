package com.PilotACS.group.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class GroupMoveRequest {

    @Schema(description = "目标分组节点引用：ROOT（移回根节点）/ G:id")
    private String nodeRef;

    @Schema(description = "OLT 设备 id")
    private Long oltId;
}
