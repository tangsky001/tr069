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

/**
 * 分组两张表自动补建与结构迁移。
 * <p>DDL 与 sql/mysql.sql 保持一致；分组树骨架来自 civilCode.csv（不落库），
 * 自定义节点在 res_group 表，绑定关系 res_group_member 用 group_ref 统一引用
 * 区划节点（C:xx）与自定义节点（G:xx）。</p>
 */
@Component
public class GroupSchemaInitializer implements InitializingBean {

    private static final String CREATE_GROUP_SQL = """
            CREATE TABLE IF NOT EXISTS `res_group` (
                `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                `parent_id`         BIGINT       DEFAULT NULL COMMENT '父自定义分组 id；挂在区划下时为 null',
                `parent_civil_code` VARCHAR(12)  DEFAULT NULL COMMENT '父区划编号（civilCode）；挂在自定义分组下时为 null',
                `name`              VARCHAR(64)  NOT NULL COMMENT '分组名称',
                `sort`              INT          DEFAULT 0 COMMENT '排序号，同级从小到大',
                `remark`            VARCHAR(255) DEFAULT NULL COMMENT '备注',
                `created_by`        VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
                `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (`id`),
                KEY `idx_parent` (`parent_id`),
                KEY `idx_parent_civil` (`parent_civil_code`)
            ) ENGINE = InnoDB COMMENT '自定义分组（挂在区划树上）'
            """;

    private static final String CREATE_MEMBER_SQL = """
            CREATE TABLE IF NOT EXISTS `res_group_member` (
                `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                `group_ref`   VARCHAR(32)  NOT NULL COMMENT '分组节点引用：C:区划编号 或 G:自定义分组 id',
                `member_type` VARCHAR(16)  NOT NULL DEFAULT 'OLT' COMMENT '成员类型：OLT（预留 DEVICE）',
                `member_id`   BIGINT       NOT NULL COMMENT '成员主键：OLT 为 olt_device.id',
                `member_label` VARCHAR(128) DEFAULT NULL COMMENT '绑定时的成员名称快照',
                `remark`      VARCHAR(255) DEFAULT NULL COMMENT '备注',
                `created_by`  VARCHAR(64)  DEFAULT NULL COMMENT '操作人',
                `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (`id`),
                UNIQUE KEY `uk_member` (`member_type`, `member_id`),
                KEY `idx_group_ref` (`group_ref`)
            ) ENGINE = InnoDB COMMENT '分组成员绑定关系'
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
            jdbcTemplate.execute(CREATE_GROUP_SQL);
            jdbcTemplate.execute(CREATE_MEMBER_SQL);
            migrateGroupTable();
            migrateMemberTable();
            Logs.info(Logs.Module.SYSTEM, "分组表已就绪", "表", "res_group, res_group_member");
        } catch (Exception e) {
            Logs.error(Logs.Module.SYSTEM, "分组表自动建表失败，请手工执行 sql/mysql.sql", e);
        }
    }

    /** res_group 迁移：补 parent_civil_code 列（挂在区划下的自定义节点） */
    private void migrateGroupTable() {
        if (!columnExists("res_group", "parent_civil_code")) {
            jdbcTemplate.execute("ALTER TABLE `res_group` ADD COLUMN `parent_civil_code` VARCHAR(12) DEFAULT NULL COMMENT '父区划编号（civilCode）；挂在自定义分组下时为 null'");
            Logs.info(Logs.Module.SYSTEM, "分组表结构补齐", "表", "res_group", "列", "parent_civil_code");
        }
    }

    /** res_group_member 迁移：老结构（group_code/group_id）统一改为 group_ref 节点引用 */
    private void migrateMemberTable() {
        boolean hasGroupRef = columnExists("res_group_member", "group_ref");
        if (!hasGroupRef) {
            jdbcTemplate.execute("ALTER TABLE `res_group_member` ADD COLUMN `group_ref` VARCHAR(32) DEFAULT NULL COMMENT '分组节点引用：C:区划编号 或 G:自定义分组 id'");
        }
        // 老结构的唯一键允许同一设备挂多组，改成按设备唯一
        dropIndexIfExists("res_group_member", "uk_group_member");
        addIndexIfMissing("res_group_member", "uk_member",
                "UNIQUE KEY `uk_member` (`member_type`, `member_id`)");
        if (!hasGroupRef) {
            Logs.info(Logs.Module.SYSTEM, "分组绑定表已切换为 group_ref 节点引用");
        }
    }

    private void dropIndexIfExists(String table, String index) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics" +
                " WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, index);
        if (count != null && count > 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` DROP INDEX `" + index + "`");
        }
    }

    private void addIndexIfMissing(String table, String index, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics" +
                " WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, index);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD " + definition);
        }
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns" +
                " WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return count != null && count > 0;
    }
}
