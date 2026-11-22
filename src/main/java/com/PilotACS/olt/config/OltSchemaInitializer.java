/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.733
 *
 */

package com.PilotACS.olt.config;

import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** OLT 七张表的自动补建与字段补齐 */
@Component
@RequiredArgsConstructor
public class OltSchemaInitializer implements InitializingBean {

    private static final List<String> TABLES =
            List.of("olt_device", "olt_board", "olt_sync_record",
                    "olt_ont_reg_template", "olt_ont_reg_config", "olt_ont_reg_record",
                    "olt_quick_setup_config");

    /** 需补齐的列 */
    private static final String[][] EXPECTED_COLUMNS = {
            {"olt_device", "patch_version", "VARCHAR(64) DEFAULT NULL COMMENT '补丁版本，如 SPC107'"},
            {"olt_device", "backplane", "VARCHAR(64) DEFAULT NULL COMMENT '背板/主板型号，如 H802MABO'"},
            {"olt_device", "slot_count", "INT DEFAULT NULL COMMENT '机框槽位数（display board 0 槽位表）'"},
            {"olt_device", "mac_address", "VARCHAR(32) DEFAULT NULL COMMENT '主控 MAC'"},
            {"olt_device", "uptime", "VARCHAR(64) DEFAULT NULL COMMENT '运行时长'"},
            {"olt_device", "vlan_json", "LONGTEXT DEFAULT NULL COMMENT 'VLAN 快照(JSON)'"},
            {"olt_device", "template_json", "LONGTEXT DEFAULT NULL COMMENT 'DBA/线路模板快照(JSON)'"},
            {"olt_board", "board_kind", "VARCHAR(16) DEFAULT NULL COMMENT '板卡类别'"},
            {"olt_board", "description", "VARCHAR(128) DEFAULT NULL COMMENT '板卡描述'"},
            {"olt_board", "ports_json", "LONGTEXT DEFAULT NULL COMMENT '端口光模块状态快照(JSON)'"}
    };

    /** 建表语句。 */
    private static final String[] CREATE_SQL = {
            """
            CREATE TABLE IF NOT EXISTS `olt_device` (
                `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                `name`            VARCHAR(64)  NOT NULL COMMENT '设备名称',
                `host`            VARCHAR(128) NOT NULL COMMENT 'IP/主机名',
                `protocol`        VARCHAR(16)  DEFAULT 'telnet' COMMENT '协议：telnet',
                `username`        VARCHAR(64)  DEFAULT NULL COMMENT '登录账号',
                `password`        VARCHAR(255) DEFAULT NULL COMMENT '登录密码（OltCrypto 加密）',
                `port`            INT          DEFAULT 23 COMMENT '端口',
                `charset`         VARCHAR(16)  DEFAULT 'UTF-8' COMMENT '回显编码',
                `bind_local_ip`   VARCHAR(64)  DEFAULT NULL COMMENT '绑定本机出口 IP',
                `web_accounts`    VARCHAR(255) DEFAULT NULL COMMENT '关联 Web 账号（逗号分隔用户名）',
                `vendor`          VARCHAR(32)  DEFAULT NULL COMMENT '厂商：huawei/hsgq/zte/fiberhome/generic',
                `model`           VARCHAR(64)  DEFAULT NULL COMMENT '型号',
                `software_version` VARCHAR(64) DEFAULT NULL COMMENT '软件版本',
                `patch_version`   VARCHAR(64)  DEFAULT NULL COMMENT '补丁版本，如 SPC107',
                `backplane`       VARCHAR(64)  DEFAULT NULL COMMENT '背板/主板型号，如 H802MABO',
                `slot_count`      INT          DEFAULT NULL COMMENT '机框槽位数',
                `mac_address`     VARCHAR(32)  DEFAULT NULL COMMENT '主控 MAC',
                `uptime`          VARCHAR(64)  DEFAULT NULL COMMENT '运行时长',
                `status`          VARCHAR(16)  DEFAULT 'unknown' COMMENT '状态：online/offline/unknown',
                `last_connect_at` DATETIME     DEFAULT NULL COMMENT '最近连通时间',
                `last_sync_at`    DATETIME     DEFAULT NULL COMMENT '最近完整同步时间',
                `last_error`      VARCHAR(512) DEFAULT NULL COMMENT '最近一次失败原因',
                `sync_summary`    VARCHAR(255) DEFAULT NULL COMMENT '最近一次同步摘要',
                `vlan_json`       LONGTEXT     DEFAULT NULL COMMENT 'VLAN 快照(JSON)',
                `template_json`   LONGTEXT     DEFAULT NULL COMMENT 'DBA/线路模板快照(JSON)',
                `remark`          VARCHAR(255) DEFAULT NULL COMMENT '备注',
                `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '1已删除',
                `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (`id`),
                KEY `idx_host` (`host`)
            ) ENGINE = InnoDB COMMENT 'OLT 设备台账'
            """,
            """
            CREATE TABLE IF NOT EXISTS `olt_board` (
                `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                `olt_id`      BIGINT       NOT NULL COMMENT 'olt_device.id',
                `slot_no`     INT          DEFAULT NULL COMMENT '槽位号',
                `board_type`  VARCHAR(64)  DEFAULT NULL COMMENT '板卡型号，如 H901GPHF',
                `status`      VARCHAR(32)  DEFAULT NULL COMMENT '原始状态：Normal/Active_normal',
                `board_kind`  VARCHAR(16)  DEFAULT NULL COMMENT '板卡类别：SERVICE/CONTROL/UPLINK/POWER',
                `description` VARCHAR(128) DEFAULT NULL COMMENT '板卡描述',
                `port_mode`   VARCHAR(16)  DEFAULT NULL COMMENT '端口类型：GPON/EPON/PON',
                `port_count`  INT          DEFAULT NULL COMMENT '端口数量',
                `temperature` INT          DEFAULT NULL COMMENT '温度(摄氏度)',
                `cpu_usage`   VARCHAR(16)  DEFAULT NULL COMMENT 'CPU 占用',
                `mem_usage`   VARCHAR(16)  DEFAULT NULL COMMENT '内存占用',
                `remark`      VARCHAR(128) DEFAULT NULL COMMENT '备注',
                `ports_json`  LONGTEXT     DEFAULT NULL COMMENT '端口光模块状态快照(JSON)',
                `synced_at`   DATETIME     DEFAULT NULL COMMENT '采集时间',
                PRIMARY KEY (`id`),
                KEY `idx_olt_slot` (`olt_id`, `slot_no`)
            ) ENGINE = InnoDB COMMENT 'OLT 板卡'
            """,
            """
            CREATE TABLE IF NOT EXISTS `olt_sync_record` (
                `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                `olt_id`     BIGINT       DEFAULT NULL COMMENT 'olt_device.id，连接诊断未保存设备时为空',
                `action`     VARCHAR(64)  DEFAULT NULL COMMENT '动作：sync/quick-sync/test/...',
                `success`    TINYINT      DEFAULT NULL COMMENT '1成功 0失败',
                `message`    VARCHAR(512) DEFAULT NULL COMMENT '结果摘要/失败原因',
                `cost_ms`    INT          DEFAULT NULL COMMENT '耗时(毫秒)',
                `operator`   VARCHAR(64)  DEFAULT NULL COMMENT '操作人',
                `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (`id`),
                KEY `idx_olt_time` (`olt_id`, `created_at`)
            ) ENGINE = InnoDB COMMENT 'OLT 采集记录'
            """,
            """
            CREATE TABLE IF NOT EXISTS `olt_ont_reg_template` (
                `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                `olt_id`         BIGINT       DEFAULT NULL COMMENT 'olt_device.id，空=全局模板',
                `olt_ip`         VARCHAR(64)  DEFAULT NULL COMMENT '设备 IP 冗余',
                `name`           VARCHAR(64)  NOT NULL COMMENT '模板名',
                `priority`       INT          NOT NULL DEFAULT 0 COMMENT '匹配优先级，小者优先',
                `enabled`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用',
                `auth_mode`      VARCHAR(16)  DEFAULT 'sn' COMMENT '认证方式：sn/password',
                `pon_type`       VARCHAR(16)  DEFAULT 'gpon' COMMENT 'PON 类型：gpon/epon',
                `line_profile_id` INT         DEFAULT NULL COMMENT '线路模板 ID',
                `srv_profile_id` INT          DEFAULT NULL COMMENT '业务模板 ID',
                `bind_ports`     VARCHAR(128) DEFAULT NULL COMMENT '绑定端口，如 0/2/1',
                `ont_models`     VARCHAR(255) DEFAULT NULL COMMENT '适配型号（逗号分隔，空=不限）',
                `work_mode`      VARCHAR(16)  DEFAULT NULL COMMENT '工作模式',
                `service_vlan_map` VARCHAR(255) DEFAULT NULL COMMENT '业务 VLAN 映射',
                `tr069_vlan`     VARCHAR(16)  DEFAULT NULL COMMENT 'TR069 VLAN',
                `iptv_vlan`      VARCHAR(16)  DEFAULT NULL COMMENT 'IPTV VLAN',
                `multicast_vlan` VARCHAR(16)  DEFAULT NULL COMMENT '组播 VLAN',
                `remark`         VARCHAR(255) DEFAULT NULL COMMENT '备注',
                `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '1已删除',
                `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (`id`),
                KEY `idx_olt` (`olt_id`)
            ) ENGINE = InnoDB COMMENT 'ONT 注册模板'
            """,
            """
            CREATE TABLE IF NOT EXISTS `olt_ont_reg_config` (
                `id`                 BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
                `olt_id`             BIGINT   NOT NULL COMMENT 'olt_device.id',
                `enabled`            TINYINT  DEFAULT 0 COMMENT '自动注册开关',
                `scan_seconds`       INT      DEFAULT 30 COMMENT '扫描间隔(秒)',
                `save_seconds`       INT      DEFAULT 60 COMMENT '保存间隔(秒)',
                `concurrency`        INT      DEFAULT 1 COMMENT '并发数',
                `auto_move`          TINYINT  DEFAULT 0 COMMENT '注册后自动移动',
                `auto_delete_offline` TINYINT DEFAULT 0 COMMENT '离线自动删除',
                `last_scan_at`       DATETIME DEFAULT NULL COMMENT '最近扫描时间',
                `last_register_at`   DATETIME DEFAULT NULL COMMENT '最近注册时间',
                `last_save_at`       DATETIME DEFAULT NULL COMMENT '最近保存时间',
                `created_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                `updated_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (`id`),
                KEY `idx_olt` (`olt_id`)
            ) ENGINE = InnoDB COMMENT 'ONT 自动注册配置'
            """,
            """
            CREATE TABLE IF NOT EXISTS `olt_ont_reg_record` (
                `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                `olt_id`      BIGINT       NOT NULL COMMENT 'olt_device.id',
                `template_id` BIGINT       DEFAULT NULL COMMENT '命中的模板 id',
                `template_name` VARCHAR(64) DEFAULT NULL COMMENT '命中的模板名',
                `fsp`         VARCHAR(32)  DEFAULT NULL COMMENT '端口，如 0/2/1',
                `ont_sn`      VARCHAR(64)  DEFAULT NULL COMMENT 'ONT SN',
                `model`       VARCHAR(64)  DEFAULT NULL COMMENT 'ONT 型号',
                `auth_mode`   VARCHAR(16)  DEFAULT NULL COMMENT '认证方式',
                `success`     TINYINT      DEFAULT NULL COMMENT '1成功 0失败',
                `source`      VARCHAR(16)  DEFAULT NULL COMMENT '来源：manual/auto',
                `command`     VARCHAR(512) DEFAULT NULL COMMENT '下发命令',
                `message`     VARCHAR(512) DEFAULT NULL COMMENT '结果消息',
                `operator`    VARCHAR(64)  DEFAULT NULL COMMENT '操作人（auto=自动）',
                `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (`id`),
                KEY `idx_olt_time` (`olt_id`, `created_at`)
            ) ENGINE = InnoDB COMMENT 'ONT 注册记录'
            """,
            """
            CREATE TABLE IF NOT EXISTS `olt_quick_setup_config` (
                `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
                `olt_id`      BIGINT   NOT NULL COMMENT 'olt_device.id',
                `config_json` LONGTEXT DEFAULT NULL COMMENT '快速开通参数(JSON)',
                `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (`id`),
                KEY `idx_olt` (`olt_id`)
            ) ENGINE = InnoDB COMMENT '快速开通参数'
            """
    };

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.olt.auto-create-table:true}")
    private boolean enabled;

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            Logs.info(Logs.Module.SYSTEM, "OLT 表自动建表已关闭");
            return;
        }
        try {
            for (String sql : CREATE_SQL) {
                jdbcTemplate.execute(sql);
            }
            ensureColumns();
            Logs.info(Logs.Module.SYSTEM, "OLT 表已就绪", "表", String.join(", ", TABLES));
        } catch (Exception e) {
            Logs.error(Logs.Module.SYSTEM, "OLT 表自动建表失败，请手工执行 sql/mysql.sql", e);
        }
    }

    /** 补齐缺失字段。 */
    private void ensureColumns() {
        for (String[] spec : EXPECTED_COLUMNS) {
            if (columnExists(spec[0], spec[1])) {
                continue;
            }
            jdbcTemplate.execute("ALTER TABLE `" + spec[0] + "` ADD COLUMN `" + spec[1] + "` " + spec[2]);
            Logs.info(Logs.Module.SYSTEM, "OLT 表结构补齐", "表", spec[0], "列", spec[1]);
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
