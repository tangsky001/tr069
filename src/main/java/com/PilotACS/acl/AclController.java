/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.127
 *
 */

package com.PilotACS.acl;

import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.RequiresPermission;
import com.PilotACS.entity.SysUser;
import com.PilotACS.common.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.PilotACS.mapper.SysUserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 设备/分组级 ACL 管理：细到每台设备的看/操作/编辑/删除授权。 */
@Tag(name = "设备级 ACL", description = "按用户授予某分组/某台设备的查看与操作权限")
@RestController
@RequestMapping("/acl")
@RequiredArgsConstructor
@RequiresPermission("acl:manage")
public class AclController {

    private final DeviceAclService aclService;
    private final SysUserMapper userMapper;

    /** 可授权的用户清单 */
    @Operation(summary = "用户清单（授权下拉用）")
    @GetMapping("/users")
    public Result<List<SysUser>> users() {
        return Result.ok(userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .orderByAsc(SysUser::getId)));
    }

    /** 某范围（分组/设备）的授权清单 */
    @Operation(summary = "某范围的授权清单；memberType=OLT/ONU（默认 OLT）")
    @GetMapping("/list")
    public Result<List<ResAcl>> list(@RequestParam String scopeType, @RequestParam String scopeId,
                                     @RequestParam(value = "memberType", required = false) String memberType) {
        return Result.ok(aclService.listByScope(scopeType, scopeId, memberType));
    }

    /** 授权（同主体+范围覆盖更新） */
    @Operation(summary = "授权：给用户授予某分组/设备的动作；memberType=OLT/ONU（默认 OLT）")
    @PostMapping("/grant")
    public Result<Void> grant(@RequestBody AclGrantRequest request,
                              @RequestParam(value = "memberType", required = false) String memberType) {
        String operator = UserContext.get() == null ? null : UserContext.get().getUsername();
        String error = aclService.grant(request.getPrincipalId(), request.getScopeType(),
                request.getScopeId(), request.getPerms(), operator, memberType);
        return error == null ? Result.ok() : Result.fail(error);
    }

    /** 撤销授权 */
    @Operation(summary = "撤销授权；memberType=OLT/ONU（默认 OLT）")
    @PostMapping("/revoke")
    public Result<Void> revoke(@RequestBody AclRevokeRequest request,
                               @RequestParam(value = "memberType", required = false) String memberType) {
        String error = aclService.revoke(request.getId(), memberType);
        return error == null ? Result.ok() : Result.fail(error);
    }
}
