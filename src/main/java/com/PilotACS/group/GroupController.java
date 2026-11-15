/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.457
 *
 */

package com.PilotACS.group;

import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.UserContext;
import com.PilotACS.group.model.GroupAttachRequest;
import com.PilotACS.group.model.GroupCreateRequest;
import com.PilotACS.group.model.GroupDeleteRequest;
import com.PilotACS.group.model.GroupMemberView;
import com.PilotACS.group.model.GroupMoveRequest;
import com.PilotACS.group.model.GroupNodeView;
import com.PilotACS.group.model.GroupRenameRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分组管理：分组树骨架来自 civilCode.csv（省→市→区县自动存在），
 * 自定义节点挂靠在区划或自定义父节点下；设备归属关系在 res_group_member 表（添加设备时落组）。
 */
@Tag(name = "分组管理", description = "区划骨架分组树（civilCode.csv）、自定义子分组（创建/重命名/删除）、组内设备台账")
@RestController
@RequestMapping("/group")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    /** 分组树：区划骨架 + 自定义节点，节点带成员数量（直接数与含下级汇总数）；memberType=OLT/ONU 分开统计 */
    @Operation(summary = "分组树：区划骨架（civilCode.csv，34 省逐级到区县）+ 自定义节点，节点带成员数量；memberType=OLT/ONU（默认 OLT）")
    @GetMapping("/tree")
    public Result<List<GroupNodeView>> tree(@RequestParam(value = "memberType", required = false) String memberType) {
        return Result.ok(groupService.tree(memberType));
    }

    /** 建组候选：父节点下一级可选的区划列表（根节点列省份，省列市，市列区县） */
    @Operation(summary = "建组候选：父节点下一级可选区划（根→省，省→市，市→区县；自定义节点无候选）")
    @GetMapping("/candidates")
    public Result<List<GroupNodeView>> candidates(@RequestParam(value = "parentRef", required = false) String parentRef) {
        return Result.ok(groupService.candidates(parentRef == null || parentRef.isBlank() ? "ROOT" : parentRef));
    }

    /** 创建分组（落库）：选区划（层级必须衔接）或自定义名称 */
    @Operation(summary = "创建分组：父节点下落库；选区划（与父节点层级衔接）或自定义名称")
    @PostMapping("/create")
    public Result<Void> create(@RequestBody GroupCreateRequest request, HttpServletRequest httpRequest) {
        String error = groupService.create(request.getParentRef(), request.getCivilCode(),
                request.getName(), operator(httpRequest));
        return error == null ? Result.ok() : Result.fail(error);
    }

    /** 重命名自定义分组 */
    @Operation(summary = "重命名自定义分组（区划节点不可改名）")
    @PostMapping("/rename")
    public Result<Void> rename(@RequestBody GroupRenameRequest request) {
        Long id = parseGroupId(request.getId());
        String error = id == null ? "分组不存在" : groupService.rename(id, request.getName());
        return error == null ? Result.ok() : Result.fail(error);
    }

    /** 删除自定义分组：无下级且无绑定成员才允许 */
    @Operation(summary = "删除自定义分组：有子分组或绑定成员时拒绝；区划节点不可删除")
    @PostMapping("/delete")
    public Result<Void> delete(@RequestBody GroupDeleteRequest request) {
        String error = groupService.delete(request.getId());
        return error == null ? Result.ok() : Result.fail(error);
    }

    /** 组内成员：某节点的设备台账与状态（OLT 实时台账；ONU 为归属快照，光猫模块未接入） */
    @Operation(summary = "组内成员：某节点的设备台账与在线状态，按绑定时间倒序；memberType=OLT/ONU（默认 OLT）")
    @GetMapping("/members")
    public Result<List<GroupMemberView>> members(@RequestParam("nodeRef") String nodeRef,
                                                 @RequestParam(value = "memberType", required = false) String memberType) {
        return Result.ok(groupService.members(nodeRef, memberType));
    }


    /** 移动设备到目标分组：换分组直接改归属；ROOT = 移回根节点 */
    @Operation(summary = "移动设备到目标分组（ROOT=移回根节点），无需先解绑")
    @PostMapping("/move")
    public Result<Void> move(@RequestBody GroupMoveRequest request, HttpServletRequest httpRequest) {
        String error = groupService.move(request.getNodeRef(), request.getOltId(), operator(httpRequest));
        return error == null ? Result.ok() : Result.fail(error);
    }

    /** 通用归属：光猫等设备模块接入时调用；groupRef 空/ROOT=根节点（不写归属关系） */
    @Operation(summary = "通用归属落库（光猫等设备模块接入用）：memberType=OLT/ONU，memberRef=设备业务标识")
    @PostMapping("/attach")
    public Result<Void> attach(@RequestBody GroupAttachRequest request) {
        String error = groupService.attach(request.getMemberType(), request.getMemberRef(),
                request.getMemberLabel(), request.getGroupRef());
        return error == null ? Result.ok() : Result.fail(error);
    }

    /** 通用移动：光猫等设备模块接入时调用；ROOT = 移回根节点 */
    @Operation(summary = "通用移动（光猫等设备模块接入用）：memberType=OLT/ONU，memberRef=设备业务标识")
    @PostMapping("/move-member")
    public Result<Void> moveMember(@RequestBody GroupAttachRequest request, HttpServletRequest httpRequest) {
        String error = groupService.moveMember(request.getGroupRef(), request.getMemberType(),
                request.getMemberRef(), operator(httpRequest));
        return error == null ? Result.ok() : Result.fail(error);
    }

    private static Long parseGroupId(String id) {
        try {
            return id == null ? null : Long.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String operator(HttpServletRequest request) {
        var user = UserContext.get();
        return user == null ? null : user.getUsername();
    }
}
