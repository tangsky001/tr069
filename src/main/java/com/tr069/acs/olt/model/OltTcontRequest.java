package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 线路模板里加一条 T-CONT：{@code tcont <T-CONT 索引> dba-profile-id <DBA 模板>}。
 *
 * <p>T-CONT 是 ONT 的上行容器，挂哪档带宽就靠引用的那条 DBA 模板——所以这步必须
 * 先有 DBA（页面下拉框选的就是已采到的 DBA 模板）。</p>
 */
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
