package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 一条静态路由，来自 {@code display ip routing-table} 里 Proto 为 Static 的行。
 */
@Data
public class OltStaticRoute {

    /** 目的网络/掩码，回显原样，如 0.0.0.0/0 */
    private String destination;

    /** 下一跳网关，如 10.172.10.1 */
    private String nextHop;

    /** 出接口，如 vlanif46 */
    private String iface;

    /** 优先级（Pre） */
    private Integer preference;

    /** 开销（Cost） */
    private Integer cost;
}
