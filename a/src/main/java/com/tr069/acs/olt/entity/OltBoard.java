package com.tr069.acs.olt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tr069.acs.olt.model.OltFrontPanel;
import com.tr069.acs.olt.model.OltPort;
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

    /** 板卡类型，如 H901GPHF / GTGO */
    private String boardType;

    /** 设备返回的原始状态：Normal / Active_normal / inservice */
    private String status;

    /** 板卡类别：SERVICE / CONTROL / UPLINK / POWER / INTERFACE / UNKNOWN */
    private String boardKind;

    /** 板卡描述，如 8口GPON（业务板） */
    private String description;

    /** 端口类型：GPON / EPON / PON，解析不出时为空 */
    private String portMode;

    /** 端口数量 */
    private Integer portCount;

    /** 温度(摄氏度) */
    private Integer temperature;

    private String cpuUsage;

    private String memUsage;

    private String remark;

    /** 端口光模块状态快照（JSON 数组），页面展开端口列表用；板卡端口数不多，不做单表 */
    private String portsJson;

    /** 反序列化后的端口列表，只在内存里用，不落库 */
    @TableField(exist = false)
    private List<OltPort> ports;

    /** 面板样式（pon / ge / xge / generic），按型号档案算出来给页面画 SVG 用 */
    @TableField(exist = false)
    private String panelKind;

    /** 是不是主控板（型号档案给出，页面据此决定要不要画管理口） */
    @TableField(exist = false)
    private Boolean controlBoard;

    /** 设备状态的中文说法（{@code Active_normal} → 激活），页面直接显示 */
    @TableField(exist = false)
    private String statusText;

    /** 状态等级：success / warning / danger / info，页面据此选图标与颜色 */
    @TableField(exist = false)
    private String statusLevel;

    /** 板卡状态灯三态：online / alarm / offline（由状态等级换算，页面直接用） */
    @TableField(exist = false)
    private String lampState;

    /** 板卡面板上的 ALM 灯：alarm（红）/ offline（灭） */
    @TableField(exist = false)
    private String alarmLamp;

    /** 板卡下所有端口的 ONT 合计文本：「23 ONT / 22 在线」，一个端口汇总都没有为空 */
    @TableField(exist = false)
    private String onuSummary;

    /** 板卡下的在线 ONT 合计，ACT 灯与「N ONT 在线」徽标用它 */
    @TableField(exist = false)
    private Integer onuOnline;

    /** 面板丝印副标题：「GPON 8口 主控」，页面按宽度自己截断显示 */
    @TableField(exist = false)
    private String subtitle;

    /** ACT 灯：板卡下有在线 ONT 就 online，否则 offline */
    @TableField(exist = false)
    private String actLamp;

    /** ENV 灯：温度到告警阈值 alarm，没采到温度或没超阈值都是 offline */
    @TableField(exist = false)
    private String envLamp;

    /** 整机前面板：一体化 OLT（鸿升光）没有板卡，整台设备就是一条记录 */
    @TableField(exist = false)
    private OltFrontPanel frontPanel;

    private LocalDateTime syncedAt;
}
