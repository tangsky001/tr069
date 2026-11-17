package com.PilotACS.template.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/** 配置意图模板保存请求 */
@Data
@Schema(description = "配置意图模板保存请求：id 为空表示新建")
public class ConfigTemplateSaveRequest {

    @Schema(description = "模板 id，为空表示新建")
    private Long id;

    @Schema(description = "模板名称")
    private String name;

    @Schema(description = "模板类型")
    private String category;

    @Schema(description = "模板分组")
    private String groupName;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "意图字段，按类型不同：如 ip_protocol / vlan_id / service_list / bind_ports")
    private Map<String, Object> fields;

    @Schema(description = "增量业务：true 时下发只新增不清理存量连接")
    private Boolean incremental;
}
