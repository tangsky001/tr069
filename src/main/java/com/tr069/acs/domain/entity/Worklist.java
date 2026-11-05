package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 工单实例 */
@Data
@TableName("worklist")
public class Worklist {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单唯一描述，建议由"调用方流水号"构成，便于幂等 */
    private String worklistDesc;

    private String worklistName;

    /** physic / logic */
    private String worklistType;

    private String sn;

    private String isp;

    private String ispVersion;

    /** 设备类型，如 ADSL_4+1 */
    private String domain;

    /** 工单状态全集。页面筛选下拉与状态流转都取它，避免两边各写一份 */
    public static final List<String> STATUSES = List.of(
            "build", "bind", "reserve", "standby", "running",
            "success", "fail", "abnormal", "expire");

    /** build/bind/reserve/standby/running/success/fail/abnormal/expire */
    private String status;

    /** 失败是否回退 */
    private Integer rollbackFlag;

    /** 下发参数，JSON */
    private String parameters;

    /** 执行结果摘要，JSON */
    private String result;

    private String username;

    private LocalDateTime timeInit;

    private LocalDateTime timeBind;

    private LocalDateTime timeReserve;

    private LocalDateTime timeExecStart;

    private LocalDateTime timeExecFinish;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 状态中文：构建中 / 待执行 / 执行中 / 成功 / 失败 / 异常 / 已过期 */
    @TableField(exist = false)
    private String statusText;

    /** 状态标签色（Element UI type）：成功绿、失败红、执行中橙、其余灰 */
    @TableField(exist = false)
    private String statusLevel;

    /** 创建时间文本：yyyy-MM-dd HH:mm:ss，没建单是「-」 */
    @TableField(exist = false)
    private String timeInitText;

    /** 完成时间文本，没完成是「-」 */
    @TableField(exist = false)
    private String timeExecFinishText;

    /** 是否已到终态（成功 / 失败 / 异常 / 过期） */
    @TableField(exist = false)
    private Boolean finished;

    /** 状态码与中文的唯一对照表：工单列表、详情、轮询提示都从这里取 */
    private static String statusTextOf(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case "build" -> "构建中";
            case "bind" -> "绑定中";
            case "reserve" -> "预约中";
            case "standby" -> "待执行";
            case "running" -> "执行中";
            case "success" -> "成功";
            case "fail" -> "失败";
            case "abnormal" -> "异常";
            case "expire" -> "已过期";
            default -> status;
        };
    }

    private static String statusLevelOf(String status) {
        if (status == null) {
            return "info";
        }
        return switch (status) {
            case "success" -> "success";
            case "fail", "abnormal" -> "danger";
            case "running" -> "warning";
            default -> "info";
        };
    }

    /** 时间文本：页面原来只做「T 换空格」，现在连空值占位一起在这边定 */
    private static String timeTextOf(LocalDateTime time) {
        return time == null ? "-" : time.format(TIME_FORMAT);
    }

    private static final java.time.format.DateTimeFormatter TIME_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 返回给页面前补齐展示字段 */
    public static Worklist decorate(Worklist record) {
        if (record == null) {
            return null;
        }
        record.setStatusText(statusTextOf(record.getStatus()));
        record.setStatusLevel(statusLevelOf(record.getStatus()));
        record.setTimeInitText(timeTextOf(record.getTimeInit()));
        record.setTimeExecFinishText(timeTextOf(record.getTimeExecFinish()));
        // 终态集合与 STATUSES 一致：除建造/绑定/预约/待执行/执行中外都算跑完
        String status = record.getStatus();
        record.setFinished(status != null
                && !List.of("build", "bind", "reserve", "standby", "running").contains(status));
        return record;
    }
}
