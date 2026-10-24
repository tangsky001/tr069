package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 三层 VLAN 接口（Vlanif），来自 {@code display ip interface brief}。
 *
 * <p>回显里只有 {@code vlanif46  10.172.10.254/24  up  up} 这样一行，
 * 页面按「VLAN ID / VLAN IP / 子网掩码」展示，所以这里把 /24 换算成 255.255.255.0。</p>
 */
@Data
public class OltIpInterface {

    /** 接口名，如 vlanif46 */
    private String name;

    /** 从接口名里带出来的 VLAN ID */
    private Integer vlanId;

    /** 接口 IP，带掩码那段的左边部分 */
    private String ipAddress;

    /** 掩码位数，24 */
    private Integer prefixLength;

    /** 点分十进制掩码，255.255.255.0 */
    private String mask;

    /** 物理状态 up/down */
    private String physical;

    /** 协议状态 up/down */
    private String protocol;
}
