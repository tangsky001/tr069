package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单执行步骤明细。
 *
 * <p>一张工单实例由若干 CWMP 步骤组成，逐步串行下发。这里记录每一步的
 * 实际下发参数与设备响应，便于失败时精确定位到第几步、哪个参数出问题。</p>
 */
@Data
@TableName("worklist_step")
public class WorklistStepRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long worklistId;

    /**
     * 步骤序号，从 0 开始
     */
    private Integer stepIndex;

    private String comment;

    /**
     * CWMP 方法名
     */
    private String rpc;

    /**
     * 实际下发的参数，JSON
     */
    private String requestArgs;

    /**
     * 设备返回结果，JSON
     */
    private String responseContent;

    /**
     * init / success / fail / skip
     */
    private String status;

    private String errorMsg;

    private Long durationMs;

    private LocalDateTime createdAt;
}
