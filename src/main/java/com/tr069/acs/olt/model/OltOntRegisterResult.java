package com.tr069.acs.olt.model;

import lombok.Data;

/** 一台 ONT 的注册结果 */
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
