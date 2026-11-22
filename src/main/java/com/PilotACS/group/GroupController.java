/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.457
 *
 */

package com.PilotACS.group;

import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.RequiresPermission;
import com.PilotACS.common.security.UserContext;
import com.PilotACS.group.model.GroupAttachRequest;
import com.PilotACS.group.model.GroupCreateRequest;
import com.PilotACS.group.model.GroupDeleteRequest;
import com.PilotACS.group.model.GroupMemberView;
import com.PilotACS.group.model.GroupMoveRequest;
import com.PilotACS.group.model.GroupNodeView;
import com.PilotACS.group.model.GroupRenameRequest;
import com.PilotACS.group.model.ProvinceMapVO;
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

/** 分组管理接口。 */
@Tag(name = "分组管理", description = "区划骨架分组树（civilCode.csv）、自定义子分组（创建/重命名/删除）、组内设备台账")
@RestController
@RequestMapping("/group")
@RequiredArgsConstructor
@RequiresPermission("group:view")
public class GroupController {

    private final GroupService groupService;

    /** 分组树查询。 */
    @Operation(summary = "分组树：区划骨架（civilCode.csv，34 省逐级到区县）+ 自定义节点，节点带成员数量；memberType=OLT/ONU（默认 OLT）")
    @GetMapping("/tree")
    public Result<List<GroupNodeView>> tree(@RequestParam(value = "memberType", required = false) String memberType) {
        return Result.ok(groupService.tree(memberType));
    }

    /** 省份地图汇总。 */
    @Operation(summary = "省份地图汇总：根下省级节点的设备数与地图填充色（有设备的省高亮蓝+清单在前，无设备的省随机观感粉彩）；memberType=OLT/ONU（默认 OLT）")
    @GetMapping("/province-map")
    public Result<ProvinceMapVO> provinceMap(@RequestParam(value = "memberType", required = false) String memberType) {
        return Result.ok(groupService.provinceMap(memberType));
    }

    /** 建组候选查询。 */
    @Operation(summary = "建组候选：父节点下一级可选区划（根→省，省→市，市→区县；自定义节点无候选）；memberType=OLT/ONU（默认 OLT）")
    @GetMapping("/candidates")
    public Result<List<GroupNodeView>> candidates(@RequestParam(value = "parentRef", required = false) String parentRef,
                                                  @RequestParam(value = "memberType", required = false) String memberType) {
        return Result.ok(groupService.candidates(parentRef == null || parentRef.isBlank() ? "ROOT" : parentRef, memberType));
    }

    /** 创建分组。 */
    @Operation(summary = "创建分组：父节点下落库；选区划（与父节点层级衔接）或自定义名称；memberType=OLT/ONU（默认 OLT）")
    @PostMapping("/create")
    @RequiresPermission("group:update")
    public Result<Void> create(@RequestBody GroupCreateRequest request, HttpServletRequest httpRequest) {
        String error = groupService.create(request.getParentRef(), request.getCivilCode(),
                request.getName(), operator(httpRequest), request.getMemberType());
        return error == null ? Result.ok() : Result.fail(error);
    }

    /** 重命名自定义分组 */
    @Operation(summary = "重命名自定义分组（区划节点不可改名）；memberType=OLT/ONU（默认 OLT）")
    @PostMapping("/rename")
    @RequiresPermission("group:update")
    public Result<Void> rename(@RequestBody GroupRenameRequest request) {
        Long id = parseGroupId(request.getId());
        String error = id == null ? "分组不存在" : groupService.rename(id, request.getName(), request.getMemberType());
        return error == null ? Result.ok() : Result.fail(error);
    }

    /** 删除自定义分组。 */
    @Operation(summary = "删除自定义分组：有子分组或绑定成员时拒绝；区划节点不可删除；memberType=OLT/ONU（默认 OLT）")
    @PostMapping("/delete")
    @RequiresPermission("group:update")
    public Result<Void> delete(@RequestBody GroupDeleteRequest request) {
        String error = groupService.delete(request.getId(), request.getMemberType());
        return error == null ? Result.ok() : Result.fail(error);
    }

    /** 组内成员查询。 */
    @Operation(summary = "组内成员：某节点的设备台账与在线状态，按绑定时间倒序；memberType=OLT/ONU（默认 OLT）")
    @GetMapping("/members")
    public Result<List<GroupMemberView>> members(@RequestParam("nodeRef") String nodeRef,
                                                 @RequestParam(value = "memberType", required = false) String memberType) {
        return Result.ok(groupService.members(nodeRef, memberType));
    }


    /** 移动设备到目标分组。 */
    @Operation(summary = "移动设备到目标分组（ROOT=移回根节点），无需先解绑")
    @PostMapping("/move")
    @RequiresPermission("group:update")
    public Result<Void> move(@RequestBody GroupMoveRequest request, HttpServletRequest httpRequest) {
        String error = groupService.move(request.getNodeRef(), request.getOltId(), operator(httpRequest));
        return error == null ? Result.ok() : Result.fail(error);
    }

    /** 通用归属落库。 */
    @Operation(summary = "通用归属落库（光猫等设备模块接入用）：memberType=OLT/ONU，memberRef=设备业务标识")
    @PostMapping("/attach")
    @RequiresPermission("group:update")
    public Result<Void> attach(@RequestBody GroupAttachRequest request) {
        String error = groupService.attach(request.getMemberType(), request.getMemberRef(),
                request.getMemberLabel(), request.getGroupRef());
        return error == null ? Result.ok() : Result.fail(error);
    }

    /** 通用移动。 */
    @Operation(summary = "通用移动（光猫等设备模块接入用）：memberType=OLT/ONU，memberRef=设备业务标识")
    @PostMapping("/move-member")
    @RequiresPermission("group:update")
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
