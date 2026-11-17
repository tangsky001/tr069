package com.PilotACS.biz.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/** 业务模板成员视图：成员数据 + 引用的配置模板摘要 + 可用性判定 */
@Data
@Schema(description = "业务模板成员视图")
public class BizTemplateItemView {

    @Schema(description = "成员 id")
    private Long id;

    @Schema(description = "所属业务模板 id")
    private Long templateId;

    @Schema(description = "引用的配置模板 id")
    private String templateKey;

    @Schema(description = "执行顺序")
    private Integer itemOrder;

    @Schema(description = "成员备注")
    private String remark;

    @Schema(description = "入参覆盖")
    private Map<String, String> params;

    @Schema(description = "引用的配置模板名")
    private String templateName;

    @Schema(description = "引用的配置模板类型")
    private String category;

    @Schema(description = "配置模板类型展示名")
    private String categoryLabel;

    @Schema(description = "开通意图摘要")
    private String intentSummary;

    @Schema(description = "配置模板是否可用（存在且启用）")
    private boolean available;

    @Schema(description = "能否按现有能力下发")
    private boolean executable;

    @Schema(description = "不可用/不可执行原因")
    private String issue;
}
