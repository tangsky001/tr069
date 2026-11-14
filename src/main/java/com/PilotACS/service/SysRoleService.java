/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.143
 *
 */

package com.PilotACS.service;

import com.PilotACS.common.constant.AuthConstant;
import com.PilotACS.common.exception.BusinessException;
import com.PilotACS.common.security.LoginUser;
import com.PilotACS.common.security.UserContext;
import com.PilotACS.dto.RoleCreateDTO;
import com.PilotACS.entity.SysRole;
import com.PilotACS.entity.SysRolePermission;
import com.PilotACS.entity.SysUser;
import com.PilotACS.mapper.SysRoleMapper;
import com.PilotACS.mapper.SysRolePermissionMapper;
import com.PilotACS.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 角色管理服务
@Service
@RequiredArgsConstructor
public class SysRoleService extends ServiceImpl<SysRoleMapper, SysRole> {

    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserMapper userMapper;

    // 角色列表：code/name/userCount/builtin/permissions
    public List<Map<String, Object>> listRoles() {
        Map<String, Long> counts = userMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(u -> u.getRoleCode() == null ? "" : u.getRoleCode(), Collectors.counting()));
        Map<String, List<String>> perms = rolePermissionMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(SysRolePermission::getRoleCode,
                        Collectors.mapping(SysRolePermission::getPermCode, Collectors.toList())));
        List<Map<String, Object>> list = new ArrayList<>();
        for (SysRole r : this.lambdaQuery().orderByDesc(SysRole::getBuiltin).list()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", r.getCode());
            row.put("name", r.getName());
            row.put("userCount", counts.getOrDefault(r.getCode(), 0L).intValue());
            row.put("builtin", r.getBuiltin() != null && r.getBuiltin() == 1);
            row.put("permissions", perms.getOrDefault(r.getCode(), List.of()));
            list.add(row);
        }
        return list;
    }

    @Transactional
    public void createRole(RoleCreateDTO dto) {
        if (!dto.getCode().matches("[A-Z0-9_]{2,32}")) {
            throw new BusinessException("角色编码须为 2-32 位大写字母、数字或下划线");
        }
        if (AuthConstant.SUPER_ADMIN_CODE.equals(dto.getCode())) {
            throw new BusinessException("角色编码已存在");
        }
        if (this.getById(dto.getCode()) != null) {
            throw new BusinessException("角色编码已存在");
        }
        SysRole role = new SysRole();
        role.setCode(dto.getCode());
        role.setName(dto.getName());
        role.setBuiltin(0);
        role.setCreatedAt(LocalDateTime.now());
        role.setUpdatedAt(LocalDateTime.now());
        this.save(role);
    }

    // 覆盖式保存角色权限码
    @Transactional
    public void savePermissions(String code, List<String> permissions) {
        SysRole role = this.getById(code);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        if (role.getBuiltin() != null && role.getBuiltin() == 1) {
            // 超级管理员角色拥有全部权限，禁止修改
            throw new BusinessException("内置角色禁止修改权限");
        }
        LoginUser operator = UserContext.get();
        if (AuthConstant.ITMS_ROLE_CODE.equals(code) && (operator == null || !operator.isSuperAdmin())) {
            // ITMS 角色权限仅超级管理员可编辑
            throw new BusinessException("仅超级管理员可以编辑 ITMS 权限");
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleCode, code));
        if (permissions != null) {
            for (String code0 : permissions) {
                SysRolePermission rp = new SysRolePermission();
                rp.setRoleCode(code);
                rp.setPermCode(code0);
                rolePermissionMapper.insert(rp);
            }
        }
    }

    @Transactional
    public void deleteRole(String code) {
        SysRole role = this.getById(code);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        if (role.getBuiltin() != null && role.getBuiltin() == 1) {
            throw new BusinessException("内置角色禁止删除");
        }
        if (AuthConstant.ITMS_ROLE_CODE.equals(code)) {
            throw new BusinessException("ITMS 角色禁止删除");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRoleCode, code)) > 0) {
            throw new BusinessException("存在使用该角色的用户，禁止删除");
        }
        this.removeById(code);
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleCode, code));
    }
}
