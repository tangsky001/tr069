package com.tr069.acs.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tr069.acs.api.auth.PermissionCodes;
import com.tr069.acs.domain.entity.SysRole;
import com.tr069.acs.domain.entity.SysRolePermission;
import com.tr069.acs.domain.entity.SysUser;
import com.tr069.acs.mapper.SysRoleMapper;
import com.tr069.acs.mapper.SysRolePermissionMapper;
import com.tr069.acs.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色与权限。
 *
 * <p>系统管理员（{@value PermissionCodes#ROLE_SUPER_ADMIN}）是特例：它天生全权限，
 * 权限表里<b>没有</b>它的行——存了反而要操心"表里那行和代码里那句不一致时谁说了算"。
 * 所以判定它在 {@link #has} 里直接短路，改它的权限直接拒。</p>
 *
 * <p>权限点本身不落库（见 {@link PermissionCodes}），库里只存「哪个角色勾了哪些」。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    /** 运维管理员的默认权限：能看全部，能改设备、工单、文件、标签、OLT，不能动系统管理。 */
    private static final List<String> OPS_DEFAULT = List.of(
            "dashboard:view",
            "device:view", "device:manage",
            "template:view",
            "biz:view",
            "model:view",
            "worklist:view", "worklist:manage",
            "olt:view", "olt:manage",
            "file:view", "file:manage",
            "tag:view", "tag:manage");

    private final SysRoleMapper roleMapper;

    private final SysRolePermissionMapper permissionMapper;

    private final SysUserMapper userMapper;

    /** 角色权限缓存：每次请求都要判，不能每条都查库；改权限时清掉。 */
    private final Map<String, List<String>> cache = new ConcurrentHashMap<>();

    private volatile boolean defaultsReady = false;

    /** 角色列表，带权限点与每个角色下的用户数。 */
    public List<Map<String, Object>> list() {
        ensureDefaults();
        List<SysRole> roles = roleMapper.selectList(
                new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId));
        List<Map<String, Object>> data = new ArrayList<>();
        for (SysRole role : roles) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", role.getCode());
            row.put("name", role.getName());
            row.put("builtin", isBuiltin(role));
            row.put("remark", role.getRemark());
            row.put("permissions", permissionsOf(role.getCode()));
            row.put("userCount", userMapper.selectCount(
                    new LambdaQueryWrapper<SysUser>().eq(SysUser::getRole, role.getCode())));
            data.add(row);
        }
        return data;
    }

    /** 这个角色勾了哪些权限。系统管理员直接给全份，不查库。 */
    public List<String> permissionsOf(String roleCode) {
        // 先保证两个默认角色与历史角色值都落定，再谈权限
        ensureDefaults();
        if (roleCode == null || roleCode.isBlank()) {
            return List.of();
        }
        if (PermissionCodes.ROLE_SUPER_ADMIN.equals(roleCode)) {
            return PermissionCodes.all();
        }
        List<String> cached = cache.get(roleCode);
        if (cached != null) {
            return cached;
        }
        List<String> codes = permissionMapper.selectList(
                        new LambdaQueryWrapper<SysRolePermission>()
                                .eq(SysRolePermission::getRoleCode, roleCode))
                .stream()
                .map(SysRolePermission::getPermission)
                .filter(PermissionCodes::known)
                .distinct()
                .toList();
        cache.put(roleCode, codes);
        return codes;
    }

    /** 这个角色有没有这个权限。系统管理员恒为真。 */
    public boolean has(String roleCode, String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        if (PermissionCodes.ROLE_SUPER_ADMIN.equals(roleCode)) {
            return true;
        }
        return permissionsOf(roleCode).contains(permission);
    }

    /** 建角色。内置同名角色不允许重建。 */
    public void create(String code, String name, String remark) {
        String key = normalizeCode(code);
        if (key == null) {
            throw new IllegalStateException("角色编码只能是字母、数字与下划线");
        }
        if (roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getCode, key)) != null) {
            throw new IllegalStateException("角色编码已存在: " + key);
        }
        SysRole role = new SysRole();
        role.setCode(key);
        role.setName(name == null || name.isBlank() ? key : name.trim());
        role.setRemark(remark);
        role.setBuiltin(0);
        roleMapper.insert(role);
        log.info("新建角色: code={}, name={}", key, role.getName());
    }

    /** 设置角色的权限，整份覆盖。 */
    public void setPermissions(String roleCode, Collection<String> permissions) {
        SysRole role = role(roleCode);
        if (isBuiltin(role)) {
            throw new IllegalStateException("系统管理员拥有全部权限，不允许修改");
        }
        // 清单外的权限点直接丢掉，不让库里出现一个没人判的死权限
        List<String> kept = permissions == null ? List.of() : permissions.stream()
                .filter(PermissionCodes::known)
                .distinct()
                .toList();
        permissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleCode, roleCode));
        for (String code : kept) {
            SysRolePermission row = new SysRolePermission();
            row.setRoleCode(roleCode);
            row.setPermission(code);
            permissionMapper.insert(row);
        }
        cache.remove(roleCode);
        log.info("设置角色权限: role={}, 权限数={}", roleCode, kept.size());
    }

    /** 删角色。内置角色与还有人的角色都删不掉。 */
    public void delete(String roleCode) {
        SysRole role = role(roleCode);
        if (isBuiltin(role)) {
            throw new IllegalStateException("系统管理员不允许删除");
        }
        long users = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getRole, roleCode));
        if (users > 0) {
            throw new IllegalStateException("还有 " + users + " 个人用着这个角色，先给他们换个角色");
        }
        permissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleCode, roleCode));
        roleMapper.deleteById(role.getId());
        cache.remove(roleCode);
        log.info("删除角色: code={}", roleCode);
    }

    /** 角色是否存在。 */
    public boolean exists(String roleCode) {
        return roleCode != null && !roleCode.isBlank()
                && roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getCode, roleCode)) > 0;
    }

    /**
     * 老版本的 role 是 admin / operator / viewer 三个字符串，逐个迁到角色编码上。
     *
     * <p>只认这三个老值，自建角色的编码不在里面，不会被误伤。迁移不做的话，
     * 老库里的 admin 账号登进来会因为角色不存在而一个权限都没有。</p>
     */
    private void migrateLegacyRoles() {
        SysUser toSuper = new SysUser();
        toSuper.setRole(PermissionCodes.ROLE_SUPER_ADMIN);
        userMapper.update(toSuper, new LambdaUpdateWrapper<SysUser>()
                .in(SysUser::getRole, List.of("admin", "ADMIN")));
        SysUser toOps = new SysUser();
        toOps.setRole(PermissionCodes.ROLE_OPS_ADMIN);
        userMapper.update(toOps, new LambdaUpdateWrapper<SysUser>()
                .in(SysUser::getRole, List.of("operator", "viewer")));
        userMapper.update(toOps, new LambdaUpdateWrapper<SysUser>().isNull(SysUser::getRole));
    }

    private SysRole role(String code) {
        SysRole role = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getCode, code));
        if (role == null) {
            throw new IllegalStateException("角色不存在: " + code);
        }
        return role;
    }

    private static boolean isBuiltin(SysRole role) {
        return role.getBuiltin() != null && role.getBuiltin() == 1;
    }

    private static String normalizeCode(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (!v.matches("[A-Z0-9_]{2,32}")) {
            return null;
        }
        return v;
    }

    /** 两个默认角色只在第一次用到时落库，避免启动期就访问库。 */
    private void ensureDefaults() {
        if (defaultsReady) {
            return;
        }
        synchronized (this) {
            if (defaultsReady) {
                return;
            }
            try {
                if (roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getCode, PermissionCodes.ROLE_SUPER_ADMIN)) == 0) {
                    SysRole superAdmin = new SysRole();
                    superAdmin.setCode(PermissionCodes.ROLE_SUPER_ADMIN);
                    superAdmin.setName("系统管理员");
                    superAdmin.setBuiltin(1);
                    superAdmin.setRemark("拥有全部权限，不可删除，权限不可修改");
                    roleMapper.insert(superAdmin);
                }
                if (!exists(PermissionCodes.ROLE_OPS_ADMIN)) {
                    SysRole ops = new SysRole();
                    ops.setCode(PermissionCodes.ROLE_OPS_ADMIN);
                    ops.setName("运维管理员");
                    ops.setBuiltin(0);
                    ops.setRemark("默认能看全部、能改设备与工单，权限可调整");
                    roleMapper.insert(ops);
                    for (String code : OPS_DEFAULT) {
                        SysRolePermission row = new SysRolePermission();
                        row.setRoleCode(PermissionCodes.ROLE_OPS_ADMIN);
                        row.setPermission(code);
                        permissionMapper.insert(row);
                    }
                    log.info("已初始化默认角色: 系统管理员 / 运维管理员");
                }
                migrateLegacyRoles();
                defaultsReady = true;
            } catch (Exception e) {
                log.warn("初始化默认角色失败: {}", e.getMessage());
            }
        }
    }
}
