package com.tr069.acs.cwmp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** CPE 上报的 Inform 请求 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InformRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private DeviceIdStruct deviceId;

    @Builder.Default
    private List<EventStruct> eventList = new ArrayList<>();

    /** 最大信元数 */
    private String maxEnvelopes;

    private String currentTime;

    private String retryCount;

    /** Inform 消息体携带的参数列表 */
    @Builder.Default
    private List<ParameterValueStruct> parameterList = new ArrayList<>();

    /** 设备声明的 CWMP 版本，从 SOAP Envelope 的 xmlns:cwmp 命名空间提取，如 cwmp-1-0 / cwmp-1-4 */
    private String cwmpVersion;
}
