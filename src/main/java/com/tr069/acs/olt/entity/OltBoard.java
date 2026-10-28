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

/**
 * OLT 板卡信息，一次同步按槽位覆盖写。
 */
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

    /** 设备返回的原始状态：Normal / Active_normal / inservice ... */
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

    /**
     * 面板样式（pon / ge / xge / generic），按型号档案算出来给页面画 SVG 用。
     *
     * <p>型号档案是设备知识，以前整张表在页面里，页面自己匹配型号；现在由服务端
     * 匹配好随板卡返回，页面拿到就能画。</p>
     */
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

    /**
     * 整机前面板：一体化 OLT（鸿升光）没有板卡，整台设备就是一条记录，
     * 面板上有几口 PON、几个上联 GE / XGE、有没有 NMS / CONSOLE 由型号决定。
     *
     * <p>有值时页面画「整机前面板」，不再画竖插的板卡；华为那种插框设备为 null。</p>
     */
    @TableField(exist = false)
    private OltFrontPanel frontPanel;

    private LocalDateTime syncedAt;
}
