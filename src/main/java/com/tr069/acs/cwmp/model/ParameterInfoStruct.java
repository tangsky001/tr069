package com.tr069.acs.cwmp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * GetParameterNames 响应中的参数信息结构，对应 {@code cwmp:ParameterInfoStruct}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParameterInfoStruct implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 参数路径
     */
    private String name;

    /**
     * 是否可写：true / false
     */
    private String writable;

    public static ParameterInfoStruct of(String name, String writable) {
        return new ParameterInfoStruct(name, writable);
    }
}
