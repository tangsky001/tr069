package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.List;

/**
 * 新建 VLAN 的表单（页面「创建」弹窗）。
 *
 * <p>页面填的是表达式（{@code 100}、{@code 100-200,300}）+ tagged / untagged 端口，
 * 服务端按厂商拼命令：{@code vlan standard 100} → {@code port epon 1,2 tagged} → {@code exit}。</p>
 */
@Data
public class OltVlanRequest {

    /**
     * VLAN ID 表达式，支持 {@code ,} 与 {@code -} 连接，如 {@code 100}、{@code 100-200,300}。
     *
     * <p>留空时退回 {@link #vlanId} / {@link #endVlanId}（老页面只填起止 ID）。</p>
     */
    private String vlanIds;

    /** 起止写法（兼容旧表单）：只建一个时 {@link #endVlanId} 留空 */
    private Integer vlanId;

    /** 起止写法（兼容旧表单） */
    private Integer endVlanId;

    /** 打进 Tagged 的端口，写法 {@code epon1} / {@code ge3}，与 {@link OltVlanPort} 的 type + no 对应 */
    private List<String> tagged;

    /** 打进 Untagged 的端口 */
    private List<String> untagged;
}
