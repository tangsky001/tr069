package com.PilotACS.group.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 从分组解绑成员 */
@Data
@Schema(description = "从分组解绑成员")
public class GroupUnbindRequest {

    @Schema(description = "绑定关系主键（组内成员列表里的 id）")
    private Long id;
}
