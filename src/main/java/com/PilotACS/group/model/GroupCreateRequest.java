/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.502
 *
 */

package com.PilotACS.group.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 创建分组节点（落库）：选区划或自定义名称 */
@Data
@Schema(description = "创建分组节点")
public class GroupCreateRequest {

    @Schema(description = "父节点引用：ROOT（根节点下）或 G:分组 id；null = 根节点下")
    private String parentRef;

    @Schema(description = "所选区划编号（civilCode，可选）：必须与父节点区划层级衔接；选择后名称默认取区划名")
    private String civilCode;

    @Schema(description = "分组名称：选区划时可留空（默认区划名）；自定义节点必填，如「北京其他区域」")
    private String name;
}
