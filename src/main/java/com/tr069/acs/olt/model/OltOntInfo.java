package com.tr069.acs.olt.model;

import lombok.Data;

/** 单条 ONT 的详情：{@code display ont info <端口> } 里那些块状字段 */
@Data
public class OltOntInfo {

    private Integer ontId;

    /** 认证串（GPON 是 SN，EPON 是 MAC） */
    private String sn;

    /** ONT 型号/描述，如 HG5145F */
    private String desc;

    /** ONT IP 0 地址/掩码，如 10.172.10.17/24 */
    private String ip;

    /** MAC 地址：部分版本的单台详情回显里有，没有时采集侧按认证码还原 */
    private String mac;

    /** 线路模板：编号与名称，页面显示成 100 / FTTH-GPON-100 */
    private Integer lineProfileId;

    private String lineProfileName;

    /** 业务（能力）模板：编号与名称 */
    private Integer srvProfileId;

    private String srvProfileName;

    /** 最后一次下线原因，如 LOSi/LOBi【光质量差/光纤断】 */
    private String lastDownReason;

    private String lastUpTime;

    private String lastDownTime;

    private String onlineDuration;

    private String runState;

    private String configState;

    private String matchState;
}
