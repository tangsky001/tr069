/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 解析后的 CPE SOAP 请求/响应报文 */
@Data
public class SoapMessage {

    /** 报文头 ID */
    private String id;
    /** 方法名 */
    private String name;

    /** 报文使用的 CWMP 命名空间（如 urn:dslforum-org:cwmp-1-2），设备自适应 */
    private String cwmpNamespace;
    /** 由命名空间推出的 CWMP 协议版本（1.0/1.1/1.2…） */
    private String cwmpVersion;

    private String manufacturer;
    private String oui;
    private String productClass;
    private String serialNumber;
    private List<String> events = new ArrayList<>();
    private long maxEnvelopes = 1;
    private long retryCount;
    private List<CpeParam> params = new ArrayList<>();

    private List<CpeParam> results = new ArrayList<>();

    /** 0=已应用 1=待应用 */
    private int status;

    private long instanceNumber;

    private List<String> methods = new ArrayList<>();

    private String commandKey;
    private int tcFaultCode = -1;
    private String tcFaultString;
    private String startValue;
    private String endValue;

    private String startTime;
    private String completeTime;

    /** 设备声明不再发后续请求 */
    private boolean noMoreRequests;
    /** 设备声明的会话超时（秒） */
    private long sessionTimeoutSeconds;
    /** 设备声明的 HoldRequests */
    private boolean holdRequests;

    private boolean fault;
    private int faultCode;
    private String faultString;
    /** SPV 部分失败明细 */
    private List<SetFault> setFaults = new ArrayList<>();

    /** 是否空 POST（CPE 表示"可以下发了"） */
    public boolean isEmptyPost() {
        return name == null;
    }

    public record SetFault(String name, int faultCode, String faultString) {
    }
}
