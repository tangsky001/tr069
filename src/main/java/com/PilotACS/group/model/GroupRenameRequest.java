package com.PilotACS.group.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 重命名分组节点 */
@Data
@Schema(description = "重命名分组节点")
public class GroupRenameRequest {

    @Schema(description = "自定义分组 id")
    private String id;

    @Schema(description = "新名称")
    private String name;
}
