package com.PilotACS.group.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 可绑定的 OLT 选项：台账信息 + 当前绑定位置，绑定弹窗用 */
@Data
@Schema(description = "可绑定的 OLT 选项")
public class GroupOltOption {

    @Schema(description = "OLT 设备 id")
    private Long id;

    @Schema(description = "设备名称")
    private String name;

    @Schema(description = "设备地址")
    private String host;

    @Schema(description = "厂商文案")
    private String vendorText;

    @Schema(description = "状态文案：在线 / 离线 / 未采集")
    private String statusText;

    @Schema(description = "当前绑定的节点引用：C:区划编号 或 G:自定义分组 id，未绑定为 null")
    private String boundCode;

    @Schema(description = "当前绑定的分组全路径文案，未绑定为空")
    private String boundText;
}
