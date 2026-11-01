# 后端开发文档（OLT 模块）

面向要在本项目上继续开发的同学：**设备知识该放哪、命令与校验怎么保证一致、新增厂商/型号/命令要动哪几个文件、哪些坑已经踩过**。

相关文档：[README.md](../README.md) 全局概览与部署 · [API.md](../API.md) 接口明细 · [DEBUGGING.md](DEBUGGING.md) 排障 · [ADAPTED_MODELS.md](ADAPTED_MODELS.md) 已适配型号 · [DEPLOYMENT.md](DEPLOYMENT.md) 部署。

---

## 1. OLT 模块总览

### 1.1 包结构

`com.tr069.acs.olt` 下按职责分包：

| 包 | 职责 | 关键点 |
| --- | --- | --- |
| `controller` | `OltController`，`/api/olt/**` 全部接口 | 只做参数转发与异常转 `Result`，不含业务规则 |
| `service` | `OltService`（编排、校验、落库）、`OltTelnetService`（采集）、`OltAutoRefreshService`（定时刷新） | `OltService` 是最大的类，改之前先看有没有现成私有方法 |
| `telnet` | `OltCliSession`（会话与命令编排）、`OltInfoParser`（回显解析）、`TelnetClient`（原始连接与续页）、`OltVendor`（厂商枚举） | 只有 `display` / `show` / `enable` / `config` / `quit` 这几种入口 |
| `vendor` | `OltVendorProfile` 接口 + 各厂商实现 + 命令动作目录 | **新增品牌只动这个包** |
| `entity` | `OltDevice` / `OltBoard` / `OltSyncRecord` 三张表 | `@TableField(exist = false)` 的字段不落库，见 §7.1 |
| `model` | 对外出参入参、表单元数据（`*Form`）、快照（`OltVlanInfo`、`OltTemplateInfo`） | 会被序列化进 JSON 缓存的用 `@Data` 类，不用 record |

### 1.2 数据落在哪

| 数据 | 存放位置 | 说明 |
| --- | --- | --- |
| 设备台账、连接信息、型号、状态 | `olt_device` 行 | |
| VLAN 快照 | `olt_device.vlan_json` | 整体覆盖 |
| DBA / 线路模板快照 | `olt_device.template_json` | 整体覆盖 |
| 板卡（槽位、型号、状态、温度、端口 JSON） | `olt_board` 行 | 每次同步**先删后插** |
| 采集记录 | `olt_sync_record` | 成功失败、耗时、原始回显 |

**板卡表只存设备上真有的列。** `OltBoard` 里的 `ports`、`panelKind`、`controlBoard`、`statusText`、`statusLevel`、`frontPanel` 全是 `@TableField(exist = false)`，读出来必须在 `OltService#fillPorts` / `fillBoardProfile` 里现算。见 §7.1。

### 1.3 一次详情请求经过什么

```
GET /api/olt/{id}
  → OltService.detail(id)
      → boardMapper.selectList(...)       板卡行
      → fillPorts(board)                  反序列化 ports_json
      → fillBoardProfile(board)           型号档案：面板样式 / 主控板 / 端口数兜底
                                          整机：按型号补 frontPanel
                                          状态：英文 → 中文 + 等级
      → readVlan / readTemplate           设备行上的 JSON 快照
      → records(id, 10)                   最近采集记录
```

---

## 2. 厂商接入模型

### 2.1 `OltVendorProfile` 有哪些能力

| 方法 | 用途 | 默认 |
| --- | --- | --- |
| `vendor()` / `matches(output)` | 身份与识别（按回显关键字认厂商） | 必须实现 |
| `pagingCommands()` / `versionCommands()` / `boardCommands()` | 关分页、取版本、取板卡的命令 | 必须实现 |
| `extractModel(text)` | 从回显里抠型号 | 必须实现 |
| `wholeDevice()` / `buildWholeDevice(model)` | 是不是一体化整机、整机「那块卡」长什么样 | `false` / `null` |
| `collectDetails(...)` | 厂商专属采集（模板、ONT、VLAN…） | 空 |
| `supportsVlanConfig()` / `createVlanCommands(...)` / `deleteVlanCommands(...)` / `vlanMemberPortTypes()` | VLAN 增删 | 不支持 |
| `supportsTr069Profile()` / `createTr069ProfileCommands(...)` / `parseTr069Profiles(...)` | TR-069 模板 | 不支持 |
| `supportsQuickSetup()` / `quickSetupCommands(...)` | 一键开局 | 不支持 |
| `actions()` | 这家设备能执行的命令动作目录 | 空 |
| `slotCount(boardText, boards)` | 机框槽位数 | 解析槽位表 |

**识别只认回显里的厂商关键字**，不拿命令风格猜厂商——早年"有 `display` 回显就是华为"，把一批也用 `display` 的第三方设备全存成了 HUAWEI，页面一律出华为界面，命令全是 `Unknown command`。

### 2.2 注册表

`OltVendorProfiles`：

```
BRANDS = [Huawei, Zte, Fiberhome, HsgqGpon, Hsgq]   ← 顺序即识别优先级
GENERIC = GenericOltProfile                          ← 兜底
```

`OltVendorProfiles.of(vendor)` 按厂商取；`.detect(texts...)` 依次问每家 `matches`。

`HsgqGpon` 必须排在 `Hsgq` 前面：同一家的回显里若同时出现 `gpon` 与 `hsgq`，先认 GPON。

### 2.3 现有实现

| Profile | 厂商 | 特点 |
| --- | --- | --- |
| `HuaweiOltProfile` | HUAWEI | `display` 命令族，机框 + 板卡 |
| `ZteOltProfile` / `FiberhomeOltProfile` | ZTE / FIBERHOME | `show` 命令族，目前只有探测 |
| `HsgqOltProfile` | HSGQ | 鸿升光 EPON 一体化，整机架构，`show` 命令族 |
| `HsgqGponOltProfile` | HSGQ_GPON | 继承上一行，PON 口是 `gpon`，业务走 `service-port`；只覆写 `ponTypeName()` |
| `GenericOltProfile` | GENERIC | 只做通用探测 |

### 2.4 命令动作目录

`HsgqActionCatalog` / `HsgqGponActionCatalog` 是"这台设备能执行哪些命令"的清单（对应手册 13 章那一个个功能格），每个动作带：所属视图、参数、预览用的命令模板。

页面 `GET /{id}/actions` 拿到目录直接渲染，**不自己判断哪个厂商有哪些功能**；能不能下发由目录决定，不是页面按厂商猜。

---

## 3. 设备知识都在后端

这一条是本轮重构的核心原则：**型号有几口、端口什么类型、状态英文字串怎么翻、哪些值允许填，都属于设备知识，应由后端给出**。前端不再保存第二份，也不会出现"页面说能填、下发被设备拒"。

### 3.1 整机型号规格

`vendor/HsgqModelCatalog`：一体化 OLT 没有板卡，**型号直接决定前面板长什么样**。

```
HSGQ-XE08R   8×GPON  4×GE  4×XGE  NMS  CONSOLE
HSGQ-G16R    16×GPON 4×GE  2×XGE  NMS  CONSOLE
HSGQ-G16L    16×GPON 4×GE  2×XGE       CONSOLE
HSGQ-G08R    8×GPON  4×GE  4×XGE  NMS  CONSOLE
HSGQ-G08L    8×GPON  4×GE  2×XGE       CONSOLE
HSGQ-G04R    4×GPON  4×GE  4×XGE  NMS  CONSOLE USB
HSGQ-G04L    4×GPON  4×GE  2×XGE       CONSOLE
Neutral-G16 / G08 / G04                （刷机后的型号）
HSGQ-E16 / E08 / E04  16/8/4×EPON 4×GE CONSOLE
```

- `match(model)` 按型号找规格，找不到返回 `null`
- `fallback(ponType, ponCount)` 型号不认识时按"几个 PON 口"兜底，面板不至于空着
- `frontPanelOf(model)` 生成 `OltFrontPanel`；**只认 `HSGQ-` / `NEUTRAL-` 开头**，华为板卡型号一律返回 `null`

整机面板随板卡返回（`OltBoard.frontPanel`），页面据此画：左边型号，PON 口一排，右边 GE / XGE / NMS / CONSOLE。

### 3.2 板卡型号档案

`vendor/OltBoardCatalog`：

- `match(boardType)` → `OltBoardProfile`（面板样式 `pon`/`ge`/`xge`/`generic`、端口类型、端口数、是否主控板）
- `statusOf(status)` → `OltBoardStatusRule`（`Active_normal` → 「激活」+ `success`）

`display board 0` 只回板卡名和状态，端口数得靠档案补；状态英文到中文的翻译也在后端，前端只剩"等级 → 图标"这一步。

### 3.3 码表

`GET /api/olt/options`（`OltDeviceOptions`）一次性给出：厂商列表、字符集、默认端口、默认账号、板卡档案、视图中文名、ONU 操作、VLAN 类型中文、板卡类别中文、机框槽位兜底、温度告警阈值。

前端带缓存只发一次请求，各组件按需取。**加一个码表项：改 `OltDeviceOptions` + `OltService#deviceOptions()`，前端取用即可。**

---

## 4. 命令与校验同源

### 4.1 三件套

每个可下发的操作都是三个接口：

| | 接口 | 作用 |
| --- | --- | --- |
| 表单 | `GET /{id}/xxx/form` | 可选项、取值范围、默认值 |
| 预览 | `POST /{id}/xxx/preview` | **不下发**，把这次会执行的命令算出来 |
| 下发 | `POST /{id}/xxx` | 真正下发 |

**预览和下发走同一个方法**，页面看到的必然就是即将下发的那条序列。以前前端自己拼一份预览，命令一改两边就对不上（先建 VLAN 还是先改端口这种顺序问题尤其容易漏）。

### 4.2 已收口的四处

| 功能 | 命令方法 | 表单方法 |
| --- | --- | --- |
| 一键开局 | `profile.quickSetupCommands(request)` | `quickSetupForm` |
| 新建 VLAN | `vlanCommands(id, request)` | `vlanForm` |
| DBA 模板 | `dbaProfileCommand(request)` | `dbaForm` |
| TR-069 模板 | `profile.createTr069ProfileCommands(request)` | `tr069Form` |

共同点：**校验在命令方法里**（取值越界、必填项、批量上限、表达式写法），表单元数据走同一份常量，所以"页面提示"与"下发判定"不可能不一致。

### 4.3 加一个新下发能力时

1. 命令生成 + 校验写进**一个**方法（如 `xxxCommand(request)`），`IllegalArgumentException` 带中文原因
2. Service 暴露 `xxxForm(id)` 给出可选项与范围（复用同一个常量）
3. 暴露 `previewXxx(id, request)`，内部调第 1 步那个方法
4. Controller 三个 mapping；校验异常转 `Result.failure(e.getMessage())`

---

## 5. 采集与同步

| 接口 | Service 方法 | 行为 |
| --- | --- | --- |
| `POST /{id}/sync` | `sync` | 全量采集后写库 |
| `POST /{id}/quick-sync` | `quickSync` | 只采板卡立即返回；端口 / ONT / VLAN / 模板交给后台线程池补采 |
| `POST /{id}/refresh` | `refresh` | 直连采最新，写库后返回 |
| （内部） | `startDeepSync(id, operator)` | 异步补采，`deepSyncing` 去重；`detail` 用 `deepSyncing` 字段告诉页面"还在采"，页面据此轮询 |

采集链路：`OltTelnetService.collectBoards` / `collectFull` → `OltCliSession` → `TelnetClient`。

- 登录后先发版本命令定厂商（`OltVendorProfiles.detect`），再决定后续发 `display` 还是 `show`
- `TelnetClient` 处理 `--More--` 之类的分页提示续页
- 字符集按设备行上的 `charset`（AUTO / UTF-8 / GBK / GB18030）
- 每次下发/采集都写 `olt_sync_record`（成功失败、耗时、原始回显），排障先看「采集记录」页签

---

## 6. 接口清单（`/api/olt`）

### 设备

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/list?keyword=` | 台账列表 |
| GET | `/{id}` | 详情（板卡 / 端口 / VLAN / 模板 / 记录） |
| POST | `/save` | 新增或更新 |
| POST | `/delete` | 删除 |
| POST | `/test`、`/{id}/test` | 连通性测试 |
| POST | `/{id}/sync`、`/{id}/quick-sync`、`/{id}/refresh` | 三种同步 |
| POST | `/{id}/ont-query` | 查 ONT 详情 |
| GET | `/options` | 码表与阈值 |
| GET | `/web-accounts` | 允许访问的 Web 登录账号 |
| GET | `/{id}/records?limit=` | 采集记录 |

### 下发与预览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/{id}/actions` | 这台设备能执行的命令目录 |
| POST | `/{id}/exec` | 执行一个动作 |
| POST | `/{id}/actions/preview` | 动作命令预览 |
| GET | `/{id}/vlan/form` | 新建 VLAN 的约束 |
| POST | `/{id}/vlan/preview` | VLAN 命令预览 |
| POST / DELETE | `/{id}/vlan` | 新建 / 删除 VLAN |
| GET | `/{id}/dba-profile/form` | DBA 表单约束 |
| POST | `/{id}/dba-profile/preview` | DBA 命令预览 |
| POST / DELETE | `/{id}/dba-profile` | 新增 / 删除 DBA 模板 |
| GET | `/{id}/tr069/form`、`/{id}/tr069-profiles` | TR-069 约束与列表 |
| POST | `/{id}/tr069-profiles` | 新增 TR-069 模板 |
| GET | `/{id}/quick-setup/form` | 开局表单约束 |
| POST | `/{id}/quick-setup/preview` | 开局命令预览 |
| POST | `/{id}/quick-setup` | 一键开局 |

---

## 7. 已经踩过的坑

### 7.1 运行时字段不落库（整机面板空了）

`OltBoard.frontPanel` 是 `@TableField(exist = false)`，`olt_board` 表里没有这一列。采集时在内存里生成了，写库时被丢掉，**页面读库时它就是 null**，整机面板只剩一个空括号，GE / XGE / NMS / CONSOLE 全不见。

**规则：凡是 `exist = false` 的字段，读取路径必须能现算出来。** 现在 `fillBoardProfile` 里：

```java
if (board.getFrontPanel() == null) {
    board.setFrontPanel(HsgqModelCatalog.frontPanelOf(board.getBoardType()));
}
```

同理：`panelKind`、`controlBoard`、`statusText`、`statusLevel` 也都是每次读取时补的，别指望它们能从库里读出来。

### 7.2 接口返回包了一层 `{ code, data }`

`request` 拦截器返回的是 `{ code, message, data }`，业务数据在 `.data` 里。前端写成 `result.commands` 会拿到 `undefined`——表现为"勾了端口填了 VLAN，点下发却提示没有可下发的命令"。三个预览接口都犯过这个错。

### 7.3 会被反序列化的模型不要用 record

`OltFrontPanel` 最初写成 record，但它要跟着板卡 JSON 读回来，record 反序列化依赖编译期参数名，容易读不出来。凡是会进 JSON 缓存的模型（`OltBoard` 内部嵌套的那些）一律用 `@Data` 类；record 只用于一次性的出参入参。

### 7.4 VLAN 表达式

统一走 `VlanIdExpression`（`41,45-46` ↔ `[41,45,46]`），不允许在别处手写 `split(',')` + `indexOf('-')`。设备侧才是最终裁决，本地只做"提前提醒"。

### 7.5 只起一个实例

本地跑 jar 时容易同时起两个（端口冲突或数据对不上）。打包前先确认：

```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*pilotacs-server.jar*' }
```

---

## 8. 扩展指南

### 新增一个厂商品牌

1. `vendor/` 下写一个类继承 `AbstractOltVendorProfile`（照 `HsgqOltProfile` 抄），实现 `vendor()` / `matches()` / `pagingCommands()` / `versionCommands()` / `boardCommands()` / `extractModel()`
2. `telnet/OltVendor` 枚举加一个取值（库里和页面下拉存的是这个 code）
3. `OltVendorProfiles.BRANDS` 加一行，注意识别优先级
4. 会话主流程、`OltInfoParser` 都不用动

### 新增一个整机型号

`HsgqModelCatalog.SPECS` 加一行 `new Spec(型号, 标签, "GPON"/"EPON", PON 口数, GE 数, XGE 数, 有 NMS?, 有 CONSOLE?, 有 USB?)`。建议同时在 `HsgqModelCatalogTest` 里补一条断言。

### 新增一条命令动作

`HsgqActionCatalog` / `HsgqGponActionCatalog` 里加一条：所属视图、参数定义、命令模板。页面自动出现在「设备配置」页签与动作预览里。

### 新增一个码表

`OltDeviceOptions` 加字段 → `OltService#deviceOptions()` 填值 → 前端从 `GET /api/olt/options` 取用。

---

## 9. 测试

```bash
mvn -o test                      # 全量
mvn -o test -Dtest=HsgqModelCatalogTest
```

测试分布在：

| 目录 | 覆盖 |
| --- | --- |
| `olt/telnet` | 会话编排、回显解析（板卡 / VLAN / 模板 / ONT / 业务流） |
| `olt/vendor` | 厂商识别、型号规格、命令动作目录、VLAN / TR-069 / 一键开局命令 |
| `olt/model` | `VlanIdExpression` 表达式解析 |
| `olt/service` | 动作渲染、定时刷新 |

改命令生成规则时，**对应命令序列的断言要一起改**——`HsgqQuickSetupTest`、`HsgqVlanProvisionTest`、`HsgqTr069ProfileTest` 里断言的是完整命令文本。

---

## 10. 构建与启动

```bash
# 后端编译 / 测试 / 打包
cd e:/tr069
mvn -o -DskipTests compile
mvn -o test
mvn -o -DskipTests package      # target/pilotacs-server.jar

# 前端（构建产物直接进 src/main/resources/static）
cd web
npm run build:prod

# 启动
cd e:\tr069\target
java -jar .\pilotacs-server.jar
```

前端改动后**必须重新 `npm run build:prod` 再打 jar**，静态资源是打进 jar 的。
