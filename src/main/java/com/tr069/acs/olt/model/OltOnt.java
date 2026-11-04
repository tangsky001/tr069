package com.tr069.acs.olt.model;

import lombok.Data;

/** 端口下一个 ONT（也就是页面常说的 ONU）的状态 */
@Data
public class OltOnt {

    /** ONT ID，同一端口内唯一 */
    private Integer ontId;

    /** GPON 是 SN（16 位十六进制），EPON 是 MAC */
    private String sn;

    /** Control flag：active / deactive */
    private String controlFlag;

    /** Run state：online / offline（EPON 写 up / down） */
    private String runState;

    /** Config state：normal / initial / failed */
    private String configState;

    /** Match state：match / mismatch */
    private String matchState;

    /** 设备回显里的 Description 段，一般是 ONT 型号，如 SK-D748S */
    private String description;

    /** Run state 是不是在线 */
    private Boolean online;

    /** 在线状态中文：在线 / 离线 / 未知（采集没给 runState 时不算离线） */
    private String stateText;

    /** 配置状态中文：正常 / 初始 / 失败，未采集为空 */
    private String configText;

    /** 设备上的 ONU 名称（鸿升光 {@code show onu-info all} 的 ONU-Name 列，如 ONU01/01） */
    private String name;

    /** 认证状态：true 表示已通过认证（回显 Table 的 Auth 列 TRUE/FALSE） */
    private Boolean authorized;

    /** 注册（上线）时间，回显原样保留，如 {@code 2026/10/25 00:46:10} */
    private String registerTime;

    /** 最后掉线时间；设备不上报时为空，页面显示「-」 */
    private String lastOfflineTime;

    /** 掉线原因；设备不上报时为空 */
    private String offlineReason;

    /** 设备类型：SFU / HGU */
    private String deviceType;

    /** ONU 类型，如 {@code 1ge3fe}（几个 GE 口几个 FE 口） */
    private String onuType;

    /** 逻辑距离（米） */
    private Integer distance;

    /** 接收光功率，回显原样（如 {@code -21.4267 dBm}） */
    private String rxPower;

    /** 管理地址（{@code display ont info} 单台详情里的 ONT IP 0 地址/掩码） */
    private String mgmtIp;

    /** MAC 地址：优先取单台详情回显里的 MAC 字段；MAC 认证（EPON）的认证码本身就是 MAC */
    private String mac;

    /** 线路模板编号（单台详情里的 Line profile ID） */
    private Integer lineProfileId;

    /** 线路模板名称 */
    private String lineProfileName;

    /** 业务模板编号（单台详情里的 Service profile ID） */
    private Integer srvProfileId;

    /** 业务模板名称 */
    private String srvProfileName;
}
