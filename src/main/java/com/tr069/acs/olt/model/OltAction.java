package com.tr069.acs.olt.model;

import java.util.List;

/** 一条可下发的配置/查询动作，对应《EPON 光纤接入局端设备 命令行操作手册》里的一个功能格 */
public record OltAction(String code, String chapter, String label, View view, List<String> commands,
                        List<String> previewCommands, List<OltActionParam> params, boolean query,
                        boolean danger, String note) {

    /** 命令要在哪个视图里执行 */
    public enum View {
        /** 全局配置视图 OLT(config)# */
        CONFIG,
        /** VLAN 视图 OLT(config-vlan-100)#：进视图用 {@code vlan standard } */
        VLAN,
        /** 端口视图 OLT(config-ge-1)#：进视图用 {@code interface  } */
        PORT,
        /** PON 口视图 OLT(config-epon-1)#：进视图用 {@code interface epon } */
        PON,
        /** GPON 口视图 OLT(config-gpon-1)#：进视图用 {@code interface gpon }（鸿升光 GPON） */
        GPON,
        /** 线路模板视图 OLT(config-gpon-lineprofile-1)#：进视图用 {@code ont-lineprofile gpon profile-id } */
        LINE_PROFILE,
        /** 业务模板视图 OLT(config-gpon-srvprofile-1)#：进视图用 {@code ont-srvprofile gpon profile-id } */
        SRV_PROFILE,
        /** ONU 管理视图 OLT(config-onu-1/1)#：进视图用 {@code interface onu /} */
        ONU,
        /** 系统管理视图 OLT(config-manage)#：进视图用 {@code interface manage}（配置文件导入导出、升级） */
        MANAGE,
        /** ACL 视图 OLT(acl-basic-2000)# / OLT(acl-adv-3000)# / OLT(acl-link-5000)#：进视图用 {@code acl } */
        ACL,
        /** 组播 VLAN 视图 OLT(multicast-vlan-100)#：进视图用 {@code multicast-vlan } */
        MCAST,
        /** 三层接口视图 OLT(config-vlanif-1)#：进视图用 {@code interface vlanif } */
        VLANIF
    }

    /** 全局配置视图下的写操作 */
    public static OltAction config(String code, String chapter, String label, String note,
                                   List<OltActionParam> params, String... commands) {
        return new OltAction(code, chapter, label, View.CONFIG, List.of(commands), List.of(),
                params, false, false, note);
    }

    /** 全局配置视图下的查询 */
    public static OltAction query(String code, String chapter, String label, String note, String command) {
        return new OltAction(code, chapter, label, View.CONFIG, List.of(command), List.of(),
                List.of(), true, false, note);
    }

    /** 带视图切换的写操作 */
    public static OltAction in(View view, String code, String chapter, String label, String note,
                               List<OltActionParam> params, String... commands) {
        return new OltAction(code, chapter, label, view, List.of(commands), List.of(),
                params, false, false, note);
    }

    /** 带参数的查询（如 ping <目标地址>） */
    public static OltAction queryWith(String code, String chapter, String label, String note,
                                      List<OltActionParam> params, String command) {
        return new OltAction(code, chapter, label, View.CONFIG, List.of(command), List.of(),
                params, true, false, note);
    }

    /** 带视图切换的查询 */
    public static OltAction queryIn(View view, String code, String chapter, String label, String note,
                                    List<OltActionParam> params, String command) {
        return new OltAction(code, chapter, label, view, List.of(command), List.of(),
                params, true, false, note);
    }

    /** 标记为破坏性操作（页面二次确认，后端照常执行） */
    public OltAction dangerous() {
        return new OltAction(code, chapter, label, view, commands, previewCommands,
                params, query, true, note);
    }

    /** 带上给页面展示的命令序列（进视图 + 命令 + exit，占位符保留） */
    public OltAction withPreviewCommands(List<String> preview) {
        return new OltAction(code, chapter, label, view, commands, preview, params, query, danger, note);
    }
}
