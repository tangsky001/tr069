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
}
