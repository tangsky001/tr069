package com.tr069.acs.olt.model;

import java.util.List;

/** 一条可下发动作的参数定义 */
public record OltActionParam(String key, String label, String type, Integer min, Integer max,
                             List<String> options, boolean required, String hint, String defaultValue) {

    /** 1-4094 这类数值参数 */
    public static OltActionParam number(String key, String label, int min, int max, String hint) {
        return new OltActionParam(key, label, "number", min, max, null, true, hint, null);
    }

    /** 端口号（单个）：interface ge 1 里的那个 1 */
    public static OltActionParam portNo(String label) {
        return new OltActionParam("portNo", label, "number", 1, 128, null, true,
                "单个端口号，例如 1", null);
    }

    /** 端口列表：1,3,4-8 这种写法，命令里原样拼 */
    public static OltActionParam ports(String key, String label, String hint) {
        return new OltActionParam(key, label, "ports", null, null, null, true, hint, null);
    }

    /** 端口类型：epon / ge / xge */
    public static OltActionParam portType() {
        return new OltActionParam("portType", "端口类型", "enum", null, null,
                List.of("epon", "ge", "xge"), true, "PON 口选 epon，上联电口 ge，万兆口 xge", "epon");
    }

    /** 必填文本（IP / MAC / 规则名这类） */
    public static OltActionParam text(String key, String label, String hint) {
        return new OltActionParam(key, label, "text", null, null, null, true, hint, null);
    }

    /** 可选文本：不填就把整段命令省略（模板里不能出现它） */
    public static OltActionParam optionalText(String key, String label, String hint) {
        return new OltActionParam(key, label, "text", null, null, null, false, hint, null);
    }

    /** 可选数字 */
    public static OltActionParam optionalNumber(String key, String label, int min, int max, String hint) {
        return new OltActionParam(key, label, "number", min, max, null, false, hint, null);
    }

    /** 下拉单选 */
    public static OltActionParam choice(String key, String label, List<String> options,
                                        String defaultValue, String hint) {
        return new OltActionParam(key, label, "enum", null, null, options, true, hint, defaultValue);
    }
}
