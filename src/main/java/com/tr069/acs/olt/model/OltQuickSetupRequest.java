package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.List;

/**
 * 一键开局的请求体：把开局要做的几件事一次性提交，后端按顺序编成命令下发。
 *
 * <p>三个步骤各自独立，哪个字段是空就跳过哪一步：</p>
 * <ol>
 *   <li>{@link #ports} 非空 → 改端口 VLAN 模式（默认 trunk）；</li>
 *   <li>{@link #createVlanIds} 非空 → 创建业务 VLAN，勾了 {@link #addCreatedToPorts}
 *       再把上面那些端口当 tagged 成员加进去；</li>
 *   <li>{@link #tr069} 非空 → 建一个 TR-069（ACS）模板。</li>
 * </ol>
 */
@Data
public class OltQuickSetupRequest {

    /** 要改模式的端口，空就是不改端口 */
    private List<PortRef> ports;

    /** 端口 VLAN 模式：access / hybrid / trunk */
    private String mode;

    /**
     * trunk、hybrid 放通的 VLAN 列表，写法 {@code 100-200,300}；
     * 空表示只改模式，不动已经在放通的 VLAN。
     */
    private String vlanList;

    /** 要创建的 VLAN ID，写法 {@code 100,200} 或 {@code 100-105}；空就是不建 */
    private String createVlanIds;

    /** 新建的 VLAN 要不要把 {@link #ports} 加成 tagged 成员 */
    private Boolean addCreatedToPorts;

    /** TR-069 模板参数，null 就是不建 */
    private OltTr069ProfileRequest tr069;

    /**
     * 页面勾选的一个端口：{@code {"type":"epon","no":1}}。
     *
     * <p>用 type + no 而不是 {@code epon1} 这种字符串，是因为这里既要拼
     * {@code interface epon 1} 又要拼 {@code port epon 1 tagged}，分开存不用再拆一遍。</p>
     */
    @Data
    public static class PortRef {

        /** 端口类型：epon / gpon / ge / xge */
        private String type;

        /** 端口号，从 1 开始 */
        private Integer no;
    }
}
