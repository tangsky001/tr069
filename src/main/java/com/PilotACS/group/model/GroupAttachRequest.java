/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.452
 *
 */

package com.PilotACS.group.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 通用归属/移动请求（光猫等设备模块接入分组时用）。
 * attach：groupRef + memberType + memberRef（+label）；
 * move-member：groupRef 当作目标节点（ROOT=移回根节点）+ memberType + memberRef。
 */
@Data
@Schema(description = "通用归属/移动请求")
public class GroupAttachRequest {

    @Schema(description = "成员类型：OLT / ONU（光猫）")
    private String memberType;

    @Schema(description = "设备业务标识：OLT 为 olt_device.id 字符串；ONU 为光猫标识（如 SN）")
    private String memberRef;

    @Schema(description = "设备名称快照（归属时写入，可空）")
    private String memberLabel;

    @Schema(description = "分组节点引用：ROOT 或 G:自定义分组 id；attach 时为空=根节点，move 时为移动目标")
    private String groupRef;
}
