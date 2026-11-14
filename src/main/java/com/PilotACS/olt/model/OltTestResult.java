/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.672
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class OltTestResult {

    private boolean success;

    private String message;

    private String vendor;

    private String vendorLabel;

    @Schema(description = "命令风格：DISPLAY（华为）/ SHOW（第三方），探测不出来为 null")
    private String cliStyle;

    private String model;

    private String softwareVersion;

    private String prompt;

    private Integer boardCount;

    @Schema(description = "本次交互回显（截断），页面可折叠查看")
    private String rawOutput;

    private long costMs;

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
