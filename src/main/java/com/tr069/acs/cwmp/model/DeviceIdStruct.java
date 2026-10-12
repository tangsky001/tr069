package com.tr069.acs.cwmp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * CWMP 设备标识结构，对应 {@code cwmp:DeviceIdStruct}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceIdStruct implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 厂商 OUI
     */
    private String manufacturer;

    /**
     * 厂商标识
     */
    private String oui;

    /**
     * 产品类别
     */
    private String productClass;

    /**
     * 序列号
     */
    private String serialNumber;
}
