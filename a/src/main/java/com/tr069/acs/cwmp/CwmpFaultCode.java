package com.tr069.acs.cwmp;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/** CWMP 标准故障码（TR-069 附录 A） */
@Getter
public enum CwmpFaultCode {

    // ---------------- SOAP / 内部（8000 系列） ----------------

    /** 方法未实现 */
    METHOD_NOT_SUPPORTED("8000", "Method not supported"),
    /** 请求参数越界或非法 */
    INVALID_ARGUMENTS("8001", "Invalid arguments"),
    /** ACS 内部错误 */
    INTERNAL_ERROR("8002", "Internal error"),
    /** ACS 资源不足或队列不可用 */
    QUEUE_FAILURE("8003", "Value not allowed / resource exceeded"),
    /** 重启后参数失效 */
    INVALID_PARAMETER_NAME("8004", "Invalid parameter name (invalid after reboot)"),
    /** 未知方法名 */
    BAD_METHOD("8005", "Invalid parameter name (unknown method)"),

    // ---------------- CPE 侧（9000 系列，按 TR-069 附录 A.3.3） ----------------

    /** 请求被拒绝（设备侧未说明原因） */
    REQUEST_DENIED("9000", "Request denied (no reason specified)"),
    /** 请求被拒绝（设备内部错误） */
    REQUEST_DENIED_INTERNAL("9001", "Request denied (internal error)"),
    /** 设备内部错误（CWMP agent 忙、数据模型异常等笼统失败，是最常见的 CPE 侧故障） */
    CPE_INTERNAL_ERROR("9002", "Internal error"),
    /** 设备不支持该方法 */
    METHOD_NOT_SUPPORTED_CPE("9003", "Method not supported"),
    /** 设备忙，请求被拒 */
    REQUEST_DENIED_BUSY("9004", "Request denied (process busy)"),
    /** 参数名不存在 */
    INVALID_PARAMETER_NAME_CPE("9005", "Invalid parameter name"),
    /** 参数类型不合法 */
    INVALID_PARAMETER_TYPE("9006", "Invalid parameter type"),
    /** 参数值不合法 */
    INVALID_PARAMETER_VALUE("9007", "Invalid parameter value"),
    /** 参数只读，不可写 */
    NON_WRITABLE_PARAMETER("9008", "Non-writable parameter"),
    /** 参数值与其他配置冲突 */
    PARAMETER_VALUE_CONFLICT("9009", "Parameter value conflict"),
    /** 参数重建冲突 */
    PARAMETER_RECONSTRUCTION_CONFLICT("9010", "Parameter reconstruction conflict"),
    /** 设备重启后才能创建对象 */
    REBOOT_REQUIRED("9011", "Object creation denied (reboot required)"),
    /** DeleteObject 对象不存在 */
    INVALID_DELETE_OBJECT("9012", "Object deletion denied (invalid object)"),
    /** DeleteObject 对象被引用 */
    REFERENCED_OBJECT("9013", "Object deletion denied (object referenced)"),
    /** DeleteObject 重启后生效 */
    DELETE_REBOOT_REQUIRED("9014", "Object deletion denied (reboot required)"),
    /** 参数级失败，见明细 */
    SET_PARAMETER_FAILURE("9015", "Invalid parameter (see detail)"),
    /** 会话超限 */
    SESSION_LIMIT("9016", "Request denied (session limit reached)"),
    /** 重复请求拒绝 */
    REQUEST_DENIED_UNKNOWN("9017", "Request denied (unknown reason)");

    private final String code;

    private final String description;

    CwmpFaultCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    private static final Map<String, CwmpFaultCode> INDEX = new HashMap<>();

    static {
        for (CwmpFaultCode fault : values()) {
            INDEX.put(fault.code, fault);
        }
    }

    /** 按故障码查枚举，未收录时返回 null */
    public static CwmpFaultCode of(String code) {
        return code == null ? null : INDEX.get(code.trim());
    }

    /** 故障码的可读描述，未收录时返回空串 */
    public static String describe(String code) {
        CwmpFaultCode fault = of(code);
        return fault == null ? "" : fault.description;
    }
}
