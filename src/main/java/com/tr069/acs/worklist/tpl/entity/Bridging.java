package com.tr069.acs.worklist.tpl.entity;

import lombok.Data;

/** 桥接配置。一行 = 一个 (bridge, port) 组合 */
@Data
public class Bridging {

    private Long id;
    private Long deviceId;

    private Integer bridgeInstance;
    private Integer portInstance;

    /** ADD = 该实例需先 AddObject 创建；SET = 实例已存在，直接设值 */
    private String action;

    /** VERIFIED 已验证 / UNSUPPORTED 该型号不支持 / UNVERIFIED 未验证 */
    private String verified;

    private String pathBridgeEnable;
    private String valBridgeEnable;

    private String pathPortEnable;
    private String valPortEnable;

    private String pathBindIface;
    private String valBindIface;

    /** 桥接的关键：连接类型须为 Bridged、NAT 必须关掉，否则不是真正的桥接 */
    private String pathConnType;
    private String valConnType;

    private String pathNatEnabled;
    private String valNatEnabled;

    /** 该桥端口绑定的 LAN 口号（1~N），方便人工核对 */
    private Integer bindLanNo;
}
