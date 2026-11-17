/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.254
 *
 */

package com.PilotACS.group.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 组内成员行：绑定关系 + 设备台账（OLT 实时；ONU 为归属快照），展示字段全部在后端拼好 */
@Data
@Schema(description = "组内成员（OLT / ONU）")
public class GroupMemberView {

    @Schema(description = "绑定关系主键，解绑用")
    private Long id;

    @Schema(description = "分组节点引用：ROOT 或 G:自定义分组 id")
    private String nodeRef;

    @Schema(description = "分组节点全路径文案：北京市 / 市辖区 / 海淀区 / 北京其他区域")
    private String groupText;

    @Schema(description = "实际归属分组全路径文案（根视角下可能与所选节点不同；未归属=资源根组）")
    private String actualGroupText;

    @Schema(description = "OLT 设备 id；ONU 为 null")
    private Long oltId;

    @Schema(description = "设备业务标识：OLT 为 id 字符串；ONU 为光猫标识（如 SN）")
    private String memberRef;

    @Schema(description = "设备名称（OLT 实时台账；ONU 为归属快照）")
    private String name;

    @Schema(description = "设备地址；ONU 暂无")
    private String host;

    @Schema(description = "厂商文案：华为 / 鸿升光 / 中兴 / 烽火 / 通用")
    private String vendorText;

    @Schema(description = "在线状态：ONLINE / OFFLINE / unknown")
    private String status;

    @Schema(description = "状态文案：在线 / 离线 / 未采集")
    private String statusText;

    @Schema(description = "状态标签色：success / danger / info")
    private String statusLevel;

    @Schema(description = "光猫软件版本（ONU 台账）")
    private String softwareVersion;

    @Schema(description = "光猫运营商（ONU 台账）")
    private String isp;

    @Schema(description = "光猫 IP（参数树派生，未采集为 null）")
    private String ip;

    @Schema(description = "最近上报时间文案（ONU 台账）")
    private String lastInformText;

    @Schema(description = "机房编号（ONU 台账，下发时替换模板 %room%）")
    private String roomNo;

    @Schema(description = "备注（ONU 台账）")
    private String remark;

    @Schema(description = "绑定时间")
    private LocalDateTime boundAt;

    @Schema(description = "绑定操作人")
    private String createdBy;
}
