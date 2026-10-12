package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单实例。
 *
 * <p>对应 Python 版 WORKLIST + WORKLIST_EX 的合并。一张工单实例就是
 * "给某台设备下发某个业务"的一次完整记录，包含参数、结果与各阶段时间戳。</p>
 *
 * <p>状态流转：build -> bind -> standby -> running -> success / fail / abnormal -> expire</p>
 */
@Data
@TableName("worklist")
public class Worklist {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 工单唯一描述，建议由"调用方流水号"构成，便于幂等
     */
    private String worklistDesc;

    private String worklistName;

    /**
     * physic / logic
     */
    private String worklistType;

    private String sn;

    private String isp;

    private String ispVersion;

    /**
     * 设备类型，如 ADSL_4+1
     */
    private String domain;

    /**
     * build/bind/reserve/standby/running/success/fail/abnormal/expire
     */
    private String status;

    /**
     * 失败是否回退
     */
    private Integer rollbackFlag;

    /**
     * 下发参数，JSON
     */
    private String parameters;

    /**
     * 执行结果摘要，JSON
     */
    private String result;

    private String username;

    private LocalDateTime timeInit;

    private LocalDateTime timeBind;

    private LocalDateTime timeReserve;

    private LocalDateTime timeExecStart;

    private LocalDateTime timeExecFinish;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
