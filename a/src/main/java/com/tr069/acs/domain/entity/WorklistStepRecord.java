package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单执行步骤明细 */
@Data
@TableName("worklist_step")
public class WorklistStepRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long worklistId;

    /** 步骤序号，从 0 开始 */
    private Integer stepIndex;

    private String comment;

    /** CWMP 方法名 */
    private String rpc;

    /** 实际下发的参数，JSON */
    private String requestArgs;

    /** 设备返回结果，JSON */
    private String responseContent;

    /** init / success / fail / skip */
    private String status;

    private String errorMsg;

    private Long durationMs;

    private LocalDateTime createdAt;
}
