package com.acsr.acs.service;

import com.acsr.acs.common.BizException;
import com.acsr.acs.mapper.SysUserMapper;
import com.acsr.acs.model.entity.SysUser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 管理端用户与角色（MySQL）。 */
@Slf4j
@Service
public class UserService {

  private final SysUserMapper userMapper;
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwordEncoder;
  private final com.acsr.acs.config.AcsProperties properties;

  public UserService(SysUserMapper userMapper,
                     @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                     PasswordEncoder passwordEncoder,
                     com.acsr.acs.config.AcsProperties properties) {
    this.userMapper = userMapper;
    this.jdbc = jdbc;
    this.passwordEncoder = passwordEncoder;
    this.properties = properties;
  }

  public SysUser findByUsername(String username) {
    return userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
        .eq(SysUser::getUsername, username));
  }

  /** 登录校验。 */
  public SysUser login(String username, String password) {
    SysUser user = findByUsername(username);
    if (user == null) {
      throw new BizException("用户名或密码错误");
    }
    if (user.getStatus() == null || user.getStatus() != 1) {
      throw new BizException("账号已停用");
    }
    if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
      throw new BizException("用户名或密码错误");
    }
    return user;
  }

  public List<String> rolesOf(Long userId) {
    return jdbc.queryForList(
        "SELECT r.role_code FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id WHERE ur.user_id = ?",
        String.class, userId);
  }

  public List<SysUser> list() {
    return userMapper.selectList(new LambdaQueryWrapper<SysUser>().orderByAsc(SysUser::getId));
  }

  public SysUser create(String username, String password, String displayName) {
    if (findByUsername(username) != null) {
      throw new BizException("用户名已存在");
    }
    SysUser user = new SysUser();
    user.setUsername(username);
    user.setPasswordHash(passwordEncoder.encode(password));
    user.setDisplayName(displayName);
    user.setStatus(1);
    user.setCreateTime(LocalDateTime.now());
    userMapper.insert(user);
    Long adminRole = roleId("ADMIN");
    if (adminRole != null) {
      jdbc.update("INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES (?,?)", user.getId(), adminRole);
    }
    return user;
  }

  public void changePassword(Long userId, String newPassword) {
    jdbc.update("UPDATE sys_user SET password_hash=? WHERE id=?",
        passwordEncoder.encode(newPassword), userId);
  }

  public void updateStatus(Long userId, Integer status) {
    jdbc.update("UPDATE sys_user SET status=? WHERE id=?", status, userId);
  }

  public void delete(Long userId) {
    jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", userId);
    userMapper.deleteById(userId);
  }

  private Long roleId(String code) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT id FROM sys_role WHERE role_code = ?", code);
    return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
  }

  /** 首次启动创建默认管理员。 */
  public void initDefaultAdmin() {
    Long count = userMapper.selectCount(new LambdaQueryWrapper<>());
    if (count != null && count > 0) {
      return;
    }
    String username = properties.getDefaultAdminUsername();
    String password = properties.getDefaultAdminPassword();
    SysUser user = new SysUser();
    user.setUsername(username);
    user.setPasswordHash(passwordEncoder.encode(password));
    user.setDisplayName("系统管理员");
    user.setStatus(1);
    user.setCreateTime(LocalDateTime.now());
    userMapper.insert(user);
    Long adminRole = roleId("ADMIN");
    if (adminRole != null) {
      jdbc.update("INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES (?,?)", user.getId(), adminRole);
    }
    log.warn("已创建默认管理员账号: {} / {}", username, password);
  }
}
