package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 一条 DBA 模板（{@code display dba-profile all}）。
 *
 * <p>DBA（Dynamic Bandwidth Allocation）决定 ONT 上行能抢到多少带宽：
 * type1 固定、type2 保证、type3 保证+最大、type4 最大、type5 固定+保证+最大。
 * 三个带宽值单位是 kbps，设备回显就是这三个数。</p>
 */
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
