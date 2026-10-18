package com.tr069.acs.worklist.tpl.entity;

import lombok.Data;

/**
 * 型号主表。一行 = 一个 (isp, model_key, software_version) 组合。
 *
 * <p>字段承接原 MySQL {@code cpe_model}：既存从设备读到的固有属性（厂商、大类、
 * 软硬件版本），也存人工适配申报的能力（{@code capLanPorts} / {@code capSipCount} /
 * {@code capWifiSpec}）——后者决定各业务表该有几行。</p>
 *
 * <p>注意：这里<b>不存</b>设备的实时状态、台账信息、参数实时值，那些属于 MySQL。</p>
 */
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
