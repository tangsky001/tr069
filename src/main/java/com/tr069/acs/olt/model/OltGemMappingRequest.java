package com.tr069.acs.olt.model;

import lombok.Data;

/** 给 GEM Port 加映射：{@code gem mapping  <映射序号> vlan } */
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
