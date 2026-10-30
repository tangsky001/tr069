-- 模板库建表脚本（光猫已适配数据的便携化 SQLite 文件）。
-- 由 Tr069TplSchemaInitializer 在应用启动时执行，IF NOT EXISTS 保证幂等。
--
-- 边界：本库只装「光猫适配数据」——型号是什么样、哪些能力可用、下发走什么路径。
--       设备台账、在线状态、参数实时值仍在 MySQL，这里一概不存。
--
-- 字段命名约定：
--   path_xx / val_xx  成对出现，val 里只放变量（%room% / %wifi_pwd% / 1 / true 等），
--                     绝不放任何真实口令或用户信息——拷走即用，泄露零风险。
--   action            ADD = 该参数所在实例需要先 AddObject 创建；
--                     SET = 实例已存在，直接 SetParameterValues。
--   verified          VERIFIED    = 探测验证过，路径在该型号上真实存在
--                     UNSUPPORTED = 该型号不支持此能力（AddObject 返回 fault）
--                     UNVERIFIED  = 按模型拼接但尚未验证

-- ==================== 型号主表 ====================
-- 一行 = 一个 (isp, model_key, software_version) 组合。
-- 字段承接原 MySQL cpe_model，cap_* 是人工申报的能力，决定各业务表该有几行。
CREATE TABLE IF NOT EXISTS device (
    id                BIGINT PRIMARY KEY,
    isp               TEXT NOT NULL DEFAULT 'standard',
    model_key         TEXT NOT NULL,
    manufacturer      TEXT,
    product_class     TEXT,
    software_version  TEXT,
    hardware_version  TEXT,
    status            TEXT NOT NULL DEFAULT 'PENDING',
    sample_sn         TEXT,
    root_node         TEXT NOT NULL DEFAULT 'InternetGatewayDevice.',
    cap_lan_ports     TEXT,
    cap_lan_speed     TEXT,
    cap_sip_count     TEXT,
    cap_wifi_spec     TEXT,
    remark            TEXT,
    created_at        TEXT,
    updated_at        TEXT,
    -- model_key 本身已含固件版本（如 P22g-CIac_V1.0.00.488），再拿 software_version
    -- 拼唯一键是冗余，且与 MySQL cpe_model 的 uk_cpe_model(isp, model_key) 不一致。
    -- 保持两边一致，同一份 modelKey 才能查到同一条记录。
    UNIQUE(isp, model_key)
);

-- ==================== 2.4G WiFi ====================
-- cap_wifi_spec 含 2.4G 时才有记录
CREATE TABLE IF NOT EXISTS wlan_a (
    id            BIGINT PRIMARY KEY,
    device_id     BIGINT NOT NULL,
    instance_no   INTEGER NOT NULL,
    action        TEXT NOT NULL DEFAULT 'SET',
    verified      TEXT NOT NULL DEFAULT 'UNVERIFIED',
    path_enable   TEXT, val_enable   TEXT,
    path_ssid     TEXT, val_ssid     TEXT,
    path_channel  TEXT, val_channel  TEXT,
    path_beacon   TEXT, val_beacon   TEXT,
    path_wpa_mode TEXT, val_wpa_mode TEXT,
    path_key      TEXT, val_key      TEXT,
    path_psk      TEXT, val_psk      TEXT,
    UNIQUE(device_id, instance_no)
);

-- ==================== 5.8G WiFi ====================
-- cap_wifi_spec 含 5.8G 时才有记录，结构同 wlan_a
CREATE TABLE IF NOT EXISTS wlan_b (
    id            BIGINT PRIMARY KEY,
    device_id     BIGINT NOT NULL,
    instance_no   INTEGER NOT NULL,
    action        TEXT NOT NULL DEFAULT 'SET',
    verified      TEXT NOT NULL DEFAULT 'UNVERIFIED',
    path_enable   TEXT, val_enable   TEXT,
    path_ssid     TEXT, val_ssid     TEXT,
    path_channel  TEXT, val_channel  TEXT,
    path_beacon   TEXT, val_beacon   TEXT,
    path_wpa_mode TEXT, val_wpa_mode TEXT,
    path_key      TEXT, val_key      TEXT,
    path_psk      TEXT, val_psk      TEXT,
    UNIQUE(device_id, instance_no)
);

-- ==================== SIP 语音 ====================
-- 实例数由 cap_sip_count 决定
CREATE TABLE IF NOT EXISTS sip (
    id            BIGINT PRIMARY KEY,
    device_id     BIGINT NOT NULL,
    instance_no   INTEGER NOT NULL,
    action        TEXT NOT NULL DEFAULT 'SET',
    verified      TEXT NOT NULL DEFAULT 'UNVERIFIED',
    path_enable   TEXT, val_enable   TEXT,
    path_user     TEXT, val_user     TEXT,
    path_pwd      TEXT, val_pwd      TEXT,
    path_uri      TEXT, val_uri      TEXT,
    path_proxy    TEXT, val_proxy    TEXT,
    path_port     TEXT, val_port     TEXT,
    path_registrar    TEXT, val_registrar    TEXT,
    path_registrar_port TEXT, val_registrar_port TEXT,
    UNIQUE(device_id, instance_no)
);

-- ==================== WAN 连接 ====================
-- 实例号两种来源：SET 模式按设备实际采集值存（不做「从 1 连续编号」的假设）；
-- ADD 模式实例号由设备在 AddObject 时分配，事先不知道，参数路径用 {i} 占位，
-- 下发引擎 AddObject 后拿返回的实例号回填。
CREATE TABLE IF NOT EXISTS device_wan (
    id             BIGINT PRIMARY KEY,
    device_id      BIGINT NOT NULL,
    instance_no    INTEGER NOT NULL,
    conn_type      TEXT,
    action         TEXT NOT NULL DEFAULT 'SET',
    verified       TEXT NOT NULL DEFAULT 'UNVERIFIED',
    -- action=ADD 时 AddObject 的父路径，末尾不带实例号，
    -- 如 InternetGatewayDevice.WANDevice.1.WANConnectionDevice.2.WANIPConnection.
    object_path    TEXT,
    path_enable    TEXT, val_enable    TEXT,
    path_conn_type TEXT, val_conn_type TEXT,
    -- IPAddressingType（DHCP / Static / PPPOE）
    path_addr_type TEXT, val_addr_type TEXT,
    -- NATEnabled
    path_nat       TEXT, val_nat       TEXT,
    -- X_CMCC_BindLANPort，空串表示不修改
    path_bind_port TEXT, val_bind_port TEXT,
    path_username  TEXT, val_username  TEXT,
    path_password  TEXT, val_password  TEXT,
    path_ip        TEXT, val_ip        TEXT,
    path_mask      TEXT, val_mask      TEXT,
    path_gw        TEXT, val_gw        TEXT,
    path_dns       TEXT, val_dns       TEXT,
    -- MaxMTUSize。默认留空＝不修改：MTU 各家现网取值不同（PPPoE 常见 1492、
    -- IPoE/静态常见 1500），模板没明确要求就不动设备的现有值。
    -- 填 0 更危险——设备会按 0 处理并可能直接断网，下发时按不填处理。
    path_mtu       TEXT, val_mtu       TEXT,
    UNIQUE(device_id, instance_no)
);

-- ==================== VLAN ====================
-- 真实存储位置是 WAN 下的 X_CMCC_VLANIDMark / X_CMCC_VLANID（参数名因型号而异，
-- 靠 AddObject 建实例后 GPN 自动发现），wan_instance 关联 device_wan.instance_no
CREATE TABLE IF NOT EXISTS vlan (
    id             BIGINT PRIMARY KEY,
    device_id      BIGINT NOT NULL,
    wan_instance   INTEGER,
    action         TEXT NOT NULL DEFAULT 'SET',
    verified       TEXT NOT NULL DEFAULT 'UNVERIFIED',
    path_vlan_id   TEXT, val_vlan_id   TEXT,
    path_vlan_mode TEXT, val_vlan_mode TEXT,
    path_multicast TEXT, val_multicast TEXT,
    path_8021p     TEXT, val_8021p     TEXT,
    UNIQUE(device_id, wan_instance)
);

-- ==================== LAN 网口 ====================
-- 网口数由 cap_lan_ports 决定
CREATE TABLE IF NOT EXISTS device_lan (
    id            BIGINT PRIMARY KEY,
    device_id     BIGINT NOT NULL,
    instance_no   INTEGER NOT NULL,
    action        TEXT NOT NULL DEFAULT 'SET',
    verified      TEXT NOT NULL DEFAULT 'UNVERIFIED',
    path_enable   TEXT, val_enable   TEXT,
    path_duplex   TEXT, val_duplex   TEXT,
    path_speed    TEXT, val_speed    TEXT,
    UNIQUE(device_id, instance_no)
);

-- ==================== 桥接 ====================
-- 一行 = 一个 (bridge, port) 组合。桥接下发是两步：
--   ① AddObject 建 WANConnectionDevice.{j}.WANIPConnection. 拿实例号
--   ② SetParameterValues 设 Enable / ConnectionType=Bridged / NATEnabled=0 /
--      X_CMCC_VLANID / X_CMCC_BindLANPort
-- bind_lan_no 记录该桥端口绑定的 LAN 口号
CREATE TABLE IF NOT EXISTS bridging (
    id                 BIGINT PRIMARY KEY,
    device_id          BIGINT NOT NULL,
    bridge_instance    INTEGER NOT NULL,
    port_instance      INTEGER NOT NULL,
    action             TEXT NOT NULL DEFAULT 'ADD',
    verified           TEXT NOT NULL DEFAULT 'UNVERIFIED',
    path_bridge_enable TEXT, val_bridge_enable TEXT,
    path_port_enable   TEXT, val_port_enable   TEXT,
    path_bind_iface    TEXT, val_bind_iface    TEXT,
    path_conn_type     TEXT, val_conn_type     TEXT,
    path_nat_enabled   TEXT, val_nat_enabled   TEXT,
    bind_lan_no        INTEGER,
    UNIQUE(device_id, bridge_instance, port_instance)
);

-- 索引：业务表按 device_id 点查是核心查询路径
CREATE INDEX IF NOT EXISTS idx_wlan_a_device     ON wlan_a(device_id);
CREATE INDEX IF NOT EXISTS idx_wlan_b_device     ON wlan_b(device_id);
CREATE INDEX IF NOT EXISTS idx_sip_device        ON sip(device_id);
CREATE INDEX IF NOT EXISTS idx_device_wan_device ON device_wan(device_id);
CREATE INDEX IF NOT EXISTS idx_vlan_device       ON vlan(device_id);
CREATE INDEX IF NOT EXISTS idx_device_lan_device ON device_lan(device_id);
CREATE INDEX IF NOT EXISTS idx_bridging_device   ON bridging(device_id);
