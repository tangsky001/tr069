package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 一个 VLAN 的概况，来自 {@code display vlan all}。
 *
 * <p>现场回显把标准口数和业务流数写在同一个表里，页面上直接列出来，
 * 一眼能看出这个 VLAN 挂在几个口、下挂几条业务流。</p>
 */
@Data
public class OltVlan {

    /** VLAN ID，1-4094 */
    private Integer vlanId;

    /** 类型：common（单层）/ smart / mux / standard，对应回显的 Type 列 */
    private String type;

    /** 属性，回显的 Attribute 列，通常是 common */
    private String attribute;

    /** 标准口个数（STND-Port NUM） */
    private Integer stdPortNum;

    /** 业务虚端口个数（SERV-Port NUM） */
    private Integer servPortNum;

    /** VLAN-Con 个数，回显为「-」时为 null */
    private Integer vlanConNum;
}
