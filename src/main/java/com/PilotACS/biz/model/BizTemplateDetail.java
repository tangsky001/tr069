package com.PilotACS.biz.model;

import com.PilotACS.biz.entity.BizTemplate;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/** 业务模板详情：模板主体 + 成员视图列表 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "业务模板详情")
public class BizTemplateDetail extends BizTemplate {

    @Schema(description = "成员视图列表（带配置模板摘要与可用性）")
    private List<BizTemplateItemView> items;
}
