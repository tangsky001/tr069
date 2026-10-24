package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个端口上放通的 VLAN，来自 {@code display port vlan 0/<槽位>/<端口>}。
 *
 * <p>上行口才有 Native VLAN 这一行，PON 口只有 VLAN 列表。</p>
 */
@Data
public class OltPortVlan {

    /** 机框号，华为固定 0 */
    private Integer frameNo;

    private Integer slotNo;

    private Integer portNo;

    /** 接口标识，如 0/6/0，和命令行一致 */
    private String iface;

    /** 该口放通的 VLAN，升序；页面把连续段压成 45-46 显示 */
    private List<Integer> vlans = new ArrayList<>();

    /** Native VLAN（PVID），回显里没有这一行时为 null */
    private Integer nativeVlan;
}
