# 光猫能力库 SQLite 改造方案

> 状态：**待确认**。本文是改造前的方案文档，确认后按「第 7 节实施步骤」逐步落地。

---

## 1. 目标与边界

### 1.1 目标

光猫「已适配的能力」统一存入单个 SQLite 文件 `configuration/tr069_tpl.pack`，实现：

- 拷走即用：新机器部署后，光猫一上线即识别为已适配，无需重新适配
- 结构化：能力按业务模块分表，不再是一整棵能力树 XML
- 参数化：所有需要修改的值全部用变量表示，库里不含任何真实口令

### 1.2 边界（本次改造的硬约束）

| 范围 | 内容 | 存储 |
| --- | --- | --- |
| **进 SQLite** | 型号 + 运营商 + 版本，及其 wan / vlan / wlan / sip / bridging / lan 等**业务下发参数**（路径 + 变量值） | `configuration/tr069_tpl.pack` |
| **留在 MySQL** | `cpe_device` 台账（**含 TR069 ACS URL、管理 IP**）、`cpe_parameter`、工单、用户、OLT、日志 | MySQL 不动 |
| **停写保留** | `cpe_model` 表（读源切走，暂不删）、`configuration/models/`（迁移后不再写入） | — |

**明确不做**：

- 不存完整能力树（1685 个参数全量）。只存**下发用得到的业务参数**，每型号几十~几百行
- 不存任何真实参数值。可写参数的值一律用变量，库里出现明文口令视为 Bug
- TR069 ACS URL、管理 IP 等**平台侧配置不进 SQLite**，继续走 MySQL 台账

### 1.3 查询模型（两步点查）

```
① 设备上报 → 取型号 + 版本 + isp
② SELECT id FROM device WHERE isp=? AND model=? AND firmware=?     ← 唯一索引
③ SELECT * FROM <业务表> WHERE device_id=? AND instance_no=?        ← 业务表索引
```

全程索引点查，无全表扫描。库内型号条目量级为**几十~几百条**（不是千万级）。

---

## 2. 设计依据：真实采集数据

对现网设备 `P22g-CIac_V1.0.00.488`（CIOT，1685 参数 / 325 对象）实测结果，表结构据此设计，**不是拍脑袋定的**。

### 2.1 实例号（关键：不是都从 1 开始）

| 业务 | 实际实例号 | 说明 |
| --- | --- | --- |
| WiFi `WLANConfiguration` | **1 ~ 8** | 1-4 为 2.4G，5-8 为 5.8G |
| 语音 `Line` | **1 ~ 4** | SIP 线路 |
| WAN `WANIPConnection` | **9 ~ 12** | ⚠️ **不是 1-4**，实例号无规律 |
| `VoiceProfile` | 1 | 语音档 |
| `WANPPPConnection` | 无实例 | 该设备当前未用 PPPoE |

**结论**：实例号必须按设备**实际采集值**存储，不能假设从 1 连续。路径整存（不拼接），避免拼错。

### 2.2 各模块真实参数项

**WiFi**（`WLANConfiguration.{n}`）

| 下发用 | `Enable`、`SSID`、`KeyPassphrase`、`PreSharedKey`、`Channel`、`SSIDAdvertisementEnabled`、`BeaconType`、`WPAAuthenticationMode`、`WPAEncryptionModes`、`IEEE11iAuthenticationMode`、`BasicAuthenticationMode`、`WEPKey`、`TransmitPower`、`AutoChannelEnable`、`Standard`、`Name` |
| --- | --- |
| 频段标识 | `X_CMCC_RFBand` ← **用这个区分 2.4G / 5.8G，不靠实例号猜** |
| 只读状态 | `BSSID`、`Status`、`TotalAssociations`、`TotalBytesSent` 等（不存） |

> 密码要写**两个**参数：`PreSharedKey.1.KeyPassphrase` 与 `PreSharedKey.1.PreSharedKey`，是该型号的实际要求。

**SIP**（`VoiceProfile.1.Line.{n}.SIP`）：`AuthUserName`、`AuthPassword`、`URI`

**WAN**（`WANConnectionDevice.1.WANIPConnection.{n}`）

| 基础 | `Enable`、`ConnectionType`、`AddressingType`、`ExternalIPAddress`、`SubnetMask`、`DefaultGateway`、`DNSServers`、`MaxMTUSize`、`NATEnabled`、`Name` |
| --- | --- |
| 移动扩展 | `X_CMCC_VLANIDMark`（**VLAN ID 挂在这里**）、`X_CMCC_VLANMode`、`X_CMCC_ServiceList`、`X_CMCC_LanInterface`、`X_CMCC_IPMode`、`X_CMCC_802-1pMark`、`X_CMCC_MulticastVlan` |
| 只读状态 | `ConnectionStatus`、`Uptime`、`LastConnectionError`（不存） |

**VLAN**：无独立对象，**VLAN 参数挂在 WAN 连接下**（`X_CMCC_VLANIDMark` / `X_CMCC_VLANMode` / `X_CMCC_MulticastVlan`）

**Bridging**：该设备**无 Bridge 对象**（表结构保留，允许空）

**LAN**：`LANEthernetInterfaceConfig.{n}`（`Enable`、`DuplexMode`、`MACAddress` 等）

---

## 3. 表结构（8 张表）

### 3.1 `device` 型号主表

```sql
CREATE TABLE device (
    id           BIGINT PRIMARY KEY,      -- 雪花 ID
    isp          TEXT    NOT NULL,        -- ct / cmcc / cu
    brand        TEXT,                    -- 厂商（manufacturer），可空
    model        TEXT    NOT NULL,        -- P22g-CIac
    firmware     TEXT    NOT NULL,        -- V1.0.00.488
    created_at   TEXT,
    updated_at   TEXT,
    UNIQUE(isp, model, firmware)
);
```

### 3.2 `wlan_a` 2.4G WiFi（实例 1-4，一行 = 一个实例）

```sql
CREATE TABLE wlan_a (
    id             BIGINT PRIMARY KEY,
    device_id      BIGINT NOT NULL,
    instance_no    INTEGER NOT NULL,      -- 实际采集值 1..4
    band           TEXT,                  -- 2.4G（取自 X_CMCC_RFBand）
    path_enable    TEXT,  val_enable    TEXT,
    path_ssid      TEXT,  val_ssid      TEXT,   -- %room%
    path_key       TEXT,  val_key       TEXT,   -- %wifi_pwd%   KeyPassphrase
    path_psk       TEXT,  val_psk       TEXT,   -- %wifi_pwd%   PreSharedKey
    path_channel   TEXT,  val_channel   TEXT,
    path_beacon    TEXT,  val_beacon    TEXT,
    path_wpa_mode  TEXT,  val_wpa_mode  TEXT,
    UNIQUE(device_id, instance_no)
);
```

### 3.3 `wlan_b` 5.8G WiFi（实例 5-8）

结构同 `wlan_a`，`band='5.8G'`，实例号 5..8。

### 3.4 `sip` SIP 语音（Line 1-4）

```sql
CREATE TABLE sip (
    id             BIGINT PRIMARY KEY,
    device_id      BIGINT NOT NULL,
    instance_no    INTEGER NOT NULL,      -- 1..4
    path_enable    TEXT,  val_enable    TEXT,
    path_user      TEXT,  val_user      TEXT,   -- %sip_user%   AuthUserName
    path_pwd       TEXT,  val_pwd       TEXT,   -- %sip_pwd%    AuthPassword
    path_uri       TEXT,  val_uri       TEXT,   -- %sip_number% URI
    UNIQUE(device_id, instance_no)
);
```

### 3.5 `device_wan` WAN 连接（实例 9-12）

```sql
CREATE TABLE device_wan (
    id               BIGINT PRIMARY KEY,
    device_id        BIGINT NOT NULL,
    instance_no      INTEGER NOT NULL,      -- 实际值 9..12
    conn_type        TEXT,                  -- IPoE / PPPoE / Bridge
    path_enable      TEXT, val_enable      TEXT,
    path_conn_type   TEXT, val_conn_type   TEXT,
    path_username    TEXT, val_username    TEXT,   -- %wan_user%
    path_password    TEXT, val_password    TEXT,   -- %wan_pwd%
    path_ip_mode     TEXT, val_ip_mode     TEXT,
    path_service     TEXT, val_service     TEXT,   -- X_CMCC_ServiceList
    UNIQUE(device_id, instance_no)
);
```

### 3.6 `vlan` VLAN（挂在 WAN 实例下）

```sql
CREATE TABLE vlan (
    id             BIGINT PRIMARY KEY,
    device_id      BIGINT NOT NULL,
    wan_instance   INTEGER,                -- 关联 device_wan.instance_no
    path_vlan_id   TEXT, val_vlan_id   TEXT,   -- %vlan_id%    X_CMCC_VLANIDMark
    path_vlan_mode TEXT, val_vlan_mode TEXT,   -- X_CMCC_VLANMode
    path_multicast TEXT, val_multicast TEXT,   -- X_CMCC_MulticastVlan
    path_8021p     TEXT, val_8021p     TEXT,   -- X_CMCC_802-1pMark
    UNIQUE(device_id, wan_instance)
);
```

### 3.7 `device_lan` LAN 网口

```sql
CREATE TABLE device_lan (
    id             BIGINT PRIMARY KEY,
    device_id      BIGINT NOT NULL,
    instance_no    INTEGER NOT NULL,
    path_enable    TEXT, val_enable    TEXT,
    path_duplex    TEXT, val_duplex    TEXT,
    path_mac       TEXT,                    -- 只读，不存值
    UNIQUE(device_id, instance_no)
);
```

### 3.8 `bridging` 桥接

结构与 `device_wan` 同构（该型号无实例，表保留允许空）。

---

## 4. 变量机制

### 4.1 变量清单

| 变量 | 含义 | 取值来源 |
| --- | --- | --- |
| `%room%` | 房间号 | **设备备注**（前端 SN 后可编辑，如 `0001`） |
| `%wifi_pwd%` | WiFi 密码 | 工单传入 / 默认策略 |
| `%ssid%` | WiFi 名称 | 默认等同 `%room%`，可单独指定 |
| `%sip_user%` / `%sip_pwd%` | SIP 账号 / 密码 | 工单 |
| `%sip_number%` | SIP 号码（URI） | 工单 |
| `%vlan_id%` | VLAN ID | 工单 |
| `%wan_user%` / `%wan_pwd%` | 宽带账号 / 口令 | 工单 |

**不进 SQLite 的变量**（走 MySQL 台账）：TR069 ACS URL、管理 IP。

### 4.2 规则

- 模板里只允许出现**变量**或**开关类固定值**（`1` / `true`）
- 禁止出现真实口令——`.pack` 外发、备份、入库均不泄密
- 下发前统一替换，未替换的变量视为错误并拒绝下发（避免把 `%room%` 字面下发到设备）

### 4.3 设备备注

`cpe_device` 新增 `remark` 字段（MySQL，台账侧），前端设备列表 SN 后提供可编辑列。下发时 `%room%` 取该值。

---

## 5. 下发流程

### 5.1 适配（写入）

```
采集能力 → 按业务模块匹配真实路径 → 生成参数行（值替换为变量）→ 写入 SQLite 各业务表
```

示例：2.4G 实例 1

| path_ssid | val_ssid | path_key | val_key |
| --- | --- | --- | --- |
| `...WLANConfiguration.1.SSID` | `%room%` | `...PreSharedKey.1.KeyPassphrase` | `%wifi_pwd%` |

### 5.2 下发（读取）

```
① 设备上报 → 型号+版本+isp → 查 device 拿 id
② 按业务模块 SELECT 参数行
③ 变量替换（%room% ← 设备备注，其余 ← 工单入参）
④ 拼 SetParameterValues → 下发
```

### 5.3 工单合并下发（新增能力）

支持把**多个模块的参数合并成一次下发**：

```
工单 = [WiFi 实例1 + SIP 实例1 + WAN 实例9(VLAN)]
   ↓
各模块分别查参数行 → 合并成一个 SetParameterValues 报文 → 一次下发
```

好处：一次会话完成整机配置，减少设备交互轮次；失败可整体回滚（各模块独立记录结果）。

---

## 6. 改造清单

### 6.1 配置（2 项）

1. `application.yml`：`acs.device.capability.collect-on-inform: false`（全量能力树不再上线自动采，改为人工点适配）
2. 新增：`acs.worklist.template-pack-path: ./configuration/tr069_tpl.pack`、`acs.worklist.template-pack-node-id: 1`

### 6.2 新增（7 项）

3. `pom.xml` 引入 `org.xerial:sqlite-jdbc:3.46.1.0`（已下载至本地仓库，离线可用）
4. SQLite 数据源配置：独立 `DataSource` + `JdbcTemplate`，`PRAGMA journal_mode=WAL`、`busy_timeout=5000`
5. 建表：8 张表，启动时自动建（文件不存在则创建）
6. 实体类：8 张表对应实体
7. DAO：按 `device_id` + `instance_no` / `isp+model+firmware` 的查询与 upsert
8. 雪花 ID 生成器（节点号走配置）
9. 迁移工具（独立 main，带 dry-run）

### 6.3 改造（6 项）

10. `DeviceCapabilityService.onInform()`：改为「查 SQLite 判断型号是否已适配」，不再自动采集
11. `ModelTreeStore.export()`：写 XML 文件 → 写 SQLite 各业务表（不存完整树、不存值）
12. `ModelTreeStore` 读取方法（`locate` / `modelParams` / `readParams` / `resolvePaths`）：读文件 → 读 SQLite
13. `ModelAdaptationService.save()`：写 SQLite 业务表，停写 `cpe_model`
14. `ModelAdaptationService.status()` / `statusBatch()`：判定「已适配」改读 SQLite
15. 下发入口加未适配校验：未命中拒绝下发并提示「请先完成机型适配」

### 6.4 前端与台账（3 项）

16. `cpe_device` 加 `remark` 字段（MySQL）+ 实体
17. 前端设备列表 SN 后加可编辑备注列 + 保存接口
18. 变量替换器（下发前替换 `%room%` 等）+ 工单合并下发支持

---

## 7. 实施步骤

| 阶段 | 内容 | 验证 |
| --- | --- | --- |
| **1** | 依赖 + 数据源 + 建表 + 实体 + DAO + 雪花 ID | 单测：建表、upsert、按 isp+model+firmware 点查 |
| **2** | 迁移工具（dry-run 先跑） | 对比清单与实际数据条数 |
| **3** | `ModelTreeStore` 读写切 SQLite | 适配一台设备，确认写入各业务表且不再生成 XML 文件 |
| **4** | `ModelAdaptationService` + `onInform` 切源 | 新设备显示「需要适配」；点适配后显示「已适配」 |
| **5** | 下发校验 + 变量替换 + 工单合并 | 未适配拒绝下发；已适配按 `%room%` 替换下发成功 |
| **6** | 前端备注列 + 文档更新 | 备注编辑生效，README 目录结构与配置表同步 |

---

## 8. 存量迁移

**源**：`configuration/models/**/*.xml`（现存 1 份）+ MySQL `cpe_model` + `model_param_mapping`

**规则**：

1. `modelKey = P22g-CIac_V1.0.00.488` 拆为 `model=P22g-CIac` + `firmware=V1.0.00.488`
2. `isp` 归一化：`CMCC`→`cmcc`、`CT`→`ct`、`CU`→`cu`
3. 按业务模块匹配参数路径，生成各业务表行
4. **可写参数的 value 一律替换为变量**（密码类 → `%wifi_pwd%` / `%sip_pwd%`，SSID → `%room%` 等）
5. 同 `(isp, model, firmware)` 重复才去重

**执行**：

```bash
java -cp javaacs-server.jar <MigratorMain> --dry-run
java -cp javaacs-server.jar <MigratorMain>
```

迁移后 `configuration/models/` 保留 7 天可回滚。

---

## 9. 测试与回滚

**单测**：建表与索引、upsert 后点查命中、不同 isp 同型号不冲突、变量替换、迁移去重规则、库中无明文口令（断言 `KeyPassphrase` 等字段值只能是变量）。

**变异验证**：改坏关键路径（去掉 `isp` 条件、去掉唯一索引、恢复明文值写入），确认测试变红。

**集成验证**：

1. 空库 → 光猫上线 → 显示「需要适配」
2. 点适配 → 采集 → 存库 → 显示「已适配」
3. 重启 → 上线直接「已适配」
4. 拷 `tr069_tpl.pack` 到新机器 → 上线即已适配

**回滚**：加开关 `acs.worklist.template-storage: sqlite | file`，改回 `file` 即走旧路径；旧目录与 `cpe_model` 迁移后保留。

---

## 10. 待确认

| # | 问题 | 建议 |
| --- | --- | --- |
| 1 | `vlan` 独立成表（当前设计）还是并入 `device_wan`（真实结构就是挂在 WAN 下）？ | 建议**独立表**便于管理，但关联 `wan_instance` |
| 2 | `bridging` 该型号无实例，是否仍建表？ | 建议建，允许空 |
| 3 | WiFi 2.4G/5.8G 是按实例号（1-4 / 5-8）还是按 `X_CMCC_RFBand` 判定？ | 建议**以 RFBand 为准**，实例号仅作排序 |
| 4 | 未适配设备是「禁止下发」还是「允许下发但告警」？ | 建议**禁止**并提示先适配 |
| 5 | 变量未替换时（如设备备注为空）如何处理？ | 建议拒绝下发并提示缺失变量 |
