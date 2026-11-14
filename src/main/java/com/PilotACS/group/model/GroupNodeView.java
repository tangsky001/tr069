/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.770
 *
 */

package com.PilotACS.group.model;

import com.PilotACS.group.entity.ResGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 分组树节点：固定根节点（kind=root）+ 已落库分组节点（kind=group，可选区划）。
 * 另复用为「建组候选」行（kind=civil，仅 code/name/level 有值）。
 */
@Data
@Schema(description = "分组树节点 / 建组候选行")
public class GroupNodeView {

    @Schema(description = "节点引用：ROOT（根节点）/ G:分组 id（已落库）/ C:区划编号（仅候选行），树 key/绑定/查询都用它")
    private String nodeRef;

    @Schema(description = "节点类型：root=固定根，group=已落库分组，civil=建组候选行（仅 /candidates 返回）")
    private String kind;

    @Schema(description = "分组 id；根节点与候选行为 null")
    private Long id;

    @Schema(description = "父分组 id；根节点下为 null")
    private Long parentId;

    @Schema(description = "节点名称：区划名或自定义分组名")
    private String name;

    @Schema(description = "区划编号（节点落库时选的区划；候选行的 code）")
    private String code;

    @Schema(description = "关联区划编号（同 code，兼容字段）")
    private String civilCode;

    @Schema(description = "区划层级：1=省，2=市，3=区县；自定义节点与根节点为 0")
    private int level;

    @Schema(description = "直接挂在本节点的成员数量")
    private long memberCount;

    @Schema(description = "含下级的成员总数（汇总）")
    private long memberTotal;

    @Schema(description = "下级节点")
    private List<GroupNodeView> children = new ArrayList<>();

    @Schema(description = "节点直属设备名称清单（树节点悬停提示用）")
    private List<String> memberNames = new ArrayList<>();

    @Schema(description = "节点直属设备清单（树里直接展示设备名）")
    private List<com.PilotACS.group.model.GroupMemberBrief> members = new ArrayList<>();

    /** 建组候选行视图（来自 civilCode.csv 的下级区划） */
    public static GroupNodeView civil(String code, String name, int level) {
        GroupNodeView view = new GroupNodeView();
        view.setNodeRef("C:" + code);
        view.setKind("civil");
        view.setCode(code);
        view.setCivilCode(code);
        view.setName(name);
        view.setLevel(level);
        return view;
    }

    /** 已落库分组节点视图 */
    public static GroupNodeView group(ResGroup group) {
        GroupNodeView view = new GroupNodeView();
        view.setNodeRef("G:" + group.getId());
        view.setKind("group");
        view.setId(group.getId());
        view.setParentId(group.getParentId());
        view.setName(group.getName());
        view.setCode(group.getCivilCode());
        view.setCivilCode(group.getCivilCode());
        view.setLevel(0);
        return view;
    }
}
