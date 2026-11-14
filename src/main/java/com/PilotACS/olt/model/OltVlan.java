package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

@Data
public class OltVlan {

    @Schema(description = "VLAN ID，1-4094")
    private Integer vlanId;

    @Schema(description = "类型：common（单层）/ smart / mux / standard，对应回显的 Type 列")
    private String type;

    @Schema(description = "属性，回显的 Attribute 列，通常是 common")
    private String attribute;

    @Schema(description = "标准口个数（STND-Port NUM）")
    private Integer stdPortNum;

    @Schema(description = "业务虚端口个数（SERV-Port NUM）")
    private Integer servPortNum;

    @Schema(description = "VLAN-Con 个数，回显为「-」时为 null")
    private Integer vlanConNum;

    @Schema(description = "这个 VLAN 的成员端口（鸿升光 show vlan 才有这栏，华为的 display vlan all 没有）")
    private String taggedPorts;

    @Schema(description = "不带标签的成员端口")
    private String untaggedPorts;

    @Schema(description = "缺省 VLAN 成员端口（port ... default-vlan）")
    private String defaultVlanPorts;
}
