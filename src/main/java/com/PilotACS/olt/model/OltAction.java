/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.347
 *
 */

package com.PilotACS.olt.model;

import java.util.List;

/** 可下发动作定义。 */
public record OltAction(String code, String chapter, String label, View view, List<String> commands,
                        List<String> previewCommands, List<OltActionParam> params, boolean query,
                        boolean danger, String note) {

    /** 命令要在哪个视图里执行 */
    public enum View {
        /** 全局配置视图 */
        CONFIG,
        /** VLAN 视图 */
        VLAN,
        /** 端口视图 */
        PORT,
        /** PON 口视图 */
        PON,
        /** GPON 口视图 */
        GPON,
        /** 线路模板视图 */
        LINE_PROFILE,
        /** 业务模板视图 */
        SRV_PROFILE,
        /** ONU 管理视图 */
        ONU,
        /** 系统管理视图 */
        MANAGE,
        /** ACL 视图 */
        ACL,
        /** 组播 VLAN 视图 */
        MCAST,
        /** 三层接口视图 */
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

    /** 标记为破坏性操作。 */
    public OltAction dangerous() {
        return new OltAction(code, chapter, label, view, commands, previewCommands,
                params, query, true, note);
    }

    /** 设置预演命令序列。 */
    public OltAction withPreviewCommands(List<String> preview) {
        return new OltAction(code, chapter, label, view, commands, preview, params, query, danger, note);
    }
}
