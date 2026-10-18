package com.tr069.acs.worklist.tpl.entity;

import lombok.Data;

/**
 * 5.8G WiFi 配置。结构同 {@link WlanA}，实例 5-8。
 */
@Data
public class WlanB {

    private Long id;
    private Long deviceId;
    private Integer instanceNo;

    /** ADD = 该实例需先 AddObject 创建；SET = 实例已存在，直接设值 */
    private String action;

    /** VERIFIED 已验证 / UNSUPPORTED 该型号不支持 / UNVERIFIED 未验证 */
    private String verified;

    private String pathEnable;
    private String valEnable;

    private String pathSsid;
    private String valSsid;

    private String pathChannel;
    private String valChannel;

    private String pathBeacon;
    private String valBeacon;

    private String pathWpaMode;
    private String valWpaMode;

    private String pathKey;
    private String valKey;

    private String pathPsk;
    private String valPsk;
}
