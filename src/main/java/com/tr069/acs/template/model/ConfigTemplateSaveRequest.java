package com.tr069.acs.template.model;

import lombok.Data;

import java.util.Map;

/**
 * 配置意图模板保存请求。
 */
@Data
public class ConfigTemplateSaveRequest {

    /** 为空表示新建。 */
    private Long id;

    private String name;

    private String category;

    private String groupName;

    private Boolean enabled;

    private String remark;

    /** 意图字段，按类型不同：如 ip_protocol / vlan_used / port_bind。 */
    private Map<String, Object> fields;

    /** 增量业务：true 时下发只新增这一条，不清理/不改写设备上已有的其它业务；为空按 false。 */
    private Boolean incremental;
}
