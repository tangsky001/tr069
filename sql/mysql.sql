-- TR-069 ACS 权限框架初始化脚本（MySQL 8 / MariaDB 10.6+）
CREATE DATABASE IF NOT EXISTS `acs` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `acs`;

-- 用户表
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`         VARCHAR(64)  NOT NULL COMMENT '登录名',
    `password`         VARCHAR(100) NOT NULL COMMENT 'sha256(salt+password)',
    `salt`             VARCHAR(32)  NOT NULL COMMENT '盐',
    `nickname`         VARCHAR(64)  DEFAULT NULL COMMENT '显示名',
    `role_code`        VARCHAR(64)  NOT NULL COMMENT '角色编码',
    `status`           TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
    `last_login_ip`    VARCHAR(64)  DEFAULT NULL COMMENT '最后登录IP',
    `last_login_time`  DATETIME     DEFAULT NULL COMMENT '最后登录时间',
    `last_login_source` VARCHAR(16) DEFAULT NULL COMMENT 'WEB/CLIENT/MOBILE',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_role_code` (`role_code`)
) ENGINE = InnoDB COMMENT '系统用户';

-- 角色表（code 为主键）
CREATE TABLE IF NOT EXISTS `sys_role` (
    `code`       VARCHAR(64) NOT NULL COMMENT '角色编码',
    `name`       VARCHAR(64) NOT NULL COMMENT '角色名称',
    `builtin`    TINYINT     NOT NULL DEFAULT 0 COMMENT '1内置 0自定义，内置禁止删除/改权限',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`code`)
) ENGINE = InnoDB COMMENT '角色';

-- 角色权限关联（存权限码：模块:动作）
CREATE TABLE IF NOT EXISTS `sys_role_permission` (
    `role_code` VARCHAR(64)  NOT NULL,
    `perm_code` VARCHAR(128) NOT NULL,
    PRIMARY KEY (`role_code`, `perm_code`)
) ENGINE = InnoDB COMMENT '角色权限';

-- 登录日志
CREATE TABLE IF NOT EXISTS `sys_login_log` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`   VARCHAR(64)  NOT NULL COMMENT '登录名',
    `ip`         VARCHAR(64)  NOT NULL COMMENT '登录IP',
    `source`     VARCHAR(16)  NOT NULL COMMENT 'WEB/CLIENT/MOBILE',
    `success`    TINYINT      NOT NULL COMMENT '1成功 0失败',
    `user_agent` VARCHAR(512) DEFAULT NULL COMMENT 'UA',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
    PRIMARY KEY (`id`),
    KEY `idx_username` (`username`),
    KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB COMMENT '登录日志';

-- 远程运维配置表（全局一份）
CREATE TABLE IF NOT EXISTS `ops_remote_config` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `token`            VARCHAR(512) NOT NULL COMMENT '装维APP登录Token（完整JWT）',
    `permissions`      VARCHAR(128) DEFAULT NULL COMMENT '操作权限，逗号分隔：ticket/config/reboot',
    `device_scope`     VARCHAR(16)  DEFAULT 'all' COMMENT '可见设备范围',
    `subdomain`        VARCHAR(32)  DEFAULT NULL COMMENT '内网穿透二级域名前缀',
    `instance_id`      VARCHAR(64)  DEFAULT NULL COMMENT 'ACS 实例唯一标识',
    `remote_enabled`   TINYINT      DEFAULT 0 COMMENT '是否启用内网穿透 0否 1是',
    `lan_qr_content`   VARCHAR(255) DEFAULT NULL COMMENT '内网登录二维码内容',
    `cloud_qr_content` VARCHAR(255) DEFAULT NULL COMMENT '外网登录二维码内容',
    `apk_qr_content`   VARCHAR(255) DEFAULT NULL COMMENT 'APK下载二维码内容',
    `token_created_at` DATETIME     DEFAULT NULL COMMENT 'Token 生成时间',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB COMMENT '远程运维配置';

-- 内置角色：超级管理员（全部权限）/ 运维管理员 / 装维员
INSERT INTO `sys_role` (`code`, `name`, `builtin`) VALUES
('SUPER_ADMIN', '超级管理员', 1),
('OPS_ADMIN',   '运维管理员', 0),
('INSTALLER',   '装维员',     0);

-- 种子用户（初始密码均为admin/admin 其他用户 123456）
INSERT INTO `sys_user` (`id`, `username`, `password`, `salt`, `nickname`, `role_code`, `status`) VALUES
(1, 'admin',     'dca9656aa5627e9800a47ff4409b50881f029cdd86d06135f8884b4be5518443', 'f3a9c2e8d7b6', '超级管理员', 'SUPER_ADMIN', 1),
(2, 'opsadmin',  '5c2e8f6ac9afe1b3b62c2aaee56aa56554c68d702093fd30696baa4dadb02ae6', 'a1b2c3d4e5f6', '运维管理员', 'OPS_ADMIN',   1),
(3, 'installer', 'd9ba3ad7f13422b0b7796f496fdea05624433a95f93b0953e8c8a050ab211faa', '9e8d7c6b5a4f', '装维员',     'INSTALLER',   1);

-- 运维管理员：全部权限
INSERT INTO `sys_role_permission` (`role_code`, `perm_code`)
SELECT 'OPS_ADMIN', p.`code` FROM (
    SELECT 'dashboard:view' AS code UNION ALL
    SELECT 'template:view' UNION ALL SELECT 'template:update' UNION ALL SELECT 'template:delete' UNION ALL
    SELECT 'biz:view' UNION ALL SELECT 'biz:update' UNION ALL SELECT 'biz:delete' UNION ALL
    SELECT 'device:view' UNION ALL SELECT 'device:update' UNION ALL SELECT 'device:delete' UNION ALL
    SELECT 'file:view' UNION ALL SELECT 'file:update' UNION ALL SELECT 'file:delete' UNION ALL
    SELECT 'worklist:view' UNION ALL SELECT 'worklist:update' UNION ALL SELECT 'worklist:delete' UNION ALL
    SELECT 'olt:view' UNION ALL SELECT 'olt:update' UNION ALL SELECT 'olt:delete' UNION ALL
    SELECT 'system:view' UNION ALL SELECT 'system:update' UNION ALL
    SELECT 'ops:view' UNION ALL SELECT 'ops:update'
) p;

-- 装维员：仅查看
INSERT INTO `sys_role_permission` (`role_code`, `perm_code`) VALUES
('INSTALLER', 'dashboard:view'),
('INSTALLER', 'template:view'),
('INSTALLER', 'biz:view'),
('INSTALLER', 'device:view'),
('INSTALLER', 'file:view'),
('INSTALLER', 'worklist:view'),
('INSTALLER', 'olt:view');

-- ITMS 对接角色与账号（默认仅主页权限；权限仅超级管理员可编辑，账号禁止删除）
INSERT INTO `sys_role` (`code`, `name`, `builtin`) VALUES
('ITMS', 'ITMS平台', 0);
INSERT INTO `sys_user` (`id`, `username`, `password`, `salt`, `nickname`, `role_code`, `status`) VALUES
(4, 'itms', 'ed0243d91d219bb0caa469fa5259ea46aafc534061ab7b23a89efa223fb0d5a4', 'itms2026salt1234', 'ITMS对接', 'ITMS', 1);
INSERT INTO `sys_role_permission` (`role_code`, `perm_code`) VALUES
('ITMS', 'dashboard:view');

-- ==================== OLT（Telnet 采集）七张表，与 OltSchemaInitializer 保持一致 ====================
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
) ENGINE = InnoDB COMMENT 'OLT 设备台账';

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
) ENGINE = InnoDB COMMENT 'OLT 板卡';

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
) ENGINE = InnoDB COMMENT 'OLT 采集记录';

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
    `service_vlan_map` VARCHAR(255) DEFAULT NULL COMMENT 'OLT 侧业务 VLAN 映射：空=不映射业务；一行一组可多组，2 个数=光猫侧 OLT 侧（如 104 46 / 4011 46），1 个数=透传',
    `tr069_vlan`     VARCHAR(16)  DEFAULT NULL COMMENT '下发 TR069 VLAN 及 ACS 地址，如 46 10（管理 VLAN + tr069-server profile-id），空=不下发 ont tr069-server-config',
    `iptv_vlan`      VARCHAR(16)  DEFAULT NULL COMMENT 'IPTV VLAN',
    `multicast_vlan` VARCHAR(16)  DEFAULT NULL COMMENT '组播 VLAN',
    `remark`         VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '1已删除',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_olt` (`olt_id`)
) ENGINE = InnoDB COMMENT 'ONT 注册模板';

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
) ENGINE = InnoDB COMMENT 'ONT 自动注册配置';

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
) ENGINE = InnoDB COMMENT 'ONT 注册记录';

CREATE TABLE IF NOT EXISTS `olt_quick_setup_config` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `olt_id`      BIGINT   NOT NULL COMMENT 'olt_device.id',
    `config_json` LONGTEXT DEFAULT NULL COMMENT '快速开通参数(JSON)',
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_olt` (`olt_id`)
) ENGINE = InnoDB COMMENT '快速开通参数';

-- ============ OLT / 光猫分组：各一套物理表（树 + 归属 + ACL），互不混 ============
-- 旧单表 res_group / res_group_member / res_acl 已由启动迁移分流到下面对应新表（旧表保留，确认无误后可手工删除）

-- 自定义分组节点（OLT 树）：骨架来自 civilCode.csv（省→市→区县，不落库），此处只存手动创建/挂靠的节点
CREATE TABLE IF NOT EXISTS `olt_res_group` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `parent_id`         BIGINT       DEFAULT NULL COMMENT '父自定义分组 id；挂在区划下时为 null',
    `parent_civil_code` VARCHAR(12)  DEFAULT NULL COMMENT '父区划编号（civilCode）；挂在自定义分组下时为 null',
    `civil_code`        VARCHAR(12)  DEFAULT NULL COMMENT '节点关联的区划编号（civilCode，可选）',
    `name`              VARCHAR(64)  NOT NULL COMMENT '分组名称',
    `sort`              INT          DEFAULT 0 COMMENT '排序号，同级从小到大',
    `remark`            VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `created_by`        VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_parent` (`parent_id`),
    KEY `idx_parent_civil` (`parent_civil_code`),
    KEY `idx_civil` (`civil_code`)
) ENGINE = InnoDB COMMENT 'OLT 自定义分组（挂在区划树上）';

-- OLT 成员归属关系（group_ref 统一引用：ROOT / G:id；member_type 恒 OLT）
CREATE TABLE IF NOT EXISTS `olt_res_group_member` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `group_ref`   VARCHAR(32)  NOT NULL COMMENT '分组节点引用：ROOT 或 G:自定义分组 id',
    `member_type` VARCHAR(16)  NOT NULL DEFAULT 'OLT' COMMENT '成员类型：本表只存 OLT',
    `member_id`   BIGINT       NOT NULL COMMENT '成员主键：olt_device.id',
    `member_ref`  VARCHAR(64)  DEFAULT NULL COMMENT '成员业务标识：同 member_id 字符串',
    `member_label` VARCHAR(128) DEFAULT NULL COMMENT '绑定时的成员名称快照',
    `remark`      VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `created_by`  VARCHAR(64)  DEFAULT NULL COMMENT '操作人',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_member` (`member_ref`),
    KEY `idx_group_ref` (`group_ref`)
) ENGINE = InnoDB COMMENT 'OLT 分组成员归属关系';

-- 自定义分组节点（光猫树）：与 OLT 树结构相同、数据独立
CREATE TABLE IF NOT EXISTS `onu_res_group` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `parent_id`         BIGINT       DEFAULT NULL COMMENT '父自定义分组 id；挂在区划下时为 null',
    `parent_civil_code` VARCHAR(12)  DEFAULT NULL COMMENT '父区划编号（civilCode）；挂在自定义分组下时为 null',
    `civil_code`        VARCHAR(12)  DEFAULT NULL COMMENT '节点关联的区划编号（civilCode，可选）',
    `name`              VARCHAR(64)  NOT NULL COMMENT '分组名称',
    `sort`              INT          DEFAULT 0 COMMENT '排序号，同级从小到大',
    `remark`            VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `created_by`        VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_parent` (`parent_id`),
    KEY `idx_parent_civil` (`parent_civil_code`),
    KEY `idx_civil` (`civil_code`)
) ENGINE = InnoDB COMMENT '光猫自定义分组（挂在区划树上）';

-- 光猫成员归属关系（group_ref 统一引用：ROOT / G:id；member_type 恒 ONU，member_ref = 光猫 SN）
CREATE TABLE IF NOT EXISTS `onu_res_group_member` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `group_ref`   VARCHAR(32)  NOT NULL COMMENT '分组节点引用：ROOT 或 G:自定义分组 id',
    `member_type` VARCHAR(16)  NOT NULL DEFAULT 'ONU' COMMENT '成员类型：本表只存 ONU（光猫）',
    `member_id`   BIGINT       NOT NULL COMMENT '成员主键：光猫模块未接入，恒 0',
    `member_ref`  VARCHAR(64)  DEFAULT NULL COMMENT '成员业务标识：光猫标识（如 SN）',
    `member_label` VARCHAR(128) DEFAULT NULL COMMENT '绑定时的成员名称快照',
    `remark`      VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `created_by`  VARCHAR(64)  DEFAULT NULL COMMENT '操作人',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_member` (`member_ref`),
    KEY `idx_group_ref` (`group_ref`)
) ENGINE = InnoDB COMMENT '光猫分组成员归属关系';

-- ============ 设备/分组级 ACL（OLT 与光猫各一套，规则相同） ============
CREATE TABLE IF NOT EXISTS `olt_res_acl` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `principal_id` BIGINT      NOT NULL COMMENT '授权对象：sys_user.id',
    `scope_type`   VARCHAR(10) NOT NULL COMMENT '范围类型：device / group',
    `scope_id`     VARCHAR(40) NOT NULL COMMENT '范围引用：设备为 olt_device.id；分组为 ROOT / G:id',
    `perms`        VARCHAR(64) NOT NULL DEFAULT 'view' COMMENT '授予动作，逗号分隔：view,operate,update,delete',
    `created_by`   VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_principal_scope` (`principal_id`, `scope_type`, `scope_id`)
) ENGINE = InnoDB COMMENT 'OLT 设备/分组级 ACL';

CREATE TABLE IF NOT EXISTS `onu_res_acl` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `principal_id` BIGINT      NOT NULL COMMENT '授权对象：sys_user.id',
    `scope_type`   VARCHAR(10) NOT NULL COMMENT '范围类型：device / group',
    `scope_id`     VARCHAR(40) NOT NULL COMMENT '范围引用：分组为 ROOT / G:id（光猫设备级待模块接入）',
    `perms`        VARCHAR(64) NOT NULL DEFAULT 'view' COMMENT '授予动作，逗号分隔：view,operate,update,delete',
    `created_by`   VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_principal_scope` (`principal_id`, `scope_type`, `scope_id`)
) ENGINE = InnoDB COMMENT '光猫设备/分组级 ACL';

-- OPS_ADMIN 增加 ACL 管理权限码（幂等；老库手动执行一次即可）
INSERT INTO `sys_role_permission` (`role_code`, `perm_code`)
SELECT 'OPS_ADMIN', 'acl:manage' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_permission` WHERE `role_code` = 'OPS_ADMIN' AND `perm_code` = 'acl:manage');

-- ============ 光猫台账（CWMP/TR-069）：台账与任务在 MySQL，参数树/机型模板在 SQLite template.pack ============
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

CREATE TABLE IF NOT EXISTS `cpe_device_snapshot` (
    `sn`            VARCHAR(64) NOT NULL COMMENT '序列号（主键）',
    `snapshot_json` MEDIUMTEXT  COMMENT '业务快照 JSON（WAN/LAN/WIFI/SIP）',
    `updated_at`    BIGINT      DEFAULT NULL COMMENT '刷新毫秒时间戳',
    PRIMARY KEY (`sn`)
) ENGINE = InnoDB COMMENT '光猫业务快照（会话结束/采集完成时刷新）';

CREATE TABLE IF NOT EXISTS `cpe_task` (
    `id`             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_no`        VARCHAR(40) DEFAULT NULL COMMENT '任务编号',
    `sn`             VARCHAR(64) NOT NULL COMMENT '设备序列号',
    `type`           VARCHAR(40) NOT NULL COMMENT '任务类型 Reboot/GPV/SPV/RefreshBusiness 等',
    `params_json`    MEDIUMTEXT  COMMENT '任务参数 JSON（业务刷新的叶子清单可能很大）',
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
-- =====================================================================
-- 配置模板 / 业务模板（对应 TemplateMysqlSchemaInitializer，启动自动补建）
-- =====================================================================

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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '配置意图模板';

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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务模板';

CREATE TABLE IF NOT EXISTS `biz_template_item` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '成员 id',
    `template_id`  BIGINT       NOT NULL COMMENT '所属业务模板 id',
    `template_key` VARCHAR(64)  NOT NULL COMMENT '引用的配置模板 id',
    `item_order`   INT          DEFAULT 0 COMMENT '执行顺序',
    `params`       TEXT         COMMENT '入参覆盖 JSON',
    `remark`       VARCHAR(255) DEFAULT '' COMMENT '成员备注',
    PRIMARY KEY (`id`),
    KEY `idx_bti_tpl` (`template_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务模板成员';

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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务模板执行记录';
