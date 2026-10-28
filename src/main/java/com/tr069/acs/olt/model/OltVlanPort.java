package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 一个可以做 VLAN 成员的物理端口，页面「创建 VLAN」弹窗里就是一个复选框。
 *
 * <p>端口来自设备回显（{@code show port state all}），采不到时按型号兜底生成，
 * 所以勾选框里出现的口，下发给设备一定认得。</p>
 */
@Data
public class OltVlanPort {

    /** 端口类型，与命令里的关键字一致：{@code epon} / {@code gpon} / {@code ge} / {@code xge} */
    private String type;

    /** 端口号，从 1 开始 */
    private Integer no;

    /** 页面上显示的名字：PON01 / GE03 / XGE01 */
    private String label;

    public OltVlanPort() {
    }

    public OltVlanPort(String type, Integer no) {
        this.type = type;
        this.no = no;
        this.label = label(type, no);
    }

    /**
     * 端口类型 + 编号 → 面板上的叫法。
     *
     * <p>PON 口不管是 EPON 还是 GPON，页面上都叫 PON（用户不关心关键字），
     * 上联电口叫 GE、万兆口叫 XGE。</p>
     */
    public static String label(String type, Integer no) {
        String prefix = switch (type == null ? "" : type.toLowerCase()) {
            case "epon", "gpon" -> "PON";
            case "xge" -> "XGE";
            default -> "GE";
        };
        return prefix + String.format("%02d", no == null ? 0 : no);
    }
}
