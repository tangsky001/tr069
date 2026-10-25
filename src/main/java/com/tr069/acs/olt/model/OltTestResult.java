package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * 诊断测试结果。
 *
 * <p>失败也是一次有效返回（HTTP 200 + success=false），页面据此在弹窗里直接提示原因，
 * 不用走统一的错误提示条。</p>
 */
@Data
public class OltTestResult {

    private boolean success;

    private String message;

    private String vendor;

    private String vendorLabel;

    /** 命令风格：DISPLAY（华为）/ SHOW（第三方），探测不出来为 null */
    private String cliStyle;

    private String model;

    private String softwareVersion;

    private String prompt;

    private Integer boardCount;

    /** 本次交互回显（截断），页面可折叠查看 */
    private String rawOutput;

    private long costMs;

    public static OltTestResult ok(String message) {
        OltTestResult result = new OltTestResult();
        result.setSuccess(true);
        result.setMessage(message);
        return result;
    }

    public static OltTestResult fail(String message) {
        OltTestResult result = new OltTestResult();
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }
}
