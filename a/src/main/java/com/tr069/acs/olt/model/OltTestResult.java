package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 诊断测试结果 */
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

    /** 结果一行话：「登录成功；提示符：&lt;Huawei&gt;；耗时 1200 ms」 */
    public String getSummary() {
        List<String> parts = new ArrayList<>();
        if (message != null && !message.isBlank()) {
            parts.add(message);
        }
        if (prompt != null && !prompt.isBlank()) {
            parts.add("提示符：" + prompt);
        }
        if (costMs > 0) {
            parts.add("耗时 " + costMs + " ms");
        }
        return String.join("；", parts);
    }

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
