package com.tr069.acs.olt.model;

import java.util.List;

/**
 * 「新建 VLAN」表单的元数据：VLAN ID 范围、一次最多几个、哪些端口能做成员。
 *
 * <p>这些以前页面上也存了一份（范围 1-4094、带端口时最多 16 个、端口类型顺序、
 * 同一个端口不能既 tagged 又 untagged），改了服务端忘改页面就会出现
 * 「页面说能建、下发被拒」。现在页面照着这份渲染，不再自己判。</p>
 *
 * @param supported          这台设备支不支持 VLAN 下发
 * @param vlanIdMin          VLAN ID 下限
 * @param vlanIdMax          VLAN ID 上限（4094）
 * @param batchMax           不带成员端口时一次最多建几个
 * @param batchMaxWithPorts  带成员端口时一次最多建几个（每个 VLAN 都要单独进视图）
 * @param portTypes          能做 VLAN 成员的端口类型，顺序即页面分组顺序
 * @param exclusiveTagMode   同一个端口能不能既 tagged 又 untagged（这台设备不行）
 */
public record OltVlanForm(boolean supported, int vlanIdMin, int vlanIdMax,
                          int batchMax, int batchMaxWithPorts,
                          List<String> portTypes, boolean exclusiveTagMode) {
}
