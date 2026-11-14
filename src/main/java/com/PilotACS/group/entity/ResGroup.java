package com.PilotACS.group.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自定义分组节点：挂在区划树上的补充节点（如「北京其他区域」）。
 *
 * <p>分组树骨架 = civilCode.csv 的省→市→区县（自动存在，不可建删），
 * 自定义节点通过 parent_civil_code 挂在区划节点下，或通过 parent_id 挂在其它自定义节点下。</p>
 */
@Data
@TableName("res_group")
@Schema(description = "自定义分组节点（挂在区划树上）")
public class ResGroup {

    @TableId(type = IdType.AUTO)
    @Schema(description = "分组 id")
    private Long id;

    @Schema(description = "父自定义分组 id；挂在区划下时为 null")
    private Long parentId;

    @Schema(description = "父区划编号（civilCode）；挂在自定义分组下时为 null")
    private String parentCivilCode;

    @Schema(description = "分组名称")
    private String name;

    @Schema(description = "关联区划编号（civilCode，可选）")
    private String civilCode;

    @Schema(description = "排序号，同级从小到大")
    private Integer sort;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建人")
    private String createdBy;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
