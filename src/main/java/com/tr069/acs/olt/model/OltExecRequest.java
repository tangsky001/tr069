package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.Map;

/**
 * 执行一条命令动作的请求（页面「设备配置」页签里点执行时提交）。
 *
 * <p>{@code code} 对应 {@link OltAction#code()}；{@code params} 是表单里填的值，
 * 键名对应 {@link OltActionParam#key()}。</p>
 */
@Data
public class OltExecRequest {

    /** 动作编号，如 {@code vlan.create} */
    private String code;

    /** 表单参数，如 {"vlanId": "100"} */
    private Map<String, String> params;
}
