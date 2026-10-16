package com.tr069.acs.worklist.tpl.entity;

import lombok.Data;

/**
 * SIP 语音配置。一行 = 一个 Line 实例（P22g-CIac 是 1-4）。
 */
@Data
public class Sip {

    private Long id;
    private Long deviceId;
    private Integer instanceNo;

    /** ADD = 该实例需先 AddObject 创建；SET = 实例已存在，直接设值 */
    private String action;

    /** VERIFIED 已验证 / UNSUPPORTED 该型号不支持 / UNVERIFIED 未验证 */
    private String verified;

    private String pathEnable;
    private String valEnable;

    /** AuthUserName，SIP 注册用户名 */
    private String pathUser;
    private String valUser;

    /** AuthPassword */
    private String pathPwd;
    private String valPwd;

    /** URI（如 88880001@ims.domain） */
    private String pathUri;
    private String valUri;

    /** 注册服务器，型号不同可能挂在不同路径 */
    private String pathProxy;
    private String valProxy;

    private String pathPort;
    private String valPort;
}
