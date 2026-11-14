package com.tr069.acs.worklist.tpl.entity;

import lombok.Data;

/** 型号主表。一行 = 一个 (isp, model_key, software_version) 组合 */
@Data
public class Device {

    private Long id;

    /** 运营商 CT/CU/CMCC/standard */
    private String isp;

    /** 型号标识，建议 型号_固件，如 HS8145V5_V5R19 */
    private String modelKey;

    /** 厂商，来自 DeviceInfo.Manufacturer */
    private String manufacturer;

    /** 型号，来自 DeviceInfo.ProductClass / ModelName */
    private String productClass;

    /** 固件版本，来自 DeviceInfo.SoftwareVersion */
    private String softwareVersion;

    private String hardwareVersion;

    /** PENDING 待适配 / READY 已映射 / DISABLED 停用 */
    private String status;

    /** 做适配时参考的样机序列号 */
    private String sampleSn;

    /** 根数据模型节点：Device.(TR-181) / InternetGatewayDevice.(TR-098) */
    private String rootNode;

    /** 适配申报网口数量：无/1/2/3/4-8 */
    private String capLanPorts;

    /** 适配申报网口速率：无/自动/100M/1000M/2500M */
    private String capLanSpeed;

    /** 适配申报 SIP 数量：无/1/2/3/4-8 */
    private String capSipCount;

    /** 适配申报 WiFi 规格：无/2.4G/2.4G+5.8G——决定要不要建 wlan_b 表记录 */
    private String capWifiSpec;

    private String remark;

    private String createdAt;

    private String updatedAt;
}
