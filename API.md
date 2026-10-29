# TR069 ACS 接口文档

覆盖管理端（`/api`）与北向（`/openapi/v1`）两套 HTTP 接口。南向设备上报口
（`/ACS-server/ACS`）是 CWMP SOAP 协议，不在本文范围内，仅在 §6 列出端口与路径便于排障。

> **在线文档**：服务启动后浏览器打开 <http://127.0.0.1:8888/doc.html>（Knife4j 界面）
> 可直接查看全部接口的用途、请求参数与返回结构，顶部「Authorize / 认证」填一次 token 就能在线调试。
> <http://127.0.0.1:8888/v3/api-docs> 是同一份内容的 OpenAPI 3.0 JSON，
> 在 Apifox / Postman 里「导入 → OpenAPI/Swagger」粘贴这个地址即可批量生成接口。
> 说明文字集中在 `config/OpenApiDocConfig` 的 `DOC` 表，新增接口在那儿补一句即可。

---

## 1. 通用约定

### 1.1 端口

| 端口 | 用途 | 配置项 |
| --- | --- | --- |
| 8888 | 管理端页面与 `/api/**` | `server.port` |
| 8899 | 北向 `/openapi/v1/**` | `acs.openapi.port` |
| 9090 / 8012 / 8080 / 8082 / 8172 / 5481 | 光猫 CWMP 上报（按厂商出厂端口），另有 6060 | `acs.cwmp.listen-ports` |
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
{ "code": 20000, "message": "success",
  "data": { "token": "eyJhbGciOi...", "role": "SUPER_ADMIN", "source": "WEB" } }
```

失败原因：`用户名或密码不能为空` / `用户名或密码错误` / `账号已禁用`。

**登录来源**在登录时判一次，记进账号的 `last_login_source`，用来知道这个人是从哪儿登进来的：

| 来源 | 判定 |
| --- | --- |
| `WEB` 网页端 | 常规浏览器 |
| `CLIENT` 客户端 | `X-Client-Type: client`，或 curl / okhttp 这类非浏览器 UA |
| `MOBILE` 移动端 | `X-Client-Type: mobile`，或 UA 带 iPhone / Android 等移动设备字样 |

自己的 App 与脚本带上 `X-Client-Type` 头最准，没有它才去猜 UA。来源只是标签，不做限制——
同一个人今天用网页、明天用 App，来源不同但能干的事一样。

同一次登录还会把 IP 记下来（`X-Forwarded-For` 的第一段，其次 `X-Real-IP`，都没有才用连接的地址），
并落一条登录日志；失败的那次也记。用户列表上能看到时间与来源，完整记录在 §4.13.5。

### 2.2 当前用户信息

`GET /api/user/info`（别名 `/vue-admin-template/user/info`）

```json
{ "code": 20000, "data": {
    "name": "admin",
    "avatar": "https://...",
    "roles": ["SUPER_ADMIN"],
    "role": "SUPER_ADMIN",
    "permissions": ["dashboard:view", "device:view", "device:manage"],
    "source": "WEB",
    "introduction": "TR069 ACS 管理端" } }
```

`permissions` 是当前角色勾中的权限点，前端用它决定菜单与按钮露不露出来。
系统管理员这里返回的是全部权限点。

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
| 工单 / 光猫档案 | `/api/worklist` | §4.8 |
| 首页概览 | `/api/dashboard` | §4.9 |
| OLT 管理（Telnet 采集） | `/api/olt` | §4.10 |
| 设备预设（引擎内部） | 无接口 | §4.11 |
| 文件与固件 | `/api/files` | §4.12 |
| 系统管理（用户与角色权限） | `/api/system` | §4.13 |

---

## 4. 管理端接口详解

### 4.1 设备台账

基础路径 `/api/device`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/page` | 分页列表，参数 `page`(默认1)、`size`(默认20)、`sn`(模糊)、`roomNo`(房间号，模糊)、`isp`(精确)、`deviceType`(精确)，按最近上报时间倒序 |
| GET | `/{sn}` | 详情：`{device, session}`，`session` 含 `online`/`state`/`lastContactTime`/`pendingCount`（设备离线时没有 `session` 字段） |
| GET | `/{sn}/status` | 在线状态：`{sn, online, state}` |
| GET | `/online` | 全部在线会话明细列表 |
| GET | `/{sn}/admin-account` | 实时查询设备本地账号（超管）：`{accounts:[{instance, path, username, password}], status}`；`status` 为 `success` / `empty` / `timeout` / `fail` / `offline`。设备需在线，且型号需把 `User.{i}.Username/Password` 暴露给 TR-069 |
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
| GET | `` | 汇总：`{sn, rootNode, dataModel, lastCollectTime, registerTime, lastContactTime, device, wan, wifi, lan, sip, pon, ip}` |
| POST | `/collect` | 向在线设备读一遍业务参数后回库解析 |

关键字段：

- `rootNode` / `dataModel`：`InternetGatewayDevice.`(TR-098) 或 `Device.`(TR-181)。
  业务前缀由 `DataModel.businessPrefixes` 同时覆盖两种模型，**即便台账里的根节点是脏值也能查出数据**；
  排查「某些型号业务分区一片空白」时先看这两个字段。
- `wan` / `wifi` / `lan` / `sip`：按实例对象分组。`lan` 额外带 `connected`（是否插线）、`instance`（网口号）、`mac`；
  `sip` 只保留真实线路实例（`VoiceProfile.{n}.SIP.{i}.` / `Line.{i}.SIP.`），汇总对象 `VoiceProfile.{n}.SIP.` 会被剔除
  ——只存在汇总对象时才会保留它，避免误删数据。
- `registerTime` / `lastContactTime`：设备台账的注册时间与最近上报时间（来自 `cpe_device`），不依赖能力树采集。
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

### 4.8 工单（建单 + 查询）

基础路径 `/api/worklist`。建单即转下发任务，执行由 `IntentDeliveryService` 异步推进。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/instances` | 建单：body `{sn, category, worklistDesc, values, templateId?}`；以 `worklistDesc` 做幂等键，重复提交只受理一次 |
| GET | `/instances` | 分页：`sn`、`status`、`page`、`size` |
| GET | `/instances/{id}` | 单条工单 |
| GET | `/instances/device/{sn}` | 某设备的工单列表 |
| GET | `/instances/{id}/steps` | 工单步骤记录 |

建单后立刻转下发任务（`source=worklist`），任务结束按结果回写工单终态与失败原因（`remark`）。

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

### 4.10 OLT 管理（Telnet 采集）

基础路径 `/api/olt`。

OLT 不走 TR-069：纳管方式是 Telnet 登录设备命令行执行查询。台账在 `olt_device`，
同步结果在 `olt_board`，每次连接/同步的回显留档在 `olt_sync_record`（排查解析问题用）。

| 方法 | 路径 | 参数 | 说明 |
| --- | --- | --- | --- |
| GET | `/list` | `keyword`(名称/地址/型号模糊) | OLT 列表，密码不回显，只给 `hasPassword` |
| GET | `/{id}` | — | 详情：`{device, boards, records}`，`records` 为最近 10 条 |
| POST | `/save` | 见下 | 新增（`id` 为空）或修改（`id` 必填） |
| POST | `/delete` | `{id}` | 删除台账，同时物理清理板卡 |
| POST | `/test` | 保存入参 | 诊断测试，**未保存的表单也能测**；不覆盖已采集的板卡 |
| POST | `/{id}/test` | — | 对已保存设备做诊断测试 |
| POST | `/{id}/sync` | — | 登录采集版本与板卡，覆盖写 `olt_board` 并刷新台账，返回最新详情 |
| POST | `/{id}/vlan` | `{vlanIds, tagged[], untagged[]}` | 新建 VLAN 并配成员端口，成功后自动重采 |
| DELETE | `/{id}/vlan` | `[100, 200]` | 删除勾选的 VLAN（下发 `no vlan ...`，连续段合并成一条），成功后自动重采 |
| GET | `/{id}/actions` | — | 命令动作目录：这台设备能下发哪些命令（章节 / 功能 / 参数 / 视图 / 是否查询 / 是否高风险） |
| POST | `/{id}/exec` | `{code, params}` | 执行一条命令动作。查询类返回设备回显，配置类下发后重采并返回最新详情 |
| GET | `/{id}/records` | `limit`(默认10，上限100) | 采集记录，`rawPreview` 为回显片段 |
| GET | `/web-accounts` | — | 「允许访问的 Web 登录账号」候选，来自 `sys_user` 启用账号 |

新建 VLAN 入参（页面「创建」弹窗）：

```json
{
  "vlanIds": "100-102,300",
  "tagged": ["epon1", "epon2", "ge3"],
  "untagged": ["ge4"]
}
```

- `vlanIds`：1-4094，支持 `,` 与 `-` 连接，写反或越界当场报错；不带端口时一次最多 64 个，带端口一次最多 16 个
  （每个 VLAN 都要单独进视图配端口）。
- `tagged` / `untagged`：端口写法 `类型+编号`，同一个口不能两边都占（页面会互斥）。
- 下发顺序：`vlan standard 100-102` → 逐個 VLAN `vlan standard <id>` + `port epon 1-2 tagged` + `exit`。
- 可勾的端口来自采集时执行的 `show port state all`（`vlan.memberPorts`）；GPON 的 PON 口不在里面，
  它不做二层成员，勾了后端也会拒绝并说明原因。

保存入参：

```json
{
  "id": 12,
  "name": "淮安SAS2108-核心机房03A",
  "host": "10.10.19.44",
  "protocol": "TELNET",
  "username": "root",
  "password": "",
  "port": 23,
  "charset": "AUTO",
  "bindLocalIp": "10.10.19.2",
  "webAccounts": ["admin", "operator01"],
  "remark": "核心机房"
}
```

约定：

- `password` 为空：新增时表示使用设备默认口令 `admin`；修改时表示**沿用原密码**。
- `username` 为空按 `root` 处理；`port` 为空按协议默认值（Telnet 23 / SSH 22）。
- `charset` 取 `AUTO` / `UTF-8` / `GBK` / `GB18030`。`AUTO` 先按 UTF-8 登录，
  回显出现替换符时自动用 GBK 重连一次。
- `webAccounts` 为空表示不限制；落库为逗号分隔串。
- `protocol=SSH` 当前返回失败：只实现了 Telnet，SSH 待后续接入。
- 修改地址 / 协议 / 端口后，台账里的型号、厂商、板卡摘要会被清空（不再代表这台设备）。

诊断测试返回（失败也是 HTTP 200，`success=false`）：

```json
{ "success": true, "message": "登录成功：华为 MA5600T V800R018C10，提示符 <HUAWEI>",
  "vendor": "HUAWEI", "vendorLabel": "华为", "model": "MA5600T",
  "softwareVersion": "V800R018C10", "prompt": "<HUAWEI>", "costMs": 1830,
  "rawOutput": "Welcome...\n$ display version\n..." }
```

采集规则（`OltVendor` / `OltInfoParser`）：

| 厂商 | 识别关键字 | 版本命令 | 板卡命令 | 温度命令 |
| --- | --- | --- | --- | --- |
| 华为 `HUAWEI` | huawei / vrp / ma5600 / ma5800 | `display version` | `display board 0` | `display temperature all` |
| 中兴 `ZTE` | zte / zxan / c320 / c300 | `show version` | `show card` | — |
| 烽火 `FIBERHOME` | fiberhome / an5516 / an6000 | `show version` | `show card` | — |
| 鸿升光 `HSGQ` | hsgq / hongsheng / 鸿升光 | `show version` | 无（整机按型号生成） | — |
| 通用 `GENERIC` | 兜底 | 两条都试 | `display board 0`、`show card` | — |

鸿升光（EPON 一体化）另外那几条，都在 `HsgqOltProfile` 里：

| 用途 | 命令 | 说明 |
| --- | --- | --- |
| 采集 PON 口 ONU 数 | `show pon-info` | `PON01 Up 5/5`（在线/总数） |
| 采集 ONU 明细 | `show onu-info all` | MAC 即 EPON 的 SN，离线也列出来 |
| 采集 VLAN | `show running-config` | `vlan standard 100` 一行一个 VLAN，紧跟的 `port epon 2 untagged` 是成员端口；采不到时退回 `show vlan` |
| 新建 VLAN | `vlan standard <id>` / `vlan standard <start> <end>` | 会切进 vlan 视图，下发时自动补 `exit` 回全局配置视图 |
| 删除 VLAN | `no vlan <id>` / `no vlan <start> <end>` | 全局配置视图命令，勾选连续时合并成一条 |

采集顺序（现场要求）：登录成功 → 关分页 → **`display board 0`** → `display version` →
`enable` → `config` → 温度（按槽位）→ 每块业务板一条 `display ont info 0 <槽位> all` →
`display vlan all` → 上行口/主控口逐个 `display port vlan 0/<槽位>/<端口>` → `quit` 退到特权视图。
板卡放在最前面，是因为版本命令在部分设备上很慢（甚至停在 `Parameter: FrameID/SlotID` 等输入），
一旦超时，后面的板卡采集会跟着落空。某条命令超时后会先把残留回显读干净再继续，
否则下一条命令会匹配到旧提示符，出现「同步成功却 0 块板卡」。

现场这台 MA5683T（V800R015C00）有几处和通用写法不一样，都已按实际回显适配：

| 现象 | 处理 |
| --- | --- |
| 用户视图下 `config` 也是 Unknown command | 先 `enable` 进特权视图，再 `config`；`quit` 只退一层，**不能再 quit**（那是 logout，设备会停下来问 y/n） |
| `display temperature all` → `% Parameter error` | 退到按槽位 `display temperature 0/<槽位>`，回显 `The temperature of the board:  41C( 105F)` |
| 这一版 `display port` 只有 desc/mode/statistics/vlan | **不发任何 display port 命令**，页面端口行只显示 ONT 数量 |
| `display ont info 0 1 all` 只在 config 视图生效 | ONT 采集放在 config 视图里，参数是空格分隔；**按板查，不逐端口查**，一条命令带回整块板各端口的 ONT |
| 空端口在整板回显里没有对应段落 | 按板卡端口数补齐，记成 0 个 ONT，端口仍然列出 |

ONT 汇总行的实际写法带空格：`In port 0/ 1/0 , the total of ONTs are: 23, online: 22`，
端口写成 `0/ 1/0`、逗号前有空格，正则按这个形态匹配；没有汇总行的版本退回数明细行，
`Run state` 为 online（EPON 写 up）的算在线。

MA5683T 的 `display version` 不给参数时会先回一条参数提示：
```
 { <cr>|backplane<K>|frameid/slotid<S><Length 1-15> }:
```

然后停在原地等输入。客户端识别到这类提示（`{ <cr>|... }:` 或 `Parameter: ...`）会自动补一个回车
取默认参数 `<cr>`，再继续读正文——不补回车就只能等到命令超时，版本、背板、运行时长全空。

另外提示符判定加了「静默 120ms 才算结束」：设备一行回显可能分几个 TCP 包到，
只看 `>` 结尾会把 `|backplane<K>` 这种半行当成提示符，命令只拿到半截。

同步摘要里会带上诊断：`display board 0 无回显（命令超时或连接被断开）`、
`display board 0 回显无法解析：xxx`、`命令异常：xxx`；厂商与型号在版本命令失败时会从登录横幅兜底。

华为这条链路解析出来的字段：

| 来源 | 字段 |
| --- | --- |
| `display version` | `model`（MA5683T）、`softwareVersion`（MA5600V800R015C00）、`patchVersion`（SPC107）、`backplane`（H802MABO）、`macAddress`、`uptime`（转成 59天18小时21分11秒） |
| `display board 0` | 每槽 `boardType` / `status`（Normal、Active_normal、Standby_normal、Auto_find、Failed…）、`boardKind`、`description`、`portMode`、`portCount`；另外用**槽位表里最大的 SlotID + 1** 当机框槽位数 `slotCount`（空槽位也占一行：0..12 就是 13 个槽位，只有 1/6/8 插了板卡，机框照样画 13 格） |
| `display temperature all` | 每槽 `temperature`，一格多传感器取最高温 |
| `display ont info 0 <槽位> all` | 该板各端口 ONT：`onuTotal` 总数、`onuOnline` 在线数、`onts[]` 每条 ONT 的明细（ID / SN / 在线 / 型号） |
| `display vlan all` | VLAN 列表：`vlanId`、`type`（smart/standard/mux）、`attribute`、`stdPortNum`、`servPortNum`、`vlanConNum`（回显是 `-` 时为 null） |
| `display port vlan 0/<槽位>/<端口>` | 上行口/主控口放通的 VLAN：`vlans[]`（升序）、`nativeVlan`（PON 口没有这一行） |
| `display ip interface brief` | 三层 VLAN 接口：只取 `vlanif<N>` 行，`vlanId` / `ipAddress` / `prefixLength` / `mask`（点分十进制）/ `physical` / `protocol`；`meth0`、`null0` 不算 |
| `display ip routing-table` | 静态路由：只取 `Proto=Static` 的行，`destination`（如 `0.0.0.0/0`）/ `nextHop` / `iface` / `preference` / `cost` |

VLAN 快照（VLAN 列表 + 各上行口放通情况 + 三层接口 + 静态路由）按 JSON 存在 `olt_device.vlan_json`，
`GET /api/olt/{id}` 响应里多一个 `vlan` 字段：
`{ vlans:[], portVlans:[], ipInterfaces:[], staticRoutes:[], total, collectedAt }`。只对上联板和主控板的端口发
`display port vlan`（现场是 0/6/0-3 与 0/8/0-1，共 6 条），**PON 业务口不逐个问**；
某块板连着两个口没回显就收手，说明这版设备不认这条命令。整轮 VLAN 采集共 9 条命令
（1 条 `display vlan all` + 6 条 `display port vlan` + 接口 + 路由表）。

ONT 数量必须用**空格分隔**的参数（`display ont info 0 1 all`，不是 0/1），而且必须先 `config` 进配置视图，
用户视图下设备不认这条命令。客户端流程是 `enable` → `config` → 每块业务板一条 → `quit` 退回；
**不逐端口发命令**，8 口板省下 7 条，一轮采集快得多。
在线数优先取每个端口末尾的汇总行 `In port 0/ 1/0 , the total of ONTs are: 23, online: 22`，
没有汇总行的版本按明细行数，`Run state` 为 online（EPON 写 up）的算在线。

端口列表以 JSON 存在 `olt_board.ports_json`（端口数不多，不单开表），详情接口按 `board.ports` 数组返回。
每个端口带 `frameNo` / `slotNo` / `portNo`，页面显示成 `P0 0/1/0   23 ONT / 22 在线`——接口标识和
命令行里的 F/S/P 一致，能直接对上物理口；回显里没出现的端口记 `0 ONT / 0 在线`，照常列出。
不发 display port 命令，因此不采集光模块在位状态，页面也不画灯。

点开某个端口会列出这个口下**每一条 ONT**（`onts[]`），在线和不在线都在，页面显示
`#0  SN  型号  在线/离线`。字段：`ontId` / `sn`（GPON 是 SN，EPON 是 MAC） / `runState`
（online / offline，EPON 写 up / down） / `online` / `description`（Description 段的型号，如 SK-D748S）。
明细行解析用的正则**不锚定行首**：分页时设备会把 `( Press 'Q' to break ) ----` 直接打在行中间，
后面跟着几条 ONT，锚定行首会漏掉它们（数量对、明细少几条）。

`boardKind` 取值：`SERVICE`（业务板）/ `CONTROL`（主控板）/ `UPLINK`（上联板）/ `POWER`（电源板）/
`INTERFACE`（通用接口板）/ `UNKNOWN`。型号认不出时按 MA5600T 槽位约定兜底（6/7 主控、8/9 上联、10/11 电源、12 通用，其余业务板）。

机框视图：`OltChassis.vue` 按 **`display board 0` 槽位表给出的槽位数**画整框（上导轨风扇格栅、下导轨槽位号，
槽位号就是 SlotID；接口返回 `device.slotCount`，没采到才按 12 兜底），**槽位 60 宽 / 700 高，板卡 55 宽**：
槽位尺寸和面板 viewBox 一致、边框用 inset 阴影画，板卡插进去上下左右顶齐不留缝；槽位里渲染 `OltBoardPanel.vue` 的
`compact` 模式——只画板卡本体（底板、丝印、螺丝、助拔器、RUN/ALM/ACT/ENV 灯、上面的 `PON` 运行灯带
（8 颗灯独立渲染、分两列排）+ 下面一列竖排的端口、底部的 `N ONT 在线`），两侧导轨和逐口标注列都省掉
（60 宽放不下），型号与运行状态放在 HTML `title` 里，口名 / RX / ONT / VLAN 用 SVG `<title>` 悬停看。
没插板的槽位画成虚线空槽位。
槽位数默认 12，有板子插在更大槽位号上自动补齐；整框约 760 宽，一屏放得下 12 个槽位；
点槽位高亮该槽位与下导轨上的槽位号，点端口展开 ONT 明细。

板卡面板：前端用 SVG 现画（`OltBoardPanel.vue`），复刻 MA5683T 机框业务板外观——板卡**竖着插在机框导轨里**、
**板卡宽 55、槽位（机框）高 700**（面板 viewBox `237×700`：`SLOT_H = 76 + 8×72 = 652`，
加上底部汇总 24 与丝印 24 = **700**，端口少的板下面就是空白金属面板；
底部的「N ONT 在线」汇总行也固定占一格，有没有 ONT 高度都一样），
深灰金属底板、拉丝纹理、四角螺丝、顶部助拔器、型号/槽位丝印与 RUN/ALM/ACT/ENV 板卡灯；端口**自上而下一列竖排**
（GPON/EPON 业务板 8 个 PON 口，PON 0 在最上面），每个口画出光口笼（GE 口画 RJ45）+ 口序号丝印 +
一个圆形指示灯：**绿色闪烁=在线、红色常亮=告警**（光模块异常或注册过 ONT 但全部离线）、**灰色=离线**。

主控板（`controlBoard` 的型号，如 H802SCUN）在板卡灯下面多画一段**管理区**，和真板卡一样：
`RESET` 复位孔 + `CON` 控制台串口 + `ETH` 管理网口（右侧绿=Link、黄=Act 两颗灯）+ `ESC` 级联扩展口，
口名用竖排丝印贴在口的右边，标注列写明各自是什么口；管理区下面的 4 个 GE 口照常带数据标注。

右边是**每口的标注列**，用细引出线连到对应的物理口，一口三行：
`PON 0 … -8.12 dBm`（口名 + 接收光功率）、`27 / 28 ONT 在线`（或上行口的 `链路 在线`）、
`0/1/0 · VLAN 1、46`（接口号 + 上行口放通的 VLAN，来自 `display port vlan` 快照，PON 口一般没有）。
标注列表头是板卡运行状态与温度；`N ONT 在线` 的整板汇总嵌在**板卡底部金属面上（底部丝印上方）**，
跟着板卡走而不是挂在标注列下面，这样各块板在机框里对齐时徽标也在同一条线上。点某个口在面板下方展开这个口下的 ONT 明细。

面板样式由 `boardProfiles.js` 的 `panel` 字段决定：`pon`（PON 业务板）/ `ge`（主控板、以太网上行）/
`xge`（10GE 上联）/ `generic`（未知型号按端口数画）。已建档：H807GPBD、H801XGBD、H808EPSD（8 口 PON）、
H802SCUN（主控板 4 个 GE 口）、H801X2CS（2 路 10GE）、HSGQ-E04（4 口 EPON 整机）；
没建档的板卡按端口类型推断样式，不再依赖任何图片资源。

型号、版本、板卡均为宽松解析：解析不出就留空（页面显示 `-`），不阻断同步。
解析不符合现场设备时，用 `GET /{id}/records` 拿到原始回显（前 8000 字符）再补正则。

## OLT 首屏两段式同步（板卡先出来，慢数据后台补）

打开一台 OLT 时页面等的是机框：板卡插在哪个槽位、什么型号。这部分只要一条命令——华为
`display board 0`（一两秒就回完），所以 `POST /api/olt/{id}/quick-sync` 只做这一件事：

1. 按台账里已认出的厂商直接发对应板卡命令（华为 `display board 0`、第三方 `show card`），
   不再探测版本与命令风格；型号优先取登录横幅，横幅里没有才补一条版本命令；
2. 板卡写库后**立即返回**详情（含 `deepSyncing: true`），本次没采到的端口 / 温度沿用上次留下的，
   不会把页面正显示着的数据抹成空；
3. 温度、逐板端口状态与光模块、ONT 明细、VLAN、DBA/线路模板交给后台线程池补采，
   跑完覆盖写库；页面轮询 `GET /api/olt/{id}` 即可看到补齐的数据，`deepSyncing` 变回 false。

完整采集（`POST /api/olt/{id}/sync`、`POST /api/olt/{id}/refresh`）保持原样，一次把所有数据采回来，
给「同步ONT状态」这类明确要慢数据的操作、以及后台定时任务用。

## OLT 厂商适配：新增一家 / 一个型号要改哪些地方

各家 OLT 的命令根本不同：华为是 `display`、第三方是 `show`；华为 MA5600 关分页是 `scroll`，
其它家是 `terminal length 0`；中兴要认 `show card`，而鸿升光这类一体化设备压根没有板卡概念。
这些差别集中在 `src/main/java/com/tr069/acs/olt/vendor/` 下的厂商 profile 里：

| 文件 | 作用 |
| --- | --- |
| `OltVendorProfile` | 厂商能力接口：识别、命令清单、型号怎么抠、有没有板卡、怎么进配置视图、配置视图里补采什么 |
| `AbstractOltVendorProfile` | 默认实现（第三方 OLT 的常见样子），继承它只覆写不一样的那几个方法 |
| `HuaweiOltProfile` / `ZteOltProfile` / `FiberhomeOltProfile` / `HsgqOltProfile` | 已接入的品牌 |
| `GenericOltProfile` | 认不出厂商时的兜底：两套命令都试，型号挨家问一遍（新品牌注册进来自动认得） |
| `OltVendorProfiles` | 注册表，会话按厂商取 profile、按回显认厂商 |

**加一家品牌的完整改动：**

1. `src/main/java/com/tr069/acs/olt/vendor/XxxOltProfile.java`——继承 `AbstractOltVendorProfile`，
   通常只要写 `vendor()`、`matches()`（回显关键字）、几条命令和型号正则；
2. `OltVendor` 枚举加一个取值（库里存的厂商编号、页面下拉用它）；
3. `OltVendorProfiles.register()` 里加一行。

会话主流程（登录、探测风格、采板卡、深度采集编排）和 `OltInfoParser` 都不用动。

**同一个品牌加型号**通常在自家 profile 内部就能解决：鸿升光的 PON 口数在 `HsgqOltProfile.PON_PORT_COUNT`
里加一行；像 `HSGQ-E08A` 这种「字母 + 数字」的型号不用加，`ponPortCount` 会自己从型号里抠出口数。
需要在页面上出现新型号的示意图时，另外往 `web/src/views/olt/boardProfiles.js` 里加一条。

## OLT 命令动作目录（手册里的命令怎么变成页面上的功能）

鸿升光两台设备、两份手册加起来六百多条命令（EPON 13 章 332 条、GPON 15 章 300 余条），
一条一个接口写不完也维护不了，所以它们被登记成「动作目录」：

| 设备 | 厂商码 | 目录 | 手册 |
| --- | --- | --- | --- |
| 鸿升光 EPON（E04/E08/E16） | `HSGQ` | `HsgqActionCatalog` | 13 章 141 页 |
| 鸿升光 GPON（4/8/16 口，Neutral-G04/G08/G16） | `HSGQ_GPON` | `HsgqGponActionCatalog` | 15 章 157 页 |

一条动作 = 章节 + 功能名 + 命令模板 + 参数定义 + 执行视图 + 是否查询 + 是否高风险；
页面（「设备配置」页签）按目录自动生成表单，服务端按同一份定义校验参数、拼视图、下发、留档。
两台设备共用同一套界面：`GET /{id}/actions` 取哪份目录由设备的 `vendor` 决定。

**GPON 与 EPON 的差别**（写动作时别照抄）：PON 口关键字是 `gpon`、PON 口不做二层成员端口
（业务靠 `service-port` 绑 GEM Port）、多了第 5 章模板（DBA / 线路模板 / 业务模板）与第 6 章虚端口、
ONT 按 SN/LOID 注册且第 9 章命令全在 GPON 口视图里、第 3 章多一节风扇管理。

**加一条命令**就是加一行：

```java
list.add(config("vlan.create", CH_VLAN, "创建单个 VLAN", "端口 VLAN，取值 1-4094",
        List.of(vlanId), "vlan standard {vlanId}"));
list.add(query("sys.cpu", CH_CPU, "查看 CPU 利用率", null, "show cpu-usage"));
list.add(in(OltAction.View.PON, "pon.mac.bind", CH_PON_AUTH, "MAC 认证：添加白名单并绑定 ONU", null,
        List.of(ponId, mac), "bind-onu[{ onuId}] mac {mac}"));
```

**执行视图**（命令在哪个提示符下发，`exit` 自动补）：

| view | 进入命令 | 提示符 |
| --- | --- | --- |
| `CONFIG` | — | `OLT(config)#` |
| `VLAN` | `vlan standard <vlanId>` | `OLT(config-vlan-100)#` |
| `PORT` | `interface <portType> <portNo>` | `OLT(config-ge-1)#` |
| `PON` | `interface epon <ponId>` | `OLT(config-epon-1)#`（EPON） |
| `GPON` | `interface gpon <ponId>` | `OLT(config-gpon-1)#`（GPON） |
| `LINE_PROFILE` | `ont-lineprofile gpon profile-id <profileId>` | `OLT(config-gpon-lineprofile-1)#`（GPON） |
| `SRV_PROFILE` | `ont-srvprofile gpon profile-id <profileId>` | `OLT(config-gpon-srvprofile-1)#`（GPON） |
| `ONU` | `interface onu <ponId>/<onuId>` | `OLT(config-onu-1/1)#`（EPON） |
| `MANAGE` | `interface manage` | `OLT(config-manage)#` |
| `ACL` | `acl <aclId>` | `OLT(acl-basic-2000)#` |
| `MCAST` | `multicast-vlan <vlanId>` | `OLT(multicast-vlan-100)#` |
| `VLANIF` | `interface vlanif <vlanId>` | `OLT(config-vlanif-1)#` |

**可选参数**写成方括号段，参数没填就整段去掉：`ntp enable ntp-server-ip {serverIp}[ backup-server-ip {backupIp}]`、
`user add {userName} {password} {level}[ reenter {reenter}][ {info}]`。

**参数校验**：`number` 卡范围、`ports` 只认 `1,3,4-8`、`enum` 只认下拉值、`text` 拒绝换行与分号
（命令是一条条发的，参数里混进换行等于多发一条命令）。

**查询 vs 配置**：`query`/`queryWith`/`queryIn` 只把回显返回页面；配置类下发成功后会自动重采一次
（`dispatchConfig`），页面直接换成新数据。

两份手册里还剩缩写术语那一章（EPON 第 13 章、GPON 第 15 章）没有动作——那是名词表，不是命令。

## OLT 自动采集（不用手点同步）

后台定时任务 `OltAutoRefreshService`：**启动后 5 秒自动采一轮，之后每 5 分钟一轮**，
每轮重新登录每台 OLT 走一遍上面的采集，重新写 `olt_board` 和台账字段。
板卡插拔、端口 ONT 数、设备重启后的运行时长这些只能到设备上看的东西，页面上会自动跟上；
页面不主动去「同步设备」，只读库里的最新数据。

周期和相关参数写死在 `OltAutoRefreshService` 里，不放到配置文件——现场忘了配或配错成 0，
数据就会停在过去某个时刻而没人发现：

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `INTERVAL_MILLIS` | 5 分钟 | 两轮之间的间隔，失败退避也按它的倍数算 |
| `INITIAL_DELAY_MILLIS` | 5 秒 | 启动后跑第一轮，只留出建表和连接池初始化的时间 |
| `maxRoundMillis` | 5 分钟 | 单轮耗时上限，超了剩下的设备留到下一轮 |
| `MAX_BACKOFF` | 6 | 连续失败最多退避到 6 倍间隔 |

几个保护：串行采集（OLT 的 Telnet 并发会话数很小）且上一轮没跑完本轮跳过；
单台连不上时按连续失败次数把它的间隔逐步拉长（最多 6 倍），连好后恢复，不让一台掉线设备拖慢整轮；
单台失败只记日志和采集记录（操作人写「自动刷新」），不影响其他 OLT。

页面侧只有「自动刷新」开关和间隔下拉（5/10/30/60 秒），只轮询详情接口把最新数据显示出来，
**不会因为数据旧而自己去登录设备**；「同步」按钮是唯一的手工入口，应急时才点。

超时配置：`acs.olt.connect-timeout-ms` / `login-timeout-ms` / `command-timeout-ms`，
建表开关 `acs.olt.auto-create-table`。

```bash
# 先测通再保存
curl -X POST 'http://127.0.0.1:8888/api/olt/test' -H 'X-Token: <token>' \
  -H 'Content-Type: application/json' \
  -d '{"host":"10.10.19.44","protocol":"TELNET","username":"root","password":"admin","port":23}'

# 保存后同步
curl -X POST 'http://127.0.0.1:8888/api/olt/12/sync' -H 'X-Token: <token>'
```

---

## 4.11 设备预设（上报时自动收敛配置）

预设是引擎内部的规则：**匹配条件 + 动作**，设备上报时按优先级依次匹配，命中后把动作里的
目标值下发下去。已经对得上的参数会自动跳过，所以反复上报不会反复下发；一轮改完的参数会
补进已知值，下一轮可能让另一条预设的条件成立，最多 4 轮，防止预设互相触发形成死循环。

预设存在 `cpe_preset` 表里，**没有对外接口，也没有页面**：纳管那一条由引擎自己保证存在，
要加别的规则直接改库。

### 4.11.1 内置纳管预设

引擎首次用到预设时会确保库里有一条内置预设「纳管（内置）」，动作为：

```json
[{"type":"set","path":"ManagementServer.URL","value":"${acsUrl}"},
 {"type":"set","path":"ManagementServer.PeriodicInformInterval","value":"${informInterval}"},
 {"type":"set","path":"ManagementServer.ConnectionRequestUsername","value":"${crUsername}"},
 {"type":"set","path":"ManagementServer.ConnectionRequestPassword","value":"${crPassword}"}]
```

它不设条件（命中所有设备）、重跑间隔 24 小时、BOOTSTRAP 时强制重跑，
等价于改造前 `InformProvisionService` 写死的那 4 个参数。改造后这四项改由预设驱动，
原来的 `acs.device.provision.*` 配置继续生效——现在它们作为变量的来源。

### 4.11.2 条件

```json
[{"field":"productClass","op":"eq","value":"HS8145V5"},
 {"field":"param:ManagementServer.URL","op":"neq","value":"http://10.0.0.1:9090/ACS-server/ACS"}]
```

多条之间是**且**的关系，全部成立才算命中。

`field` 可取：

| 取值 | 含义 |
| --- | --- |
| `sn` | 设备序列号 |
| `oui` | 厂商 OUI |
| `productClass` | 设备型号 |
| `softwareVersion` / `hardwareVersion` | 软件 / 硬件版本 |
| `manufacturer` | 厂商 |
| `rootNode` | 数据模型根节点（`InternetGatewayDevice.` 或 `Device.`） |
| `isp` | 运营商，读设备台账 |
| `param:<参数路径>` | 参数当前值，写叶子段即可，如 `param:ManagementServer.URL` |

`op` 可取：

| 取值 | 含义 |
| --- | --- |
| `eq` | 相等（忽略大小写，不写就是它） |
| `neq` | 不等 |
| `contains` | 包含 |
| `startsWith` | 以…开头 |
| `in` | 属于，值用逗号或分号分隔，如 `CMCC,CT` |
| `regex` | 正则匹配 |
| `gt` / `lt` | 大于 / 小于，按数值比较 |

参数值取不到时条件**不成立**（`neq` 也一样），所以不要用 `neq` 表达「参数还没设过」。

### 4.11.3 动作

```json
[{"type":"set","path":"ManagementServer.PeriodicInformInterval","value":"300"},
 {"type":"reboot","key":""}]
```

| 类型 | 参数 | 说明 |
| --- | --- | --- |
| `set` | `path` + `value` | 设参数值，转 SetParameterValues；同一次会话内的 set 会合并成一条下发 |
| `reboot` | `key` | 重启，转 Reboot |
| `addObject` | `objectName` + `key` | 建实例，转 AddObject |
| `deleteObject` | `objectName` + `key` | 删实例，转 DeleteObject |

`set` 的 `path` 写 `InternetGatewayDevice.` 或 `Device.` 开头就是绝对路径，否则按设备根节点补全，
所以通常只写 `ManagementServer.URL` 这一段。

`value` 支持这些变量：

| 变量 | 展开成 |
| --- | --- |
| `${acsUrl}` | ACS 上报地址，取 `acs.device.provision.acs-url`；留空则按 `acs.public-host` + 上报端口 + `acs.cwmp.cpe-path` 拼 |
| `${informInterval}` | 上报间隔秒数，取 `acs.device.provision.inform-interval-seconds`；配 0 时这条动作自动丢弃 |
| `${crUsername}` / `${crPassword}` | 连接请求凭据，取 `acs.device.provision.connection-request-*`；留空或 `auto` 按 `itms` |
| `${sn}` / `${productClass}` | 设备序列号 / 型号 |
| `${rootNode}` | 设备数据模型根节点 |

### 4.11.4 其它约定

- 重跑间隔按「设备 + 预设」分别计时，存在进程内存里，**重启进程会重新计时**。
- 下发失败也按间隔节流，不会每次上报都重试——否则会一直占住设备的下发窗口。
  真正失败的设备等间隔过去、或等它重启（BOOTSTRAP）时会再跑一次。
- 下发改写过连接请求凭据时，会同步登记进设备台账，避免之后拿设备已经不认的旧密码去唤醒。

---

## 4.12 文件与固件升级

文件台账在 `/api/files`，设备取文件是 `GET /files/{id}`。

### 4.12.1 两个口子

| 用途 | 路径 | 鉴权 |
| --- | --- | --- |
| 上传、清单、删除、下发 | `/api/files`（管理端口 8888） | 要令牌 |
| 设备下载文件 | `/files/{id}`（上报端口 9090） | 不要令牌 |

分得开是因为所有端口共用一个 Web 容器：上报口是设备必然连得上的口子，
把文件口挂在那里最稳；它又不在 `/api` 与 `/openapi` 下，不会被鉴权拦住——
设备下载时不会带令牌。

### 4.12.2 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/files` | 文件清单，新的在前 |
| GET | `/api/files/{id}` | 文件详情 |
| GET | `/api/files/{id}/url` | 设备取这个文件的地址 |
| POST | `/api/files` | 上传（multipart） |
| POST | `/api/files/{id}/deliver` | 下发给一批设备 |
| DELETE | `/api/files/{id}` | 删除 |

### 4.12.3 上传

`multipart/form-data`：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `file` | 是 | 文件本身，单个上限 512MB |
| `fileType` | 否 | `FIRMWARE`（默认）/ `CONFIG` / `VENDOR` |
| `version` | 否 | 版本号 |
| `remark` | 否 | 备注 |

落盘路径是 `acs.file.dir`（默认 `files/`）下的 `yyyyMMdd/uuid_文件名`。
台账里记的是落盘路径，**不是对外 URL**。对外只有 `/files/{id}` 这一个地址，
所以文件挪位置、换目录都不影响已经下发出去的下载任务。

### 4.12.4 设备取文件

`GET /files/{id}` 支持：

| 能力 | 说明 |
| --- | --- |
| Range | `bytes=0-1023` / `bytes=1024-` / `bytes=-500`，命中回 `206` + `Content-Range`；范围不满足回 `416` |
| ETag | 取内容摘要（SHA-256），内容不变它就不变 |
| Last-Modified | 文件落盘时间 |
| If-None-Match | 命中回 `304`，设备不用重新拉一遍 |

大文件断点续传靠 Range。设备下载到一半断开是常态，服务端不把它当错误。

⚠️ **设备取文件的地址是现场最容易踩的坑**：默认按 `acs.public-host` + 上报端口拼，
但 ACS 的地址和设备所在的网段经常不是一回事。对不上就配 `acs.file.base-url`
（如 `http://10.10.19.2:9090`），直接写设备真正能访问到的那个地址。

### 4.12.5 下发升级

```bash
curl -X POST 'http://127.0.0.1:8888/api/files/1/deliver' -H 'X-Token: <token>' \
  -H 'Content-Type: application/json' \
  -d '{"sns":["ZXHN000001","ZXHN000002"],"delaySeconds":0}'
```

每台设备各排一条 `Download`：队列是按设备分流的，多设备之间不会互相堵，
不像对同一台设备连发多条那样会把它的队列占满。

下发的参数按 CWMP 的 Download 结构拼（顺序即 SOAP 里的元素顺序）：

`CommandKey`、`FileType`、`URL`、`FileSize`、`TargetFileName`、`DelaySeconds`

`FileType` 由台账类型转过来：`FIRMWARE` → `1 Firmware Upgrade Image`，
`CONFIG` / `VENDOR` → `3 Vendor Configuration File`。

返回每台设备的 `taskId`，进度用 `GET /api/rpc/{taskId}` 查。

### 4.12.6 下载完成的上报

设备下载完会主动上报 `TransferComplete`（往往比 Download 指令晚几分钟）。
ACS 会回 `TransferCompleteResponse`——不回的话设备会以为 ACS 没收到而反复重报。

下载结果**不记在 Download 指令的账上**：那条指令在设备回 `DownloadResponse` 时就结了，
这条上报是独立结果，只记日志：

- 成功：`设备下载完成: sn=..., commandKey=...`
- 失败：`设备下载失败: sn=..., commandKey=..., fault=<码> <描述>`

排查升级失败主要看这条日志里的 fault 码。

---

## 4.13 系统管理

基础路径 `/api/system`。角色、权限、用户都在这里。

### 4.13.1 权限模型

权限点是**代码里的功能清单**，不落库，也不允许增删：删掉一个权限点、代码还在判它，
那个功能就变成谁也点不动的死按钮。清单由 `GET /api/system/permissions` 给出，
页面按它勾选，勾的结果存在 `sys_role_permission` 里。

判定按 URL 自动映射，不用给每个接口加注解：

| 请求 | 要的权限点 |
| --- | --- |
| `GET /api/device/**` | `device:view` |
| `POST` / `PUT` / `DELETE /api/device/**` | `device:manage` |

认不出模块的路径（如 `/api/user/info`）不拦——登进来就能看自己是谁，没必要再配权限。

模块一共十个：dashboard / device / template / biz / model / worklist / olt / file / tag / system，
每个一读一写，共 20 个权限点。

### 4.13.2 两个默认角色

| 角色 | 说明 |
| --- | --- |
| `SUPER_ADMIN` 系统管理员 | 内置。**拥有全部权限**，不可删除，改它的权限会被拒 |
| `OPS_ADMIN` 运维管理员 | 默认能看全部、能改设备与工单，权限可调整 |

系统管理员的「全权限」是在代码里短路的，**权限表里没有它的行**——
存一份就要操心「表里那行和代码里那句不一致时谁说了算」。
所以它出现在角色列表里时权限是满的，但页面上那排勾选框是禁用的，保存按钮点不动。

其它角色的权限随便勾，**整份覆盖**：传进去的就是这个角色的全部权限，没传的就没了。
清单外的权限点会被直接丢掉。

### 4.13.3 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/system/permissions` | 权限点清单，按模块分组 |
| GET | `/api/system/roles` | 角色列表，带权限与每个角色下的人数 |
| POST | `/api/system/roles` | 新建角色，新角色一个权限都没有 |
| PUT | `/api/system/roles/{code}/permissions` | 设置权限，整份覆盖 |
| DELETE | `/api/system/roles/{code}` | 删角色 |
| GET | `/api/system/users` | 用户列表，带最近登录来源与 IP |
| POST | `/api/system/users` | 新建用户 |
| PUT | `/api/system/users/{id}` | 改昵称 / 角色 / 状态 / 密码（改密码要二次验证） |
| DELETE | `/api/system/users/{id}` | 删用户 |
| GET | `/api/system/login-logs` | 登录日志，失败的也在里面 |

### 4.13.4 改密码要二次验证

改密码（改谁的都算，包括改自己的）除了新密码，还得把操作者**自己**的当前密码一起提交：

```json
{ "password": "新密码", "currentPassword": "操作者自己的密码" }
```

少 `currentPassword` 或它不对，请求直接被拒，一个字都不写。

理由：拿到会话不等于拿到密码。会话被借走的人改不了密码，改不了就等于拿不走账号。
只改昵称、只改角色不需要这步。

### 4.13.5 登录日志

`GET /api/system/login-logs?limit=50`，传 `userId` 只看某一个人。

每次登录记一条：时间、登录名、IP、来源（WEB / CLIENT / MOBILE）、结果、浏览器标识。
**失败的也记**——连着几十次失败是有人在试密码，只记成功的话这条线索整个就没了。

| 失败原因 | 含义 |
| --- | --- |
| 用户不存在 | 用户名本身就不在库里，这条日志的 `user_id` 是空的 |
| 密码错误 | 用户名对、密码不对 |
| 账号已禁用 | 账号被人停了 |

IP 取自 `X-Forwarded-For` 的第一段（前面挂反代时，那才是真正的客户端），
其次 `X-Real-IP`，都没有才用连接的地址。反代没配好时别指望它准。
日志只留每个人最近 50 条，超出的把最旧的删掉——它是个只增不查的表。

### 4.13.6 权限不止菜单

页面上的按钮也按权限摆：没有 `device:manage` 的人，设备台账上"重启""恢复出厂""批量下发模版"
这些按钮根本不出现；没有 `olt:manage` 就看不到 OLT 的增删改。

但按钮只是让人别点到不该点的，**真正挡人的是后端**：

- 新接口没在 `PermissionCodes` 里登记模块的，会被直接拒掉（403）并在日志里告警。
  这是默认拒绝：漏登记会立刻暴露成"功能打不开"，而不是变成"所有人都能访问"。
- 免检的只有登录三件套：`/api/user/login`、`/api/user/info`、`/api/user/logout`。

### 4.13.7 几条规矩

- **初始系统账号 `admin` 删不掉**，接口返回「系统用户禁止删除」。它是建库脚本写进去的种子账号，
  删完没有人能进系统。应用层挡一道，**数据库里还有一个 `BEFORE DELETE` 触发器兜底**——
  就算有人在 MySQL 命令行里 `delete from sys_user` 也会被拦下。
- **至少要留一个系统管理员**：删它、停用它、把它降成别的角色，只要它是最后一个就会被拒。
  不留的话没人能再改权限，系统就锁死了。这一条同样写在数据库触发器里，判的是库里的实际数量。
- **不能删自己**。
- **还有人的角色删不掉**，先给他们换角色。
- 改角色**立即生效**，不用让对方重新登录——每次请求都按库里的角色判，不缓存在令牌里。
- 没有权限的请求返回 **403**，业务码 `50403`，消息里带着缺的那个权限点。

### 4.13.8 老版本升级

升级前 `sys_user.role` 存的是 `admin` / `operator` / `viewer` 三个字符串。
首次用到角色时会自动迁：`admin` → `SUPER_ADMIN`，`operator` / `viewer` / 空 → `OPS_ADMIN`。

**不迁的话老账号会因为角色不存在而一个权限都没有**，等于被锁在门外——
所以迁移放在每次都走得到的路径上（`RoleService.migrateLegacyRoles`），不指望人工跑脚本。

### 4.13.9 例子

```bash
# 建一个只能看的角色
curl -X POST 'http://127.0.0.1:8888/api/system/roles' -H 'X-Token: <token>' \
  -H 'Content-Type: application/json' \
  -d '{"code":"VIEWER","name":"只读"}'

curl -X PUT 'http://127.0.0.1:8888/api/system/roles/VIEWER/permissions' \
  -H 'X-Token: <token>' -H 'Content-Type: application/json' \
  -d '{"permissions":["dashboard:view","device:view","worklist:view"]}'

# 建个用户，让他只能看
curl -X POST 'http://127.0.0.1:8888/api/system/users' -H 'X-Token: <token>' \
  -H 'Content-Type: application/json' \
  -d '{"username":"wang","password":"123456","nickname":"老王","role":"VIEWER"}'
```

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
| 端口 | `acs.cwmp.listen-ports`，默认 `9090,8082,6060,8012,8080,8172,5481` |
| 路径 | `acs.cwmp.cpe-path` 默认 `/ACS-server/ACS`，另有 `/cpe` 与 `acs.cwmp.extra-cpe-paths` 兼容路径 |
| 鉴权 | HTTP Digest，账号 `acs.auth.cpe-to-acs-username/password`；配 `auto` 或留空即免认证（上线前务必改回） |
| 报文 | CWMP SOAP 1.1 envelope，见 `cwmp/SoapEnvelopeBuilder` |

下行 connection request 的 URL 由 `acs.public-host` 拼出（默认 `127.0.0.1`，上线前必改：
否则让设备主动回连时，它会连向自己）。

### 6.1 连接断开与指令重发

CWMP 是短连接：光猫报完一轮就把 TCP 断了。**连接断开不等于设备离线，也不等于指令失败**，
所以这边的处理是：

| 情况 | 处理 |
| --- | --- |
| TCP 连接断开 | 只摘掉连接标识，等回应中的指令继续留着，由响应定时器兜底超时 |
| 设备从新连接回来（NAT 换端口、被唤醒后重连） | 按会话接管处理：会话对象没变，不判成重复会话、不关会话 |
| 一轮没收到回应 | 重排队列，按 `acs.session.retry-delay-seconds`（默认 60 秒）× 2ⁿ 退避后重发，最多 `acs.session.max-delivery-attempts`（默认 2）次 |
| 设备回了 fault（9003 / 9002 等） | 直接判失败，不重试——重试还是同一个错 |
| 超过最大尝试次数 | 判 `8002` 失败 |

**有副作用的方法一律不重发**：没收到回应时并不知道设备执行了没有，下面这些重发就是真干了第二次，
所以直接按失败结，不重排队列：

`Reboot`、`Download`、`Upload`、`FactoryReset`、`AddObject`、`DeleteObject`、
`ChangeDUState`、`ScheduleDownload`、`ScheduleInform`、`CancelTransfer`、`SetVouchers`

`SetParameterValues` / `GetParameterValues` / `GetParameterNames` 这类设值与取值，
重复一次结果一样，可以安全重发。

### 6.2 会话号

每轮 Inform 都是一次新的 CWMP 会话，会换发新的 `session` Cookie。带着过期 Cookie 的请求蹭不进下一轮会话。
同一轮会话里，空 POST 与 RPC 响应常常各开一条 TCP 连接（源端口不同），只有 Cookie 能认出是同一台设备——
这是连接标识 `ip:端口` 做不到的，所以重靠 Cookie。

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
`GET /api/device/online`、`GET /api/device/{sn}/admin-account`、`GET /api/device/business/batch`、
`POST /api/device/{sn}/rpc/{rpcName}`

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

**工单**：`POST /api/worklist/instances`、`GET /api/worklist/instances`、`GET /api/worklist/instances/{id}`、
`GET /api/worklist/instances/device/{sn}`、`GET /api/worklist/instances/{id}/steps`

**概览**：`GET /api/dashboard/overview`

**系统**：`GET /api/system/permissions`、`GET /api/system/roles`、`POST /api/system/roles`、
`PUT /api/system/roles/{code}/permissions`、`DELETE /api/system/roles/{code}`、
`GET /api/system/users`、`POST /api/system/users`、`PUT /api/system/users/{id}`、
`DELETE /api/system/users/{id}`

**文件**：`GET /api/files`、`GET /api/files/{id}`、`GET /api/files/{id}/url`、`POST /api/files`、
`POST /api/files/{id}/deliver`、`DELETE /api/files/{id}`

**OLT**：`GET /api/olt/list`、`GET /api/olt/{id}`、`POST /api/olt/save`、`POST /api/olt/delete`、
`POST /api/olt/test`、`POST /api/olt/{id}/test`、`POST /api/olt/{id}/sync`、
`POST /api/olt/{id}/quick-sync`、`POST /api/olt/{id}/refresh`、
`GET /api/olt/{id}/records`、`GET /api/olt/web-accounts`

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
