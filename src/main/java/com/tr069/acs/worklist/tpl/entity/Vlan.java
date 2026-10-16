package com.tr069.acs.worklist.tpl.entity;

import lombok.Data;

/**
 * VLAN 配置。真实存储位置是 WAN 连接下的 X_CMCC_VLAN* 私有参数（部分型号是标准
 * IEEE 802.1Q），但为管理便利独立成表，{@code wanInstance} 关联
 * {@link DeviceWan#getInstanceNo()}。
 */
@Data
public class Vlan {

    private Long id;
    private Long deviceId;

    /** 关联 device_wan.instance_no */
    private Integer wanInstance;

    /** ADD = 该实例需先 AddObject 创建；SET = 实例已存在，直接设值 */
    private String action;

    /** VERIFIED 已验证 / UNSUPPORTED 该型号不支持 / UNVERIFIED 未验证 */
    private String verified;

    private String pathVlanId;
    private String valVlanId;

    private String pathVlanMode;
    private String valVlanMode;

    private String pathMulticast;
    private String valMulticast;

    private String path8021p;
    private String val8021p;
}
