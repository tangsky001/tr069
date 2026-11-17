package com.PilotACS.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 业务模板成员：引用一张配置模板，并可覆盖它的入参默认值 */
@Data
@TableName("biz_template_item")
@Schema(description = "业务模板成员：templateKey 存配置模板 id")
public class BizTemplateItem {

    @TableId(type = IdType.AUTO)
    @Schema(description = "成员 id")
    private Long id;

    @Schema(description = "所属业务模板 id")
    private Long templateId;

    @Schema(description = "引用的配置模板 id（字符串存，兼容历史文件 key）")
    private String templateKey;

    @Schema(description = "执行顺序，小的先执行")
    private Integer itemOrder;

    @Schema(description = "入参覆盖 JSON 对象，如 {\"vlan_id\":\"41\"}，覆盖配置模板同名字段")
    private String params;

    @Schema(description = "成员备注")
    private String remark;
}
