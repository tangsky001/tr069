package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 一条业务流（{@code display service-port all} 的一行）。
 *
 * <p>业务流把 ONT 的用户侧 VLAN 和 OLT 上的端口、ONT、GEM port 绑在一起：
 * 一条流就是「0/1/0 这个 PON 口的 1 号 ONT，VLAN 45 的流量走 GEM port 0」。</p>
 */
@Data
public class OltServicePort {

    /** 业务流索引（INDEX），删除/修改时按它定位 */
    private Integer index;

    /** 用户侧 VLAN ID */
    private Integer vlanId;

    /** VLAN 属性，回显里的第二列（常见 common / smart / qinq） */
    private String vlanAttr;

    /** 端口类型：gpon / epon */
    private String portType;

    private Integer frameNo;

    private Integer slotNo;

    private Integer portNo;

    /** ONT ID（GPON 才有） */
    private Integer ontId;

    /** 承载它的 GEM port */
    private Integer gemport;

    /** 接口标识，和 OLT 命令行一致：0/1/0 */
    public String iface() {
        if (slotNo == null || portNo == null) {
            return null;
        }
        return (frameNo == null ? 0 : frameNo) + "/" + slotNo + "/" + portNo;
    }
}
