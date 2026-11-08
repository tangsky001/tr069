-- ============================================================
-- pilotacs-server 全库建表脚本，所有语句可反复执行
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------- 管理端用户 ----------------------------
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`      VARCHAR(64)  NOT NULL COMMENT '登录名',
    `password`      VARCHAR(128) NOT NULL COMMENT '密码',
    `nickname`      VARCHAR(64)  DEFAULT NULL COMMENT '显示名',
    `role`          VARCHAR(32)  NOT NULL DEFAULT 'admin' COMMENT '角色编码',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 禁用',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `last_login_at` DATETIME     DEFAULT NULL COMMENT '最近登录时间',
    `last_login_source` VARCHAR(16) DEFAULT NULL COMMENT '最近登录来源',
    `last_login_ip` VARCHAR(64)  DEFAULT NULL COMMENT '最近登录 IP',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_username` (`username`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '管理端用户';

-- ---------------------------- 角色 ----------------------------
CREATE TABLE IF NOT EXISTS `sys_role` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `code`       VARCHAR(32) NOT NULL COMMENT '角色编码',
    `name`       VARCHAR(64) NOT NULL COMMENT '角色名',
    `builtin`    TINYINT     NOT NULL DEFAULT 0 COMMENT '1 不可删除、权限不可改',
    `remark`     VARCHAR(255) DEFAULT NULL COMMENT '说明',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_role_code` (`code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色';

-- ---------------------------- 角色权限 ----------------------------
CREATE TABLE IF NOT EXISTS `sys_role_permission` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `role_code`   VARCHAR(32) NOT NULL COMMENT '角色编码',
    `permission`  VARCHAR(64) NOT NULL COMMENT '权限点：查看 :view / 修改 :update / 删除 :delete',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_permission` (`role_code`, `permission`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色权限';

-- ---------------------------- 防止删光管理员账号 ----------------------------
-- 注意：用了 DELIMITER，整份脚本请用 mysql 客户端执行
DROP TRIGGER IF EXISTS `trg_sys_user_before_delete`;
DELIMITER //
CREATE TRIGGER `trg_sys_user_before_delete` BEFORE DELETE ON `sys_user`
FOR EACH ROW
BEGIN
    IF LOWER(OLD.`username`) = 'admin' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '系统用户禁止删除';
    END IF;
    IF OLD.`role` = 'SUPER_ADMIN'
        AND (SELECT COUNT(*) FROM `sys_user` WHERE `role` = 'SUPER_ADMIN') <= 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '至少要留一个系统管理员';
    END IF;
END//
DELIMITER ;

-- ---------------------------- 登录日志 ----------------------------
CREATE TABLE IF NOT EXISTS `sys_login_log` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT      DEFAULT NULL COMMENT '用户 id',
    `username`    VARCHAR(64) DEFAULT NULL COMMENT '尝试登录的用户名',
    `ip`          VARCHAR(64) DEFAULT NULL COMMENT '来源 IP',
    `source`      VARCHAR(16) DEFAULT NULL COMMENT '登录来源',
    `user_agent`  VARCHAR(255) DEFAULT NULL COMMENT '客户端标识',
    `success`     TINYINT     NOT NULL DEFAULT 0 COMMENT '1 成功 0 失败',
    `fail_reason` VARCHAR(64) DEFAULT NULL COMMENT '失败原因',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_login_log_user` (`user_id`, `created_at`),
    KEY `idx_login_log_time` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '登录日志';

-- ---------------------------- 运营商配置 ----------------------------
CREATE TABLE IF NOT EXISTS `operator_config` (
    `id`                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `isp`                VARCHAR(16) NOT NULL COMMENT '运营商',
    `version`            VARCHAR(16) NOT NULL COMMENT '运营商版本',
    `base_isp`           VARCHAR(16) DEFAULT NULL COMMENT '继承的基础运营商',
    `acs_to_cpe_name`    VARCHAR(64) DEFAULT NULL COMMENT 'ACS 反向连 CPE 的用户名',
    `acs_to_cpe_password` VARCHAR(64) DEFAULT NULL COMMENT 'ACS 反向连 CPE 的密码',
    `cpe_to_acs_name`    VARCHAR(64) DEFAULT NULL COMMENT 'CPE 连 ACS 的用户名',
    `cpe_to_acs_password` VARCHAR(64) DEFAULT NULL COMMENT 'CPE 连 ACS 的密码',
    `eventcode_map`      TEXT        COMMENT '事件码映射，JSON',
    `deleted`            TINYINT     NOT NULL DEFAULT 0,
    `created_at`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_operator_config` (`isp`, `version`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '运营商配置';

-- ---------------------------- 设备 ----------------------------
CREATE TABLE IF NOT EXISTS `cpe_device` (
    `id`                      BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `sn`                      VARCHAR(70) NOT NULL COMMENT '序列号',
    `oui`                     VARCHAR(32)  DEFAULT NULL COMMENT '厂商 OUI',
    `manufacturer`            VARCHAR(64)  DEFAULT NULL COMMENT '厂商',
    `product_class`           VARCHAR(64)  DEFAULT NULL COMMENT '产品类别',
    `software_version`        VARCHAR(64)  DEFAULT NULL COMMENT '软件版本',
    `hardware_version`        VARCHAR(64)  DEFAULT NULL COMMENT '硬件版本',
    `root_node`               VARCHAR(64)  NOT NULL DEFAULT 'InternetGatewayDevice.' COMMENT '根数据模型节点',
    `connection_request_url`  VARCHAR(255) DEFAULT NULL COMMENT '反向连接地址',
    `cwmp_version`            VARCHAR(32)  NOT NULL DEFAULT 'cwmp-1-0' COMMENT 'CWMP 命名空间版本',
    `auth_type`               VARCHAR(16)  NOT NULL DEFAULT 'digest' COMMENT '认证类型',
    `cpe_to_acs_name`         VARCHAR(64)  DEFAULT NULL COMMENT 'CPE 连 ACS 用户名',
    `cpe_to_acs_password`     VARCHAR(64)  DEFAULT NULL,
    `acs_to_cpe_name`         VARCHAR(64)  DEFAULT NULL COMMENT 'ACS 连 CPE 用户名',
    `acs_to_cpe_password`     VARCHAR(64)  DEFAULT NULL,
    `isp`                     VARCHAR(16)  DEFAULT NULL COMMENT '所属运营商',
    `isp_version`             VARCHAR(16)  DEFAULT NULL COMMENT '运营商版本',
    `device_type`             VARCHAR(64)  DEFAULT NULL COMMENT '设备类型',
    `room_no`                 VARCHAR(32)  DEFAULT NULL COMMENT '房间号',
    `remark`                  VARCHAR(255) DEFAULT NULL COMMENT '备注',
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'CPE 设备';

-- ---------------------------- RPC 记录 ----------------------------
CREATE TABLE IF NOT EXISTS `rpc_record` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `rpc_name`      VARCHAR(50) NOT NULL COMMENT 'CWMP 方法名',
    `sn`            VARCHAR(70) NOT NULL COMMENT '设备序列号',
    `worklist_id`   BIGINT      DEFAULT NULL COMMENT '关联工单',
    `request_args`  TEXT        COMMENT '请求参数 JSON',
    `result_content` MEDIUMTEXT COMMENT '结果内容',
    `status`        VARCHAR(16) NOT NULL DEFAULT 'init' COMMENT 'init/success/fail/timeout',
    `start_time`    DATETIME    DEFAULT NULL,
    `finish_time`   DATETIME    DEFAULT NULL,
    `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_rpc_record_sn` (`sn`),
    KEY `idx_rpc_record_worklist` (`worklist_id`),
    KEY `idx_rpc_record_created` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'RPC 记录';

-- ---------------------------- SOAP 报文日志 ----------------------------
CREATE TABLE IF NOT EXISTS `soap_message` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `rpc_id`       BIGINT      DEFAULT NULL COMMENT '关联 RPC',
    `sn`           VARCHAR(70) NOT NULL COMMENT '设备序列号',
    `msg_type`     VARCHAR(64)  DEFAULT NULL COMMENT '报文类型',
    `direction`    VARCHAR(4)   DEFAULT NULL COMMENT '方向：in/out',
    `event_code`   VARCHAR(64)  DEFAULT NULL COMMENT 'Inform 事件码',
    `content_body` MEDIUMTEXT   COMMENT '报文体',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_soap_message_sn` (`sn`),
    KEY `idx_soap_message_rpc` (`rpc_id`),
    KEY `idx_soap_message_created` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'SOAP 报文日志';

-- ---------------------------- 设备参数能力树 ----------------------------
CREATE TABLE IF NOT EXISTS `cpe_parameter` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `sn`            VARCHAR(70)  NOT NULL COMMENT '设备序列号',
    `param_path`    VARCHAR(400) NOT NULL COMMENT '参数完整路径，对象以英文句点结尾',
    `parent_path`   VARCHAR(400) DEFAULT NULL COMMENT '父对象路径',
    `node_name`     VARCHAR(128) DEFAULT NULL COMMENT '路径末段名',
    `node_type`     VARCHAR(16)  NOT NULL DEFAULT 'param' COMMENT 'object 对象 / param 参数',
    `writable`      TINYINT      NOT NULL DEFAULT 0 COMMENT '1 可写 0 只读',
    `param_value`   TEXT         COMMENT '参数当前值',
    `value_type`    VARCHAR(32)  DEFAULT NULL COMMENT '值类型',
    `depth`         INT          NOT NULL DEFAULT 0 COMMENT '层级深度',
    `collect_batch` VARCHAR(32)  DEFAULT NULL COMMENT '采集批次号',
    `collect_time`  DATETIME     DEFAULT NULL COMMENT '采集时间',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cpe_parameter` (`sn`, `param_path`),
    KEY `idx_cpe_parameter_parent` (`sn`, `parent_path`),
    KEY `idx_cpe_parameter_type` (`sn`, `node_type`),
    KEY `idx_cpe_parameter_collect` (`sn`, `collect_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '设备参数能力树';

-- ---------------------------- 配置意图模板 ----------------------------
CREATE TABLE IF NOT EXISTS `config_template` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(128) NOT NULL COMMENT '模板名称',
    `category`    VARCHAR(32)  NOT NULL COMMENT '类型',
    `group_name`  VARCHAR(64)  COMMENT '业务分组',
    `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    `remark`      VARCHAR(255) COMMENT '备注',
    `fields`      TEXT         COMMENT '意图字段 JSON',
    `incremental` TINYINT      NOT NULL DEFAULT 0 COMMENT '1 增量业务，不改写已有业务',
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_template` (`category`, `name`),
    KEY `idx_config_template_cat` (`category`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '配置意图模板';

-- ---------------------------- 工单 ----------------------------
CREATE TABLE IF NOT EXISTS `worklist` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `worklist_desc`    VARCHAR(128) DEFAULT NULL COMMENT '工单唯一描述',
    `worklist_name`    VARCHAR(128) DEFAULT NULL COMMENT '工单名',
    `worklist_type`    VARCHAR(16)  DEFAULT 'physic' COMMENT 'physic/logic',
    `sn`               VARCHAR(70)  NOT NULL COMMENT '设备序列号',
    `isp`              VARCHAR(16)  DEFAULT NULL COMMENT '运营商',
    `isp_version`      VARCHAR(16)  DEFAULT NULL COMMENT '运营商版本',
    `domain`           VARCHAR(64)  DEFAULT NULL COMMENT '设备类型',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'build' COMMENT '工单状态',
    `rollback_flag`    TINYINT      NOT NULL DEFAULT 0 COMMENT '失败是否回退',
    `parameters`       MEDIUMTEXT   COMMENT '工单参数 JSON',
    `result`           MEDIUMTEXT   COMMENT '执行结果 JSON',
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单';

-- ---------------------------- 工单执行步骤明细 ----------------------------
CREATE TABLE IF NOT EXISTS `worklist_step` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `worklist_id`    BIGINT       NOT NULL COMMENT '所属工单',
    `step_index`     INT          NOT NULL COMMENT '步骤序号',
    `comment`        VARCHAR(255) DEFAULT NULL COMMENT '步骤说明',
    `rpc`            VARCHAR(64)  NOT NULL COMMENT 'CWMP 方法名',
    `request_args`   MEDIUMTEXT   COMMENT '下发参数 JSON',
    `response_content` MEDIUMTEXT COMMENT '设备返回结果 JSON',
    `status`         VARCHAR(16)  NOT NULL DEFAULT 'init' COMMENT 'init/success/fail/skip',
    `error_msg`      VARCHAR(1024) DEFAULT NULL COMMENT '失败原因',
    `duration_ms`    BIGINT       DEFAULT NULL COMMENT '耗时(毫秒)',
    `deleted`        TINYINT      NOT NULL DEFAULT 0,
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_worklist_step_wl` (`worklist_id`, `step_index`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单执行步骤明细';

-- ---------------------------- 监控规则 ----------------------------
CREATE TABLE IF NOT EXISTS `monitor_rule` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `monitor_desc` VARCHAR(128) NOT NULL COMMENT '规则唯一描述',
    `type`         VARCHAR(32)  DEFAULT NULL COMMENT '监控类型',
    `status`       VARCHAR(16)  DEFAULT NULL,
    `status_desc`  VARCHAR(255) DEFAULT NULL,
    `cfg`          MEDIUMTEXT   COMMENT '规则配置 JSON',
    `sn`           VARCHAR(70)  DEFAULT NULL,
    `time_start`   DATETIME     DEFAULT NULL,
    `time_stop`    DATETIME     DEFAULT NULL,
    `deleted`      TINYINT      NOT NULL DEFAULT 0,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_monitor_rule_desc` (`monitor_desc`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '监控规则';

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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '监控结果';

-- ---------------------------- 系统日志 ----------------------------
CREATE TABLE IF NOT EXISTS `sys_log` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `message`     VARCHAR(255) DEFAULT NULL,
    `message_ext` MEDIUMTEXT   COMMENT '扩展信息',
    `time_start`  DATETIME     DEFAULT NULL,
    `time_finish` DATETIME     DEFAULT NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_sys_log_created` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统日志';

-- ---------------------------- 业务模板 ----------------------------
CREATE TABLE IF NOT EXISTS `biz_template` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`             VARCHAR(128) NOT NULL COMMENT '业务模板名称',
    `group_name`       VARCHAR(64)  DEFAULT NULL COMMENT '业务分组',
    `remark`           VARCHAR(512) DEFAULT NULL COMMENT '备注',
    `enabled`          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    `continue_on_error` TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '成员失败是否继续执行',
    `created_at`       DATETIME     DEFAULT NULL,
    `updated_at`       DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_template_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务模板';

CREATE TABLE IF NOT EXISTS `biz_template_item` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `template_id`  BIGINT       NOT NULL COMMENT '所属业务模板',
    `template_key` VARCHAR(512) NOT NULL COMMENT '配置模板 key',
    `item_order`   INT          NOT NULL DEFAULT 0 COMMENT '执行顺序',
    `params`       TEXT         COMMENT '入参覆盖 JSON',
    `remark`       VARCHAR(512) DEFAULT NULL COMMENT '成员备注',
    PRIMARY KEY (`id`),
    KEY `idx_biz_item_template` (`template_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务模板成员';

CREATE TABLE IF NOT EXISTS `biz_template_execution` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `template_id`   BIGINT      NOT NULL COMMENT '业务模板 id',
    `template_name` VARCHAR(128) DEFAULT NULL COMMENT '业务模板名',
    `sn`            VARCHAR(128) NOT NULL COMMENT '设备序列号',
    `status`        VARCHAR(32) NOT NULL COMMENT 'running/success/partial/fail',
    `username`      VARCHAR(64) DEFAULT NULL COMMENT '操作人',
    `total_count`   INT         NOT NULL DEFAULT 0 COMMENT '成员总数',
    `finished_count` INT        NOT NULL DEFAULT 0 COMMENT '已执行成员数',
    `success_count` INT         NOT NULL DEFAULT 0 COMMENT '成功数',
    `fail_count`    INT         NOT NULL DEFAULT 0 COMMENT '失败数',
    `current_key`   VARCHAR(512) DEFAULT NULL COMMENT '正在执行的成员',
    `result`        TEXT        COMMENT '各成员执行结果 JSON',
    `error_message` VARCHAR(1024) DEFAULT NULL COMMENT '中止原因',
    `created_at`    DATETIME    DEFAULT NULL,
    `finished_at`   DATETIME    DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_biz_exec_sn` (`sn`),
    KEY `idx_biz_exec_template` (`template_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务模板下发记录';

-- ---------------------------- 型号档案 ----------------------------
CREATE TABLE IF NOT EXISTS `cpe_model` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `isp`               VARCHAR(16)  NOT NULL DEFAULT 'standard' COMMENT '运营商',
    `model_key`         VARCHAR(128) NOT NULL COMMENT '型号标识，型号_固件',
    `manufacturer`      VARCHAR(64)  DEFAULT NULL COMMENT '厂商',
    `product_class`     VARCHAR(64)  DEFAULT NULL COMMENT '型号',
    `software_version`  VARCHAR(64)  DEFAULT NULL COMMENT '固件版本',
    `hardware_version`  VARCHAR(64)  DEFAULT NULL COMMENT '硬件版本',
    `config_path`       VARCHAR(255) DEFAULT NULL COMMENT '能力树文件相对路径',
    `adapt_path`        VARCHAR(255) DEFAULT NULL COMMENT '适配状态文件相对路径',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / READY / DISABLED',
    `sample_sn`         VARCHAR(64)  DEFAULT NULL COMMENT '样机序列号',
    `root_node`         VARCHAR(64)  NOT NULL DEFAULT 'InternetGatewayDevice.' COMMENT '根数据模型节点',
    `cap_lan_ports`     VARCHAR(16)  DEFAULT NULL COMMENT '申报网口数量',
    `cap_lan_speed`     VARCHAR(16)  DEFAULT NULL COMMENT '申报网口速率',
    `cap_sip_count`     VARCHAR(16)  DEFAULT NULL COMMENT '申报 SIP 数量',
    `cap_wifi_spec`     VARCHAR(32)  DEFAULT NULL COMMENT '申报 WIFI 规格',
    `remark`            VARCHAR(255) DEFAULT NULL,
    `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cpe_model` (`isp`, `model_key`),
    KEY `idx_cpe_model_pc` (`product_class`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '光猫型号档案';

-- ---------------------------- 型号语义映射 ----------------------------
CREATE TABLE IF NOT EXISTS `model_param_mapping` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `model_id`    BIGINT       NOT NULL COMMENT 'cpe_model.id',
    `biz_object`  VARCHAR(32)  NOT NULL COMMENT '业务对象',
    `biz_field`   VARCHAR(64)  NOT NULL COMMENT '业务字段',
    `cwmp_path`   VARCHAR(512) NOT NULL COMMENT '真实参数路径，实例号用 {i} 占位',
    `value_type`  VARCHAR(32)  DEFAULT NULL COMMENT '值类型',
    `writable`    TINYINT      NOT NULL DEFAULT 1 COMMENT '该节点实际可写',
    `required`    TINYINT      NOT NULL DEFAULT 0 COMMENT '缺失时是否阻断下发',
    `remark`      VARCHAR(255) DEFAULT NULL,
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_field` (`model_id`, `biz_object`, `biz_field`),
    KEY `idx_mapping_model` (`model_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务字段到 CWMP 参数节点映射';

CREATE TABLE IF NOT EXISTS `model_object_mapping` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `model_id`      BIGINT      NOT NULL COMMENT 'cpe_model.id',
    `biz_object`    VARCHAR(32) NOT NULL COMMENT '业务对象',
    `parent_path`   VARCHAR(512) NOT NULL COMMENT '连接设备路径',
    `object_name`   VARCHAR(128) NOT NULL COMMENT 'AddObject 的对象名',
    `instance_mode` VARCHAR(16) NOT NULL DEFAULT 'ADD' COMMENT 'ADD 新建 / EXISTING 复用',
    `delete_before` TINYINT     NOT NULL DEFAULT 1 COMMENT '创建前是否先删旧实例',
    `remark`        VARCHAR(255) DEFAULT NULL,
    `created_at`    DATETIME    DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_object` (`model_id`, `biz_object`),
    KEY `idx_object_model` (`model_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务对象到 CWMP 对象路径映射';

-- ---------------------------- 下发任务 ----------------------------
CREATE TABLE IF NOT EXISTS `device_task` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_no`       VARCHAR(64)  NOT NULL COMMENT '业务单号，幂等用',
    `sn`            VARCHAR(64)  NOT NULL COMMENT '设备序列号',
    `category`      VARCHAR(32)  NOT NULL COMMENT '业务类型',
    `template_id`   BIGINT       DEFAULT NULL COMMENT 'config_template.id',
    `source`        VARCHAR(16)  NOT NULL DEFAULT 'admin' COMMENT '来源：openapi / admin',
    `created_by`    VARCHAR(64)  DEFAULT NULL COMMENT '发起方账号',
    `intent_fields` TEXT         COMMENT '意图字段快照 JSON',
    `plan`          TEXT         COMMENT '下发计划 JSON',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / SUCCESS / FAILED / REJECTED',
    `error_msg`     VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `finished_at`   DATETIME     DEFAULT NULL COMMENT '完成时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_task_no` (`source`, `task_no`),
    KEY `idx_device_task_sn` (`sn`, `id`),
    KEY `idx_device_task_status` (`status`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '下发任务';

-- ---------------------------- 北向 Webhook 订阅 ----------------------------
CREATE TABLE IF NOT EXISTS `openapi_webhook` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(128) NOT NULL COMMENT '订阅名称',
    `url`         VARCHAR(512) NOT NULL COMMENT '回调地址',
    `secret`      VARCHAR(128) DEFAULT NULL COMMENT '签名密钥',
    `event_types` VARCHAR(255) NOT NULL COMMENT '事件类型，* 表示全部',
    `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_by`  VARCHAR(64)  DEFAULT NULL,
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '北向 Webhook 订阅';

-- ---------------------------- Webhook 投递记录 ----------------------------
CREATE TABLE IF NOT EXISTS `openapi_webhook_delivery` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `webhook_id`    BIGINT      NOT NULL COMMENT 'openapi_webhook.id',
    `event_id`      VARCHAR(128) NOT NULL COMMENT '事件唯一号，接收方去重用',
    `event_type`    VARCHAR(64) NOT NULL,
    `payload`       TEXT        COMMENT '推送内容 JSON',
    `status`        VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SUCCESS / FAILED',
    `attempts`      INT         NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    `next_retry_at` DATETIME    DEFAULT NULL COMMENT '下次重试时间',
    `response_code` INT         DEFAULT NULL COMMENT '回调 HTTP 状态码',
    `response_body` VARCHAR(512) DEFAULT NULL COMMENT '回调响应片段',
    `created_at`    DATETIME    DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_webhook_event` (`webhook_id`, `event_id`),
    KEY `idx_delivery_retry` (`status`, `next_retry_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '北向事件投递记录';

-- ---------------------------- OLT 设备 ----------------------------
CREATE TABLE IF NOT EXISTS `olt_device` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`             VARCHAR(128) NOT NULL COMMENT 'OLT 名称',
    `host`             VARCHAR(128) NOT NULL COMMENT '地址',
    `protocol`         VARCHAR(16)  NOT NULL DEFAULT 'TELNET' COMMENT 'TELNET / SSH',
    `username`         VARCHAR(64)  DEFAULT NULL COMMENT '登录账号',
    `password`         VARCHAR(128) DEFAULT NULL COMMENT '登录密码',
    `port`             INT          NOT NULL DEFAULT 23 COMMENT '登录端口',
    `charset`          VARCHAR(32)  NOT NULL DEFAULT 'AUTO' COMMENT '字符集编码',
    `bind_local_ip`    VARCHAR(64)  DEFAULT NULL COMMENT '绑定本地出口 IP',
    `web_accounts`     VARCHAR(255) DEFAULT NULL COMMENT '允许访问的 Web 账号，空不限',
    `vendor`           VARCHAR(32)  DEFAULT NULL COMMENT '厂商',
    `model`            VARCHAR(128) DEFAULT NULL COMMENT '型号',
    `software_version` VARCHAR(128) DEFAULT NULL COMMENT '软件版本',
    `patch_version`    VARCHAR(64)  DEFAULT NULL COMMENT '补丁版本',
    `backplane`        VARCHAR(64)  DEFAULT NULL COMMENT '背板型号',
    `slot_count`       INT          DEFAULT NULL COMMENT '槽位数',
    `mac_address`      VARCHAR(32)  DEFAULT NULL COMMENT '主控 MAC',
    `uptime`           VARCHAR(64)  DEFAULT NULL COMMENT '运行时长',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN / ONLINE / OFFLINE',
    `last_connect_at`  DATETIME     DEFAULT NULL COMMENT '最近连接成功时间',
    `last_sync_at`     DATETIME     DEFAULT NULL COMMENT '最近同步时间',
    `last_error`       VARCHAR(500) DEFAULT NULL COMMENT '最近失败原因',
    `sync_summary`     VARCHAR(255) DEFAULT NULL COMMENT '最近同步摘要',
    `vlan_json`        LONGTEXT     DEFAULT NULL COMMENT 'VLAN 快照 JSON',
    `template_json`    LONGTEXT     DEFAULT NULL COMMENT '模板快照 JSON',
    `remark`           VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `deleted`          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_olt_device_name` (`name`, `deleted`),
    KEY `idx_olt_device_host` (`host`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'OLT 设备台账';

-- ---------------------------- OLT 板卡 ----------------------------
CREATE TABLE IF NOT EXISTS `olt_board` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `olt_id`      BIGINT      NOT NULL COMMENT 'olt_device.id',
    `slot_no`     INT         NOT NULL COMMENT '槽位号',
    `board_type`  VARCHAR(64) DEFAULT NULL COMMENT '板卡型号',
    `status`      VARCHAR(32) DEFAULT NULL COMMENT '设备原始状态',
    `board_kind`  VARCHAR(16) DEFAULT NULL COMMENT '板卡类别',
    `description` VARCHAR(128) DEFAULT NULL COMMENT '板卡描述',
    `port_mode`   VARCHAR(16) DEFAULT NULL COMMENT '端口类型',
    `port_count`  INT         NOT NULL DEFAULT 0 COMMENT '端口数量',
    `temperature` INT         DEFAULT NULL COMMENT '温度(摄氏度)',
    `cpu_usage`   VARCHAR(16) DEFAULT NULL COMMENT 'CPU 占用',
    `mem_usage`   VARCHAR(16) DEFAULT NULL COMMENT '内存占用',
    `remark`      VARCHAR(255) DEFAULT NULL,
    `ports_json`  LONGTEXT     DEFAULT NULL COMMENT '端口状态快照 JSON',
    `synced_at`   DATETIME    DEFAULT NULL COMMENT '同步时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_olt_board_slot` (`olt_id`, `slot_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'OLT 板卡信息';

-- ---------------------------- OLT 采集记录 ----------------------------
CREATE TABLE IF NOT EXISTS `olt_sync_record` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `olt_id`     BIGINT       DEFAULT NULL COMMENT 'olt_device.id',
    `action`     VARCHAR(16)  NOT NULL COMMENT '动作：TEST/SYNC/CONFIG/QUERY/DELETE',
    `success`    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否成功',
    `message`    VARCHAR(1000) DEFAULT NULL COMMENT '结果说明',
    `raw_output` MEDIUMTEXT   COMMENT '设备回显原文',
    `cost_ms`    INT          DEFAULT NULL COMMENT '耗时(毫秒)',
    `operator`   VARCHAR(64)  DEFAULT NULL COMMENT '操作人',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_olt_sync_record` (`olt_id`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'OLT 采集与操作记录';

-- ---------------------------- ONT 注册模板 ----------------------------
CREATE TABLE IF NOT EXISTS `olt_ont_reg_template` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `olt_id`           BIGINT       DEFAULT NULL COMMENT 'olt_device.id，NULL 表示通用',
    `olt_ip`           VARCHAR(64)  DEFAULT NULL COMMENT '绑定的 OLT 地址',
    `name`             VARCHAR(64)  NOT NULL COMMENT '模板名称',
    `priority`         INT          NOT NULL DEFAULT 0 COMMENT '优先级，小的先匹配',
    `enabled`          TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    `auth_mode`        VARCHAR(16)  NOT NULL DEFAULT 'SN' COMMENT '注册方式：SN/MAC/LOID/PASSWORD',
    `pon_type`         VARCHAR(16)  NOT NULL DEFAULT 'GPON' COMMENT 'GPON / EPON',
    `line_profile_id`  INT          DEFAULT NULL COMMENT '线路模板 ID',
    `srv_profile_id`   INT          DEFAULT NULL COMMENT '业务模板 ID',
    `bind_ports`       VARCHAR(255) DEFAULT NULL COMMENT '限定的 PON 口，空不限',
    `ont_models`       VARCHAR(255) DEFAULT NULL COMMENT '限定的 ONT 型号，空不限',
    `work_mode`        VARCHAR(16)  DEFAULT NULL COMMENT '工作模式',
    `service_vlan_map` VARCHAR(255) DEFAULT NULL COMMENT '业务 VLAN 映射',
    `tr069_vlan`       VARCHAR(64)  DEFAULT NULL COMMENT 'TR069 VLAN',
    `iptv_vlan`        VARCHAR(64)  DEFAULT NULL COMMENT 'IPTV VLAN',
    `multicast_vlan`   VARCHAR(64)  DEFAULT NULL COMMENT '组播 VLAN',
    `remark`           VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `deleted`          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_ont_reg_template` (`olt_id`, `enabled`, `priority`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'ONT 注册模板';

-- ---------------------------- ONT 自动注册参数 ----------------------------
CREATE TABLE IF NOT EXISTS `olt_ont_reg_config` (
    `id`                 BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `olt_id`             BIGINT   NOT NULL COMMENT 'olt_device.id',
    `enabled`            TINYINT  NOT NULL DEFAULT 0 COMMENT '是否启用自动注册',
    `scan_seconds`       INT      NOT NULL DEFAULT 30 COMMENT '扫描间隔(秒)',
    `save_seconds`       INT      NOT NULL DEFAULT 28800 COMMENT '自动存盘间隔(秒)',
    `concurrency`        INT      NOT NULL DEFAULT 1 COMMENT '单轮注册数上限',
    `auto_move`          TINYINT  NOT NULL DEFAULT 0 COMMENT '换 PON 口是否自动迁移',
    `auto_delete_offline` TINYINT NOT NULL DEFAULT 0 COMMENT '注册表满时是否删离线 ONT',
    `last_scan_at`       DATETIME DEFAULT NULL COMMENT '上次扫描时间',
    `last_register_at`   DATETIME DEFAULT NULL COMMENT '上次注册时间',
    `last_save_at`       DATETIME DEFAULT NULL COMMENT '上次存盘时间',
    `created_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ont_reg_config_olt` (`olt_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'ONT 自动注册参数';

-- ---------------------------- ONT 注册记录 ----------------------------
CREATE TABLE IF NOT EXISTS `olt_ont_reg_record` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `olt_id`        BIGINT        DEFAULT NULL COMMENT 'olt_device.id',
    `template_id`   BIGINT        DEFAULT NULL COMMENT 'olt_ont_reg_template.id',
    `template_name` VARCHAR(64)   DEFAULT NULL COMMENT '模板名称快照',
    `fsp`           VARCHAR(32)   DEFAULT NULL COMMENT '框/槽/端口',
    `ont_sn`        VARCHAR(64)   DEFAULT NULL COMMENT 'ONT SN',
    `model`         VARCHAR(64)   DEFAULT NULL COMMENT 'ONT 型号',
    `auth_mode`     VARCHAR(16)   DEFAULT NULL COMMENT '注册方式',
    `success`       TINYINT       NOT NULL DEFAULT 0 COMMENT '是否成功',
    `source`        VARCHAR(16)   NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO 自动 / MANUAL 手动',
    `command`       VARCHAR(500)  DEFAULT NULL COMMENT '下发的命令',
    `message`       VARCHAR(1000) DEFAULT NULL COMMENT '结果说明',
    `operator`      VARCHAR(64)   DEFAULT NULL COMMENT '操作人',
    `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_ont_reg_record` (`olt_id`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'ONT 注册记录';

-- ---------------------------- 一键开局参数 ----------------------------
CREATE TABLE IF NOT EXISTS `olt_quick_setup_config` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `olt_id`      BIGINT   NOT NULL COMMENT 'olt_device.id',
    `config_json` LONGTEXT DEFAULT NULL COMMENT '开局参数快照 JSON',
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_quick_setup_config_olt` (`olt_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'OLT 一键开局参数';

-- ---------------------------- 设备预设 ----------------------------
CREATE TABLE IF NOT EXISTS `cpe_preset` (
    `id`                         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`                       VARCHAR(64)  NOT NULL COMMENT '预设名称',
    `priority`                   INT          NOT NULL DEFAULT 100 COMMENT '优先级，小的先执行',
    `enabled`                    TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    `conditions_json`            TEXT         COMMENT '匹配条件 JSON，空命中全部',
    `actions_json`               TEXT         NOT NULL COMMENT '动作 JSON',
    `reprovision_interval_hours` INT          NOT NULL DEFAULT 24 COMMENT '重跑最小间隔(小时)，0 每次都跑',
    `force_on_bootstrap`         TINYINT      NOT NULL DEFAULT 0 COMMENT 'BOOTSTRAP 时是否强制重跑',
    `built_in`                   TINYINT      NOT NULL DEFAULT 0 COMMENT '1 内置不可删',
    `remark`                     VARCHAR(255) DEFAULT NULL,
    `created_at`                 DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                 DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_preset_run` (`enabled`, `priority`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '设备预设';

-- ---------------------------- 文件 ----------------------------
CREATE TABLE IF NOT EXISTS `cpe_file` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`       VARCHAR(128) NOT NULL COMMENT '文件名',
    `file_type`  VARCHAR(32)  NOT NULL COMMENT 'FIRMWARE / CONFIG / VENDOR',
    `version`    VARCHAR(64)  DEFAULT NULL COMMENT '版本号',
    `size`       BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
    `sha256`     VARCHAR(64)  DEFAULT NULL COMMENT '内容摘要',
    `store_path` VARCHAR(255) NOT NULL COMMENT '落盘相对路径',
    `remark`     VARCHAR(255) DEFAULT NULL,
    `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_file_type` (`file_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件台账';

SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------- 初始化角色与权限 ----------------------------
INSERT IGNORE INTO `sys_role` (`code`, `name`, `builtin`, `remark`)
VALUES ('SUPER_ADMIN', '系统管理员', 1, '拥有全部权限，不可删除，权限不可修改');
INSERT IGNORE INTO `sys_role` (`code`, `name`, `builtin`, `remark`)
VALUES ('OPS_ADMIN', '运维管理员', 0, '默认能看全部、能改设备与工单，权限可调整');

INSERT IGNORE INTO `sys_role_permission` (`role_code`, `permission`) VALUES
('OPS_ADMIN', 'dashboard:view'),
('OPS_ADMIN', 'device:view'),
('OPS_ADMIN', 'device:update'),
('OPS_ADMIN', 'device:delete'),
('OPS_ADMIN', 'template:view'),
('OPS_ADMIN', 'biz:view'),
('OPS_ADMIN', 'model:view'),
('OPS_ADMIN', 'worklist:view'),
('OPS_ADMIN', 'worklist:update'),
('OPS_ADMIN', 'worklist:delete'),
('OPS_ADMIN', 'olt:view'),
('OPS_ADMIN', 'olt:update'),
('OPS_ADMIN', 'olt:delete'),
('OPS_ADMIN', 'file:view'),
('OPS_ADMIN', 'file:update'),
('OPS_ADMIN', 'file:delete'),
('OPS_ADMIN', 'ops:view');

-- ---------------------------- 初始化管理员 ----------------------------
INSERT IGNORE INTO `sys_user` (`id`, `username`, `password`, `nickname`, `role`, `status`)
VALUES (1, 'admin', 'admin', '系统管理员', 'SUPER_ADMIN', 1);

-- ---------------------------- 角色值迁移 ----------------------------
UPDATE `sys_user` SET `role` = 'SUPER_ADMIN' WHERE `role` IN ('admin', 'ADMIN');
UPDATE `sys_user` SET `role` = 'OPS_ADMIN' WHERE `role` IN ('operator', 'viewer') OR `role` IS NULL;

-- ---------------------------- 权限点迁移 ----------------------------
-- 把 :manage 拆成 :update 与 :delete
INSERT IGNORE INTO `sys_role_permission` (`role_code`, `permission`)
SELECT `role_code`, CONCAT(SUBSTRING(`permission`, 1, CHAR_LENGTH(`permission`) - 7), ':update')
FROM `sys_role_permission` WHERE `permission` LIKE '%:manage';
INSERT IGNORE INTO `sys_role_permission` (`role_code`, `permission`)
SELECT `role_code`, CONCAT(SUBSTRING(`permission`, 1, CHAR_LENGTH(`permission`) - 7), ':delete')
FROM `sys_role_permission` WHERE `permission` LIKE '%:manage';
DELETE FROM `sys_role_permission` WHERE `permission` LIKE '%:manage';

-- ---------------------------- 远程运维配置（装维 APP 扫码登录）----------------------------
CREATE TABLE IF NOT EXISTS `ops_remote_config` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `token`            VARCHAR(128) DEFAULT NULL COMMENT '装维 APP 登录 Token',
    `permissions`      VARCHAR(255) DEFAULT NULL COMMENT '操作权限，逗号分隔：ticket/config/reboot',
    `device_scope`     VARCHAR(32)  DEFAULT 'all' COMMENT '可见设备范围：all/online/5m/10m/15m/1h/2h/24h',
    `subdomain`        VARCHAR(64)  DEFAULT NULL COMMENT '内网穿透分配的随机二级域名前缀（10位[a-z0-9]）',
    `instance_id`      VARCHAR(64)  DEFAULT NULL COMMENT 'ACS 实例唯一标识（UUID）',
    `remote_enabled`   TINYINT      DEFAULT 0 COMMENT '是否启用内网穿透：0否 1是',
    `lan_qr_content`   VARCHAR(512) DEFAULT NULL COMMENT '内网本地登录二维码内容',
    `cloud_qr_content` VARCHAR(512) DEFAULT NULL COMMENT '外网云登录二维码内容',
    `apk_qr_content`   VARCHAR(512) DEFAULT NULL COMMENT 'APK 下载二维码内容',
    `token_created_at` DATETIME     DEFAULT NULL COMMENT 'Token 生成时间',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '远程运维配置（装维 APP）';
