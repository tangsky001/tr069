package com.tr069.acs.olt.model;

import lombok.Data;

/** 一个可以做 VLAN 成员的物理端口，页面「创建 VLAN」弹窗里就是一个复选框 */
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

    /** 端口类型 + 编号 → 面板上的叫法 */
    public static String label(String type, Integer no) {
        String prefix = switch (type == null ? "" : type.toLowerCase()) {
            case "epon", "gpon" -> "PON";
            case "xge" -> "XGE";
            default -> "GE";
        };
        return prefix + String.format("%02d", no == null ? 0 : no);
    }
}
