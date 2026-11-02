package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 线路模板里加一个 GEM Port：{@code gem add <GEM Port 索引> eth tcont <T-CONT 索引> encrypt on}。
 *
 * <p>GEM Port 挂在 T-CONT 上，业务 VLAN 再从 GEM 映射进来——先 T-CONT、再 GEM Port、最后映射。</p>
 */
@Data
public class OltGemportRequest {

    /** 线路模板 ID */
    private Integer profileId;

    /** gpon / epon，留空按 gpon */
    private String ponType;

    /** GEM Port 挂到哪条 T-CONT 上 */
    private Integer tcontId;

    /** GEM Port 索引，华为 0-1023 */
    private Integer gemportId;
}
