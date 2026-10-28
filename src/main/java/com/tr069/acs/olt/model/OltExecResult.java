package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次命令动作的执行结果。
 *
 * <p>查询类（{@link OltAction#query()}）只看 {@code outputs}，页面上原样贴回显；
 * 配置类执行完会重采一次，{@code detail} 带着最新的板卡/VLAN 等数据，页面直接换上去。</p>
 */
@Data
public class OltExecResult {

    private String code;

    private String label;

    /** 真正下发的命令（含进视图的 vlan standard / interface 与收尾的 exit），页面显示出来便于核对 */
    private List<String> commands = new ArrayList<>();

    /** 每条命令的回显 */
    private List<String> outputs = new ArrayList<>();

    /** 是不是查询类动作 */
    private boolean query;

    /** 配置类动作执行后重采的最新详情；查询类为 null */
    private OltDetail detail;
}
