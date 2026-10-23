package com.tr069.acs.cwmp.model;

import com.tr069.acs.cwmp.RpcMethod;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条 CWMP 报文的解析结果。
 *
 * <p>{@link #getBody()} 是各 RPC 响应的统一字段结构，上层按一套方式取值，
 * 不必逐个 RPC 分辨。</p>
 */
@Data
@Builder
public class SoapMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话 ID，取自 cwmp:ID
     */
    private String cwmpId;

    /**
     * 解析出的 CWMP 方法，未识别时为 null
     */
    private RpcMethod method;

    /**
     * Body 根元素的方法名（含响应方法）
     */
    private String methodName;

    /**
     * 结构化的结果数据，对应 dict_data
     */
    @Builder.Default
    private Map<String, Object> body = new LinkedHashMap<>();

    /**
     * 可读的结果描述，对应 str_result
     */
    private String strResult;

    /**
     * SOAP Fault，非故障报文为 null
     */
    private FaultStruct fault;

    /**
     * 是否是需要 ACS 回复的方法（Inform / TransferComplete 等由 CPE 主动发起者）
     */
    public boolean isRequestFromCpe() {
        if (method == null) {
            return false;
        }
        return switch (method) {
            case INFORM, TRANSFER_COMPLETE, REQUEST_DOWNLOAD, DU_STATE_CHANGE_COMPLETE,
                    AUTONOMOUS_TRANSFER_COMPLETE, AUTONOMOUS_DU_STATE_CHANGE_COMPLETE -> true;
            default -> false;
        };
    }

    /**
     * 是否为故障报文。
     */
    public boolean isFault() {
        return fault != null;
    }

    /**
     * 取 string 类型的结果字段。
     */
    public String stringValue(String key) {
        Object v = body.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * 取列表类型的结果字段。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listValue(String key) {
        Object v = body.get(key);
        if (v instanceof List<?> list) {
            return (List<Map<String, Object>>) v;
        }
        return List.of();
    }
}
