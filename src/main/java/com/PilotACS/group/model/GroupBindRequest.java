package com.PilotACS.group.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 绑定成员到分组节点 */
@Data
@Schema(description = "绑定成员到分组节点")
public class GroupBindRequest {

    @Schema(description = "分组节点引用：C:区划编号 或 G:自定义分组 id")
    private String nodeRef;

    @Schema(description = "OLT 设备 id（olt_device.id）")
    private Long oltId;
}
