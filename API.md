# TR069 ACS 接口文档

覆盖管理端（`/api`）与北向（`/openapi/v1`）两套 HTTP 接口。南向设备上报口
（`/ACS-server/ACS`）是 CWMP SOAP 协议，不在本文范围内，仅在 §6 列出端口与路径便于排障。

---

## 1. 通用约定

### 1.1 端口

| 端口 | 用途 | 配置项 |
| --- | --- | --- |
| 8888 | 管理端页面与 `/api/**` | `server.port` |
| 8899 | 北向 `/openapi/v1/**` | `acs.openapi.port` |
| 9090 / 8082 | 光猫 CWMP 上报 | `acs.cwmp.listen-ports` |
| 9528 | 前端开发服务器（代理 `/dev-api` → `http://127.0.0.1:8888/api`） | `web/vue.config.js` |

### 1.2 鉴权

管理端与北向**共用一套 JWT**：管理端账号来自 `sys_user`，北向不再另建 appKey/secret。

1. `POST /api/user/login` 换取 token（有效期 `admin.jwt.expire-hours`，默认 12 小时）；
2. 后续请求在下列任一位置携带，优先级依次递减：
   - 请求头 `X-Token: <token>`（管理端页面用这个）
   - 请求头 `Authorization: Bearer <token>`
   - query 参数 `?token=<token>`（仅调试用）
3. 未携带或校验失败返回 **HTTP 401**，body 为 `{"code":50008,"message":"登录状态已失效，请重新登录"}`。

例外路径（不鉴权）：`/api/user/login`、`/vue-admin-template/user/login`/`info`/`logout`，
以及所有光猫上报路径——设备拿不到令牌，拦了就注册不上来。

**端口隔离**：8899 上只提供 `/openapi/v1/**`，访问其它路径一律 404；反过来 8888 与上报口
也不提供北向路径。**CORS** 只对 `/api/**` 与 `/vue-admin-template/**` 放开；北向是服务端到服务端调用，
没有跨域需求，因此不放开。

### 1.3 统一响应体

```json
{ "code": 20000, "message": "success", "data": { } }
```

| 字段 | 说明 |
| --- | --- |
| `code` | `20000` 成功；`50000` 通用业务失败；`50008` 登录失效；其它为各接口自定义业务码 |
| `message` | 成功时是 `success`，失败时是可直接展示给用户中文原因 |
| `data` | 业务数据，失败时通常为 `null` |

> HTTP 状态码与 `code` 是两个维度：业务失败也返回 HTTP 200 + `code != 20000`，
> 只有未鉴权才是 HTTP 401。判断成败一律看 `code`。

### 1.4 分页

管理端列表接口统一返回 `PageResult`：

```json
{ "items": [ ], "total": 123, "page": 1, "size": 20 }
```

北向列表返回 `{ "total": 123, "page": 1, "size": 20, "items": [ ] }`，字段含义相同。

### 1.5 时间与枚举

- 时间均为 ISO-8601（`2026-10-14T09:30:00`），时区取服务端默认时区。
- 下发任务状态见 §5.3，工单状态见 §4.9。

---

## 2. 鉴权接口

### 2.1 登录

`POST /api/user/login`（同路径别名 `/vue-admin-template/user/login`）

```json
// 请求
{ "username": "admin", "password": "******" }
// 响应
{ "code": 20000, "message": "success", "data": { "token": "eyJhbGciOi..." } }
```

失败原因：`用户名或密码不能为空` / `用户名或密码错误` / `账号已禁用`。

### 2.2 当前用户信息

`GET /api/user/info`（别名 `/vue-admin-template/user/info`）

```json
{ "code": 20000, "data": {
    "name": "admin",
    "avatar": "https://...",
    "roles": ["admin"],
    "introduction": "TR069 ACS 管理端" } }
```

### 2.3 登出

`POST /api/user/logout` — 服务端无状态 Token，此接口仅用于前端清会话，返回 `success`。

---

## 3. 管理端接口速查

| 分组 | 基础路径 | 小节 |
| --- | --- | --- |
| 设备台账 | `/api/device` | §4.1 |
| 能力树 | `/api/device/{sn}/capability` | §4.2 |
| 业务快照 | `/api/device/{sn}/business` | §4.3 |
| RPC 调试与任务 | `/api/device/{sn}/rpc`、`/api/rpc` | §4.4 |
| 配置意图模板 | `/api/config-templates` | §4.5 |
| 业务模板 | `/api/biz-template` | §4.6 |
| 型号语义映射 | `/api/models` | §4.7 |
| 工单 / 光猫档案 | `/api/worklist` | §4.8 / §4.9 |
| 首页概览 | `/api/dashboard` | §4.10 |

---

## 4. 管理端接口详解

### 4.1 设备台账

基础路径 `/api/device`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/page` | 分页列表，参数 `page`(默认1)、`size`(默认20)、`sn`(模糊)、`isp`(精确)、`deviceType`(精确)，按最近上报时间倒序 |
| GET | `/{sn}` | 详情：`{device, session}`，`session` 含 `online`/`state`/`lastContactTime`/`pendingCount`（设备离线时没有 `session` 字段） |
| GET | `/{sn}/status` | 在线状态：`{sn, online, state}` |
| GET | `/online` | 全部在线会话明细列表 |
| GET | `/business/batch?sns=` | 批量业务概要，`sns` 逗号分隔，单次上限 200 台；单台解析失败不影响其余。返回 `{sn: {...}}` |
| POST | `/{sn}/rpc/{rpcName}` | 手动下发 RPC，见 §4.4 |

```bash
curl 'http://127.0.0.1:8888/api/device/page?page=1&size=20&isp=telecom' -H 'X-Token: <token>'
```

### 4.2 能力树

基础路径 `/api/device/{sn}/capability`。

| 方法 | 路径 | 参数 | 说明 |
| --- | --- | --- | --- |
| GET | `` （即 `/api/device/{sn}/capability`） | — | 采集状态汇总：节点总数、参数总数、最近采集时间等 |
| POST | `/collect` | `force` | 触发采集。`force=true` 强制重采（BOOTSTRAP 场景），返回 `CollectStatus` |
| GET | `/params` | `page`、`size`、`parent`、`type`(object/param)、`writable`(0/1)、`keyword` | 参数分页查询 |
| GET | `/tree` | `parent` | 取某对象的下一层子节点；`parent` 为空返回根节点，供前端逐级展开 |
| GET | `/param` | `path` | 判断设备是否支持该路径，回带 `nodeType`/`writable`/`value`/`valueType` |
| POST | `/export-tree` | `force` | 导出该设备的能力树为型号 XML（写 `cpe_model.config_path`） |
| GET | `/model-file` | — | 该设备型号的能力树文件位置 |
| GET | `/biz-params` | `item`、`parent`、`limit`(默认200) | 从型号 XML 查某业务项对应的参数节点，如 `item=wan_sip_vlan` |

### 4.3 业务快照

基础路径 `/api/device/{sn}/business`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `` | 汇总：`{sn, rootNode, dataModel, lastCollectTime, device, wan, wifi, sip, pon}` |
| POST | `/collect` | 向在线设备读一遍业务参数后回库解析 |

关键字段：

- `rootNode` / `dataModel`：`InternetGatewayDevice.`(TR-098) 或 `Device.`(TR-181)。
  业务前缀由 `DataModel.businessPrefixes` 同时覆盖两种模型，**即便台账里的根节点是脏值也能查出数据**；
  排查「某些型号业务分区一片空白」时先看这两个字段。
- `wan` / `wifi` / `sip`：按实例对象分组，TR-098 识别 `WANPPPConnection|WANIPConnection`、
  `WLANConfiguration`，TR-181 额外识别 `WiFi.SSID.n`。
- `pon`：PON / 光模块参数（路径含 `PON` 的能力快照），不挂在业务实例下，单独成区。
- `collectStatus`（仅 `/collect` 返回）：`success` / `timeout`（设备未在时限内回应，返回已刷到的部分）/
  `fail` / `empty`（能力树为空，需先做能力树采集）。

### 4.4 RPC 下发与任务查询

`POST /api/device/{sn}/rpc/{rpcName}`

- `{rpcName}`：CWMP RPC 名，大小写与下划线均兼容（`GetParameterValues`、`getparametervalues` 等价）。
  支持：`GetRPCMethods`、`GetParameterValues`、`SetParameterValues`、`GetParameterNames`、
  `GetParameterAttributes`、`SetParameterAttributes`、`AddObject`、`DeleteObject`、`Reboot`、
  `FactoryReset`、`Download`、`Upload`、`ScheduleInform`、`ScheduleDownload`、`CancelTransfer`、
  `ChangeDUState`、`SetVouchers`、`GetOptions`、`GetQueuedTransfers`、`GetAllQueuedTransfers`。
- 请求体：与 RPC 同名的参数对象，如 `{"parameterNames":["Device.WAN."]}`（不同 RPC 参数不同）。
- 响应（**提交即返回，不挂起等设备**）：
  ```json
  { "code": 20000, "data": { "taskId": 17, "queued": true } }
  ```
  `queued` 为 true 表示设备还没在线，指令进了队列，会在它下次上报时空 POST 带走。

`GET /api/rpc/{taskId}` 查结果（结果保留 `acs.agent.rpc.result-ttl-seconds`，默认 3600 秒）；
`GET /api/rpc/device/{sn}/pending` 查该设备堆积的未下发指令数（超过
`acs.rpc.max-pending-per-device` 会告警式丢弃，用来判断慢设备是否堵死）。

### 4.5 配置意图模板

基础路径 `/api/config-templates`。模板**只描述开通意图**，不携带任何型号参数路径。

| 方法 | 路径 | 参数 / 体 | 说明 |
| --- | --- | --- | --- |
| GET | `/meta` | — | 7 类业务的意图字段定义（WAN DHCP / WAN PPPoE / WAN 静态IP / WAN 桥接 / 2.4G WiFi / 5.8G WiFi / SIP） |
| GET | `` | `category`、`keyword` | 列表 |
| GET | `/{id}` | — | 详情 |
| POST | `` | `ConfigTemplateSaveRequest` | 新增或更新（带 `id` 为更新） |
| POST | `/{id}/delete` | — | 删除 |

### 4.6 业务模板

基础路径 `/api/biz-template`。多张意图模板编排成一个业务包。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/list` | 列表，`keyword` 可选 |
| GET | `/{id}` | 详情（含成员视图） |
| POST | `/save` | 新增或更新 |
| POST | `/delete` | 删除，body `{id}` |
| GET | `/{id}/validate` | 下发前自检：`{pass, issues:[...]}` |
| POST | `/execute` | 对设备整包下发：按成员顺序串行执行，返回执行记录（含 `id` 供轮询） |
| GET | `/executions` | 执行记录列表，可按 `sn`、`templateId`、`limit` 过滤 |
| GET | `/executions/{id}` | 单条执行记录 |

执行记录状态：`running → success / partial / fail`。整包按成员顺序串行推进，前一个结束才发下一个；
成员失败时按模板的 `continueOnError` 决定是停下还是继续，继续跑完且有失败即 `partial`。
`result` 是每个成员的明细（序号、模板、业务类型、结果、错误原因）。

### 4.7 型号语义映射

基础路径 `/api/models`。这是「业务字段 ↔ 型号真实 CWMP 路径」的适配层，下发器依赖它。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `` | 型号列表，可按 `status`、`keyword` 过滤 |
| GET | `/schema` | 业务对象 / 业务字段的字典 |
| GET | `/match` | 按 `isp`、`manufacturer`、`productClass`、`softwareVersion`、`hardwareVersion`、`sn` 匹配型号档案（未登记则自动登记为待适配），返回 `{model, ready}` |
| GET | `/{id}` | 型号详情（含参数映射与对象映射） |
| POST | `` | 新建型号，body 为 `CpeModel` |
| POST | `/{id}/update` | 局部更新型号字段 |
| POST | `/{id}/mappings` | 保存映射，body `{paramMappings:[], objectMappings:[]}` |
| GET | `/candidates` | 按 `sn`（+`bizObject`、`keyword`）从该设备的台账推荐候选路径 |

型号档案的关键字段：`model_key`（ProductClass + 固件版本决定）、`root_node`（样机的根数据模型节点）、
`status`（`DRAFT` 未适配 / `READY` 可下发，`/match` 返回的 `ready` 即 `status=READY`）。
**下发前会校验 `status=READY`，且对账
`cpe_model.root_node` 与设备 `cpe_device.root_node` 的数据模型**——不一致直接拒绝下发，
避免把 TR-098 的路径写进 TR-181 设备（设备只会回 9005）。

### 4.8 工单记录（只读）

基础路径 `/api/worklist`（执行侧）。执行引擎已移除，仅保留历史查询。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/instances` | 分页：`sn`、`status`、`page`、`size` |
| GET | `/instances/{id}` | 单条工单 |
| GET | `/instances/device/{sn}` | 某设备的工单列表 |
| GET | `/instances/{id}/steps` | 工单步骤记录 |

工单状态：`build → bind → reserve → standby → running → success / fail / abnormal → expire`。

### 4.9 首页概览

`GET /api/dashboard/overview`

```json
{ "deviceTotal": 1200, "online": 860, "offline": 340, "onlineWindowSeconds": 900,
  "dispatched": 700, "undispatched": 500,
  "brands": [{ "name": "ZTE", "count": 400 }],
  "worklistStats": { "total": 0, "success": 0, "fail": 0, "running": 0 },
  "templateStats": { } }
```

`online` 按 `last_contact_time` 落在 `acs.dashboard.online-window-seconds`（默认 900 秒）内判定，
与 §4.1 的会话实时在线口径不同——重启后会话表清空，前者不受影响。

---

## 5. 北向接口 `/openapi/v1`

面向上层运营商 / 计费 / 客服系统。**基点：`http://<host>:8899/openapi/v1`。**

### 5.1 约定

- **鉴权**：与管理端同一套 JWT，`X-Token` 或 `Authorization: Bearer`。用 §2.1 登录换令牌。
- **异步**：ACS 只能在设备上报后的那次空 POST 里捎带指令，下发结果取决于设备心跳周期。
  返回 `PENDING` / `RUNNING` 都属正常中间态。
- **幂等**：`source + taskNo` 唯一。同一 `taskNo` 重复提交只受理一次，网络重试不会重复开通。
- **安全**：该端口不含任何 CORS 放行；建议经内网或专线访问。

### 5.2 查询（只读）

| 方法 | 路径 | 参数 | 说明 |
| --- | --- | --- | --- |
| GET | `/devices` | `keyword`、`isp`、`page`、`size` | 台账分页 |
| GET | `/devices/{sn}` | — | 台账 + 能力汇总 |
| GET | `/devices/{sn}/online` | — | 在线状态 |
| GET | `/devices/{sn}/capability/tree` | `parent` | 能力树某一层，不带 `parent` 返回根节点层 |
| GET | `/devices/{sn}/capability/params` | `prefix` | 参数值清单，按路径前缀过滤 |
| GET | `/devices/{sn}/business` | — | 业务快照 |
| GET | `/devices/{sn}/deliveries` | `limit`(默认20) | 该设备的下发任务历史 |
| GET | `/devices/{sn}/work-orders` | `limit` | 工单执行结果 |

台账条目字段：

```json
{ "sn": "ZXHN0001", "oui": "00259E", "manufacturer": "ZTE", "productClass": "ZXHN F660",
  "softwareVersion": "V2.0", "hardwareVersion": "V1.0", "isp": "telecom", "status": "online",
  "lastContactTime": "2026-10-14T09:30:00",
  "rootNode": "Device.", "dataModel": "TR-181", "online": true }
```

在线状态字段：`{sn, online, state, lastContactTime}`，`state` 为会话状态机取值
（`WAIT_INFORM` / `WAIT_RESPONSE` / `IDLE` 等），调用方只需看 `online`。

能力树条目字段：`{path, name, nodeType, writable, value}`（`nodeType`：`object` / `param`）。

### 5.3 下发

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/deliveries/meta` | 业务类型 ↔ 业务对象对照 + 意图模板清单 |
| POST | `/deliveries` | 受理一次下发 |
| GET | `/deliveries/{id}` | 按内部 id 查结果 |
| GET | `/deliveries?taskNo=` | 按外部单号查结果 |
| GET | `/deliveries` | 列表：`sn`、`status`、`limit` |

`POST /deliveries` 请求体：

```json
{
  "taskNo": "ORDER-20261014-0001",
  "sn": "ZXHN0001",
  "category": "WAN_DHCP",
  "templateId": 3,
  "bizObject": "Internet_WAN",
  "values": { "vlan_id": "100", "enable": "true", "instance": "1" },
  "createInstance": false
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `taskNo` | 是 | 调用方业务流水号，兼作幂等键与结果查询键 |
| `sn` | 是 | 设备序列号 |
| `category` | 是 | 业务类型，见下表；缺省业务对象由它推导 |
| `templateId` | 否 | 意图模板 id，提供时其意图字段作为默认值参与渲染 |
| `bizObject` | 否 | 直接指定业务对象，绕开 `category` 推导 |
| `values` | 否 | 本次业务字段值，**优先于模板默认值**；`instance` 指定实例号（缺省 1） |
| `createInstance` | 否 | 是否先 `AddObject` 新建实例 |

`category` 与业务对象的对照：

| category | bizObject | 说明 |
| --- | --- | --- |
| `WAN_DHCP` / `WAN_PPPOE` / `WAN_STATIC` | `Internet_WAN` | 上网 WAN 连接，三种 IP 获取方式 |
| `WAN_BRIDGE` | `Bridge_WAN` | 桥接 WAN |
| `WIFI_24G` / `WIFI_58G` | `WiFi_2G` / `WiFi_5G` | 无线 SSID |
| `SIP` | `VOIP_WAN` | 语音 |

响应即任务视图：

```json
{ "id": 42, "taskNo": "ORDER-20261014-0001", "sn": "ZXHN0001", "category": "WAN_DHCP",
  "templateId": 3, "source": "openapi", "createdBy": "northbound",
  "status": "PENDING", "errorMsg": null,
  "intentFields": { "vlan_id": "100", "enable": "true" },
  "plan": { "bizObject": "Internet_WAN", "instance": 1,
            "objectsToAdd": [],
            "parameters": [{ "bizField": "enable", "path": "Device.WAN.1.Enable",
                             "value": "true", "valueType": "boolean" }],
            "errors": [] },
  "createdAt": "...", "updatedAt": "...", "finishedAt": null }
```

`plan.parameters` 是**实际要下发到该型号的路径与值**（渲染结果），排障时最有用：
路径写错、实例号没替换、值类型不对，这里一眼可见。

**任务状态机**

| 状态 | 含义 | 后续动作 |
| --- | --- | --- |
| `PENDING` | 已受理，计划尚未开始执行 | 继续轮询 |
| `RUNNING` | 已发向设备，等回应 | 继续轮询，注意下发超时（`acs.exec.request-timeout-seconds`，默认 90 秒） |
| `SUCCESS` | 设备确认写入 | 终态 |
| `FAILED` | 设备故障（含返回 `Status!=0`）或等待回应超时 | 终态，看 `errorMsg` |
| `REJECTED` | **未下发**：型号未适配 / 映射不完整 / 数据模型不一致 / 缺必填业务字段 | 终态，先补型号映射 |

常见 `errorMsg`：

- `设备不存在，请确认已注册: <sn>` —— 设备还没上过线，ACS 不认识它
- `该型号尚未登记，请先做语义映射: <modelKey>` —— 补登记型号后再发
- `型号映射未完成，当前状态 DRAFT，补齐必填字段后再下发` —— 该型号缺必填映射，禁止硬猜路径下发
- `数据模型不一致：型号档案按 TR-098 映射，而设备上报的是 TR-181` —— 需为该型号再建一份 TR-181 适配
- `缺必填业务字段: xxx` / `没有任何字段可以下发` —— 请求没带够字段或映射缺 CWMP 路径

### 5.4 事件订阅与推送

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/events/types` | 支持的事件类型 |
| GET | `/events/subscriptions` | 订阅列表 |
| POST | `/events/subscriptions` | 新增或修改订阅 |
| DELETE | `/events/subscriptions/{id}` | 删除订阅 |
| GET | `/events/subscriptions/{id}/deliveries` | 该订阅的投递记录 |
| GET | `/events/deliveries` | 全部投递记录，`limit` 默认 20 |
| POST | `/events/test` | 发一条测试事件，验证回调与签名 |

订阅对象字段：`{id, name, url, secret, eventTypes, enabled, createdBy, createdAt, updatedAt}`。
`eventTypes` 逗号分隔，`*` 表示全部。

事件类型：

| 事件 | 触发时机 |
| --- | --- |
| `device.online` | 会话从离线变在线 |
| `device.offline` | 会话超时剔除或状态转离线 |
| `delivery.finished` | 下发任务进入终态（成功 / 失败 / 被拒绝都推） |

> 上下线由会话快照定期比对产生（默认 15 秒一轮）。**服务刚启动的第一轮只建立基线，不会推事件**，
> 否则每次重启都会给上层推一遍全量在线设备。

推送请求：

```http
POST /your/webhook HTTP/1.1
Content-Type: application/json;charset=UTF-8
X-ACS-Event-Id: delivery.finished:1234567890:1234
X-ACS-Event-Type: delivery.finished
X-ACS-Signature: 8Z3k...（Base64）

{
  "eventId": "delivery.finished:1234567890:1234",
  "eventType": "delivery.finished",
  "occurredAt": "2026-10-14T09:30:00",
  "data": { "id": 42, "taskNo": "ORDER-20261014-0001", "sn": "ZXHN0001",
            "category": "WAN_DHCP", "status": "SUCCESS", "errorMsg": null,
            "source": "openapi", "finishedAt": "2026-10-14T09:30:12" }
}
```

接收方注意事项：

1. **必须按 `X-ACS-Event-Id` / `eventId` 去重**：超时重发、网络重试都可能造成重复投递。
2. **签名校验**：`Base64(HMAC-SHA256(请求原始 body, secret))`，与 `X-ACS-Signature` 比对
   （ secret 未配置时不带该头）。
3. **快速返回 2xx**：超过 `acs.openapi.webhook.timeout-seconds`（默认 10 秒）会被记为失败并重试。
4. **重试策略**：30s × 2^n 退避，默认最多 5 次；投递记录先落库再推，**ACS 重启不丢事件**。
   排查时用 `/events/subscriptions/{id}/deliveries` 看 `status` / `attempts` / `responseCode`。

---

## 6. 南向 CWMP 上报口（设备侧，非调用方接口）

| 项 | 值 |
| --- | --- |
| 端口 | `acs.cwmp.listen-ports`，默认 `9090,8082` |
| 路径 | `acs.cwmp.cpe-path` 默认 `/ACS-server/ACS`，另有 `/cpe` 与 `acs.cwmp.extra-cpe-paths` 兼容路径 |
| 鉴权 | HTTP Digest，账号 `acs.auth.cpe-to-acs-username/password`；配 `auto` 或留空即免认证（上线前务必改回） |
| 报文 | CWMP SOAP 1.1 envelope，见 `cwmp/SoapEnvelopeBuilder` |

下行 connection request 的 URL 由 `acs.public-host` 拼出（默认 `127.0.0.1`，上线前必改：
否则让设备主动回连时，它会连向自己）。

---

## 7. 错误排查速查

| 现象 | 先看哪里 |
| --- | --- |
| HTTP 401 / `code=50008` | token 是否过期（`admin.jwt.expire-hours`）、请求头是否用了 `X-Token` |
| HTTP 404（北向端口） | 是否在 8899 上访问了非 `/openapi/v1/**` 路径，或在 8888 上访问了北向路径 |
| 业务快照某分区为空 | 响应里的 `dataModel`；再看能力树是否已采集（`Device.WAN.` 与 `InternetGatewayDevice.WANDevice.` 的差异） |
| 下发 `REJECTED` | `plan.errors` 与 §5.3 的 errorMsg 对照表 |
| 下发一直 `RUNNING` | 设备心跳周期是否长于 `acs.exec.request-timeout-seconds`；`/api/device/{sn}/status` 看是否在线 |
| 设备报 9005 Invalid parameter | `plan.parameters` 里的路径与型号实际不符——补/改 `model_param_mapping` |
| Webhook 收不到 | 订阅 `enabled` 是否为 1、`eventTypes` 是否覆盖；投递记录里的 `responseCode` 与 `responseBody` |

---

## 8. 附录：接口索引

<details open>
<summary>全部 HTTP 接口</summary>

**鉴权**：`POST /api/user/login`、`GET /api/user/info`、`POST /api/user/logout`

**设备**：`GET /api/device/page`、`GET /api/device/{sn}`、`GET /api/device/{sn}/status`、
`GET /api/device/online`、`GET /api/device/business/batch`、`POST /api/device/{sn}/rpc/{rpcName}`

**能力树**：`GET|POST /api/device/{sn}/capability[/collect]`、`GET /params`、`GET /tree`、
`GET /param`、`POST /export-tree`、`GET /model-file`、`GET /biz-params`

**业务**：`GET /api/device/{sn}/business`、`POST /api/device/{sn}/business/collect`

**RPC 任务**：`GET /api/rpc/{taskId}`、`GET /api/rpc/device/{sn}/pending`

**模板**：`GET /api/config-templates/meta`、`GET|POST /api/config-templates`、
`GET /api/config-templates/{id}`、`POST /api/config-templates/{id}/delete`；
`GET /api/biz-template/list`、`GET /api/biz-template/{id}`、`POST /api/biz-template/save`、
`POST /api/biz-template/delete`、`GET /api/biz-template/{id}/validate`、
`GET /api/biz-template/executions`、`GET /api/biz-template/executions/{id}`

**型号**：`GET|POST /api/models`、`GET /api/models/schema`、`GET /api/models/match`、
`GET /api/models/{id}`、`POST /api/models/{id}/update`、`POST /api/models/{id}/mappings`、
`GET /api/models/candidates`

**工单**：`GET /api/worklist/instances`、`GET /api/worklist/instances/{id}`、
`GET /api/worklist/instances/device/{sn}`、`GET /api/worklist/instances/{id}/steps`

**概览**：`GET /api/dashboard/overview`

**北向查询**：`GET /openapi/v1/devices`、`GET /openapi/v1/devices/{sn}`、
`GET /openapi/v1/devices/{sn}/online`、`GET /openapi/v1/devices/{sn}/capability/tree`、
`GET /openapi/v1/devices/{sn}/capability/params`、`GET /openapi/v1/devices/{sn}/business`、
`GET /openapi/v1/devices/{sn}/deliveries`、`GET /openapi/v1/devices/{sn}/work-orders`

**北向下发**：`GET /openapi/v1/deliveries/meta`、`POST /openapi/v1/deliveries`、
`GET /openapi/v1/deliveries/{id}`、`GET /openapi/v1/deliveries`

**北向事件**：`GET /openapi/v1/events/types`、`GET|POST /openapi/v1/events/subscriptions`、
`DELETE /openapi/v1/events/subscriptions/{id}`、`GET /openapi/v1/events/subscriptions/{id}/deliveries`、
`GET /openapi/v1/events/deliveries`、`POST /openapi/v1/events/test`

</details>
