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

/**
 * 设备/分组级 ACL 管理：细到每台设备的看/操作/编辑/删除授权。
 * 仅 SUPER_ADMIN（代码级全权限）与持有 acl:manage 权限码的角色可操作。
 */
@Tag(name = "设备级 ACL", description = "按用户授予某分组/某台设备的查看与操作权限")
@RestController
@RequestMapping("/acl")
@RequiredArgsConstructor
@RequiresPermission("acl:manage")
public class AclController {

    private final DeviceAclService aclService;
    private final SysUserMapper userMapper;

    /** 可授权的用户清单（供授权下拉；不含密码字段，实体上有 @JsonProperty(WRITE_ONLY) 保护） */
    @Operation(summary = "用户清单（授权下拉用）")
    @GetMapping("/users")
    public Result<List<SysUser>> users() {
        return Result.ok(userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .orderByAsc(SysUser::getId)));
    }

    /** 某范围（分组/设备）的授权清单 */
    @Operation(summary = "某范围的授权清单")
    @GetMapping("/list")
    public Result<List<ResAcl>> list(@RequestParam String scopeType, @RequestParam String scopeId) {
        return Result.ok(aclService.listByScope(scopeType, scopeId));
    }

    /** 授权（同主体+范围覆盖更新） */
    @Operation(summary = "授权：给用户授予某分组/设备的动作")
    @PostMapping("/grant")
    public Result<Void> grant(@RequestBody AclGrantRequest request) {
        String operator = UserContext.get() == null ? null : UserContext.get().getUsername();
        String error = aclService.grant(request.getPrincipalId(), request.getScopeType(),
                request.getScopeId(), request.getPerms(), operator);
        return error == null ? Result.ok() : Result.fail(error);
    }

    /** 撤销授权 */
    @Operation(summary = "撤销授权")
    @PostMapping("/revoke")
    public Result<Void> revoke(@RequestBody AclRevokeRequest request) {
        String error = aclService.revoke(request.getId());
        return error == null ? Result.ok() : Result.fail(error);
    }
}
