package com.tr069.acs.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.api.auth.PermissionCodes;
import com.tr069.acs.domain.entity.SysUser;
import com.tr069.acs.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 管理端用户的增删改查。
 *
 * <p>密码一律经 {@link PasswordHasher} 落库；历史明文口令在登录成功时自动升级成散列，
 * 所以这里只管新用户，不管老数据。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    /**
     * 初始的系统账号：建库脚本写进去的那个，也与 AdminAccountInitializer 的兜底值一致。
     *
     * <p>它删不得——这是库里唯一的种子账号，删完没人能进系统。应用层挡一道，
     * 数据库触发器再挡一道。</p>
     */
    public static final String INITIAL_ADMIN = "admin";

    private final SysUserMapper userMapper;

    private final RoleService roleService;

    private final PasswordHasher passwordHasher;

    /** 用户列表，带上最近登录来源。 */
    public List<Map<String, Object>> list() {
        List<SysUser> users = userMapper.selectList(
                new LambdaQueryWrapper<SysUser>().orderByAsc(SysUser::getId));
        List<Map<String, Object>> data = new ArrayList<>();
        for (SysUser user : users) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", user.getId());
            row.put("username", user.getUsername());
            row.put("nickname", user.getNickname());
            row.put("role", user.getRole());
            row.put("status", user.getStatus());
            row.put("lastLoginAt", user.getLastLoginAt());
            // 没登过的用户来源为空，页面显示「-」
            row.put("lastLoginSource", user.getLastLoginSource());
            row.put("lastLoginIp", user.getLastLoginIp());
            // 页面靠它把删除按钮按死，别让人点了才知道删不掉
            row.put("builtin", INITIAL_ADMIN.equalsIgnoreCase(user.getUsername()));
            data.add(row);
        }
        return data;
    }

    /** 建用户。角色必须是库里有的角色。 */
    public void create(String username, String password, String nickname, String role) {
        String name = normalizeName(username);
        if (name == null) {
            throw new IllegalStateException("用户名只能是字母、数字与下划线，4-32 位");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalStateException("密码至少 6 位");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, name)) > 0) {
            throw new IllegalStateException("用户名已存在: " + name);
        }
        if (!roleService.exists(role)) {
            throw new IllegalStateException("角色不存在: " + role);
        }
        SysUser user = new SysUser();
        user.setUsername(name);
        user.setPassword(passwordHasher.hash(password));
        user.setNickname(nickname == null || nickname.isBlank() ? name : nickname.trim());
        user.setRole(role);
        user.setStatus(1);
        userMapper.insert(user);
        log.info("新建用户: username={}, role={}", name, role);
    }

    /**
     * 改用户：昵称、角色、状态、密码，传 null 的项不动。
     *
     * <p>改密码要二次验证：除了新密码，还得拿操作者<b>自己</b>的密码再验一次。
     * 拿到会话不等于拿到密码——会话被借走的人改不了密码，改不了就等于拿不走账号。</p>
     */
    public void update(Long id, String nickname, String role, Integer status,
                       String password, String currentPassword, String operator) {
        SysUser user = user(id);
        SysUser patch = new SysUser();
        patch.setId(id);
        if (nickname != null && !nickname.isBlank()) {
            patch.setNickname(nickname.trim());
        }
        if (role != null && !role.isBlank()) {
            if (!roleService.exists(role)) {
                throw new IllegalStateException("角色不存在: " + role);
            }
            // 不允许把最后一个系统管理员降下去：降完没人能改权限了
            if (!PermissionCodes.ROLE_SUPER_ADMIN.equals(role)
                    && PermissionCodes.ROLE_SUPER_ADMIN.equals(user.getRole())
                    && countSuperAdmin() <= 1) {
                throw new IllegalStateException("至少要留一个系统管理员");
            }
            patch.setRole(role);
        }
        if (status != null) {
            // 同理，不允许把最后一个系统管理员停用
            if (status == 0 && PermissionCodes.ROLE_SUPER_ADMIN.equals(user.getRole())
                    && countSuperAdmin() <= 1) {
                throw new IllegalStateException("至少要留一个系统管理员");
            }
            patch.setStatus(status);
        }
        if (password != null && !password.isBlank()) {
            if (password.length() < 6) {
                throw new IllegalStateException("密码至少 6 位");
            }
            if (currentPassword == null || currentPassword.isBlank()) {
                throw new IllegalStateException("改密码要先验证你自己的密码");
            }
            String stored = passwordOf(operator);
            if (stored == null || !passwordHasher.matches(currentPassword, stored)) {
                throw new IllegalStateException("你自己的密码不对，密码没改");
            }
            patch.setPassword(passwordHasher.hash(password));
        }
        userMapper.updateById(patch);
        log.info("修改用户: id={}, role={}, status={}", id, role, status);
    }

    /**
     * 删用户。三条底线，前两条都查库判断：
     *
     * <p>① 初始系统账号删不掉；② 系统管理员只剩一个时删不掉；
     * ③ 自己删不了自己。</p>
     */
    public void delete(Long id, String currentUsername) {
        SysUser user = user(id);
        if (INITIAL_ADMIN.equalsIgnoreCase(user.getUsername())) {
            throw new IllegalStateException("系统用户禁止删除");
        }
        if (user.getUsername() != null && user.getUsername().equals(currentUsername)) {
            throw new IllegalStateException("不能删自己");
        }
        if (PermissionCodes.ROLE_SUPER_ADMIN.equals(user.getRole()) && countSuperAdmin() <= 1) {
            throw new IllegalStateException("至少要留一个系统管理员");
        }
        userMapper.deleteById(id);
        log.info("删除用户: id={}, username={}", id, user.getUsername());
    }

    /** 取操作者自己的口令，取不到就当没有——验证时一律不通过。 */
    private String passwordOf(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .last("LIMIT 1"));
        return user == null ? null : user.getPassword();
    }

    private long countSuperAdmin() {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, PermissionCodes.ROLE_SUPER_ADMIN));
        return count == null ? 0 : count;
    }

    private SysUser user(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new IllegalStateException("用户不存在: " + id);
        }
        return user;
    }

    private static String normalizeName(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return v.matches("[a-z0-9_]{4,32}") ? v : null;
    }
}
