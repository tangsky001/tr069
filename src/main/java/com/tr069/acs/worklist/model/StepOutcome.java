package com.tr069.acs.worklist.model;

import lombok.Builder;
import lombok.Data;

/**
 * 工单中单个步骤的执行结果。
 */
@Data
@Builder
public class StepOutcome {

    /** 步骤序号，从 0 开始。 */
    private int index;

    /** 步骤说明。 */
    private String comment;

    /** CWMP 方法名。 */
    private String rpc;

    /** init / success / fail / skip。 */
    private String status;

    /** 实际下发的参数（占位符替换后）。 */
    private Object request;

    /** 设备返回内容。 */
    private Object response;

    /** 失败原因。 */
    private String error;

    /** 耗时(毫秒)。 */
    private long durationMs;

    /** 该步骤失败后又继续执行的标记。 */
    private boolean continued;
}
