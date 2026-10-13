-- 配置意图模板：只描述「开通意图」，不含参数定义与执行步骤
-- 例：{ "ip_protocol": "IPv4", "vlan_used": true, "port_bind": false }
-- 下发时由执行器按型号语义映射（model_param_mapping）把意图字段翻译成该机型真实 CWMP 参数
-- 由 ConfigTemplateSchemaInitializer 启动时幂等补建，重复执行无害

CREATE TABLE IF NOT EXISTS config_template (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  name        VARCHAR(128) NOT NULL COMMENT '模板名称',
  category    VARCHAR(32)  NOT NULL COMMENT '类型：WAN_DHCP / WAN_PPPOE / WAN_STATIC / WAN_BRIDGE / WIFI_24G / WIFI_58G / SIP',
  group_name  VARCHAR(64)  COMMENT '业务分组，可空',
  enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
  remark      VARCHAR(255) COMMENT '备注',
  fields      TEXT         COMMENT '意图字段 JSON：键值与型号语义映射的 biz_field 对齐',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_config_template (category, name),
  KEY idx_config_template_cat (category)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '配置意图模板（只做开通意图描述，不直接下发）';
