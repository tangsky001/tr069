package com.tr069.acs.olt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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

    private LocalDateTime syncedAt;
}
