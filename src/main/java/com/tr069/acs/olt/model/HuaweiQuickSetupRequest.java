package com.tr069.acs.olt.model;

import lombok.Data;

/** 华为 MA56XXT 一键开局的参数 */
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

    /** DBA 模板带宽（Mbps）：只决定限速值和模板名字（{@code 1000M}） */
    private Integer dbaBandwidthMbps;

    /** DBA 模板的 profile-id，设备认 10~512，没填用后端默认值 */
    private Integer dbaProfileId;

    /** ONT 线路模板的 profile-id，设备认 10~512，没填用后端默认值 */
    private Integer lineProfileId;

    /** ONT 业务模板的 profile-id，设备认 10~512，没填用后端默认值 */
    private Integer srvProfileId;

    /** TR-069 模板的 profile-id，设备只认 1~32，没填用后端默认值 */
    private Integer tr069ProfileId;

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
