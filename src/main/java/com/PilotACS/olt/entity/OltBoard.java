/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.299
 *
 */

package com.PilotACS.olt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.PilotACS.olt.model.OltFrontPanel;
import com.PilotACS.olt.model.OltPort;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** OLT 板卡信息，一次同步按槽位覆盖写 */
@Data
@TableName("olt_board")
public class OltBoard {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** olt_device.id */
    private Long oltId;

    /** 槽位号 */
    private Integer slotNo;

    /** 板卡类型 */
    private String boardType;

    /** 原始状态 */
    private String status;

    /** 板卡类别 */
    private String boardKind;

    /** 板卡描述 */
    private String description;

    /** 端口类型 */
    private String portMode;

    /** 端口数量 */
    private Integer portCount;

    /** 温度(摄氏度) */
    private Integer temperature;

    private String cpuUsage;

    private String memUsage;

    private String remark;

    /** 端口光模块状态快照（JSON） */
    private String portsJson;

    /** 端口列表（不落库） */
    @TableField(exist = false)
    private List<OltPort> ports;

    /** 面板样式 */
    @TableField(exist = false)
    private String panelKind;

    /** 灯带丝印标题 */
    @TableField(exist = false)
    private String stripTitle;

    /** 是否主控板 */
    @TableField(exist = false)
    private Boolean controlBoard;

    /** 状态中文文案 */
    @TableField(exist = false)
    private String statusText;

    /** 状态等级 */
    @TableField(exist = false)
    private String statusLevel;

    /** 状态灯三态 */
    @TableField(exist = false)
    private String lampState;

    /** ALM 灯状态 */
    @TableField(exist = false)
    private String alarmLamp;

    /** 端口 ONT 合计文本 */
    @TableField(exist = false)
    private String onuSummary;

    /** 在线 ONT 合计 */
    @TableField(exist = false)
    private Integer onuOnline;

    /** 面板丝印副标题 */
    @TableField(exist = false)
    private String subtitle;

    /** ACT 灯状态 */
    @TableField(exist = false)
    private String actLamp;

    /** ENV 灯状态 */
    @TableField(exist = false)
    private String envLamp;

    /** 整机前面板 */
    @TableField(exist = false)
    private OltFrontPanel frontPanel;

    private LocalDateTime syncedAt;
}
