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

/** ONT 注册模板：光猫上线时按优先级匹配，命中哪条就按哪条注册 */
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

    /**
     * OLT 侧业务 VLAN 映射；空表示不映射业务（不下发 native-vlan 和 service-port）。
     * 一行一组，可多组：1 个数=透传（service-port vlan V user-vlan V）；
     * 2 个数=光猫侧 OLT 侧（如「104 46」「4011 46」「4015 46」，每组一条 service-port
     * translate，首组额外下发 eth1-4 native-vlan 光猫侧 VLAN）。
     */
    private String serviceVlanMap;

    /**
     * 下发 TR069 VLAN 及 ACS 地址，如 46 10（TR069 管理 VLAN 46 + OLT 上 tr069-server profile-id 10）。
     * 非空时注册成功后下发 ont tr069-server-config &lt;port&gt; &lt;ontId&gt; profile-id 10，光猫才能拿到 ACS 地址；
     * 空 → 不下发，光猫不会来 Inform
     */
    private String tr069Vlan;

    private String iptvVlan;

    private String multicastVlan;

    private String remark;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
