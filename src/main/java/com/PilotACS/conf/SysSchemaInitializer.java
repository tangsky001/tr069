/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.conf;

import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** sys_user 表结构守护 */
@Component
@RequiredArgsConstructor
public class SysSchemaInitializer implements InitializingBean {

    /** 密码列最小长度 */
    private static final int PASSWORD_MIN_LENGTH = 72;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void afterPropertiesSet() {
        try {
            Integer len = jdbcTemplate.queryForObject("""
                    SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'password'
                    """, Integer.class);
            if (len == null) {
                return;
            }
            if (len < PASSWORD_MIN_LENGTH) {
                jdbcTemplate.execute("""
                        ALTER TABLE `sys_user` MODIFY COLUMN `password` VARCHAR(100) NOT NULL
                            COMMENT 'pbkdf2$+64hex（透明升级）或 sha256(salt+password)'
                        """);
                Logs.info(Logs.Module.SYSTEM, "sys_user.password 列已扩容 CHAR(64) → VARCHAR(100)（PBKDF2 透明升级兼容）");
            }
        } catch (Exception e) {
            Logs.error(Logs.Module.SYSTEM, "sys_user 表结构升级失败，请手工执行："
                    + "ALTER TABLE sys_user MODIFY COLUMN password VARCHAR(100) NOT NULL", e);
        }
    }
}
