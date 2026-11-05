package com.tr069.acs.olt.model;

import lombok.Data;

/** 线路模板里加一条 T-CONT：{@code tcont  dba-profile-id } */
@Data
public class OltTcontRequest {

    /** 线路模板 ID */
    private Integer profileId;

    /** gpon / epon，留空按 gpon */
    private String ponType;

    /** T-CONT 索引，华为 0-127 */
    private Integer tcontId;

    /** 引用的 DBA 模板 ID */
    private Integer dbaProfileId;
}
