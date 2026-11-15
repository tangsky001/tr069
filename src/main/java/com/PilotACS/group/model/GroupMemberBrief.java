/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.820
 *
 */

package com.PilotACS.group.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 分组树节点直属设备的简要信息（树里直接展示设备名） */
@Data
public class GroupMemberBrief {

    @Schema(description = "设备 id：OLT 为 olt_device.id；ONU 为 null")
    private Long id;

    @Schema(description = "设备业务标识：OLT 为 id 字符串；ONU 为光猫标识（如 SN）")
    private String ref;

    @Schema(description = "设备名称")
    private String name;
}
