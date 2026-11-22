/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.config;

import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 光猫台账/下发任务表自动补建（MySQL，DDL 与 sql/mysql.sql 保持一致）。 */
@Component
@RequiredArgsConstructor
public class CpeMysqlSchemaInitializer implements InitializingBean {

    private static final List<String> CREATE_SQL = List.of(
            """
            CREATE TABLE IF NOT EXISTS `cpe_device` (
                `sn`                    VARCHAR(64)  NOT NULL COMMENT '序列号（主键）',
                `oui`                   VARCHAR(16)  DEFAULT NULL COMMENT '厂商 OUI',
                `manufacturer`          VARCHAR(128) DEFAULT NULL COMMENT '厂商',
                `product_class`         VARCHAR(128) DEFAULT NULL COMMENT '型号',
                `hardware_version`      VARCHAR(64)  DEFAULT NULL COMMENT '硬件版本',
                `software_version`      VARCHAR(64)  DEFAULT NULL COMMENT '软件版本',
                `isp`                   VARCHAR(16)  DEFAULT NULL COMMENT '运营商 CT/CU/CMCC/CBN/standard',
                `device_type`           VARCHAR(32)  DEFAULT NULL COMMENT '设备类型',
                `room_no`               VARCHAR(64)  DEFAULT '' COMMENT '机房编号',
                `remark`                VARCHAR(255) DEFAULT '' COMMENT '备注',
                `root_node`             VARCHAR(64)  DEFAULT NULL COMMENT '数据模型根节点 TR-098/TR-181',
                `cwmp_version`          VARCHAR(16)  DEFAULT NULL COMMENT 'CWMP 版本',
                `connection_request_url` VARCHAR(255) DEFAULT NULL COMMENT '反向连接地址',
                `model_id`              BIGINT       DEFAULT NULL COMMENT '绑定的机型模板 id（template.pack）',
                `adapted`               TINYINT      DEFAULT 0 COMMENT '1 已适配入库',
                `adapted_from`          VARCHAR(64)  DEFAULT NULL COMMENT '适配来源设备 SN',
                `status`                VARCHAR(16)  DEFAULT 'offline' COMMENT 'online/offline',
                `last_inform_ms`        BIGINT       DEFAULT NULL COMMENT '最近 Inform 毫秒时间戳',
                `last_boot_ms`          BIGINT       DEFAULT NULL COMMENT '最近 1 BOOT 毫秒时间戳',
                `last_bootstrap_ms`     BIGINT       DEFAULT NULL COMMENT '最近 0 BOOTSTRAP 毫秒时间戳',
                `last_periodic_ms`      BIGINT       DEFAULT NULL COMMENT '最近 2 PERIODIC 毫秒时间戳',
                `last_valuechange_ms`   BIGINT       DEFAULT NULL COMMENT '最近 4 VALUE CHANGE 毫秒时间戳',
                `last_connreq_ms`       BIGINT       DEFAULT NULL COMMENT '最近 6 CONNECTION REQUEST 毫秒时间戳（CR 唤醒成功观测点）',
                `last_diag_ms`          BIGINT       DEFAULT NULL COMMENT '最近 8 DIAGNOSTICS COMPLETE 毫秒时间戳',
                `init_provisioned`      TINYINT      DEFAULT 0 COMMENT '1 首次上报已下发反向连接账密+周期上报',
                `init_provisioned_at`   BIGINT       DEFAULT NULL COMMENT '首次下发毫秒时间戳',
                `created_at`            BIGINT       DEFAULT NULL COMMENT '建档毫秒时间戳',
                `updated_at`            BIGINT       DEFAULT NULL COMMENT '更新毫秒时间戳',
                PRIMARY KEY (`sn`),
                KEY `idx_cpe_dev_status` (`status`),
                KEY `idx_cpe_dev_inform` (`last_inform_ms`)
            ) ENGINE = InnoDB COMMENT '光猫台账（CWMP/TR-069）';
            """,
            """
            CREATE TABLE IF NOT EXISTS `cpe_task` (
                `id`             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
                `task_no`        VARCHAR(40) DEFAULT NULL COMMENT '任务编号',
                `sn`             VARCHAR(64) NOT NULL COMMENT '设备序列号',
                `type`           VARCHAR(40) NOT NULL COMMENT '任务类型 Reboot/GPV/SPV/RefreshBusiness 等',
                `params_json`    TEXT        COMMENT '任务参数 JSON',
                `status`         VARCHAR(16) DEFAULT 'queued' COMMENT 'queued/running/success/fail',
                `fault_code`     VARCHAR(16) DEFAULT NULL COMMENT 'TR-069 FaultCode',
                `fault_string`   VARCHAR(255) DEFAULT NULL COMMENT '失败详情',
                `result_content` MEDIUMTEXT  COMMENT '结果 JSON',
                `created_by`     VARCHAR(64) DEFAULT NULL COMMENT '创建人',
                `created_at`     BIGINT      DEFAULT NULL COMMENT '创建毫秒时间戳',
                `started_at`     BIGINT      DEFAULT NULL COMMENT '开始毫秒时间戳',
                `finished_at`    BIGINT      DEFAULT NULL COMMENT '完成毫秒时间戳',
                PRIMARY KEY (`id`),
                KEY `uk_cpe_task_no` (`task_no`),
                KEY `idx_cpe_task_sn` (`sn`, `status`)
            ) ENGINE = InnoDB COMMENT '光猫下发任务（CWMP/TR-069）';
            """,
            """
            CREATE TABLE IF NOT EXISTS `cpe_device_snapshot` (
                `sn`            VARCHAR(64) NOT NULL COMMENT '序列号（主键）',
                `snapshot_json` MEDIUMTEXT  COMMENT '业务快照 JSON（WAN/LAN/WIFI/SIP）',
                `updated_at`    BIGINT      DEFAULT NULL COMMENT '刷新毫秒时间戳',
                PRIMARY KEY (`sn`)
            ) ENGINE = InnoDB COMMENT '光猫业务快照（会话结束/采集完成时刷新）';
            """);

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.cpe.auto-create-table:true}")
    private boolean enabled;

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        try {
            for (String sql : CREATE_SQL) {
                jdbcTemplate.execute(sql);
            }
            ensureColumns();
            upgradeTaskParamJson();
            Logs.info(Logs.Module.CPE, "MySQL 台账/任务表就绪", "表", "cpe_device/cpe_task");
        } catch (Exception e) {
            Logs.error(Logs.Module.CPE, "光猫台账表自动建表失败，请手工执行 sql/mysql.sql", e);
        }
    }

    /** 老库幂等补列 */
    private void ensureColumns() {
        addColumnIfMissing("init_provisioned",
                "ALTER TABLE `cpe_device` ADD COLUMN `init_provisioned` TINYINT DEFAULT 0 "
                        + "COMMENT '1 首次上报已下发反向连接账密+周期上报'");
        addColumnIfMissing("init_provisioned_at",
                "ALTER TABLE `cpe_device` ADD COLUMN `init_provisioned_at` BIGINT DEFAULT NULL "
                        + "COMMENT '首次下发毫秒时间戳'");
        addColumnIfMissing("last_periodic_ms",
                "ALTER TABLE `cpe_device` ADD COLUMN `last_periodic_ms` BIGINT DEFAULT NULL "
                        + "COMMENT '最近 2 PERIODIC 毫秒时间戳'");
        addColumnIfMissing("last_valuechange_ms",
                "ALTER TABLE `cpe_device` ADD COLUMN `last_valuechange_ms` BIGINT DEFAULT NULL "
                        + "COMMENT '最近 4 VALUE CHANGE 毫秒时间戳'");
        addColumnIfMissing("last_connreq_ms",
                "ALTER TABLE `cpe_device` ADD COLUMN `last_connreq_ms` BIGINT DEFAULT NULL "
                        + "COMMENT '最近 6 CONNECTION REQUEST 毫秒时间戳（CR 唤醒成功观测点）'");
        addColumnIfMissing("last_diag_ms",
                "ALTER TABLE `cpe_device` ADD COLUMN `last_diag_ms` BIGINT DEFAULT NULL "
                        + "COMMENT '最近 8 DIAGNOSTICS COMPLETE 毫秒时间戳'");
    }

    /** 老库升级：params_json 扩容为 MEDIUMTEXT */
    private void upgradeTaskParamJson() {
        String type = jdbcTemplate.queryForObject("""
                SELECT DATA_TYPE FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cpe_task' AND COLUMN_NAME = 'params_json'
                """, String.class);
        if (type != null && !"mediumtext".equalsIgnoreCase(type)) {
            jdbcTemplate.execute("ALTER TABLE `cpe_task` MODIFY `params_json` MEDIUMTEXT "
                    + "COMMENT '任务参数 JSON（业务刷新的叶子清单可能很大）'");
            Logs.info(Logs.Module.CPE, "cpe_task.params_json 扩容为 MEDIUMTEXT");
        }
    }

    private void addColumnIfMissing(String column, String ddl) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cpe_device' AND COLUMN_NAME = ?
                """, Integer.class, column);
        if (n == null || n == 0) {
            jdbcTemplate.execute(ddl);
            Logs.info(Logs.Module.CPE, "台账补列", "列", column);
        }
    }
}
