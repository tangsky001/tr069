package com.tr069.acs.olt.model;

import java.util.List;

/**
 * DBA 模板的一种类型（华为 {@code dba-profile add} 的 type1~type5）。
 *
 * <p>每类要填的带宽字段不一样：type1 只要固定带宽，type4 只要最大带宽，
 * type5 三个都得填。这份矩阵以前写在页面里，服务端校验和页面提示各一份，
 * 现在只有服务端这一处，页面照着 {@code fields} 决定显示哪几个输入框。</p>
 *
 * @param code   {@code type1}~{@code type5}
 * @param label  页面下拉里显示的文字
 * @param fields 这一类型要填的字段：fix（固定）/ assure（保证）/ max（最大）
 */
public record OltDbaType(String code, String label, List<String> fields) {
}
