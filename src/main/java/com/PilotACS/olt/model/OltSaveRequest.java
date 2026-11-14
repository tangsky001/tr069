/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.320
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.List;

@Data
public class OltSaveRequest {

    @Schema(description = "编辑时必填，新增为空")
    private Long id;

    @Schema(description = "OLT 名称，留空时按地址生成")
    private String name;

    @Schema(description = "OLT 地址：IP 或域名")
    private String host;

    @Schema(description = "登录类型：TELNET / SSH")
    private String protocol;

    @Schema(description = "登录账号，留空按 root 处理")
    private String username;

    @Schema(description = "登录密码，编辑时留空表示不修改")
    private String password;

    @Schema(description = "登录端口，留空按协议默认值（Telnet 23 / SSH 22）")
    private Integer port;

    @Schema(description = "字符集编码：AUTO / UTF-8 / GBK / GB18030")
    private String charset;

    @Schema(description = "绑定本地访问出口 IP")
    private String bindLocalIp;

    @Schema(description = "允许访问的 Web 登录账号；空列表表示不限制")
    private List<String> webAccounts;

    @Schema(description = "厂商：HUAWEI / ZTE / FIBERHOME / HSGQ / GENERIC")
    private String vendor;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "新增时的归属分组节点引用：ROOT 或 G:id，空 = 根节点；编辑时忽略")
    private String groupRef;
}
