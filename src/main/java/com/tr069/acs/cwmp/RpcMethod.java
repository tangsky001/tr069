package com.tr069.acs.cwmp;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * CWMP RPC 方法名常量。
 *
 * <p>对齐 Python 版 construct.py 中 {@code create_soap_body} 的各分支。</p>
 */
@Getter
public enum RpcMethod {

    // ---------------- ACS -> CPE（请求） ----------------

    /** 空结构 */
    GET_RPC_METHODS("GetRPCMethods"),
    SET_PARAMETER_VALUES("SetParameterValues"),
    GET_PARAMETER_VALUES("GetParameterValues"),
    GET_PARAMETER_NAMES("GetParameterNames"),
    SET_PARAMETER_ATTRIBUTES("SetParameterAttributes"),
    GET_PARAMETER_ATTRIBUTES("GetParameterAttributes"),
    ADD_OBJECT("AddObject"),
    DELETE_OBJECT("DeleteObject"),
    DOWNLOAD("Download"),
    UPLOAD("Upload"),
    REBOOT("Reboot"),
    /** 空结构 */
    FACTORY_RESET("FactoryReset"),
    SCHEDULE_INFORM("ScheduleInform"),
    SCHEDULE_DOWNLOAD("ScheduleDownload"),
    CANCEL_TRANSFER("CancelTransfer"),
    CHANGE_DU_STATE("ChangeDUState"),
    /** 原版实现为空结构：create_soap_body 首个分支即命中，后序 elif 为死代码 */
    GET_QUEUED_TRANSFERS("GetQueuedTransfers"),
    /** 原版实现为空结构，同 GET_QUEUED_TRANSFERS */
    GET_ALL_QUEUED_TRANSFERS("GetAllQueuedTransfers"),
    SET_VOUCHERS("SetVouchers"),
    GET_OPTIONS("GetOptions"),

    // ---------------- CPE -> ACS（请求） ----------------

    INFORM("Inform"),
    TRANSFER_COMPLETE("TransferComplete"),
    REQUEST_DOWNLOAD("RequestDownload"),
    DU_STATE_CHANGE_COMPLETE("DUStateChangeComplete"),
    AUTONOMOUS_TRANSFER_COMPLETE("AutonomousTransferComplete"),
    AUTONOMOUS_DU_STATE_CHANGE_COMPLETE("AutonomousDUStateChangeComplete"),

    // ---------------- 响应 ----------------

    INFORM_RESPONSE("InformResponse"),
    GET_RPC_METHODS_RESPONSE("GetRPCMethodsResponse"),
    TRANSFER_COMPLETE_RESPONSE("TransferCompleteResponse"),
    REQUEST_DOWNLOAD_RESPONSE("RequestDownloadResponse"),
    DU_STATE_CHANGE_COMPLETE_RESPONSE("DUStateChangeCompleteResponse"),
    AUTONOMOUS_TRANSFER_COMPLETE_RESPONSE("AutonomousTransferCompleteResponse"),
    AUTONOMOUS_DU_STATE_CHANGE_COMPLETE_RESPONSE("AutonomousDUStateChangeCompleteResponse"),
    KICKED_RESPONSE("KickedResponse");

    private final String value;

    RpcMethod(String value) {
        this.value = value;
    }

    /**
     * 归一化后的索引：同时收录方法名与枚举常量名，忽略大小写与下划线差异。
     *
     * <p>现场模板和早期代码里出现过把方法写成枚举常量名的情况（如 {@code GET_PARAMETER_NAMES}），
     * 严格匹配会判为未知方法，指令入队后到空 POST 才炸。这里做一次兜底识别。</p>
     */
    private static final Map<String, RpcMethod> NORMALIZED = new HashMap<>();

    static {
        for (RpcMethod method : values()) {
            NORMALIZED.put(normalize(method.value), method);
            NORMALIZED.put(normalize(method.name()), method);
        }
    }

    /**
     * 按方法名查找枚举。
     *
     * <p>先按 SOAP 里的标准写法精确匹配，再退回归一化匹配（忽略大小写、下划线与连字符），
     * 因此 {@code GetParameterNames}、{@code GET_PARAMETER_NAMES}、{@code get-parameter-names}
     * 都会解析到同一个枚举。</p>
     *
     * @param value SOAP Body 中的 CWMP 方法名
     * @return 匹配的枚举，未识别时返回 null
     */
    public static RpcMethod of(String value) {
        if (value == null) {
            return null;
        }
        for (RpcMethod method : values()) {
            if (method.value.equals(value)) {
                return method;
            }
        }
        return NORMALIZED.get(normalize(value));
    }

    /**
     * 归一化：只保留字母与数字并转小写。
     */
    private static String normalize(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) (c + 32));
            }
        }
        return sb.toString();
    }
}
