package com.tr069.acs.exec.model;

/**
 * 一条待写入的参数。
 *
 * @param bizObject 业务对象
 * @param bizField  业务字段名，如 ssid / vlan_id
 * @param path      渲染后的 CWMP 参数全路径
 * @param value     下发值
 * @param valueType xsi:type：string / unsignedInt / boolean
 */
public record PlanItem(String bizObject, String bizField, String path, String value,
                       String valueType) {
}
