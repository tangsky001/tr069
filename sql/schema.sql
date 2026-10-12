

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------- 管理端用户 ----------------------------
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`      VARCHAR(64)  NOT NULL COMMENT '登录名',
    `password`      VARCHAR(128) NOT NULL COMMENT '密码',
    `nickname`      VARCHAR(64)  DEFAULT NULL COMMENT '显示名',
    `role`          VARCHAR(32)  NOT NULL DEFAULT 'admin' COMMENT '角色：admin/operator/viewer',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0禁用',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `last_login_at` DATETIME     DEFAULT NULL COMMENT '最近登录时间',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_username` (`username`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '管理端用户';

-- ---------------------------- 运营商配置 ----------------------------
CREATE TABLE IF NOT EXISTS `operator_config` (
    `id`                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键，对应原 WL_CONFIG.CONFIG_ID',
    `isp`                VARCHAR(16) NOT NULL COMMENT '运营商：CT/CU/standard',
    `version`            VARCHAR(16) NOT NULL COMMENT '运营商版本：v3.0/v4.0/v1.0/v2.0',
    `base_isp`           VARCHAR(16) DEFAULT NULL COMMENT '继承的基础运营商',
    `acs_to_cpe_name`    VARCHAR(64) DEFAULT NULL COMMENT 'ACS 反向连 CPE 的用户名',
    `acs_to_cpe_password` VARCHAR(64) DEFAULT NULL COMMENT 'ACS 反向连 CPE 的密码',
    `cpe_to_acs_name`    VARCHAR(64) DEFAULT NULL COMMENT 'CPE 连 ACS 的用户名',
    `cpe_to_acs_password` VARCHAR(64) DEFAULT NULL COMMENT 'CPE 连 ACS 的密码',
    `eventcode_map`      TEXT        COMMENT '事件码到工单名的映射，JSON',
    `deleted`            TINYINT     NOT NULL DEFAULT 0,
    `created_at`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_operator_config` (`isp`, `version`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '运营商配置，对应原 WL_CONFIG';

-- ---------------------------- 设备 ----------------------------
CREATE TABLE IF NOT EXISTS `cpe_device` (
    `id`                      BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键，对应原 CPE.CPE_ID',
    `sn`                      VARCHAR(70) NOT NULL COMMENT '序列号',
    `oui`                     VARCHAR(32)  DEFAULT NULL COMMENT '厂商 OUI',
    `manufacturer`            VARCHAR(64)  DEFAULT NULL COMMENT '厂商',
    `product_class`           VARCHAR(64)  DEFAULT NULL COMMENT '产品类别',
    `software_version`        VARCHAR(64)  DEFAULT NULL COMMENT '软件版本',
    `hardware_version`        VARCHAR(64)  DEFAULT NULL COMMENT '硬件版本',
    `root_node`               VARCHAR(64)  NOT NULL DEFAULT 'InternetGatewayDevice.' COMMENT '根数据模型节点',
    `connection_request_url`  VARCHAR(255) DEFAULT NULL COMMENT '反向连接地址',
    `cwmp_version`            VARCHAR(32)  NOT NULL DEFAULT 'cwmp-1-0' COMMENT 'CWMP 命名空间版本',
    `auth_type`               VARCHAR(16)  NOT NULL DEFAULT 'digest' COMMENT '认证类型：digest/basic/None',
    `cpe_to_acs_name`         VARCHAR(64)  DEFAULT NULL COMMENT 'CPE->ACS 用户名，为空时回落全局默认',
    `cpe_to_acs_password`     VARCHAR(64)  DEFAULT NULL,
    `acs_to_cpe_name`         VARCHAR(64)  DEFAULT NULL COMMENT 'ACS->CPE 用户名',
    `acs_to_cpe_password`     VARCHAR(64)  DEFAULT NULL,
    `isp`                     VARCHAR(16)  DEFAULT NULL COMMENT '所属运营商',
    `isp_version`             VARCHAR(16)  DEFAULT NULL COMMENT '运营商版本',
    `device_type`             VARCHAR(64)  DEFAULT NULL COMMENT '设备类型，如 GPON_4+1，对应原 DOMAIN',
    `status`                  VARCHAR(16)  NOT NULL DEFAULT 'offline' COMMENT '在线状态',
    `last_contact_time`       DATETIME     DEFAULT NULL COMMENT '最近通信时间',
    `soap_inform_timeout`     INT          NOT NULL DEFAULT 60 COMMENT 'Inform 超时(秒)',
    `deleted`                 TINYINT      NOT NULL DEFAULT 0,
    `created_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cpe_device_sn` (`sn`, `deleted`),
    KEY `idx_cpe_device_status` (`status`),
    KEY `idx_cpe_device_type` (`device_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'CPE 设备，对应原 CPE 表';

-- ---------------------------- RPC 记录 ----------------------------
CREATE TABLE IF NOT EXISTS `rpc_record` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键，对应原 RPC.RPC_ID',
    `rpc_name`      VARCHAR(50) NOT NULL COMMENT 'CWMP 方法名',
    `sn`            VARCHAR(70) NOT NULL COMMENT '设备序列号',
    `worklist_id`   BIGINT      DEFAULT NULL COMMENT '关联工单',
    `request_args`  TEXT        COMMENT '请求参数，JSON，对应原 RPC_EX.RPC_PARAMETERS',
    `result_content` MEDIUMTEXT COMMENT '结果内容，对应原 RPC_EX.RESULT_CONTENT',
    `status`        VARCHAR(16) NOT NULL DEFAULT 'init' COMMENT 'init/success/fail/timeout',
    `start_time`    DATETIME    DEFAULT NULL,
    `finish_time`   DATETIME    DEFAULT NULL,
    `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_rpc_record_sn` (`sn`),
    KEY `idx_rpc_record_worklist` (`worklist_id`),
    KEY `idx_rpc_record_created` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'RPC 记录，对应原 RPC + RPC_EX';

-- ---------------------------- SOAP 报文日志 ----------------------------
CREATE TABLE IF NOT EXISTS `soap_message` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键，对应原 SOAP.SOAP_ID',
    `rpc_id`       BIGINT      DEFAULT NULL COMMENT '关联 RPC',
    `sn`           VARCHAR(70) NOT NULL COMMENT '设备序列号',
    `msg_type`     VARCHAR(64)  DEFAULT NULL COMMENT '报文类型：Inform/InformResponse/GetParameterValues...',
    `direction`    VARCHAR(4)   DEFAULT NULL COMMENT '方向：in/out',
    `event_code`   VARCHAR(64)  DEFAULT NULL COMMENT 'Inform 事件码',
    `content_body` MEDIUMTEXT   COMMENT '报文体，对应原 SOAP_EX.CONTENT_BODY',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_soap_message_sn` (`sn`),
    KEY `idx_soap_message_rpc` (`rpc_id`),
    KEY `idx_soap_message_created` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'SOAP 报文日志，对应原 SOAP + SOAP_EX';

-- ---------------------------- 设备参数能力树 ----------------------------
-- GetParameterNames 拉全量路径 + GetParameterValues 读值后的落库结果，
-- 供模板匹配、SetParameterValues 前的可写性校验使用。
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

-- ---------------------------- 工单模板 ----------------------------
CREATE TABLE IF NOT EXISTS `worklist_template` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，对应原 WL_TEMPLATE.TEMPLATE_ID',
    `isp`         VARCHAR(16)  NOT NULL COMMENT '运营商',
    `version`     VARCHAR(16)  NOT NULL COMMENT '运营商版本',
    `domain`      VARCHAR(64)  NOT NULL COMMENT '设备类型，如 GPON_4+1',
    `method`      VARCHAR(100) NOT NULL COMMENT '工单名，如 WLAN_ADD',
    `parameters`  TEXT         COMMENT '工单参数模板，JSON',
    `doc`         TEXT         COMMENT '工单说明',
    `deleted`     TINYINT      NOT NULL DEFAULT 0,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_worklist_template` (`isp`, `version`, `domain`, `method`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单模板，对应原 WL_TEMPLATE';

-- ---------------------------- 工单 ----------------------------
CREATE TABLE IF NOT EXISTS `worklist` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键，对应原 WORKLIST.WORKLIST_ID',
    `worklist_desc`    VARCHAR(128) DEFAULT NULL COMMENT '工单唯一描述',
    `worklist_name`    VARCHAR(128) DEFAULT NULL COMMENT '工单名',
    `worklist_type`    VARCHAR(16)  DEFAULT 'physic' COMMENT 'physic/logic',
    `sn`               VARCHAR(70)  NOT NULL COMMENT '设备序列号',
    `isp`              VARCHAR(16)  DEFAULT NULL COMMENT '运营商',
    `isp_version`      VARCHAR(16)  DEFAULT NULL COMMENT '运营商版本',
    `domain`           VARCHAR(64)  DEFAULT NULL COMMENT '设备类型',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'build' COMMENT 'build/bind/reserve/standby/running/success/fail/abnormal/expire',
    `rollback_flag`    TINYINT      NOT NULL DEFAULT 0 COMMENT '失败是否回退',
    `parameters`       MEDIUMTEXT   COMMENT '工单参数，对应原 WORKLIST_EX.PARAMETERS',
    `result`           MEDIUMTEXT   COMMENT '执行结果，对应原 WORKLIST_EX.RESULT',
    `username`         VARCHAR(64)  DEFAULT NULL COMMENT '操作人',
    `time_init`        DATETIME     DEFAULT NULL,
    `time_bind`        DATETIME     DEFAULT NULL,
    `time_reserve`     DATETIME     DEFAULT NULL,
    `time_exec_start`  DATETIME     DEFAULT NULL,
    `time_exec_finish` DATETIME     DEFAULT NULL,
    `deleted`          TINYINT      NOT NULL DEFAULT 0,
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_worklist_desc` (`worklist_desc`, `deleted`),
    KEY `idx_worklist_sn` (`sn`),
    KEY `idx_worklist_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单，对应原 WORKLIST + WORKLIST_EX';

-- ---------------------------- 工单执行步骤明细 ----------------------------
CREATE TABLE IF NOT EXISTS `worklist_step` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `worklist_id`    BIGINT       NOT NULL COMMENT '所属工单',
    `step_index`     INT          NOT NULL COMMENT '步骤序号，从 0 开始',
    `comment`        VARCHAR(255) DEFAULT NULL COMMENT '步骤说明',
    `rpc`            VARCHAR(64)  NOT NULL COMMENT 'CWMP 方法名',
    `request_args`   MEDIUMTEXT   COMMENT '实际下发的参数，JSON',
    `response_content` MEDIUMTEXT COMMENT '设备返回结果，JSON',
    `status`         VARCHAR(16)  NOT NULL DEFAULT 'init' COMMENT 'init/success/fail/skip',
    `error_msg`      VARCHAR(1024) DEFAULT NULL COMMENT '失败原因',
    `duration_ms`    BIGINT       DEFAULT NULL COMMENT '耗时(毫秒)',
    `deleted`        TINYINT      NOT NULL DEFAULT 0,
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_worklist_step_wl` (`worklist_id`, `step_index`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单执行步骤明细，用于工单结果追溯';

-- ---------------------------- 监控规则 ----------------------------
CREATE TABLE IF NOT EXISTS `monitor_rule` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `monitor_desc` VARCHAR(128) NOT NULL COMMENT '规则唯一描述',
    `type`         VARCHAR(32)  DEFAULT NULL COMMENT '监控类型',
    `status`       VARCHAR(16)  DEFAULT NULL,
    `status_desc`  VARCHAR(255) DEFAULT NULL,
    `cfg`          MEDIUMTEXT   COMMENT '规则配置，JSON',
    `sn`           VARCHAR(70)  DEFAULT NULL,
    `time_start`   DATETIME     DEFAULT NULL,
    `time_stop`    DATETIME     DEFAULT NULL,
    `deleted`      TINYINT      NOT NULL DEFAULT 0,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_monitor_rule_desc` (`monitor_desc`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '监控规则，对应原 MONITOR_RULES';

-- ---------------------------- 监控结果 ----------------------------
CREATE TABLE IF NOT EXISTS `monitor_result` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `monitor_desc` VARCHAR(128) NOT NULL,
    `soap_id`      BIGINT      DEFAULT NULL COMMENT '关联的 SOAP 报文',
    `sn`           VARCHAR(70) DEFAULT NULL,
    `content`      TEXT        COMMENT '监控结果内容',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_monitor_result_desc` (`monitor_desc`),
    KEY `idx_monitor_result_soap` (`soap_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '监控结果，对应原 MONITOR_RESULT';

-- ---------------------------- 系统日志 ----------------------------
CREATE TABLE IF NOT EXISTS `sys_log` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键，对应原 LOG.LOG_ID',
    `message`     VARCHAR(255) DEFAULT NULL,
    `message_ext` MEDIUMTEXT   COMMENT '扩展信息，对应原 LOG.MESSAGE2',
    `time_start`  DATETIME     DEFAULT NULL,
    `time_finish` DATETIME     DEFAULT NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_sys_log_created` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统日志，对应原 LOG 表';

-- ---------------------------- 业务模板（配置模板集合，对设备整包下发） ----------------------------
-- 与 server/src/main/resources/db/biz-template-schema.sql 保持一致：
-- 应用启动时若发现缺表会自动创建，这里保留一份供手工部署使用。
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

SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------- 初始化管理员 ----------------------------
INSERT IGNORE INTO `sys_user` (`id`, `username`, `password`, `nickname`, `role`, `status`)
VALUES (1, 'admin', 'admin', '系统管理员', 'admin', 1);
