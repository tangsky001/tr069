package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.List;

/**
 * 新增 / 编辑 OLT 的入参，字段与页面「添加 OLT」表单一一对应。
 */
@Data
public class OltSaveRequest {

    /** 编辑时必填，新增为空 */
    private Long id;

    /** OLT 名称，留空时按地址生成 */
    private String name;

    /** OLT 地址：IP 或域名 */
    private String host;

    /** 登录类型：TELNET / SSH */
    private String protocol;

    /** 登录账号，留空按 root 处理 */
    private String username;

    /** 登录密码，编辑时留空表示不修改 */
    private String password;

    /** 登录端口，留空按协议默认值（Telnet 23 / SSH 22） */
    private Integer port;

    /** 字符集编码：AUTO / UTF-8 / GBK / GB18030 */
    private String charset;

    /** 绑定本地访问出口 IP */
    private String bindLocalIp;

    /** 允许访问的 Web 登录账号；空列表表示不限制 */
    private List<String> webAccounts;

    /** 备注 */
    private String remark;
}
