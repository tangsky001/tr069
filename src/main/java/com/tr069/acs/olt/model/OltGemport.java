package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 线路模板里的一个 GEM port（{@code display ont-lineprofile gpon all} 的 GEM port 行）。
 *
 * <p>GEM port 挂在某个 T-CONT 上，映射方式（VLAN / 802.1p / Port）决定业务流怎么进来。</p>
 */
@Data
public class OltGemport {

    /** GEM port 编号 */
    private Integer gemportId;

    /** 承载它的 T-CONT */
    private Integer tcontId;

    /** 映射方式：VLAN / 802.1p / Port */
    private String mappingMode;

    /** GEM 内的映射序号（{@code gem mapping <gemport> <序号> vlan <vlan>} 的第二个数字） */
    private Integer mappingId;

    /**
     * GEM 内的映射列表（明细回显的映射表格，一个 GEM port 可挂多条映射）。
     * 单条映射时与 mappingId/vlan 一致；解析不到时为空列表。
     */
    private List<OltGemMapping> mappings = new ArrayList<>();

    /** 业务流里的内层 VLAN（service-port 的 user-vlan） */
    private String userVlan;

    /** 映射到的 VLAN（映射方式是 VLAN 时才有） */
    private String vlan;
}
