-- 业务模板相关表。应用启动时自动执行（CREATE TABLE IF NOT EXISTS），
-- 与 sql/schema.sql 中的同名表结构保持一致。
CREATE TABLE IF NOT EXISTS `biz_template` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`             VARCHAR(128) NOT NULL COMMENT '业务模板名称',
    `group_name`       VARCHAR(64)  DEFAULT NULL COMMENT '业务分组',
    `remark`           VARCHAR(512) DEFAULT NULL COMMENT '备注',
    `enabled`          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用，停用后不允许下发',
    `continue_on_error` TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '成员执行失败是否继续后续成员',
    `created_at`       DATETIME     DEFAULT NULL,
    `updated_at`       DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_template_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务模板，多个配置模板的有序集合';

CREATE TABLE IF NOT EXISTS `biz_template_item` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `template_id`  BIGINT       NOT NULL COMMENT '所属业务模板',
    `template_key` VARCHAR(512) NOT NULL COMMENT '配置模板 key：isp/version/domain/deviceType/name',
    `item_order`   INT          NOT NULL DEFAULT 0 COMMENT '执行顺序，小的先下发',
    `params`       TEXT         COMMENT '入参覆盖，JSON 对象，如 {"VLAN_ID":"41"}',
    `remark`       VARCHAR(512) DEFAULT NULL COMMENT '成员备注',
    PRIMARY KEY (`id`),
    KEY `idx_biz_item_template` (`template_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务模板成员';

CREATE TABLE IF NOT EXISTS `biz_template_execution` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `template_id`   BIGINT      NOT NULL COMMENT '业务模板 id',
    `template_name` VARCHAR(128) DEFAULT NULL COMMENT '业务模板名，冗余保存便于追溯',
    `sn`            VARCHAR(128) NOT NULL COMMENT '设备序列号',
    `status`        VARCHAR(32) NOT NULL COMMENT 'running/success/partial/fail',
    `username`      VARCHAR(64) DEFAULT NULL COMMENT '操作人',
    `total_count`   INT         NOT NULL DEFAULT 0 COMMENT '成员总数',
    `finished_count` INT        NOT NULL DEFAULT 0 COMMENT '已执行成员数',
    `success_count` INT         NOT NULL DEFAULT 0 COMMENT '成功数',
    `fail_count`    INT         NOT NULL DEFAULT 0 COMMENT '失败数',
    `current_key`   VARCHAR(512) DEFAULT NULL COMMENT '正在执行的成员配置模板 key',
    `result`        TEXT        COMMENT '每个成员的执行结果，JSON 数组',
    `error_message` VARCHAR(1024) DEFAULT NULL COMMENT '中止原因',
    `created_at`    DATETIME    DEFAULT NULL,
    `finished_at`   DATETIME    DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_biz_exec_sn` (`sn`),
    KEY `idx_biz_exec_template` (`template_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务模板整包下发记录';
