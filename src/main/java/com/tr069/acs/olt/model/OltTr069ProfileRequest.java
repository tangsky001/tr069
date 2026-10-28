package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 页面「增加」弹窗提交的 TR-069 模板参数。
 *
 * <p>字段范围照设备命令帮助：profile-name 1-32、url 1-64、用户名/密码 0-24、
 * port 1-65535、inform-interval 1-86400。</p>
 */
@Data
public class OltTr069ProfileRequest {

    /** 模板 ID，1-32 */
    private Integer profileId;

    /** 模板名 */
    private String profileName;

    /** ACS 服务器 URL，如 http://192.168.1.10:9090 */
    private String url;

    /** ACS 服务器用户名 */
    private String acsUsername;

    /** ACS 服务器密码 */
    private String acsPassword;

    /** 定期通知：true 使能（下发 inform-interval），false 去使能 */
    private Boolean informEnable;

    /** 定期通知间隔（秒），1-86400 */
    private Integer informInterval;

    /** 连接请求用户名 */
    private String username;

    /** 连接请求密码 */
    private String password;

    /** 连接请求端口，1-65535 */
    private Integer port;
}
