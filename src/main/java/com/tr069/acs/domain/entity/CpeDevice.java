package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CPE 设备。
 *
 * <p>对应 Python 版 CPE 表。</p>
 */
@Data
@TableName("cpe_device")
public class CpeDevice {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sn;

    private String oui;

    private String manufacturer;

    private String productClass;

    private String softwareVersion;

    private String hardwareVersion;

    /**
     * 根数据模型节点：InternetGatewayDevice. 或 Device.
     */
    private String rootNode;

    private String connectionRequestUrl;

    private String cwmpVersion;

    /**
     * 认证类型：digest / basic / None
     */
    private String authType;

    private String cpeToAcsName;

    private String cpeToAcsPassword;

    private String acsToCpeName;

    private String acsToCpePassword;

    private String isp;

    private String ispVersion;

    /**
     * 设备类型，如 GPON_4+1，对应原 DOMAIN
     */
    private String deviceType;

    /**
     * 房间号，下发时用它替换模板里的 {@code %room%} 变量，如 0001
     */
    private String roomNo;

    /**
     * 备注，运维随手记的设备说明
     */
    private String remark;

    private String status;

    private LocalDateTime lastContactTime;

    private Integer soapInformTimeout;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
