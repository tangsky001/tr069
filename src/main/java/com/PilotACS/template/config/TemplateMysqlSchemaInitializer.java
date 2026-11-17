package com.PilotACS.template.config;

import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 配置模板 / 业务模板四张表自动补建（MySQL，DDL 与 sql/mysql.sql 保持一致）。
 */
@Component
@RequiredArgsConstructor
public class TemplateMysqlSchemaInitializer implements InitializingBean {

    private final JdbcTemplate jdbc;

    @Override
    public void afterPropertiesSet() {
        createTables();
    }

    private void createTables() {
        List<String> sqlList = allDdl();
        for (String sql : sqlList) {
            try {
                jdbc.execute(sql);
            } catch (Exception e) {
                Logs.warn(Logs.Module.CPE, "模板表补建失败", "原因", e.getMessage());
            }
        }
        Logs.info(Logs.Module.CPE, "模板表补建完成（共 " + sqlList.size() + " 张，已存在则跳过）");
    }

    private static List<String> allDdl() {
        List<String> ddl = new java.util.ArrayList<>();
        ddl.add("""
                CREATE TABLE IF NOT EXISTS `config_template` (
                    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '模板 id',
                    `name`         VARCHAR(128) NOT NULL COMMENT '模板名称（同类型下唯一）',
                    `category`     VARCHAR(32)  NOT NULL COMMENT '模板类型 WAN_DHCP/WAN_PPPOE/WAN_STATIC/WAN_BRIDGE/WIFI_24G/WIFI_58G/SIP/OTHER',
                    `group_name`   VARCHAR(64)  DEFAULT '' COMMENT '模板分组',
                    `enabled`      TINYINT      DEFAULT 1 COMMENT '是否启用',
                    `remark`       VARCHAR(255) DEFAULT '' COMMENT '备注',
                    `fields`       TEXT         COMMENT '意图字段 JSON',
                    `incremental`  TINYINT      DEFAULT 0 COMMENT '增量业务：只新增不清理存量连接',
                    `created_at`   DATETIME     DEFAULT NULL COMMENT '创建时间',
                    `updated_at`   DATETIME     DEFAULT NULL COMMENT '更新时间',
                    PRIMARY KEY (`id`),
                    KEY `idx_ct_cat` (`category`)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '配置意图模板'
                """);
        ddl.add("""
                CREATE TABLE IF NOT EXISTS `biz_template` (
                    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '业务模板 id',
                    `name`              VARCHAR(128) NOT NULL COMMENT '业务模板名称（唯一）',
                    `group_name`        VARCHAR(64)  DEFAULT '' COMMENT '业务分组',
                    `remark`            VARCHAR(255) DEFAULT '' COMMENT '备注',
                    `enabled`           TINYINT      DEFAULT 1 COMMENT '是否启用',
                    `continue_on_error` TINYINT      DEFAULT 0 COMMENT '成员失败时是否继续',
                    `created_at`        DATETIME     DEFAULT NULL COMMENT '创建时间',
                    `updated_at`        DATETIME     DEFAULT NULL COMMENT '更新时间',
                    PRIMARY KEY (`id`),
                    UNIQUE KEY `uk_bt_name` (`name`)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务模板'
                """);
        ddl.add("""
                CREATE TABLE IF NOT EXISTS `biz_template_item` (
                    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '成员 id',
                    `template_id`  BIGINT       NOT NULL COMMENT '所属业务模板 id',
                    `template_key` VARCHAR(64)  NOT NULL COMMENT '引用的配置模板 id',
                    `item_order`   INT          DEFAULT 0 COMMENT '执行顺序',
                    `params`       TEXT         COMMENT '入参覆盖 JSON',
                    `remark`       VARCHAR(255) DEFAULT '' COMMENT '成员备注',
                    PRIMARY KEY (`id`),
                    KEY `idx_bti_tpl` (`template_id`)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务模板成员'
                """);
        ddl.add("""
                CREATE TABLE IF NOT EXISTS `biz_template_execution` (
                    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '执行记录 id',
                    `template_id`    BIGINT       DEFAULT NULL COMMENT '业务模板 id',
                    `template_name`  VARCHAR(128) DEFAULT '' COMMENT '冗余模板名',
                    `sn`             VARCHAR(64)  DEFAULT NULL COMMENT '设备序列号',
                    `status`         VARCHAR(16)  DEFAULT 'running' COMMENT 'running/success/partial/fail',
                    `username`       VARCHAR(64)  DEFAULT NULL COMMENT '操作人',
                    `total_count`    INT          DEFAULT 0 COMMENT '成员总数',
                    `finished_count` INT          DEFAULT 0 COMMENT '已结束成员数',
                    `success_count`  INT          DEFAULT 0 COMMENT '成功成员数',
                    `fail_count`     INT          DEFAULT 0 COMMENT '失败成员数',
                    `current_key`    VARCHAR(64)  DEFAULT '' COMMENT '正在执行的成员',
                    `result`         MEDIUMTEXT   COMMENT '每成员执行结果 JSON',
                    `error_message`  VARCHAR(255) DEFAULT NULL COMMENT '中断原因',
                    `created_at`     DATETIME     DEFAULT NULL COMMENT '创建时间',
                    `finished_at`    DATETIME     DEFAULT NULL COMMENT '结束时间',
                    PRIMARY KEY (`id`),
                    KEY `idx_bte_sn` (`sn`),
                    KEY `idx_bte_tpl` (`template_id`)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务模板执行记录'
                """);
        return ddl;
    }
}
