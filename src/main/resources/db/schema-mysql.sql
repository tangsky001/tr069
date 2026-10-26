-- =============================================================
--  TR-069 ACS  MySQL 台账库
--  只存：账号/权限、设备台账、参数、任务、审计、日志
--  光猫适配数据（能力树/参数映射）不在这里，存放在 SQLite
-- =============================================================

-- ---------------- 用户与权限 ----------------
CREATE TABLE IF NOT EXISTS sys_user (
  id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
  username      VARCHAR(64)  NOT NULL UNIQUE,
  password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt',
  display_name  VARCHAR(64),
  status        TINYINT      DEFAULT 1 COMMENT '1启用 0停用',
  create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_role (
  id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_code VARCHAR(32) NOT NULL UNIQUE,
  role_name VARCHAR(64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 型号 / 配置模板 ----------------
-- 型号只保留运营元数据，能力树在 SQLite 的 device_template
CREATE TABLE IF NOT EXISTS unit_type (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  name        VARCHAR(100) NOT NULL UNIQUE COMMENT '型号名，如 HS8145V6',
  vendor      VARCHAR(64),
  description VARCHAR(255),
  protocol    VARCHAR(16)  DEFAULT 'TR069',
  create_time DATETIME     DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 型号参数元数据（读写标记），可从 SQLite 能力树同步
CREATE TABLE IF NOT EXISTS unit_type_param (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  unit_type_id BIGINT       NOT NULL,
  name         VARCHAR(255) NOT NULL,
  flags        VARCHAR(32)  DEFAULT 'R' COMMENT 'R/RW/... 组合标记',
  UNIQUE KEY uk_utp (unit_type_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS profile (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  unit_type_id BIGINT      NOT NULL,
  name         VARCHAR(64) NOT NULL,
  description  VARCHAR(255),
  UNIQUE KEY uk_profile (unit_type_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 配置模板参数目标值
CREATE TABLE IF NOT EXISTS profile_param (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  profile_id BIGINT       NOT NULL,
  name       VARCHAR(255) NOT NULL,
  value      VARCHAR(512),
  UNIQUE KEY uk_pp (profile_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 设备台账 ----------------
CREATE TABLE IF NOT EXISTS cpe_device (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  unit_id          VARCHAR(64) NOT NULL UNIQUE COMMENT 'OUI-ProductClass-SerialNumber',
  serial_number    VARCHAR(64) NOT NULL UNIQUE COMMENT 'CWMP DeviceId.SerialNumber',
  oui              VARCHAR(16),
  manufacturer     VARCHAR(64),
  product_class    VARCHAR(64),
  model            VARCHAR(100),
  firmware_version VARCHAR(64),
  account          VARCHAR(64) COMMENT '该设备 CWMP 接入账号（Basic Auth）',
  secret           VARCHAR(128) COMMENT '该设备 CWMP 接入密码',
  unit_type_id     BIGINT,
  profile_id       BIGINT,
  ip_address       VARCHAR(64),
  vlan_id          INT COMMENT '业务 VLAN，如 41/46',
  bind_olt         VARCHAR(64) COMMENT '预留：所属 OLT / 端口位',
  remark           VARCHAR(255),
  provisioning_mode VARCHAR(16) DEFAULT 'REGULAR' COMMENT 'REGULAR/READALL',
  online           TINYINT     DEFAULT 0,
  first_online     DATETIME,
  last_online      DATETIME,
  create_time      DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_cpe_model (model, firmware_version),
  KEY idx_cpe_serial (serial_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 设备参数（ACS 侧目标值 / 只读回写值）
CREATE TABLE IF NOT EXISTS unit_param (
  id      BIGINT PRIMARY KEY AUTO_INCREMENT,
  unit_id VARCHAR(64)  NOT NULL,
  name    VARCHAR(255) NOT NULL,
  value   VARCHAR(512),
  flags   VARCHAR(32)  DEFAULT 'R',
  UNIQUE KEY uk_up (unit_id, name),
  KEY idx_up_value (name, value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 会话级临时参数（READALL 采集模式使用）
CREATE TABLE IF NOT EXISTS unit_param_session (
  unit_id VARCHAR(64)  NOT NULL,
  name    VARCHAR(255) NOT NULL,
  value   VARCHAR(512),
  PRIMARY KEY (unit_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 固件 / 配置文件 ----------------
CREATE TABLE IF NOT EXISTS file_store (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  unit_type_id BIGINT,
  name         VARCHAR(128) NOT NULL,
  type         VARCHAR(32)  DEFAULT 'SOFTWARE' COMMENT 'SOFTWARE/TR069_SCRIPT/SHELL_SCRIPT/MISC',
  version      VARCHAR(64),
  description  VARCHAR(255),
  target_name  VARCHAR(128),
  length       BIGINT DEFAULT 0,
  content      LONGBLOB,
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 下发任务 ----------------
CREATE TABLE IF NOT EXISTS dispatch_task (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_no        VARCHAR(40) NOT NULL UNIQUE,
  unit_id        VARCHAR(64) NOT NULL,
  serial_number  VARCHAR(64),
  rpc_name       VARCHAR(40) NOT NULL COMMENT 'GetParameterValues/SetParameterValues/Reboot/Download...',
  param_payload  TEXT COMMENT '下发参数 JSON',
  result_payload TEXT COMMENT '返回结果 JSON',
  status         VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  fail_reason    VARCHAR(500),
  cost_ms        BIGINT,
  created_by     BIGINT,
  create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
  finish_time    DATETIME,
  KEY idx_task_unit (unit_id, create_time),
  KEY idx_task_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 审计 ----------------
CREATE TABLE IF NOT EXISTS op_audit (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  operator_id BIGINT,
  action      VARCHAR(64),
  target      VARCHAR(128),
  detail      TEXT,
  ip          VARCHAR(45),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 协议交互日志 ----------------
CREATE TABLE IF NOT EXISTS cwmp_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  unit_id     VARCHAR(64),
  session_id  VARCHAR(64),
  direction   VARCHAR(8)  COMMENT 'IN/OUT',
  method      VARCHAR(40),
  event_codes VARCHAR(64),
  fault_code  VARCHAR(32),
  fault_string VARCHAR(255),
  ip_address  VARCHAR(64),
  cost_ms     BIGINT,
  xml         MEDIUMTEXT,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_log_unit (unit_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 设备 syslog ----------------
CREATE TABLE IF NOT EXISTS device_syslog (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  unit_id      VARCHAR(64),
  ip_address   VARCHAR(64),
  facility     INT,
  severity     INT,
  device_time  VARCHAR(64),
  hostname     VARCHAR(128),
  tag          VARCHAR(64),
  content      VARCHAR(1024),
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_syslog_unit (unit_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
