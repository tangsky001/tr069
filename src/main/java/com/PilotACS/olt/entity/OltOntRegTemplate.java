/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.973
 *
 */

package com.PilotACS.olt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** ONT 注册模板。 */
@Data
@TableName("olt_ont_reg_template")
public class OltOntRegTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设备 id，空=不限 */
    private Long oltId;

    /** 绑定的 OLT 地址 */
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

    /** 限定的 PON 口 */
    private String bindPorts;

    /** 限定的 ONT 型号 */
    private String ontModels;

    /** 光猫工作模式：HGU / SFU / MIX / MDU */
    private String workMode;

    /** 业务 VLAN 映射，空表示不映射 */
    private String serviceVlanMap;

    /** TR069 VLAN 及 ACS 地址 */
    private String tr069Vlan;

    private String iptvVlan;

    private String multicastVlan;

    private String remark;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
