-- ---------------------------- 设备参数能力树 ----------------------------
-- 与 sql/schema.sql 中的 cpe_parameter 保持一致：
-- 应用启动时若发现缺表会自动创建，这里保留一份供手工部署使用。
CREATE TABLE IF NOT EXISTS `cpe_parameter` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `sn`            VARCHAR(70)  NOT NULL COMMENT '设备序列号',
    `param_path`    VARCHAR(400) NOT NULL COMMENT '参数完整路径；对象以英文句点结尾，如 InternetGatewayDevice.DeviceInfo.',
    `parent_path`   VARCHAR(400) DEFAULT NULL COMMENT '父对象路径，根节点为空',
    `node_name`     VARCHAR(128) DEFAULT NULL COMMENT '路径末段名',
    `node_type`     VARCHAR(16)  NOT NULL DEFAULT 'param' COMMENT '节点类型：object 对象 / param 参数',
    `writable`      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否可写：1可写 0只读，取自 GetParameterNames 的 Writable',
    `param_value`   TEXT         COMMENT '参数当前值，取自 GetParameterValues',
    `value_type`    VARCHAR(32)  DEFAULT NULL COMMENT '值类型，取自 xsi:type 去掉 xsd: 前缀',
    `depth`         INT          NOT NULL DEFAULT 0 COMMENT '层级深度，根节点为 0',
    `collect_batch` VARCHAR(32)  DEFAULT NULL COMMENT '采集批次号，同一次采集写入相同值',
    `collect_time`  DATETIME     DEFAULT NULL COMMENT '采集时间',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cpe_parameter` (`sn`, `param_path`),
    KEY `idx_cpe_parameter_parent` (`sn`, `parent_path`),
    KEY `idx_cpe_parameter_type` (`sn`, `node_type`),
    KEY `idx_cpe_parameter_collect` (`sn`, `collect_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '设备参数能力树，GetParameterNames + GetParameterValues 的采集结果';
