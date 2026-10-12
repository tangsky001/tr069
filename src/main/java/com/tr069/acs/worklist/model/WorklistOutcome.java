package com.tr069.acs.worklist.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一张工单的执行结果。
 */
@Data
@Builder
public class WorklistOutcome {

    /** 数据库中的工单号，入库失败时为 null。 */
    private Long worklistId;

    /** 是否全部步骤成功。 */
    private boolean success;

    /** 工单模板 key。 */
    private String templateKey;

    /** 命中的光猫适配档案 key，未命中为空。 */
    private String profileKey;

    /** 设备序列号。 */
    private String sn;

    /** 失败原因摘要。 */
    private String error;

    /** 总耗时(毫秒)。 */
    private long durationMs;

    /** 每一步的明细。 */
    @Builder.Default
    private List<StepOutcome> steps = new ArrayList<>();
}
