package com.tr069.acs.worklist.tpl.entity;

import lombok.Data;

/**
 * WAN 连接配置。一行 = 一个 WANIPConnection 实例（实际实例号由设备采集决定，
 * P22g-CIac 是 9-12，别的型号可能 1-4、可能完全不同的数字）。
 * <p>{@code connType} 标记该连接是 IPoE / PPPoE / Bridge，下发时按此选择模板。</p>
 */
@Data
public class DeviceWan {

    private Long id;
    private Long deviceId;
    private Integer instanceNo;

    /** 业务分类：IPoE / PPPoE / Bridge，下发入口据此选模板 */
    private String connType;

    /** ADD = 该实例需先 AddObject 创建；SET = 实例已存在，直接设值 */
    private String action;

    /** VERIFIED 已验证 / UNSUPPORTED 该型号不支持 / UNVERIFIED 未验证 */
    private String verified;

    /**
     * action=ADD 时 AddObject 的父路径，末尾不带实例号（实例号由设备分配，
     * 事先不知道），形如 {@code ...WANConnectionDevice.2.WANIPConnection.}。
     * action=SET 时为空。
     */
    private String objectPath;

    private String pathEnable;
    private String valEnable;

    private String pathConnType;
    private String valConnType;

    /** IPAddressingType（DHCP / Static / PPPOE） */
    private String pathAddrType;
    private String valAddrType;

    /** NATEnabled */
    private String pathNat;
    private String valNat;

    /** X_CMCC_BindLANPort，空串表示不修改 */
    private String pathBindPort;
    private String valBindPort;

    private String pathUsername;
    private String valUsername;

    private String pathPassword;
    private String valPassword;

    private String pathIp;
    private String valIp;

    private String pathMask;
    private String valMask;

    private String pathGw;
    private String valGw;

    private String pathDns;
    private String valDns;

    /**
     * MaxMTUSize。值留空表示不修改该参数（各家现网 MTU 取值不同，不替现场做决定）。
     */
    private String pathMtu;
    private String valMtu;

    /**
     * IP 协议版本，设备上多叫 {@code X_CMCC_IPMode}（int：1=IPv4 / 2=IPv6 / 3=IPv4+IPv6）。
     * 值留空表示不修改——老型号没有这个节点时路径也留空。
     */
    private String pathIpMode;
    private String valIpMode;
}
