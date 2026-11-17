package com.PilotACS.biz.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/** 业务模板成员入参 */
@Data
@Schema(description = "业务模板成员入参：templateKey 为配置模板 id，params 为入参覆盖")
public class BizTemplateItemInput {

    @Schema(description = "成员 id（编辑时已有）")
    private Long id;

    @Schema(description = "引用的配置模板 id")
    private String templateKey;

    @Schema(description = "执行顺序")
    private Integer itemOrder;

    @Schema(description = "入参覆盖，覆盖配置模板同名字段")
    private Map<String, String> params;

    @Schema(description = "成员备注")
    private String remark;
}
