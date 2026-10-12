package com.tr069.acs.worklist.model;

import lombok.Data;

/**
 * 工单参数定义，对应 Python 版 {@code WORKLIST_ARGS} 中的每一项。
 *
 * <p>原结构中值为 {@code (默认值, 序号)} 的二元组，这里展开为对象，
 * 额外补充类型、必填、枚举等约束，便于前端自动生成下发表单。</p>
 */
@Data
public class WorklistArg {

    /** 参数名，下发时作为占位符 {@code ${name}} 使用。 */
    private String name;

    /** 默认值。 */
    private String defaultValue;

    /** 展示序号，对应原元组第二个元素。 */
    private int order;

    /** 是否必填。 */
    private boolean required = true;

    /** 值类型：string / int / boolean / enum。 */
    private String type = "string";

    /** 当 type=enum 时的可选值。 */
    private java.util.List<String> enumValues;

    /** 参数说明。 */
    private String description;
}
