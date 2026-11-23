/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.config;

import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** SIP 分机/队列表自动补建与预置数据 */
@Component
@RequiredArgsConstructor
public class SipSchemaInitializer implements InitializingBean {

    private final JdbcTemplate jdbc;

    private static final String EXTENSION_DDL = """
            CREATE TABLE IF NOT EXISTS `sys_sip_extension` (
                `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                `extension`    VARCHAR(32)  NOT NULL COMMENT '分机号',
                `password`     VARCHAR(64)  DEFAULT '' COMMENT 'SIP 注册密码',
                `display_name` VARCHAR(64)  DEFAULT NULL COMMENT '显示名（From display-name / 话机来电姓名）',
                `remark`       VARCHAR(128) DEFAULT NULL COMMENT '备注',
                `created_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                PRIMARY KEY (`id`),
                UNIQUE KEY `uk_sip_ext` (`extension`)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'SIP 分机配置'
            """;

    private static final String QUEUE_DDL = """
            CREATE TABLE IF NOT EXISTS `sys_sip_queue` (
                `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                `queue_no`   VARCHAR(8)   NOT NULL COMMENT '队列号（拨打的号码，如 0/1/9）',
                `queue_name` VARCHAR(64)  DEFAULT NULL COMMENT '队列名称',
                `targets`    VARCHAR(255) DEFAULT NULL COMMENT '有序目标分机，逗号分隔，忙音顺延下一目标',
                `members`    VARCHAR(2000) DEFAULT NULL COMMENT '允许呼叫的分机，逗号分隔，空=不限制',
                `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                PRIMARY KEY (`id`),
                KEY `idx_sip_queue_no` (`queue_no`)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'SIP 呼叫队列'
            """;

    private static final String RECORD_DDL = """
            CREATE TABLE IF NOT EXISTS `sys_sip_call_record` (
                `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                `caller`           VARCHAR(32)  NOT NULL COMMENT '主叫分机',
                `callee`           VARCHAR(32)  NOT NULL COMMENT '被叫分机',
                `caller_name`      VARCHAR(64)  DEFAULT NULL COMMENT '主叫显示名',
                `callee_name`      VARCHAR(64)  DEFAULT NULL COMMENT '被叫显示名',
                `started_at`       DATETIME     NOT NULL COMMENT '呼叫发起时间',
                `answered`         TINYINT      DEFAULT 0 COMMENT '是否接通',
                `duration_seconds` INT          DEFAULT 0 COMMENT '通话时长（秒）',
                `end_reason`       VARCHAR(128) DEFAULT NULL COMMENT '结束原因',
                `record_path`      VARCHAR(255) DEFAULT NULL COMMENT '录音文件相对路径',
                PRIMARY KEY (`id`),
                KEY `idx_sip_rec_caller` (`caller`),
                KEY `idx_sip_rec_callee` (`callee`),
                KEY `idx_sip_rec_started` (`started_at`)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'SIP 通话记录'
            """;

    @Override
    public void afterPropertiesSet() {
        try {
            jdbc.execute(EXTENSION_DDL);
            jdbc.execute(QUEUE_DDL);
            jdbc.execute(RECORD_DDL);
            // 存量表补列（CREATE IF NOT EXISTS 不会给旧表加列，重复执行报"列已存在"忽略即可）
            ensureColumn("ALTER TABLE `sys_sip_extension` "
                    + "ADD COLUMN `display_name` VARCHAR(64) DEFAULT NULL COMMENT '显示名（From display-name / 话机来电姓名）'");
            ensureColumn("ALTER TABLE `sys_sip_call_record` "
                    + "ADD COLUMN `caller_name` VARCHAR(64) DEFAULT NULL COMMENT '主叫显示名'");
            ensureColumn("ALTER TABLE `sys_sip_call_record` "
                    + "ADD COLUMN `callee_name` VARCHAR(64) DEFAULT NULL COMMENT '被叫显示名'");
            seedIfEmpty();
            Logs.info(Logs.Module.SIP, "SIP 分机/队列/通话记录表补建完成");
        } catch (Exception e) {
            Logs.error(Logs.Module.SIP, "SIP 表补建失败", "原因", e.getMessage());
        }
    }

    /** 存量表加列：列已存在等异常忽略（只记 INFO） */
    private void ensureColumn(String alterSql) {
        try {
            jdbc.execute(alterSql);
            Logs.info(Logs.Module.SIP, "SIP 表补列完成", "语句", alterSql);
        } catch (Exception e) {
            Logs.info(Logs.Module.SIP, "SIP 表补列跳过（列已存在或语句不适用）", "原因", e.getMessage());
        }
    }

    /** 首次建库预置默认分机与队列 */
    private void seedIfEmpty() {
        Integer extCount = jdbc.queryForObject("SELECT COUNT(*) FROM sys_sip_extension", Integer.class);
        if (extCount != null && extCount == 0) {
            String[][] exts = {
                    {"1111", "备用"}, {"2222", "备用"}, {"3333", "备用"},
                    {"5555", "备用"}, {"6666", "总台6666"}, {"7777", "备用"},
                    {"8888", "总台8888"}, {"9999", "机器人9999"}
            };
            for (String[] e : exts) {
                jdbc.update("INSERT INTO sys_sip_extension(extension, password, remark) VALUES (?, ?, ?)",
                        e[0], "12345678", e[1]);
            }
            Logs.info(Logs.Module.SIP, "已预置默认分机", "数量", exts.length);
        }
        Integer queueCount = jdbc.queryForObject("SELECT COUNT(*) FROM sys_sip_queue", Integer.class);
        if (queueCount != null && queueCount == 0) {
            String[][] queues = {
                    {"0", "总台8888", "8888,9999,6666"},
                    {"1", "总台9999", "9999,6666"},
                    {"9", "总台6666", "6666"}
            };
            for (String[] q : queues) {
                jdbc.update("INSERT INTO sys_sip_queue(queue_no, queue_name, targets) VALUES (?, ?, ?)",
                        q[0], q[1], q[2]);
            }
            Logs.info(Logs.Module.SIP, "已预置默认队列", "数量", queues.length);
        }
    }
}
