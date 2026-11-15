/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析后的 CPE SOAP 请求/响应报文（参考 genieacs soap.ts request() 的输出结构）
 */
@Data
public class SoapMessage {

    /** 报文头 ID */
    private String id;
    /** 方法名：Inform / GetParameterValuesResponse / Fault / TransferComplete / 空 POST 为 null */
    private String name;

    // ---- Inform ----
    private String manufacturer;
    private String oui;
    private String productClass;
    private String serialNumber;
    private List<String> events = new ArrayList<>();
    private long maxEnvelopes = 1;
    private long retryCount;
    private List<CpeParam> params = new ArrayList<>();

    // ---- GPV / GPN 响应 ----
    private List<CpeParam> results = new ArrayList<>();

    // ---- SPV 响应 ----
    /** 0=已应用 1=待应用（重启后生效） */
    private int status;

    // ---- AddObject 响应 ----
    private long instanceNumber;

    // ---- GetRPCMethods 响应 ----
    private List<String> methods = new ArrayList<>();

    // ---- TransferComplete ----
    private String commandKey;
    private int tcFaultCode = -1;
    private String tcFaultString;
    private String startValue;
    private String endValue;

    // ---- Fault ----
    private boolean fault;
    /** CPE FaultCode：9000/9002/9003/9004/9005/9009... */
    private int faultCode;
    private String faultString;
    /** SPV 部分失败的明细：参数名+错误码 */
    private List<SetFault> setFaults = new ArrayList<>();

    /** 是否空 POST（CPE 表示"可以下发了"） */
    public boolean isEmptyPost() {
        return name == null;
    }

    public record SetFault(String name, int faultCode, String faultString) {
    }
}
