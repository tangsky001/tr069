/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.487
 *
 */

package com.PilotACS.service;

import com.PilotACS.common.constant.AuthConstant;
import com.PilotACS.common.log.Logs;
import com.PilotACS.common.constant.PermissionGroups;
import com.PilotACS.common.enums.ClientType;
import com.PilotACS.common.exception.BusinessException;
import com.PilotACS.common.security.LoginUser;
import com.PilotACS.common.security.PasswordUtil;
import com.PilotACS.common.security.UserContext;
import com.PilotACS.dto.UserCreateDTO;
import com.PilotACS.dto.UserUpdateDTO;
import com.PilotACS.entity.SysRole;
import com.PilotACS.entity.SysUser;
import com.PilotACS.mapper.SysRoleMapper;
import com.PilotACS.mapper.SysUserMapper;
import com.PilotACS.service.SysOperLogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 用户管理服务
@Service
@RequiredArgsConstructor
public class SysUserService extends ServiceImpl<SysUserMapper, SysUser> {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SysRoleMapper roleMapper;
    private final SysOperLogService operLogService;

    // 用户列表，字段与前端约定对齐
    public List<Map<String, Object>> listUsers() {
        LoginUser operator = UserContext.get();
        boolean opSuper = operator != null && operator.isSuperAdmin();
        List<Map<String, Object>> list = new ArrayList<>();
        for (SysUser u : this.lambdaQuery().orderByAsc(SysUser::getId).list()) {
            boolean superAdmin = isSuperAdmin(u);
            boolean itms = AuthConstant.ITMS_USERNAME.equals(u.getUsername());
            // ITMS 账号仅超级管理员可编辑
            boolean editable = !superAdmin && (!itms || opSuper);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", u.getId());
            row.put("username", u.getUsername());
            row.put("nickname", u.getNickname());
            row.put("role", u.getRoleCode());
            row.put("status", u.getStatus());
            row.put("lastLoginAtText", u.getLastLoginTime() == null ? "-" : FMT.format(u.getLastLoginTime()));
            row.put("lastLoginIp", u.getLastLoginIp());
            row.put("lastLoginSource", u.getLastLoginSource());
            row.put("lastLoginSourceText", u.getLastLoginSource() == null ? null : ClientType.of(u.getLastLoginSource()).getLabel());
            row.put("canChangeRole", editable);
            row.put("canToggleStatus", editable);
            row.put("canDelete", editable);
            if (superAdmin) {
                row.put("deleteDisabledReason", "超级管理员禁止删除");
            } else if (itms) {
                row.put("deleteDisabledReason", "ITMS 对接账号禁止删除");
            } else {
                row.put("deleteDisabledReason", null);
            }
            list.add(row);
        }
        return list;
    }

    @Transactional
    public void createUser(UserCreateDTO dto) {
        if (!dto.getUsername().matches("[a-zA-Z0-9_]{3,32}")) {
            throw new BusinessException("登录名须为 3-32 位字母、数字或下划线");
        }
        if (AuthConstant.ITMS_USERNAME.equalsIgnoreCase(dto.getUsername())) {
            // 系统保留账号
            throw new BusinessException("该用户名为系统保留");
        }
        if (this.lambdaQuery().eq(SysUser::getUsername, dto.getUsername()).count() > 0) {
            throw new BusinessException("用户名已存在");
        }
        checkRoleAssignable(dto.getRole());
        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setNickname(dto.getNickname() == null || dto.getNickname().isBlank() ? dto.getUsername() : dto.getNickname());
        user.setRoleCode(dto.getRole());
        user.setStatus(1);
        String salt = PasswordUtil.randomSalt();
        user.setSalt(salt);
        user.setPassword(PasswordUtil.hash(dto.getPassword(), salt));
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        this.save(user);
        operLogService.log("用户", "新建用户", dto.getUsername() + "，角色=" + dto.getRole());
        Logs.info(Logs.Module.USER, "新建用户", "用户", dto.getUsername(), "角色", dto.getRole());
    }

    // 编辑：role / status / password 任选其一组合
    @Transactional
    public void updateUser(Long id, UserUpdateDTO dto) {
        SysUser target = getById(id);
        if (target == null) {
            throw new BusinessException("用户不存在");
        }
        LoginUser operator = UserContext.get();
        boolean targetSuper = isSuperAdmin(target);
        if (targetSuper && (operator == null || operator.getUserId() != id)) {
            // 超级管理员仅允许本人改密码
            throw new BusinessException("超级管理员禁止编辑");
        }
        if (AuthConstant.ITMS_USERNAME.equals(target.getUsername())
                && (operator == null || !operator.isSuperAdmin())) {
            // ITMS 对接账号仅超级管理员可编辑
            throw new BusinessException("仅超级管理员可以编辑 ITMS 账号");
        }
        if (dto.getRole() != null) {
            if (targetSuper) {
                throw new BusinessException("超级管理员禁止变更角色");
            }
            checkRoleAssignable(dto.getRole());
            SysUser upd = new SysUser();
            upd.setId(id);
            upd.setRoleCode(dto.getRole());
            upd.setUpdatedAt(LocalDateTime.now());
            this.updateById(upd);
            operLogService.log("用户", "变更角色", target.getUsername() + " → " + dto.getRole());
            Logs.info(Logs.Module.USER, "变更角色", "用户", target.getUsername(), "新角色", dto.getRole());
        }
        if (dto.getStatus() != null) {
            if (targetSuper) {
                throw new BusinessException("超级管理员禁止禁用");
            }
            SysUser upd = new SysUser();
            upd.setId(id);
            upd.setStatus(dto.getStatus());
            upd.setUpdatedAt(LocalDateTime.now());
            this.updateById(upd);
            operLogService.log("用户", "变更状态", target.getUsername() + " → " + (dto.getStatus() == 1 ? "启用" : "禁用"));
            Logs.info(Logs.Module.USER, "变更状态", "用户", target.getUsername(), "新状态", dto.getStatus() == 1 ? "启用" : "禁用");
        }
        if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
            if (dto.getPassword().length() < 6) {
                throw new BusinessException("密码至少 6 位");
            }
            // 需验证操作者本人密码
            if (dto.getCurrentPassword() == null || dto.getCurrentPassword().isBlank()) {
                throw new BusinessException("请输入当前密码");
            }
            LoginUser op = operator;
            SysUser opUser = getById(op == null ? id : op.getUserId());
            if (opUser == null || !PasswordUtil.verify(dto.getCurrentPassword(), opUser.getSalt(), opUser.getPassword())) {
                throw new BusinessException("当前密码错误");
            }
            SysUser upd = new SysUser();
            upd.setId(id);
            String salt = PasswordUtil.randomSalt();
            upd.setSalt(salt);
            upd.setPassword(PasswordUtil.hash(dto.getPassword(), salt));
            upd.setUpdatedAt(LocalDateTime.now());
            this.updateById(upd);
            operLogService.log("用户", "修改密码", "账号=" + target.getUsername());
            Logs.info(Logs.Module.USER, "修改密码", "用户", target.getUsername());
        }
    }

    @Transactional
    public void deleteUser(Long id) {
        SysUser target = getById(id);
        if (target == null) {
            throw new BusinessException("用户不存在");
        }
        if (isSuperAdmin(target)) {
            // 超级管理员禁止删除
            throw new BusinessException("超级管理员禁止删除");
        }
        if (AuthConstant.ITMS_USERNAME.equals(target.getUsername())) {
            // ITMS 对接账号禁止删除
            throw new BusinessException("ITMS 对接账号禁止删除");
        }
        this.removeById(id);
        operLogService.log("用户", "删除用户", target.getUsername());
        Logs.info(Logs.Module.USER, "删除用户", "用户", target.getUsername());
    }

    public boolean isSuperAdmin(SysUser user) {
        return user.getId() == AuthConstant.SUPER_ADMIN_ID
                || AuthConstant.SUPER_ADMIN_CODE.equals(user.getRoleCode());
    }

    // 只允许非内置角色
    private void checkRoleAssignable(String code) {
        SysRole role = roleMapper.selectById(code);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        if (role.getBuiltin() != null && role.getBuiltin() == 1) {
            throw new BusinessException("内置角色禁止分配");
        }
        PermissionGroups.allCodes();
    }
}
