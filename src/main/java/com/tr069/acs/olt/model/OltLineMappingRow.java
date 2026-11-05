package com.tr069.acs.olt.model;

import lombok.Data;

/** 线路模板「映射关系」表的一行：T-CONT → DBA 模板 → GEM port → 映射序号 / VLAN */
@Data
public class OltLineMappingRow {

    /** T-CONT 编号 */
    private Integer tcontId;

    /** 这条 T-CONT 引用的 DBA 模板 ID */
    private Integer dbaProfileId;

    /** DBA 模板名（查不到为空串，页面显示「DBA N（未采到）」由 dbaProfileId 兜） */
    private String dbaName;

    /** GEM port 编号；模板还没配 GEM 时为 null */
    private Integer gemportId;

    /** 映射序号，多个用逗号合并 */
    private String mappingId;

    /** 映射 VLAN，多个用逗号合并 */
    private String vlan;

    /** 映射方式：VLAN / 802.1p / Port */
    private String mappingMode;
}
