package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 华为 MA56XXT 一键开局的参数。
 *
 * <p>华为开局不是「改端口 VLAN 模式」那套，而是先备齐模板再注册 ONT，
 * 所以字段跟 {@link OltQuickSetupRequest} 里给鸿升光用的那几个对不上，单独一个模型。</p>
 *
 * <p><b>模板的 ID 和名字不用单独填，全部由 {@link #dbaBandwidthMbps} 推出来：</b>
 * 带宽 1000 就得到 DBA 模板 {@code 1000 / 1000M}、线路与业务模板
 * {@code 1000 / FTTH-GPON-1000}、TR-069 模板 {@code 1000 / TR069_ACS}。
 * 四套模板共用一个 ID，现场对着设备看配置时一眼能对上是哪一套。</p>
 *
 * <p><b>模板之间有依赖，创建顺序是固定的：</b>DBA 模板 → ONT 线路模板（引用 DBA，
 * 内含 T-CONT / GEM / GEM 映射）→ ONT 业务模板 → TR-069 管理模板。
 * 反过来先建线路模板会因为找不到 DBA 被设备拒绝，见
 * {@code HuaweiQuickSetupPlanner}。</p>
 */
@Data
public class HuaweiQuickSetupRequest {

    /** 管理 VLAN */
    private Integer manageVlan;

    /** 管理 VLAN 的三层地址，掩码一起写（10.172.10.254/255.255.255.0） */
    private String manageVlanIp;

    /** 缺省路由的下一跳 */
    private String defaultRoute;

    /** ONT 回连 ACS 用的 VLAN，多数局点与管理 VLAN 相同 */
    private Integer tr069Vlan;

    /** 宽带等用户业务 VLAN，写法 {@code 41,45} 或 {@code 41-45} */
    private String serviceVlan;

    /** IPTV 组播 VLAN，不做组播就留空 */
    private Integer iptvMulticastVlan;

    /** 开局 PON 类型：GPON / EPON，决定 {@code ont-lineprofile gpon} 还是 {@code epon} */
    private String ponType;

    /** ONT 回连的 ACS 地址 */
    private String acsUrl;

    /** TR-069 管理模板里带的账号，默认 acs */
    private String acsUsername;

    /** TR-069 管理模板里带的密码，默认 acs */
    private String acsPassword;

    /**
     * DBA 模板带宽（Mbps），同时就是模板 ID：1000 → DBA 模板 1000 / 1000M。
     *
     * <p>既是带宽又是 ID，取值要在模板 ID 的范围内，超了设备不认。</p>
     */
    private Integer dbaBandwidthMbps;

    /** 上联端口，多个用逗号分隔，写法与设备一致（0/6、0/6/2、0/0/6/1） */
    private String uplinkPorts;

    /** 上联口的缺省 VLAN，留空表示不动设备现有配置 */
    private String uplinkDefaultVlan;

    /** 需要二层互通的 VLAN，留空表示不配置 */
    private String l2InteropVlan;

    /** 是否允许未知组播透传 */
    private Boolean allowUnknownMulticast;

    /** 是否同时下发组播（IGMP）配置 */
    private Boolean multicastService;
}
