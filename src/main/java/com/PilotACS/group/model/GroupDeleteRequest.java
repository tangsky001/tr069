package com.PilotACS.group.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 删除分组节点 */
@Data
@Schema(description = "删除分组节点")
public class GroupDeleteRequest {

    @Schema(description = "节点引用：G:自定义分组 id（区划节点会被拒绝）")
    private String id;
}
