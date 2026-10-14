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
 * 初始管理员账号兜底。
 *
 * <p>初始管理员本身由建库脚本写入（见 sql/schema.sql 末尾的种子账号），这里只是兜底：
 * 存量库执行过旧脚本时 sys_user 可能仍为空，启动时检查到没有账号才补一个。</p>
 *
 * <p>账号几乎一律以数据库为准：一旦库里已有账号这里就什么都不做，
 * 改这里的常量不会影响已有账号。口令这样写代码里而不是配置里，是因为它只在
 * 「一条数据都没有」的首次部署瞬间生效，可被配置文件改动的空间只会带来误解。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    /** 兜底账号的登录名，与 sql/schema.sql 里的种子账号一致。 */
    private static final String DEFAULT_USERNAME = "admin";

    /** 兜底账号的初始口令，首次登录后应在管理端立即修改。 */
    private static final String DEFAULT_PASSWORD = "admin";

    /** 兜底账号的显示名。 */
    private static final String DEFAULT_NICKNAME = "系统管理员";

    private final SysUserMapper sysUserMapper;
    private final PasswordHasher passwordHasher;

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
        user.setUsername(DEFAULT_USERNAME);
        user.setPassword(passwordHasher.hash(DEFAULT_PASSWORD));
        user.setNickname(DEFAULT_NICKNAME);
        user.setRole("admin");
        user.setStatus(1);
        sysUserMapper.insert(user);
        log.warn("sys_user 为空，已创建初始管理员 [{}]，请登录后立即修改密码", DEFAULT_USERNAME);
    }
}
