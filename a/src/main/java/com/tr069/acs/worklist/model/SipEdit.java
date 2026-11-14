package com.tr069.acs.worklist.model;

import com.tr069.acs.worklist.tpl.TemplateVariables;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** SIP 语音编辑入参：业务字段，不是 CWMP 参数 */
@Data
public class SipEdit {

    /** 业务类型：与工单模板库里的分类码一致 */
    public static final String CATEGORY = "SIP";

    /** 端口没填时的行业默认值；注册与代理各一份，设备侧也是这么默认的 */
    public static final String DEFAULT_PORT = "5060";

    /** 旧的模板里可能引用了 URI 变量，这里照旧带上，避免残留字面量 */
    public static final String SIP_URI = "%sip_uri%";

    /** 设备序列号 */
    private String sn;

    /** 要改哪几路（Line 实例号）；空表示只改第 1 路 */
    private List<Integer> lines;

    /** 电话号码 / URI */
    private String number;

    /** 认证账号；留空时用号码 */
    private String authUser;

    /** 认证密码 */
    private String password;

    /** 注册服务器 */
    private String registrar;

    /** 注册服务器端口 */
    private String registrarPort;

    /** 代理服务器 */
    private String proxy;

    /** 代理服务器端口 */
    private String proxyPort;

    /** 操作人 */
    private String username;

    /** 要下发的路号：去重、升序、丢掉非法值；没传按第 1 路 */
    public List<Integer> normalizedLines() {
        if (lines == null || lines.isEmpty()) {
            return List.of(1);
        }
        List<Integer> sorted = lines.stream()
                .filter(Objects::nonNull)
                .filter(line -> line > 0)
                .distinct()
                .sorted()
                .toList();
        return sorted.isEmpty() ? List.of(1) : sorted;
    }

    /** 某一路的下发变量：键就是模板里的占位符 */
    public Map<String, String> toValues(int line) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("instance", String.valueOf(line));
        // 认证账号决定设备用哪个账号注册，没填就退回号码
        values.put(TemplateVariables.SIP_NUM, text(authUser, number));
        values.put(SIP_URI, text(number));
        values.put(TemplateVariables.SIP_PWD, text(password));
        values.put(TemplateVariables.SIP_REGISTRAR, text(registrar));
        values.put(TemplateVariables.SIP_REGISTRAR_PORT, port(registrarPort));
        values.put(TemplateVariables.SIP_PROXY, text(proxy));
        values.put(TemplateVariables.SIP_PORT, port(proxyPort));
        return values;
    }

    /** 号码必填：没有号码的「开通语音」开出来是一个空线路 */
    public String validate() {
        if (sn == null || sn.isBlank()) {
            return "缺少设备序列号";
        }
        if (number == null || number.isBlank()) {
            return "电话号码 / URI 不能为空";
        }
        return null;
    }

    private static String text(String... candidates) {
        for (String value : candidates) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String port(String value) {
        return value == null || value.isBlank() ? DEFAULT_PORT : value.trim();
    }
}
