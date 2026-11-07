package com.tr069.acs.olt.model;

import lombok.Data;

/** 一条 DBA 模板（{@code display dba-profile all}） */
@Data
public class OltDbaProfile {

    /** 模板 ID，线路模板靠它引用 */
    private Integer profileId;

    /** 模板名 */
    private String name;

    /** 类型：type1 ~ type5 */
    private String type;

    /** 固定带宽（kbps） */
    private Integer fixKbps;

    /** 保证带宽（kbps） */
    private Integer assureKbps;

    /** 最大带宽（kbps） */
    private Integer maxKbps;
}
