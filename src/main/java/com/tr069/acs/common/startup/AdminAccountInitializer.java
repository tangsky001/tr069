package com.tr069.acs.common.startup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.api.service.PasswordHasher;
import com.tr069.acs.domain.entity.SysUser;
import com.tr069.acs.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 初始管理员账号。
 *
 * <p>建表脚本只建表不插数据，首次部署时 sys_user 为空会导致无人能登录。
 * 这里在启动时检查：仅当表中没有任何账号时，才用 admin.default-user /
 * admin.default-password 写入一个管理员（口令以散列存储）。</p>
 *
 * <p>一旦库中已有账号，配置项就不再起作用，改配置不会影响已有账号，
 * 管理端账号一律以数据库为准。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private final SysUserMapper sysUserMapper;
    private final PasswordHasher passwordHasher;

    @Value("${admin.default-user:admin}")
    private String defaultUser;

    @Value("${admin.default-password:admin}")
    private String defaultPassword;

    @Value("${admin.default-nickname:系统管理员}")
    private String defaultNickname;

    @Override
    public void run(ApplicationArguments args) {
        long count;
        try {
            count = sysUserMapper.selectCount(new LambdaQueryWrapper<>());
        } catch (Exception e) {
            log.error("读取 sys_user 失败，跳过初始管理员检查：{}", e.getMessage());
            return;
        }
        if (count > 0) {
            return;
        }
        SysUser user = new SysUser();
        user.setUsername(defaultUser);
        user.setPassword(passwordHasher.hash(defaultPassword));
        user.setNickname(defaultNickname);
        user.setRole("admin");
        user.setStatus(1);
        sysUserMapper.insert(user);
        log.warn("sys_user 为空，已创建初始管理员 [{}]，请登录后立即修改密码", defaultUser);
    }
}
