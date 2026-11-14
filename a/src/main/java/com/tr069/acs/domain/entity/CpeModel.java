package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 光猫型号档案 */
@Data
@TableName("cpe_model")
public class CpeModel {

    /** 待适配：还没有做语义映射，设备上线后不下发业务 */
    public static final String STATUS_PENDING = "PENDING";

    /** 已适配：映射齐备，同型号新设备自动套用 */
    public static final String STATUS_READY = "READY";

    /** 停用：暂停自动下发 */
    public static final String STATUS_DISABLED = "DISABLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 运营商：CT / CU / CMCC / standard */
    private String isp;

    /** 型号标识，建议「型号_固件」 */
    private String modelKey;

    private String manufacturer;

    /** 型号，对应 DeviceInfo.ProductClass / ModelName */
    private String productClass;

    /** 固件版本 */
    private String softwareVersion;

    private String hardwareVersion;

    /** 该型号能力树文件相对路径，如 models/ct/hs8145v5.xml */
    private String configPath;

    /** 该型号适配状态文件相对路径，如 CM/standard/p22g-ciac_v1.0.00.488.yml */
    private String adaptPath;

    /** {@link #STATUS_PENDING} / {@link #STATUS_READY} / {@link #STATUS_DISABLED} */
    private String status;

    /** 做映射时参考的样机序列号 */
    private String sampleSn;

    /** 该型号适用的根数据模型节点：{@code Device.}（TR-181）或 {@code InternetGatewayDevice.}（TR-098） */
    private String rootNode;

    /** 适配申报的网口数量：无/1/2/3/4-8，由机型适配测试通过后写入 */
    private String capLanPorts;

    /** 适配申报的网口速率：无/自动/100M/1000M/2500M */
    private String capLanSpeed;

    /** 适配申报的 SIP 数量：无/1/2/3/4-8 */
    private String capSipCount;

    /** 适配申报的 WIFI 规格：无/2.4G/2.4G+5.8G */
    private String capWifiSpec;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
