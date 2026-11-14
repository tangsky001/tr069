package com.tr069.acs.template.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 配置意图模板：只描述开通意图（IP 协议、是否带 VLAN、是否绑定端口等） */
@Data
@TableName("config_template")
public class ConfigTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模板名称 */
    private String name;

    /** 类型：WAN_DHCP / WAN_PPPOE / WAN_STATIC / WAN_BRIDGE / WIFI_24G / WIFI_58G / SIP / OTHER */
    private String category;

    /** 业务分组 */
    private String groupName;

    /** 是否启用 */
    private Boolean enabled;

    /** 备注 */
    private String remark;

    /** 意图字段 JSON，键与型号语义映射的 biz_field 对齐 */
    private String fields;

    /** 增量业务：下发时只新增这一条业务，不清理、不改写设备上已有的其它业务 */
    private Boolean incremental;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
