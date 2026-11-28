/**
 * @author: tangsky001
 * @date:   2026年11月28日
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.List;

/** 新增 ONT 线路模板请求 */
@Data
public class OltLineProfileCreateRequest {

    @Schema(description = "模板 ID，1-8192")
    private Integer profileId;

    @Schema(description = "模板名，数字、字母及下划线组成")
    private String name;

    @Schema(description = "要绑定的 DBA 模板 ID")
    private Integer dbaProfileId;

    @Schema(description = "PON 类型：GPON / EPON")
    private String ponType;

    @Schema(description = "启用 TR069 管理（仅 GPON 线路模板支持）")
    private Boolean tr069Enable;

    @Schema(description = "VLAN 列表，逗号分隔（仅 GPON 线路模板支持，每个 VLAN 挂一个 GEM 映射）")
    private String vlans;

    /** 展开 VLAN 列表（支持 "100,200" 或 "100-200" 段写法），空列表表示不配映射 */
    public List<Integer> vlanIds() {
        if (vlans == null || vlans.isBlank()) {
            return List.of();
        }
        return VlanIdExpression.expand(vlans);
    }
}
