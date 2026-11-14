package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.Map;

/** 执行一条命令动作的请求（页面「设备配置」页签里点执行时提交） */
@Data
public class OltExecRequest {

    /** 动作编号，如 {@code vlan.create} */
    private String code;

    /** 表单参数，如 {"vlanId": "100"} */
    private Map<String, String> params;
}
