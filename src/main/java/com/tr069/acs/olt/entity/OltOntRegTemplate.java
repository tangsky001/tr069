package com.tr069.acs.olt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ONT 注册模板：光猫上线时按优先级匹配，命中哪条就按哪条注册。
 *
 * <p>一条模板就是「匹配条件 + 套哪套线路/业务模板」：条件有 PON 口、ONT 型号、
 * PON 类型；注册动作就是拿着设备自动发现到的那条记录去 {@code ont confirm}。</p>
 *
 * <p>{@link #oltId} 为空表示不限设备（公共模板）；同一个 OLT 上多条模板按
 * {@link #priority} 从小到大匹配，第一条命中的被采用。</p>
 */
@Data
@TableName("olt_ont_reg_template")
public class OltOntRegTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** olt_device.id；NULL 表示不限设备 */
    private Long oltId;

    /** 绑定的 OLT 地址，页面展示用 */
    private String oltIp;

    private String name;

    /** 优先级，小的先匹配 */
    private Integer priority;

    /** 是否启用：1 启用 / 0 停用 */
    private Integer enabled;

    /** 注册方式：SN / MAC / LOID / PASSWORD */
    private String authMode;

    /** PON 类型：GPON / EPON */
    private String ponType;

    /** 线路模板 ID（ont-lineprofile-id） */
    private Integer lineProfileId;

    /** 业务模板 ID（ont-srvprofile-id） */
    private Integer srvProfileId;

    /** 限定的 PON 口，逗号分隔，如 0/1/0,0/1/1；空表示不限 */
    private String bindPorts;

    /** 限定的 ONT 型号，逗号分隔；空表示不限 */
    private String ontModels;

    /** 光猫工作模式：HGU / SFU / MIX / MDU */
    private String workMode;

    /** 业务 VLAN 映射；空表示沿用线路模板 */
    private String serviceVlanMap;

    /** TR069 VLAN 及 ACS 模板，如 46 10 */
    private String tr069Vlan;

    private String iptvVlan;

    private String multicastVlan;

    private String remark;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
