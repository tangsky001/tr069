package com.tr069.acs.cwmp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * CWMP 参数名值结构，对应 SOAP 中的 {@code cwmp:ParameterValueStruct}。
 *
 * <p>对应 Python 版 rpcstruct.py 的同名结构。ACS 构造 SetParameterValues 时
 * 使用 {@link #name} 与 {@link #value}；解析 CPE 的 GetParameterValues 响应时
 * 会额外回填 {@link #valueType}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParameterValueStruct implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 参数完整路径，如 InternetGatewayDevice.DeviceInfo.SerialNumber
     */
    private String name;

    /**
     * 参数值
     */
    private String value;

    /**
     * 值类型，取自 xsi:type 去掉 xsd: 前缀后的类型名，如 string / unsignedInt / boolean
     */
    private String valueType;

    public static ParameterValueStruct of(String name, String value) {
        return new ParameterValueStruct(name, value, null);
    }
}
