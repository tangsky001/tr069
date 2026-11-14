package com.PilotACS.group.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 组内成员行：绑定关系 + OLT 实时台账，展示字段全部在后端拼好 */
@Data
@Schema(description = "组内成员（OLT）")
public class GroupMemberView {

    @Schema(description = "绑定关系主键，解绑用")
    private Long id;

    @Schema(description = "分组节点引用：C:区划编号 或 G:自定义分组 id")
    private String nodeRef;

    @Schema(description = "分组节点全路径文案：北京市 / 市辖区 / 海淀区 / 北京其他区域")
    private String groupText;

    @Schema(description = "OLT 设备 id")
    private Long oltId;

    @Schema(description = "OLT 名称（实时台账）")
    private String name;

    @Schema(description = "OLT 地址")
    private String host;

    @Schema(description = "厂商文案：华为 / 鸿升光 / 中兴 / 烽火 / 通用")
    private String vendorText;

    @Schema(description = "在线状态：ONLINE / OFFLINE / unknown")
    private String status;

    @Schema(description = "状态文案：在线 / 离线 / 未采集")
    private String statusText;

    @Schema(description = "状态标签色：success / danger / info")
    private String statusLevel;

    @Schema(description = "绑定时间")
    private LocalDateTime boundAt;

    @Schema(description = "绑定操作人")
    private String createdBy;
}
