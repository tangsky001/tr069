package com.PilotACS.group;

import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.UserContext;
import com.PilotACS.group.model.GroupBindRequest;
import com.PilotACS.group.model.GroupCreateRequest;
import com.PilotACS.group.model.GroupDeleteRequest;
import com.PilotACS.group.model.GroupMemberView;
import com.PilotACS.group.model.GroupNodeView;
import com.PilotACS.group.model.GroupOltOption;
import com.PilotACS.group.model.GroupRenameRequest;
import com.PilotACS.group.model.GroupUnbindRequest;
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
 * 自定义节点挂靠在区划或自定义父节点下；OLT 绑定关系在 res_group_member 表。
 */
@Tag(name = "分组管理", description = "区划骨架分组树（civilCode.csv）、自定义子分组（创建/重命名/删除）、OLT 绑定/解绑")
@RestController
@RequestMapping("/group")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    /** 分组树：区划骨架 + 自定义节点，节点带成员数量（直接数与含下级汇总数） */
    @Operation(summary = "分组树：区划骨架（civilCode.csv，34 省逐级到区县）+ 自定义节点，节点带成员数量")
    @GetMapping("/tree")
    public Result<List<GroupNodeView>> tree() {
        return Result.ok(groupService.tree());
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

    /** 组内成员：某节点的 OLT 实时台账与状态 */
    @Operation(summary = "组内成员：某节点的 OLT 台账与在线状态，按绑定时间倒序")
    @GetMapping("/members")
    public Result<List<GroupMemberView>> members(@RequestParam("nodeRef") String nodeRef) {
        return Result.ok(groupService.members(nodeRef));
    }

    /** 可绑定的 OLT 选项：绑定弹窗用，带各自当前绑定位置 */
    @Operation(summary = "可绑定的 OLT 选项：台账列表，keyword 匹配名称/地址，带当前绑定位置")
    @GetMapping("/bindable")
    public Result<List<GroupOltOption>> bindable(@RequestParam(value = "keyword", required = false) String keyword) {
        return Result.ok(groupService.bindable(keyword));
    }

    /** 绑定 OLT 到分组节点 */
    @Operation(summary = "绑定 OLT 到分组节点；一台 OLT 只挂一个节点，换节点先解绑")
    @PostMapping("/bind")
    public Result<Void> bind(@RequestBody GroupBindRequest request, HttpServletRequest httpRequest) {
        String error = groupService.bind(request.getNodeRef(), request.getOltId(), operator(httpRequest));
        return error == null ? Result.ok() : Result.fail(error);
    }

    /** 解绑成员 */
    @Operation(summary = "解绑成员：按绑定关系主键")
    @PostMapping("/unbind")
    public Result<Void> unbind(@RequestBody GroupUnbindRequest request) {
        String error = groupService.unbind(request.getId());
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
