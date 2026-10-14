package com.tr069.acs.exec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 下发任务：意图 → CWMP 参数 → 执行结果，全程留痕。
 *
 * <p>北向与管理端共用同一张表。任务一旦创建就不再修改业务入参，
 * 重查结果走 {@link #getTaskNo()} + {@link #getSource()} 两个字段定位。</p>
 */
@Data
@TableName("device_task")
public class DeviceTask {

    /** 已受理，等待执行。 */
    public static final String STATUS_PENDING = "PENDING";

    /** 正在下发，指令已排进设备队列。 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 设备已确认全部参数写入成功。 */
    public static final String STATUS_SUCCESS = "SUCCESS";

    /** 下发失败：设备故障、不到参数、或超时。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 未下发：型号未适配、设备离线、意图缺必填字段等，属于调用方可以修正的问题。 */
    public static final String STATUS_REJECTED = "REJECTED";

    /** 北向接口来源。 */
    public static final String SOURCE_OPENAPI = "openapi";

    /** 管理端来源。 */
    public static final String SOURCE_ADMIN = "admin";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 外部业务单号，与 source 组成唯一键，重复提交据此幂等。 */
    private String taskNo;

    private String sn;

    private String category;

    /** 意图模板 id，为空表示本次直接给了参数清单。 */
    private Long templateId;

    private String source;

    private String createdBy;

    /** 意图字段快照 JSON。 */
    private String intentFields;

    /** 渲染出的下发计划 JSON。 */
    private String plan;

    /** {@link #STATUS_PENDING} / {@link #STATUS_RUNNING} / {@link #STATUS_SUCCESS}
     * / {@link #STATUS_FAILED} / {@link #STATUS_REJECTED} */
    private String status;

    private String errorMsg;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime finishedAt;
}
