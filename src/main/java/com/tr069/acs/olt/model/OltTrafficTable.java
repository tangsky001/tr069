package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 一条流量限速模板（{@code display traffic table ip all} 的一行）。
 *
 * <p>业务流引用它限速：CIR 是承诺速率（保证带宽），PIR 是峰值速率（允许冲到的上限），
 * 单位 kbps。</p>
 */
@Data
public class OltTrafficTable {

    /** 模板索引 */
    private Integer index;

    /** 模板名 */
    private String name;

    /** 承诺速率 CIR（kbps），无承诺时为 null */
    private Integer cir;

    /** 峰值速率 PIR（kbps） */
    private Integer pir;
}
