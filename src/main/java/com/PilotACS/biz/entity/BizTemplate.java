package com.PilotACS.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 业务模板：一组配置模板（意图模板）的有序集合，对应一次完整业务开通，整包串行下发 */
@Data
@TableName("biz_template")
@Schema(description = "业务模板：多张配置模板的有序集合")
public class BizTemplate {

    @TableId(type = IdType.AUTO)
    @Schema(description = "业务模板 id")
    private Long id;

    @Schema(description = "业务模板名称（唯一）")
    private String name;

    @Schema(description = "业务分组，页面按分组归类展示")
    private String groupName;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "是否启用，停用的模板不允许下发")
    private Boolean enabled;

    @Schema(description = "成员执行失败时是否继续后续成员")
    private Boolean continueOnError;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
