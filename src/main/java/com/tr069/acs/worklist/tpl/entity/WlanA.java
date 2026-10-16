package com.tr069.acs.worklist.tpl.entity;

import lombok.Data;

/**
 * 2.4G WiFi 配置。一行 = 一个实例（P22g-CIac 是 1-4）。
 * 字段命名约定：path_xx / val_xx 成对出现，val 里只放变量（%room% / %wifi_pwd% / 1 / true 等），
 * 绝不放真实口令。
 */
@Data
public class WlanA {

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

    /** PreSharedKey.1.KeyPassphrase——部分型号下发 WiFi 密码要写两个参数 */
    private String pathKey;
    private String valKey;

    /** PreSharedKey.1.PreSharedKey——同上 */
    private String pathPsk;
    private String valPsk;
}
