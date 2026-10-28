package com.tr069.acs.olt.model;

import java.util.List;

/**
 * 一条可下发的配置/查询动作，对应《EPON 光纤接入局端设备 命令行操作手册》里的一个功能格。
 *
 * <p>手册里每个「功能 / 命令 / 备注说明」的小格就是这里的 {@code label / commands / note}。
 * 命令模板用 {@code {key}} 占位，页面按 {@link OltActionParam} 生成表单，服务端渲染后下发。</p>
 *
 * <p>为什么做成数据而不是给每个功能写一个方法：手册 13 章几百条命令，写方法既写不完也没法维护；
 * 做成动作目录后，新增功能只是往 {@code HsgqActionCatalog} 里加一行，
 * 页面、校验、视图切换、留档全都不用动。</p>
 *
 * @param code     动作编号（章节.功能，如 {@code vlan.create}），页面提交时用
 * @param chapter  章节标题，页面按它分组
 * @param label    功能名（手册里那一列）
 * @param view     命令要在哪个视图里执行
 * @param commands 命令模板，按顺序下发
 * @param params   参数定义
 * @param query    查询类：执行完把回显返回页面，不触发重采
 * @param danger   破坏性操作（如 no vlan all），页面要二次确认
 * @param note     手册里的「备注说明」
 */
public record OltAction(String code, String chapter, String label, View view, List<String> commands,
                        List<OltActionParam> params, boolean query, boolean danger, String note) {

    /** 命令要在哪个视图里执行 */
    public enum View {
        /** 全局配置视图 OLT(config)# */
        CONFIG,
        /** VLAN 视图 OLT(config-vlan-100)#：进视图用 {@code vlan standard <vlanId>} */
        VLAN,
        /** 端口视图 OLT(config-ge-1)#：进视图用 {@code interface <portType> <portNo>} */
        PORT,
        /** PON 口视图 OLT(config-epon-1)#：进视图用 {@code interface epon <ponId>} */
        PON,
        /** GPON 口视图 OLT(config-gpon-1)#：进视图用 {@code interface gpon <ponId>}（鸿升光 GPON） */
        GPON,
        /** 线路模板视图 OLT(config-gpon-lineprofile-1)#：进视图用 {@code ont-lineprofile gpon profile-id <profileId>} */
        LINE_PROFILE,
        /** 业务模板视图 OLT(config-gpon-srvprofile-1)#：进视图用 {@code ont-srvprofile gpon profile-id <profileId>} */
        SRV_PROFILE,
        /** ONU 管理视图 OLT(config-onu-1/1)#：进视图用 {@code interface onu <ponId>/<onuId>} */
        ONU,
        /** 系统管理视图 OLT(config-manage)#：进视图用 {@code interface manage}（配置文件导入导出、升级） */
        MANAGE,
        /** ACL 视图 OLT(acl-basic-2000)# / OLT(acl-adv-3000)# / OLT(acl-link-5000)#：进视图用 {@code acl <aclId>} */
        ACL,
        /** 组播 VLAN 视图 OLT(multicast-vlan-100)#：进视图用 {@code multicast-vlan <vlanId>} */
        MCAST,
        /** 三层接口视图 OLT(config-vlanif-1)#：进视图用 {@code interface vlanif <vlanId>} */
        VLANIF
    }

    /** 全局配置视图下的写操作 */
    public static OltAction config(String code, String chapter, String label, String note,
                                   List<OltActionParam> params, String... commands) {
        return new OltAction(code, chapter, label, View.CONFIG, List.of(commands), params, false, false, note);
    }

    /** 全局配置视图下的查询 */
    public static OltAction query(String code, String chapter, String label, String note, String command) {
        return new OltAction(code, chapter, label, View.CONFIG, List.of(command), List.of(), true, false, note);
    }

    /** 带视图切换的写操作 */
    public static OltAction in(View view, String code, String chapter, String label, String note,
                               List<OltActionParam> params, String... commands) {
        return new OltAction(code, chapter, label, view, List.of(commands), params, false, false, note);
    }

    /** 带参数的查询（如 ping <目标地址>） */
    public static OltAction queryWith(String code, String chapter, String label, String note,
                                      List<OltActionParam> params, String command) {
        return new OltAction(code, chapter, label, View.CONFIG, List.of(command), params, true, false, note);
    }

    /** 带视图切换的查询 */
    public static OltAction queryIn(View view, String code, String chapter, String label, String note,
                                    List<OltActionParam> params, String command) {
        return new OltAction(code, chapter, label, view, List.of(command), params, true, false, note);
    }

    /** 标记为破坏性操作（页面二次确认，后端照常执行） */
    public OltAction dangerous() {
        return new OltAction(code, chapter, label, view, commands, params, query, true, note);
    }
}
