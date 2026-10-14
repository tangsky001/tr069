package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 光猫型号档案。
 *
 * <p>同型号 + 同固件的设备共用一套语义映射，人工只需要为这个型号适配一次。
 * 设备上线时按「运营商 + 型号 + 固件」定位到本记录：命中且已完成映射即可自动下发，
 * 未适配则登记为待适配，等人工在映射页面补齐。</p>
 */
@Data
@TableName("cpe_model")
public class CpeModel {

    /** 待适配：还没有做语义映射，设备上线后不下发业务。 */
    public static final String STATUS_PENDING = "PENDING";

    /** 已适配：映射齐备，同型号新设备自动套用。 */
    public static final String STATUS_READY = "READY";

    /** 停用：暂停自动下发。 */
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

    /**
     * 该型号能力树文件相对路径，如 models/ct/hs8145v5.xml。
     *
     * <p>相对 {@code acs.worklist.root-path}，由 {@code ModelTreeStore} 在采集成功后导出并回填。
     * 下发前先按型号查到这里的路径，再读该文件取真实节点路径与格式。</p>
     */
    private String configPath;

    /**
     * 该型号适配状态文件相对路径，如 CM/standard/p22g-ciac_v1.0.00.488.yml。
     *
     * <p>{@link #configPath} 里的 XML 只是型号能力树（支持哪些节点），它存在不代表适配过；
     * 是否已适配看这份 YML 里的 {@code adapted}——只有能力测试通过、真正入库时才写入 true。
     * 同型号设备上线时读这份 YML 判定「已适配 / 待适配」。</p>
     */
    private String adaptPath;

    /** {@link #STATUS_PENDING} / {@link #STATUS_READY} / {@link #STATUS_DISABLED} */
    private String status;

    /** 做映射时参考的样机序列号 */
    private String sampleSn;

    /**
     * 该型号适用的根数据模型节点：{@code Device.}（TR-181）或 {@code InternetGatewayDevice.}（TR-098）。
     *
     * <p>同一型号（产品型号 + 固件）在出货中可能同时有 TR-098 与 TR-181 两种固件，
     * 两者 CWMP 路径完全不同，归档的能力树不能混用。这里记录样机上报时认定的根节点，
     * 归档与下发前用它与设备自身的 {@code cpe_device.root_node} 对账，不一致时拒绝套用。</p>
     */
    private String rootNode;

    /** 适配申报的网口数量：无/1/2/3/4-8，由机型适配测试通过后写入。 */
    private String capLanPorts;

    /** 适配申报的网口速率：无/自动/100M/1000M/2500M。 */
    private String capLanSpeed;

    /** 适配申报的 SIP 数量：无/1/2/3/4-8。 */
    private String capSipCount;

    /** 适配申报的 WIFI 规格：无/2.4G/2.4G+5.8G。 */
    private String capWifiSpec;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
