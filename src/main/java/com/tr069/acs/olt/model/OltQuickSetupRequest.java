package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.List;

/** 一键开局的请求体：把开局要做的几件事一次性提交，后端按顺序编成命令下发 */
@Data
public class OltQuickSetupRequest {

    /** 页面上「统一端口 VLAN 模式」这一步勾没勾 */
    private Boolean portModeEnabled = Boolean.TRUE;

    /** 要改模式的端口，空就是不改端口 */
    private List<PortRef> ports;

    /** 端口 VLAN 模式：access / hybrid / trunk */
    private String mode;

    /** trunk、hybrid 放通的 VLAN 列表，写法 {@code 100-200,300} */
    private String vlanList;

    /** 要创建的 VLAN ID，写法 {@code 100,200} 或 {@code 100-105}；空就是不建 */
    private String createVlanIds;

    /** 新建的 VLAN 要不要把 {@link #ports} 加成 tagged 成员 */
    private Boolean addCreatedToPorts;

    /** TR-069 模板参数，null 就是不建 */
    private OltTr069ProfileRequest tr069;

    /** 华为开局参数；不为 null 时整态都按华为那套模板流程走 */
    private HuaweiQuickSetupRequest huawei;

    /** 页面勾选的一个端口：{@code {"type":"epon","no":1}} */
    @Data
    public static class PortRef {

        /** 端口类型：epon / gpon / ge / xge */
        private String type;

        /** 端口号，从 1 开始 */
        private Integer no;
    }
}
