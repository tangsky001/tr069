package com.tr069.acs.olt.model;

import lombok.Data;

/** 一条 TR069 服务器模板 */
@Data
public class OltTr069Profile {

    private Integer profileId;

    private String name;

    /** ACS 地址 */
    private String url;

    /** ACS 登录账号（密码是密文，不展示也不采集） */
    private String user;

    /** 已绑定这个模板的 ONU 数（鸿升光回显的 BindTimes，别的厂商不给就是 null） */
    private Integer bindTimes;
}
