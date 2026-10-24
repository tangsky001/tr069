package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一台 OLT 的 VLAN 快照：VLAN 列表 + 上行/主控口的 VLAN 放通情况 + 三层接口与静态路由。
 *
 * <p>整份快照按 JSON 存在 {@code olt_device.vlan_json}：VLAN 是随设备整体刷新的，
 * 单独建表反而多一次联表；数量也就几十条，不存在撑爆的问题。</p>
 */
@Data
public class OltVlanInfo {

    /** {@code display vlan all} 里的 VLAN 列表 */
    private List<OltVlan> vlans = new ArrayList<>();

    /** 各上行口/主控口的 VLAN（回显里的「透传 VLAN」） */
    private List<OltPortVlan> portVlans = new ArrayList<>();

    /** 三层 VLAN 接口（vlanif），来自 display ip interface brief */
    private List<OltIpInterface> ipInterfaces = new ArrayList<>();

    /** 静态路由，来自 display ip routing-table 里 Proto=Static 的行 */
    private List<OltStaticRoute> staticRoutes = new ArrayList<>();

    /** 回显末尾的总数（Total: N），解析不出时用 vlans.size() */
    private Integer total;

    /** 采集时间，页面显示「VLAN 快照时间」 */
    private String collectedAt;
}
