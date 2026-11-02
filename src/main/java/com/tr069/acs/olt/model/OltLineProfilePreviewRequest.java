package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 线路模板三个弹窗的命令预演：{@code kind} 决定算哪段命令。
 *
 * <p>索引、VLAN 都用字符串收（页面上是文本框，可能还没填），
 * 校验与取值由后端的 {@code numberInRange / csvInts} 统一做。</p>
 */
@Data
public class OltLineProfilePreviewRequest {

    /** tcont / gemport / mapping */
    private String kind;

    private Integer profileId;

    private String ponType;

    /** T-CONT 索引（tcont、gemport 用） */
    private String tcontId;

    /** DBA 模板 ID（tcont 用） */
    private String dbaProfileId;

    /** GEM Port 索引（gemport、mapping 用） */
    private String gemportId;

    /** GEM 映射序号，逗号分隔（mapping 用；留空按 VLAN 顺序从 0 起） */
    private String mappingIds;

    /** 业务 VLAN，逗号分隔，与映射序号一一对应（mapping 用） */
    private String vlans;
}
