/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.540
 *
 */

package com.PilotACS.acl.config;

import com.PilotACS.common.log.Logs;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** ACL 表自动补建 */
@Component
public class AclSchemaInitializer implements InitializingBean {

    /** %s = 表名前缀 */
    private static final String CREATE_ACL_SQL = """
            CREATE TABLE IF NOT EXISTS `%sres_acl` (
                `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
                `principal_id` BIGINT      NOT NULL COMMENT '授权对象：sys_user.id',
                `scope_type`   VARCHAR(10) NOT NULL COMMENT '范围类型：device / group',
                `scope_id`     VARCHAR(40) NOT NULL COMMENT '范围引用：设备为 olt_device.id；分组为 ROOT / G:id',
                `perms`        VARCHAR(64) NOT NULL DEFAULT 'view' COMMENT '授予动作，逗号分隔：view,operate,update,delete',
                `created_by`   VARCHAR(64) DEFAULT NULL COMMENT '创建人',
                `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (`id`),
                UNIQUE KEY `uk_principal_scope` (`principal_id`, `scope_type`, `scope_id`)
            ) ENGINE = InnoDB COMMENT '设备/分组级 ACL（细到每台设备的看与操作授权）'
            """;

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.acl.auto-create-table:true}")
    private boolean enabled;

    public AclSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            Logs.info(Logs.Module.SYSTEM, "ACL 表自动建表已关闭");
            return;
        }
        try {
            for (String prefix : new String[]{"olt_", "onu_"}) {
                jdbcTemplate.execute(String.format(CREATE_ACL_SQL, prefix));
            }
            Integer legacy = countTable("res_acl");
            if (legacy != null && legacy > 0) {
                Integer target = countTable("olt_res_acl");
                if (target != null && target == 0) {
                    jdbcTemplate.update("INSERT INTO `olt_res_acl` SELECT * FROM `res_acl`");
                    Logs.info(Logs.Module.SYSTEM, "旧 ACL 数据已迁至 olt_res_acl", "行数", legacy);
                }
            }
            Logs.info(Logs.Module.SYSTEM, "ACL 表已就绪", "表", "olt_res_acl, onu_res_acl");
        } catch (Exception e) {
            Logs.error(Logs.Module.SYSTEM, "ACL 表自动建表失败，请手工执行 sql/mysql.sql", e);
        }
    }

    private Integer countTable(String table) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables" +
                " WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, table);
        if (exists == null || exists == 0) {
            return null;
        }
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class);
    }
}
