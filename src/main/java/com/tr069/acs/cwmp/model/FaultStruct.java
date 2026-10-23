package com.tr069.acs.cwmp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * CWMP 故障结构。
 *
 * <p>CPE 通过 SOAP Fault 返回时会包含此结构；SetParameterValues 部分失败时还会带
 * {@link SetParameterValuesFault} 列表。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaultStruct implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * CWMP 故障码，如 8000 / 8002 / 8003 / 8005 / 9001...
     */
    private String faultCode;

    /**
     * 故障描述
     */
    private String faultString;

    /**
     * SOAP faultcode，如 SOAP-ENV:Client / SOAP-ENV:Server
     */
    private String soapFaultCode;

    /**
     * SetParameterValues 逐参数失败明细
     */
    @Builder.Default
    private List<SetParameterValuesFault> setParameterValuesFaultList = new ArrayList<>();

    /**
     * SetParameterValues 逐参数失败项。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SetParameterValuesFault implements Serializable {

        private static final long serialVersionUID = 1L;

        private String parameterName;

        private String faultCode;

        private String faultString;
    }
}
