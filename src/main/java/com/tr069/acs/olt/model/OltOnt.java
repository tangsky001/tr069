package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 端口下一个 ONT（也就是页面常说的 ONU）的状态。
 *
 * <p>来自 config 视图的 {@code display ont info 0 <槽位> all}：一行一条 ONT，
 * 在线和不在线都会列出来（{@code Run state} 写 online / offline）。</p>
 */
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
}
