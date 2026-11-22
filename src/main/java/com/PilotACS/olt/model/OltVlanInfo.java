/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.664
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** VLAN 快照汇总 */
@Data
public class OltVlanInfo {

    @Schema(description = "display vlan all 里的 VLAN 列表")
    private List<OltVlan> vlans = new ArrayList<>();

    @Schema(description = "各上行口/主控口的 VLAN（回显里的「透传 VLAN」）")
    private List<OltPortVlan> portVlans = new ArrayList<>();

    @Schema(description = "能做 VLAN 成员的物理端口（「创建 VLAN」弹窗的复选框数据源）")
    private List<OltVlanPort> memberPorts = new ArrayList<>();

    @Schema(description = "三层 VLAN 接口（vlanif），来自 display ip interface brief")
    private List<OltIpInterface> ipInterfaces = new ArrayList<>();

    @Schema(description = "静态路由，来自 display ip routing-table 里 Proto=Static 的行")
    private List<OltStaticRoute> staticRoutes = new ArrayList<>();

    @Schema(description = "回显末尾的总数（Total: N），解析不出时用 vlans.size()")
    private Integer total;

    @Schema(description = "采集时间，页面显示「VLAN 快照时间」")
    private String collectedAt;
}
