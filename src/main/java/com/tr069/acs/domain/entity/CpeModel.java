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

    /** {@link #STATUS_PENDING} / {@link #STATUS_READY} / {@link #STATUS_DISABLED} */
    private String status;

    /** 做映射时参考的样机序列号 */
    private String sampleSn;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
