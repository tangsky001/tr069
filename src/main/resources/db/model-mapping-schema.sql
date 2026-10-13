-- 型号语义映射：业务语义模板（固定）与型号 CWMP 节点映射（一次性人工适配）
-- 由 ModelSchemaInitializer 启动时幂等补建，重复执行无害

-- 型号档案：同型号 + 同固件共用一套映射，只登记一次
CREATE TABLE IF NOT EXISTS cpe_model (
  id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
  isp               VARCHAR(16)  NOT NULL DEFAULT 'standard' COMMENT '运营商 CT/CU/CMCC/standard',
  model_key         VARCHAR(128) NOT NULL COMMENT '型号标识，建议 型号_固件，如 HS8145V5_V5R19',
  manufacturer      VARCHAR(64)  COMMENT '厂商，来自 DeviceInfo.Manufacturer',
  product_class     VARCHAR(64)  COMMENT '型号，来自 DeviceInfo.ProductClass / ModelName',
  software_version  VARCHAR(64)  COMMENT '固件版本，来自 DeviceInfo.SoftwareVersion',
  hardware_version  VARCHAR(64)  COMMENT '硬件版本',
  config_path       VARCHAR(255) COMMENT '该型号配置文件相对路径，如 ct/hs8145v5.xml',
  status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待适配 / READY 已映射 / DISABLED 停用',
  sample_sn         VARCHAR(64)  COMMENT '做映射时参考的样机序列号',
  remark            VARCHAR(255),
  created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cpe_model (isp, model_key),
  KEY idx_cpe_model_pc (product_class)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '光猫型号档案';

-- 语义字段 -> 该型号真实 CWMP 参数节点
-- 例：Internet_WAN.vlan_id -> InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.{i}.VLANIDMark
CREATE TABLE IF NOT EXISTS model_param_mapping (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  model_id    BIGINT       NOT NULL COMMENT 'cpe_model.id',
  biz_object  VARCHAR(32)  NOT NULL COMMENT '业务对象：Internet_WAN / VOIP_WAN / Bridge_WAN / WiFi_2G / WiFi_5G',
  biz_field   VARCHAR(64)  NOT NULL COMMENT '业务字段：vlan_id / ssid / wpa_key ...',
  cwmp_path   VARCHAR(512) NOT NULL COMMENT '真实参数路径，实例号用 {i} 占位',
  value_type  VARCHAR(32)  COMMENT 'string / unsignedInt / boolean，下发时推断 xsi:type',
  writable    TINYINT      NOT NULL DEFAULT 1 COMMENT '该节点实际可写',
  required    TINYINT      NOT NULL DEFAULT 0 COMMENT '缺失时是否阻断下发',
  remark      VARCHAR(255),
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_model_field (model_id, biz_object, biz_field),
  KEY idx_mapping_model (model_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '型号语义字段到 CWMP 节点映射';

-- 业务对象 -> 该型号 CWMP 父对象路径（AddObject / DeleteObject 用）
-- 例：Internet_WAN -> InternetGatewayDevice.WANDevice.1.WANConnectionDevice.1.WANIPConnection.
CREATE TABLE IF NOT EXISTS model_object_mapping (
  id            BIGINT      AUTO_INCREMENT PRIMARY KEY,
  model_id      BIGINT      NOT NULL,
  biz_object    VARCHAR(32) NOT NULL COMMENT '业务对象',
  parent_path   VARCHAR(512) NOT NULL COMMENT '连接设备路径，如 ...WANConnectionDevice.1',
  object_name   VARCHAR(128) NOT NULL COMMENT 'AddObject 的对象名，如 ...WANConnectionDevice.1.WANIPConnection.',
  instance_mode VARCHAR(16) NOT NULL DEFAULT 'ADD' COMMENT 'ADD 新建实例 / EXISTING 复用现有实例',
  delete_before TINYINT     NOT NULL DEFAULT 1 COMMENT '创建前是否先删除该对象下的旧实例',
  remark        VARCHAR(255),
  created_at    DATETIME    DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_model_object (model_id, biz_object),
  KEY idx_object_model (model_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务对象到 CWMP 对象路径映射';
