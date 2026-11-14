package com.PilotACS.group.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 分组树节点直属设备的简要信息（树里直接展示设备名） */
@Data
public class GroupMemberBrief {

    @Schema(description = "OLT 设备 id")
    private Long id;

    @Schema(description = "设备名称")
    private String name;
}