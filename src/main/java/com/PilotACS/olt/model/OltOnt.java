package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
public class OltOnt {

    @Schema(description = "ONT ID，同一端口内唯一")
    private Integer ontId;

    @Schema(description = "GPON 是 SN（16 位十六进制），EPON 是 MAC")
    private String sn;

    @Schema(description = "Control flag：active / deactive")
    private String controlFlag;

    @Schema(description = "Run state：online / offline（EPON 写 up / down）")
    private String runState;

    @Schema(description = "Config state：normal / initial / failed")
    private String configState;

    @Schema(description = "Match state：match / mismatch")
    private String matchState;

    @Schema(description = "设备回显里的 Description 段，一般是 ONT 型号，如 SK-D748S")
    private String description;

    @Schema(description = "Run state 是不是在线")
    private Boolean online;

    @Schema(description = "在线状态中文：在线 / 离线 / 未知（采集没给 runState 时不算离线）")
    private String stateText;

    @Schema(description = "配置状态中文：正常 / 初始 / 失败，未采集为空")
    private String configText;

    @Schema(description = "设备上的 ONU 名称（鸿升光 show onu-info all 的 ONU-Name 列，如 ONU01/01）")
    private String name;

    @Schema(description = "认证状态：true 表示已通过认证（回显 Table 的 Auth 列 TRUE/FALSE）")
    private Boolean authorized;

    @Schema(description = "注册（上线）时间，回显原样保留，如 2026/10/25 00:46:10")
    private String registerTime;

    @Schema(description = "最后掉线时间；设备不上报时为空，页面显示「-」")
    private String lastOfflineTime;

    @Schema(description = "掉线原因；设备不上报时为空")
    private String offlineReason;

    @Schema(description = "设备类型：SFU / HGU")
    private String deviceType;

    @Schema(description = "ONU 类型，如 1ge3fe（几个 GE 口几个 FE 口）")
    private String onuType;

    @Schema(description = "逻辑距离（米）")
    private Integer distance;

    @Schema(description = "接收光功率，回显原样（如 -21.4267 dBm）")
    private String rxPower;

    @Schema(description = "管理地址（display ont info 单台详情里的 ONT IP 0 地址/掩码）")
    private String mgmtIp;

    @Schema(description = "MAC 地址：优先取单台详情回显里的 MAC 字段；MAC 认证（EPON）的认证码本身就是 MAC")
    private String mac;

    @Schema(description = "设备明确回了 0000-0000-0000（ONT 没学到 MAC）：标记一次，之后不再重复补采")
    private Boolean macUnavailable;

    @Schema(description = "线路模板编号（单台详情里的 Line profile ID）")
    private Integer lineProfileId;

    @Schema(description = "线路模板名称")
    private String lineProfileName;

    @Schema(description = "业务模板编号（单台详情里的 Service profile ID）")
    private Integer srvProfileId;

    @Schema(description = "业务模板名称")
    private String srvProfileName;

    @Schema(description = "拉过一次配置脚本仍拿不到模板号（设备配置里就没有）：标记后不再重复拉")
    private Boolean profileUnavailable;
}
