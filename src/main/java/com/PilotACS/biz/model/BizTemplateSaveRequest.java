package com.PilotACS.biz.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** 业务模板保存请求 */
@Data
@Schema(description = "业务模板保存请求：id 为空表示新建；items 为成员有序列表")
public class BizTemplateSaveRequest {

    @Schema(description = "业务模板 id，为空表示新建")
    private Long id;

    @Schema(description = "业务模板名称")
    private String name;

    @Schema(description = "业务分组")
    private String groupName;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "成员失败时是否继续")
    private Boolean continueOnError;

    @Schema(description = "成员列表，按顺序执行")
    private List<BizTemplateItemInput> items;
}
