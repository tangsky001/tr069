package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 一台 ONT 的注册结果。
 *
 * <p>成功失败都返回：页面按它把每台光猫的结果摊开，失败的那台把设备回的原因带上——
 * 「没匹配到模板」和「设备拒绝了命令」是两回事，页面上必须看得出差别。</p>
 */
@Data
public class OltOntRegisterResult {

    /** 框/槽/端口 */
    private String fsp;

    /** ONT SN / MAC / LOID */
    private String sn;

    private String model;

    /** 命中的模板名；没匹配到模板时是 null */
    private String templateName;

    private String authMode;

    private boolean success;

    /** 失败原因或成功说明 */
    private String message;

    /** 实际下发（或预览）的命令 */
    private String command;
}
