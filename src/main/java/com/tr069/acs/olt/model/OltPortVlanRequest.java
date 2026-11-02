package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 上行口 VLAN 透传的表单：在某个口上放通（或解除）哪些 VLAN。
 *
 * <p>对应设备命令里的两段参数：口（{@code 0/7/0}）和 VLAN 清单
 * （{@code 41,45-46}，允许逗号和区间写法，和「新增 VLAN」用的是同一套解析）。</p>
 */
@Data
public class OltPortVlanRequest {

    /** 机框/板卡/端口，如 {@code 0/7/0}；只写框和槽的会被拒（不知道该放通到哪个口） */
    private String iface;

    /** VLAN ID 表达式：{@code 41}、{@code 41,45-46} */
    private String vlanIds;

    /** true 表示按 Native（不打标签）下发，只对「放通」有意义 */
    private Boolean nativeVlan;
}
