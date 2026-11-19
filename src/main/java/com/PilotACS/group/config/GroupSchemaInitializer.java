/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.492
 *
 */

package com.PilotACS.group.config;

import com.PilotACS.common.log.Logs;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 分组表自动补建与数据迁移。 */
@Component
public class GroupSchemaInitializer implements InitializingBean {

    /** %s = 表名前缀（olt_ / onu_） */
    private static final String CREATE_GROUP_SQL = """
            CREATE TABLE IF NOT EXISTS `%sres_group` (
                `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                `parent_id`         BIGINT       DEFAULT NULL COMMENT '父自定义分组 id；挂在区划下时为 null',
                `parent_civil_code` VARCHAR(12)  DEFAULT NULL COMMENT '父区划编号（civilCode）；挂在自定义分组下时为 null',
                `civil_code`        VARCHAR(12)  DEFAULT NULL COMMENT '节点关联的区划编号（civilCode，可选）',
                `name`              VARCHAR(64)  NOT NULL COMMENT '分组名称',
                `sort`              INT          DEFAULT 0 COMMENT '排序号，同级从小到大',
                `remark`            VARCHAR(255) DEFAULT NULL COMMENT '备注',
                `created_by`        VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
                `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (`id`),
                KEY `idx_parent` (`parent_id`),
                KEY `idx_parent_civil` (`parent_civil_code`),
                KEY `idx_civil` (`civil_code`)
            ) ENGINE = InnoDB COMMENT '自定义分组（挂在区划树上）'
            """;

    private static final String CREATE_MEMBER_SQL = """
            CREATE TABLE IF NOT EXISTS `%sres_group_member` (
                `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                `group_ref`   VARCHAR(32)  NOT NULL COMMENT '分组节点引用：ROOT 或 G:自定义分组 id',
                `member_type` VARCHAR(16)  NOT NULL DEFAULT '%s' COMMENT '成员类型：本表只存该类型（OLT / ONU）',
                `member_id`   BIGINT       NOT NULL COMMENT '成员主键：OLT 为 olt_device.id；ONU 恒 0',
                `member_ref`  VARCHAR(64)  DEFAULT NULL COMMENT '成员业务标识：OLT 同 member_id 字符串；ONU 为光猫标识（如 SN）',
                `member_label` VARCHAR(128) DEFAULT NULL COMMENT '绑定时的成员名称快照',
                `remark`      VARCHAR(255) DEFAULT NULL COMMENT '备注',
                `created_by`  VARCHAR(64)  DEFAULT NULL COMMENT '操作人',
                `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (`id`),
                UNIQUE KEY `uk_member` (`member_ref`),
                KEY `idx_group_ref` (`group_ref`)
            ) ENGINE = InnoDB COMMENT '分组成员归属关系'
            """;

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.group.auto-create-table:true}")
    private boolean enabled;

    public GroupSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            Logs.info(Logs.Module.SYSTEM, "分组表自动建表已关闭");
            return;
        }
        try {
            for (String prefix : new String[]{"olt_", "onu_"}) {
                jdbcTemplate.execute(String.format(CREATE_GROUP_SQL, prefix));
                jdbcTemplate.execute(String.format(CREATE_MEMBER_SQL, prefix, "olt_".equals(prefix) ? "OLT" : "ONU"));
            }
            migrateFromLegacyTables();
            Logs.info(Logs.Module.SYSTEM, "分组表已就绪",
                    "表", "olt_res_group, olt_res_group_member, onu_res_group, onu_res_group_member");
        } catch (Exception e) {
            Logs.error(Logs.Module.SYSTEM, "分组表自动建表失败，请手工执行 sql/mysql.sql", e);
        }
    }


    private void migrateFromLegacyTables() {
        if (tableExists("res_group") && !tableExistsWithData("olt_res_group")) {
            jdbcTemplate.update("""
                    INSERT INTO `olt_res_group`
                        (`id`,`parent_id`,`parent_civil_code`,`civil_code`,`name`,`sort`,`remark`,`created_by`,`created_at`)
                    SELECT `id`,`parent_id`,`parent_civil_code`,`civil_code`,`name`,`sort`,`remark`,`created_by`,
                           IFNULL(`created_at`, CURRENT_TIMESTAMP)
                    FROM `res_group`""");
            Logs.info(Logs.Module.SYSTEM, "旧分组表数据已迁至 olt_res_group", "行数",
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `olt_res_group`", Integer.class));
        }
        if (tableExists("res_group_member")) {
            if (!tableExistsWithData("olt_res_group_member")) {
                jdbcTemplate.update("""
                        INSERT INTO `olt_res_group_member`
                            (`id`,`group_ref`,`member_type`,`member_id`,`member_ref`,`member_label`,`remark`,`created_by`,`created_at`)
                        SELECT `id`,`group_ref`,`member_type`,`member_id`,`member_ref`,`member_label`,`remark`,`created_by`,
                               IFNULL(`created_at`, CURRENT_TIMESTAMP)
                        FROM `res_group_member` WHERE `member_type` <> 'ONU'""");
                Logs.info(Logs.Module.SYSTEM, "旧归属表 OLT 数据已迁至 olt_res_group_member");
            }
            if (!tableExistsWithData("onu_res_group_member")) {
                jdbcTemplate.update("""
                        INSERT INTO `onu_res_group_member`
                            (`id`,`group_ref`,`member_type`,`member_id`,`member_ref`,`member_label`,`remark`,`created_by`,`created_at`)
                        SELECT `id`,`group_ref`,`member_type`,`member_id`,`member_ref`,`member_label`,`remark`,`created_by`,
                               IFNULL(`created_at`, CURRENT_TIMESTAMP)
                        FROM `res_group_member` WHERE `member_type` = 'ONU'""");
                Logs.info(Logs.Module.SYSTEM, "旧归属表光猫数据已迁至 onu_res_group_member");
            }
        }
    }

    private boolean tableExists(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables" +
                " WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, table);
        return count != null && count > 0;
    }

    private boolean tableExistsWithData(String table) {
        if (!tableExists(table)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class);
        return count != null && count > 0;
    }
}
