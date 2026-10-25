package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 单条 ONT 的详情：{@code display ont info <端口> <ONT ID>} 里那些块状字段。
 *
 * <p>整块板的 {@code display ont info 0 <槽位> all} 只给 ID / SN / 在线状态，
 * 线路模板、业务模板、IP、最后一次掉线原因这些只有单条详情里才有，
 * 所以不进轮询采集，页面上点某条 ONT 的 SN 时才查一次。</p>
 */
@Data
public class OltOntInfo {

    private Integer ontId;

    /** 认证串（GPON 是 SN，EPON 是 MAC） */
    private String sn;

    /** ONT 型号/描述，如 HG5145F */
    private String desc;

    /** ONT IP 0 地址/掩码，如 10.172.10.17/24 */
    private String ip;

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
