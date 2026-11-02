package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 给 GEM Port 加映射：{@code gem mapping <GEM Port> <映射序号> vlan <VLAN>}。
 *
 * <p>「映射序号」是 GEM 内部的编号（0-7），和业务 VLAN 一一对应；页面上两个框都支持
 * 逗号分隔（{@code 1,2} 配 {@code 46,41}），按顺序配对下发。</p>
 */
@Data
public class OltGemMappingRequest {

    /** 线路模板 ID */
    private Integer profileId;

    /** gpon / epon，留空按 gpon */
    private String ponType;

    /** 哪个 GEM Port 上的映射 */
    private Integer gemportId;

    /** GEM 映射序号，逗号分隔，如 {@code 1,2}；留空表示从 0 开始顺延 */
    private String mappingIds;

    /** 业务 VLAN，逗号分隔，如 {@code 46,41}，与映射序号一一对应 */
    private String vlans;
}
