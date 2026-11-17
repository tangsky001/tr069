package com.PilotACS.worklist.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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

    /** 执行方式：CWMP 方法名 / IntentDelivery / BizTemplate */
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

    /** 状态中文，返回给页面前补齐 */
    @TableField(exist = false)
    private String statusText;

    /** 状态标签色（Element UI type） */
    @TableField(exist = false)
    private String statusLevel;

    /** 步骤展示字段补齐：状态码 → 中文/标签色 */
    public static WorklistStepRecord decorate(WorklistStepRecord step) {
        if (step == null) {
            return null;
        }
        String status = step.getStatus();
        switch (status == null ? "" : status) {
            case "success", "ok" -> {
                step.setStatusText("成功");
                step.setStatusLevel("success");
            }
            case "fail" -> {
                step.setStatusText("失败");
                step.setStatusLevel("danger");
            }
            case "skip" -> {
                step.setStatusText("跳过");
                step.setStatusLevel("info");
            }
            case "init" -> {
                step.setStatusText("执行中");
                step.setStatusLevel("warning");
            }
            default -> {
                step.setStatusText(status == null || status.isBlank() ? "未知" : status);
                step.setStatusLevel("info");
            }
        }
        return step;
    }
}
