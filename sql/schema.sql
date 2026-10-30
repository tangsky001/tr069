
-- ============================================================
-- pilotacs-server 全库建表脚本（唯一权威版本）
--
-- 全库只有这一份脚本：手工部署执行它，应用启动时自动补表也读它
-- （构建时复制到 classpath:db/schema.sql，见 pom.xml 的 <resources>）。
-- 所有语句都是 CREATE TABLE IF NOT EXISTS，反复执行无害。
--
-- 各模块的补建组件（xxxSchemaInitializer）在这里按表名挑出自己需要的语句，
-- 互不越权：关掉某个模块的 acs.<module>.auto-create-table 依然只对该模块生效。
-- ============================================================

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
    `last_login_source` VARCHAR(16) DEFAULT NULL COMMENT '最近登录来源：WEB/CLIENT/MOBILE',
    `last_login_ip` VARCHAR(64)  DEFAULT NULL COMMENT '最近登录 IP',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_username` (`username`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '管理端用户';

-- ---------------------------- 角色 ----------------------------
CREATE TABLE IF NOT EXISTS `sys_role` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `code`       VARCHAR(32) NOT NULL COMMENT '角色编码，如 SUPER_ADMIN',
    `name`       VARCHAR(64) NOT NULL COMMENT '角色名',
    `builtin`    TINYINT     NOT NULL DEFAULT 0 COMMENT '内置：1 不可删除、权限不可改',
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
    `permission`  VARCHAR(64) NOT NULL COMMENT '权限点，如 device:manage',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_permission` (`role_code`, `permission`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色权限';

-- ---------------------------- 删用户的底线（数据库兜底） ----------------------------
-- 应用层的 UserAdminService 已经拦了，这里再拦一道：
-- 有人在 MySQL 命令行里 delete from sys_user 也一样删不掉。
-- 注意这段用了 DELIMITER，整份脚本请用 mysql 客户端执行，别用按分号拆语句的程序喂。
DROP TRIGGER IF EXISTS `trg_sys_user_before_delete`;
DELIMITER //
CREATE TRIGGER `trg_sys_user_before_delete` BEFORE DELETE ON `sys_user`
FOR EACH ROW
BEGIN
    -- 初始的系统账号，删完就没人能进系统
    IF LOWER(OLD.`username`) = 'admin' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '系统用户禁止删除';
    END IF;
    -- 系统管理员只剩这一个时不能再删，否则没人能改权限
    IF OLD.`role` = 'SUPER_ADMIN'
        AND (SELECT COUNT(*) FROM `sys_user` WHERE `role` = 'SUPER_ADMIN') <= 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '至少要留一个系统管理员';
    END IF;
END//
DELIMITER ;

-- ---------------------------- 登录日志 ----------------------------
-- 失败的也记：连着几十次失败是有人在试密码，只记成功的话这条线索就没了
CREATE TABLE IF NOT EXISTS `sys_login_log` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT      DEFAULT NULL COMMENT '用户 id，用户名不存在时为 NULL',
    `username`    VARCHAR(64) DEFAULT NULL COMMENT '尝试登录的用户名',
    `ip`          VARCHAR(64) DEFAULT NULL COMMENT '来源 IP',
    `source`      VARCHAR(16) DEFAULT NULL COMMENT '来源：WEB/CLIENT/MOBILE',
    `user_agent`  VARCHAR(255) DEFAULT NULL COMMENT '浏览器或客户端标识',
    `success`     TINYINT     NOT NULL DEFAULT 0 COMMENT '1 成功 0 失败',
    `fail_reason` VARCHAR(64) DEFAULT NULL COMMENT '失败原因',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_login_log_user` (`user_id`, `created_at`),
    KEY `idx_login_log_time` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '登录日志';

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
    `room_no`                 VARCHAR(32)  DEFAULT NULL COMMENT '房间号，下发时替换 %room% 变量',
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

-- ---------------------------- 配置意图模板 ----------------------------
-- 只描述开通意图（IP 协议、是否带 VLAN、是否绑定端口等），不含参数定义与执行步骤；
-- 下发时由执行器按型号语义映射（model_param_mapping）生成该机型真实 CWMP 参数
CREATE TABLE IF NOT EXISTS `config_template` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(128) NOT NULL COMMENT '模板名称',
    `category`    VARCHAR(32)  NOT NULL COMMENT '类型：WAN_DHCP / WAN_PPPOE / WAN_STATIC / WAN_BRIDGE / WIFI_24G / WIFI_58G / SIP',
    `group_name`  VARCHAR(64)  COMMENT '业务分组，可空',
    `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    `remark`      VARCHAR(255) COMMENT '备注',
    `fields`      TEXT         COMMENT '意图字段 JSON，键值与型号语义映射的 biz_field 对齐',
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_template` (`category`, `name`),
    KEY `idx_config_template_cat` (`category`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '配置意图模板（只做开通意图描述，不直接下发）';

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


-- ---------------------------- 型号语义映射 ----------------------------
-- 业务语义模板（固定）与型号 CWMP 节点映射（一次性人工适配），
-- 下发执行器据此把意图字段翻译成该机型真实参数路径。

-- 型号档案：同型号 + 同固件共用一套映射，只登记一次
CREATE TABLE IF NOT EXISTS `cpe_model` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `isp`               VARCHAR(16)  NOT NULL DEFAULT 'standard' COMMENT '运营商 CT/CU/CMCC/standard',
    `model_key`         VARCHAR(128) NOT NULL COMMENT '型号标识，建议 型号_固件，如 HS8145V5_V5R19',
    `manufacturer`      VARCHAR(64)  DEFAULT NULL COMMENT '厂商，来自 DeviceInfo.Manufacturer',
    `product_class`     VARCHAR(64)  DEFAULT NULL COMMENT '型号，来自 DeviceInfo.ProductClass / ModelName',
    `software_version`  VARCHAR(64)  DEFAULT NULL COMMENT '固件版本，来自 DeviceInfo.SoftwareVersion',
    `hardware_version`  VARCHAR(64)  DEFAULT NULL COMMENT '硬件版本',
    `config_path`       VARCHAR(255) DEFAULT NULL COMMENT '该型号能力树文件相对路径，如 CM/standard/p22g-ciac.xml',
    `adapt_path`        VARCHAR(255) DEFAULT NULL COMMENT '适配状态文件(yml)相对路径，adapted=true 才算已适配，如 CM/standard/p22g-ciac.yml',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待适配 / READY 已映射 / DISABLED 停用',
    `sample_sn`         VARCHAR(64)  DEFAULT NULL COMMENT '做映射时参考的样机序列号',
    `root_node`         VARCHAR(64)  NOT NULL DEFAULT 'InternetGatewayDevice.' COMMENT '样机的根数据模型节点：Device.(TR-181) / InternetGatewayDevice.(TR-098)',
    `cap_lan_ports`     VARCHAR(16)  DEFAULT NULL COMMENT '适配申报网口数量：无/1/2/3/4-8',
    `cap_lan_speed`     VARCHAR(16)  DEFAULT NULL COMMENT '适配申报网口速率：无/自动/100M/1000M/2500M',
    `cap_sip_count`     VARCHAR(16)  DEFAULT NULL COMMENT '适配申报SIP数量：无/1/2/3/4-8',
    `cap_wifi_spec`     VARCHAR(32)  DEFAULT NULL COMMENT '适配申报WIFI规格：无/2.4G/2.4G+5.8G',
    `remark`            VARCHAR(255) DEFAULT NULL,
    `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cpe_model` (`isp`, `model_key`),
    KEY `idx_cpe_model_pc` (`product_class`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '光猫型号档案';

-- 语义字段 -> 该型号真实 CWMP 参数节点
-- 例：Internet_WAN.vlan_id -> InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.{i}.VLANIDMark
CREATE TABLE IF NOT EXISTS `model_param_mapping` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `model_id`    BIGINT       NOT NULL COMMENT 'cpe_model.id',
    `biz_object`  VARCHAR(32)  NOT NULL COMMENT '业务对象：Internet_WAN / VOIP_WAN / Bridge_WAN / WiFi_2G / WiFi_5G',
    `biz_field`   VARCHAR(64)  NOT NULL COMMENT '业务字段：vlan_id / ssid / wpa_key ...',
    `cwmp_path`   VARCHAR(512) NOT NULL COMMENT '真实参数路径，实例号用 {i} 占位',
    `value_type`  VARCHAR(32)  DEFAULT NULL COMMENT 'string / unsignedInt / boolean，下发时推断 xsi:type',
    `writable`    TINYINT      NOT NULL DEFAULT 1 COMMENT '该节点实际可写',
    `required`    TINYINT      NOT NULL DEFAULT 0 COMMENT '缺失时是否阻断下发',
    `remark`      VARCHAR(255) DEFAULT NULL,
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_field` (`model_id`, `biz_object`, `biz_field`),
    KEY `idx_mapping_model` (`model_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '型号语义字段到 CWMP 节点映射';

-- 业务对象 -> 该型号 CWMP 父对象路径（AddObject / DeleteObject 用）
-- 例：Internet_WAN -> InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.
CREATE TABLE IF NOT EXISTS `model_object_mapping` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `model_id`      BIGINT      NOT NULL COMMENT 'cpe_model.id',
    `biz_object`    VARCHAR(32) NOT NULL COMMENT '业务对象',
    `parent_path`   VARCHAR(512) NOT NULL COMMENT '连接设备路径，如 ...WANConnectionDevice.1',
    `object_name`   VARCHAR(128) NOT NULL COMMENT 'AddObject 的对象名，如 ...WANConnectionDevice.1.WANIPConnection.',
    `instance_mode` VARCHAR(16) NOT NULL DEFAULT 'ADD' COMMENT 'ADD 新建实例 / EXISTING 复用现有实例',
    `delete_before` TINYINT     NOT NULL DEFAULT 1 COMMENT '创建前是否先删除该对象下的旧实例',
    `remark`        VARCHAR(255) DEFAULT NULL,
    `created_at`    DATETIME    DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_object` (`model_id`, `biz_object`),
    KEY `idx_object_model` (`model_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务对象到 CWMP 对象路径映射';

-- ---------------------------- 下发任务 ----------------------------
-- 意图模板经型号语义映射渲染成 CWMP 参数后的执行记录
CREATE TABLE IF NOT EXISTS `device_task` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_no`       VARCHAR(64)  NOT NULL COMMENT '外部业务单号，与 source 一起保证幂等',
    `sn`            VARCHAR(64)  NOT NULL COMMENT '设备序列号',
    `category`      VARCHAR(32)  NOT NULL COMMENT '业务类型：WAN_DHCP / WAN_PPPOE / WAN_STATIC / WAN_BRIDGE / WIFI_24G / WIFI_58G / SIP',
    `template_id`   BIGINT       DEFAULT NULL COMMENT 'config_template.id，可空',
    `source`        VARCHAR(16)  NOT NULL DEFAULT 'admin' COMMENT '来源：openapi / admin',
    `created_by`    VARCHAR(64)  DEFAULT NULL COMMENT '发起方账号，北向下发时记录调用者',
    `intent_fields` TEXT         COMMENT '意图字段快照 JSON',
    `plan`          TEXT         COMMENT '渲染出的下发计划 JSON：参数名 / 值 / 类型',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / SUCCESS / FAILED / REJECTED',
    `error_msg`     VARCHAR(1000) DEFAULT NULL COMMENT '失败原因：设备返回的 CWMP 故障或校验拦截原因',
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `finished_at`   DATETIME     DEFAULT NULL COMMENT '落地时间，未完成为空',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_task_no` (`source`, `task_no`),
    KEY `idx_device_task_sn` (`sn`, `id`),
    KEY `idx_device_task_status` (`status`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '下发任务';

-- ---------------------------- 北向 Webhook 订阅 ----------------------------
-- 哪个 URL 关心哪几类事件
CREATE TABLE IF NOT EXISTS `openapi_webhook` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(128) NOT NULL COMMENT '订阅名称，便于识别对接方',
    `url`         VARCHAR(512) NOT NULL COMMENT '回调地址，ACS 通过 HTTP POST 推送',
    `secret`      VARCHAR(128) DEFAULT NULL COMMENT '签名密钥，生成 X-ACS-Signature 供接收方校验来源',
    `event_types` VARCHAR(255) NOT NULL COMMENT '关心的事件类型，逗号分隔；* 表示全部',
    `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_by`  VARCHAR(64)  DEFAULT NULL,
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '北向 Webhook 订阅';

-- 投递记录：每条事件 × 每个订阅一行，失败按退避重试
CREATE TABLE IF NOT EXISTS `openapi_webhook_delivery` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `webhook_id`    BIGINT      NOT NULL COMMENT 'openapi_webhook.id',
    `event_id`      VARCHAR(128) NOT NULL COMMENT '事件唯一号，接收方据此去重',
    `event_type`    VARCHAR(64) NOT NULL,
    `payload`       TEXT        COMMENT '推送内容 JSON',
    `status`        VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SUCCESS / FAILED',
    `attempts`      INT         NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    `next_retry_at` DATETIME    DEFAULT NULL COMMENT '下次重试时间，为空表示不再重试',
    `response_code` INT         DEFAULT NULL COMMENT '最后一次回调的 HTTP 状态码',
    `response_body` VARCHAR(512) DEFAULT NULL COMMENT '最后一次回调的响应片段',
    `created_at`    DATETIME    DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_webhook_event` (`webhook_id`, `event_id`),
    KEY `idx_delivery_retry` (`status`, `next_retry_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '北向事件投递记录';

-- ---------------------------- OLT 设备 ----------------------------
-- OLT 不走 TR-069，靠 Telnet/SSH 登录设备执行命令行采集，因此单独建台账。
-- 地址与端口是连接参数，vendor/model/software 是登录成功后采集回填的结果。
CREATE TABLE IF NOT EXISTS `olt_device` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`             VARCHAR(128) NOT NULL COMMENT 'OLT 名称，留空时按地址生成',
    `host`             VARCHAR(128) NOT NULL COMMENT 'OLT 地址：IP 或域名',
    `protocol`         VARCHAR(16)  NOT NULL DEFAULT 'TELNET' COMMENT '登录类型：TELNET / SSH',
    `username`         VARCHAR(64)  DEFAULT NULL COMMENT '登录账号，空则按厂商默认值',
    `password`         VARCHAR(128) DEFAULT NULL COMMENT '登录密码，接口不回显',
    `port`             INT          NOT NULL DEFAULT 23 COMMENT '登录端口，Telnet 默认 23，SSH 默认 22',
    `charset`          VARCHAR(32)  NOT NULL DEFAULT 'AUTO' COMMENT '字符集编码：AUTO / UTF-8 / GBK / GB18030',
    `bind_local_ip`    VARCHAR(64)  DEFAULT NULL COMMENT '绑定本地访问出口 IP，多网卡时指定源地址',
    `web_accounts`     VARCHAR(255) DEFAULT NULL COMMENT '允许访问的 Web 登录账号，逗号分隔，空表示不限制',
    `vendor`           VARCHAR(32)  DEFAULT NULL COMMENT '厂商：HUAWEI / ZTE / FIBERHOME / GENERIC',
    `model`            VARCHAR(128) DEFAULT NULL COMMENT '型号，登录后采集',
    `software_version` VARCHAR(128) DEFAULT NULL COMMENT '软件版本，登录后采集',
    `patch_version`    VARCHAR(64)  DEFAULT NULL COMMENT '补丁版本，如 SPC107',
    `backplane`        VARCHAR(64)  DEFAULT NULL COMMENT '背板/主板型号，如 H802MABO',
    `slot_count`       INT          DEFAULT NULL COMMENT '机框槽位数（display board 0 槽位表里最大 SlotID + 1）',
    `mac_address`      VARCHAR(32)  DEFAULT NULL COMMENT '主控 MAC',
    `uptime`           VARCHAR(64)  DEFAULT NULL COMMENT '运行时长，如 59天18小时21分11秒',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN' COMMENT '状态：UNKNOWN / ONLINE / OFFLINE',
    `last_connect_at`  DATETIME     DEFAULT NULL COMMENT '最近一次连接成功时间',
    `last_sync_at`     DATETIME     DEFAULT NULL COMMENT '最近一次同步时间',
    `last_error`       VARCHAR(500) DEFAULT NULL COMMENT '最近一次失败原因',
    `sync_summary`     VARCHAR(255) DEFAULT NULL COMMENT '最近一次同步摘要',
    `vlan_json`        LONGTEXT     DEFAULT NULL COMMENT 'VLAN 快照(JSON)',
    `template_json`    LONGTEXT     DEFAULT NULL COMMENT 'DBA/线路模板快照(JSON)',
    `remark`           VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `deleted`          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_olt_device_name` (`name`, `deleted`),
    KEY `idx_olt_device_host` (`host`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'OLT 设备台账';

-- 板卡信息：每次同步按槽位覆盖写，槽位已拔出的板卡会被清掉
CREATE TABLE IF NOT EXISTS `olt_board` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `olt_id`      BIGINT      NOT NULL COMMENT 'olt_device.id',
    `slot_no`     INT         NOT NULL COMMENT '槽位号',
    `board_type`  VARCHAR(64) DEFAULT NULL COMMENT '板卡类型，如 H901GPHF / GTGO',
    `status`      VARCHAR(32) DEFAULT NULL COMMENT '设备原始状态：Normal / Active_normal / inservice ...',
    `board_kind`  VARCHAR(16) DEFAULT NULL COMMENT '板卡类别：SERVICE / CONTROL / UPLINK / POWER / INTERFACE / UNKNOWN',
    `description` VARCHAR(128) DEFAULT NULL COMMENT '板卡描述，如 8口GPON（业务板）',
    `port_mode`   VARCHAR(16) DEFAULT NULL COMMENT '端口类型：GPON / EPON / 10GE，解析不出时为空',
    `port_count`  INT         NOT NULL DEFAULT 0 COMMENT '端口数量，设备未返回时为 0',
    `temperature` INT         DEFAULT NULL COMMENT '温度(摄氏度)',
    `cpu_usage`   VARCHAR(16) DEFAULT NULL COMMENT 'CPU 占用',
    `mem_usage`   VARCHAR(16) DEFAULT NULL COMMENT '内存占用',
    `remark`      VARCHAR(255) DEFAULT NULL,
    `ports_json`  LONGTEXT     DEFAULT NULL COMMENT '端口光模块状态快照(JSON)：portNo / opticStatus / opticalPresent / rxPower',
    `synced_at`   DATETIME    DEFAULT NULL COMMENT '本次同步时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_olt_board_slot` (`olt_id`, `slot_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'OLT 板卡信息';

-- 采集记录：诊断/同步的原始回显留档，型号解析不对时按它改正则
CREATE TABLE IF NOT EXISTS `olt_sync_record` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `olt_id`     BIGINT       DEFAULT NULL COMMENT 'olt_device.id，未保存的设备为 NULL',
    `action`     VARCHAR(16)  NOT NULL COMMENT '动作：TEST 诊断测试 / SYNC 同步',
    `success`    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否成功',
    `message`    VARCHAR(1000) DEFAULT NULL COMMENT '结果说明或失败原因',
    `raw_output` MEDIUMTEXT   COMMENT '设备回显原文，超长截断',
    `cost_ms`    INT          DEFAULT NULL COMMENT '耗时(毫秒)',
    `operator`   VARCHAR(64)  DEFAULT NULL COMMENT '操作人',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_olt_sync_record` (`olt_id`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'OLT 连接与同步记录';

-- 预设：设备上报后按条件自动把配置收敛到目标值，取代逐台手工下发
CREATE TABLE IF NOT EXISTS `cpe_preset` (
    `id`                         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`                       VARCHAR(64)  NOT NULL COMMENT '预设名称',
    `priority`                   INT          NOT NULL DEFAULT 100 COMMENT '优先级，小的先执行',
    `enabled`                    TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    `conditions_json`            TEXT         COMMENT '匹配条件 JSON，为空表示命中所有设备',
    `actions_json`               TEXT         NOT NULL COMMENT '动作 JSON',
    `reprovision_interval_hours` INT          NOT NULL DEFAULT 24 COMMENT '距上次执行超过该小时数才重跑，0 表示每次上报都跑',
    `force_on_bootstrap`         TINYINT      NOT NULL DEFAULT 0 COMMENT 'BOOTSTRAP 时是否跳过节流强制重跑',
    `built_in`                   TINYINT      NOT NULL DEFAULT 0 COMMENT '内置预设不允许删除',
    `remark`                     VARCHAR(255) DEFAULT NULL,
    `created_at`                 DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                 DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_preset_run` (`enabled`, `priority`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '设备预设：上报后自动收敛配置';

-- 文件：固件与配置，供设备 Download 取用
CREATE TABLE IF NOT EXISTS `cpe_file` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`       VARCHAR(128) NOT NULL COMMENT '文件名',
    `file_type`  VARCHAR(32)  NOT NULL COMMENT '类型：FIRMWARE 固件 / CONFIG 配置 / VENDOR 厂商文件',
    `version`    VARCHAR(64)  DEFAULT NULL COMMENT '版本号，固件用',
    `size`       BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
    `sha256`     VARCHAR(64)  DEFAULT NULL COMMENT '内容摘要，设备与 ACS 都用它校验',
    `store_path` VARCHAR(255) NOT NULL COMMENT '落盘的相对路径，不是对外 URL',
    `remark`     VARCHAR(255) DEFAULT NULL,
    `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_file_type` (`file_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件：固件与配置';

-- 已下线的设备标签表（cpe_device_tag）：分群改用台账上的房间号 room_no，
-- 建库脚本不再建这张表；历史库里还留着的可以直接 DROP TABLE cpe_device_tag;

SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------- 初始化角色 ----------------------------
-- 系统管理员：拥有全部权限，权限表里没有它的行，代码里直接放行
INSERT IGNORE INTO `sys_role` (`code`, `name`, `builtin`, `remark`)
VALUES ('SUPER_ADMIN', '系统管理员', 1, '拥有全部权限，不可删除，权限不可修改');
INSERT IGNORE INTO `sys_role` (`code`, `name`, `builtin`, `remark`)
VALUES ('OPS_ADMIN', '运维管理员', 0, '默认能看全部、能改设备与工单，权限可调整');

INSERT IGNORE INTO `sys_role_permission` (`role_code`, `permission`) VALUES
('OPS_ADMIN', 'dashboard:view'),
('OPS_ADMIN', 'device:view'),
('OPS_ADMIN', 'device:manage'),
('OPS_ADMIN', 'template:view'),
('OPS_ADMIN', 'biz:view'),
('OPS_ADMIN', 'model:view'),
('OPS_ADMIN', 'worklist:view'),
('OPS_ADMIN', 'worklist:manage'),
('OPS_ADMIN', 'olt:view'),
('OPS_ADMIN', 'olt:manage'),
('OPS_ADMIN', 'file:view'),
('OPS_ADMIN', 'file:manage');

-- ---------------------------- 初始化管理员 ----------------------------
INSERT IGNORE INTO `sys_user` (`id`, `username`, `password`, `nickname`, `role`, `status`)
VALUES (1, 'admin', 'admin', '系统管理员', 'SUPER_ADMIN', 1);

-- ---------------------------- 老角色值迁移 ----------------------------
-- 升级前的 role 是 admin / operator / viewer，迁到角色编码上，
-- 不迁的话老账号会因为角色不存在而一个权限都没有
UPDATE `sys_user` SET `role` = 'SUPER_ADMIN' WHERE `role` IN ('admin', 'ADMIN');
UPDATE `sys_user` SET `role` = 'OPS_ADMIN' WHERE `role` IN ('operator', 'viewer') OR `role` IS NULL;
