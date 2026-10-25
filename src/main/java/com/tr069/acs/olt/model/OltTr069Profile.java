package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 一条 TR069 服务器模板（{@code display ont tr069-server-profile all}）。
 *
 * <p>它在 OLT 上是独立于能力模板的一类模板，配置回显长这样：</p>
 * <pre>{@code
 * ont tr069-server-profile add profile-id 10 profile-name "TR069_ACS"
 *     url "http://10.172.10.1:9090/" user "acs123" "%#%#..."
 * }</pre>
 *
 * <p>页面「TR069 管理模板」那一栏看的就是它：ACS 地址与登录账号决定 ONT 上哪台 ACS 注册。</p>
 */
@Data
public class OltTr069Profile {

    private Integer profileId;

    private String name;

    /** ACS 地址 */
    private String url;

    /** ACS 登录账号（密码是密文，不展示也不采集） */
    private String user;
}
