package com.tr069.acs.worklist.tpl.entity;

import lombok.Data;

/**
 * LAN 网口配置。一行 = 一个 LANEthernetInterfaceConfig 实例。
 */
@Data
public class DeviceLan {

    private Long id;
    private Long deviceId;
    private Integer instanceNo;

    /** ADD = 该实例需先 AddObject 创建；SET = 实例已存在，直接设值 */
    private String action;

    /** VERIFIED 已验证 / UNSUPPORTED 该型号不支持 / UNVERIFIED 未验证 */
    private String verified;

    private String pathEnable;
    private String valEnable;

    private String pathDuplex;
    private String valDuplex;

    private String pathSpeed;
    private String valSpeed;
}
