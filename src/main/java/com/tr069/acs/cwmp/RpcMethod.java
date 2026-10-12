package com.tr069.acs.cwmp;

import lombok.Getter;

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
     * 按方法名查找枚举。
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
        return null;
    }
}
