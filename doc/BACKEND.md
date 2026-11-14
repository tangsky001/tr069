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
| `service` | `OltService`（编排、校验、落库）、`OltTelnetService`（采集与下发）、`OltAutoRefreshService`（全量定时刷新）、`OltBoardRefreshService`（板卡状态 2 分钟刷新）、`OltOntRegService`（注册模板匹配 + `ont confirm` 下发 + 记录）、`OltOntAutoRegisterService`（自动扫描注册）、`OltQuickSetupConfigService`（开局参数）、`OltSessionPool`（Telnet 长连接池与保活） | `OltService` 是最大的类，改之前先看有没有现成私有方法 |
| `telnet` | `OltCliSession`（会话与命令编排）、`OltInfoParser`（回显解析）、`TelnetClient`（原始连接与续页）、`OltVendor`（厂商枚举） | 采集入口只有 `display` / `show` / `enable` / `config` / `quit`；写命令走 `OltVendorProfile` 的 `*Command` 方法 |
| `vendor` | `OltVendorProfile` 接口 + 各厂商实现 + 命令动作目录 | **新增品牌只动这个包** |
| `entity` | `OltDevice` / `OltBoard` / `OltSyncRecord` + `olt_ont_reg_template` / `olt_ont_reg_config` / `olt_ont_reg_record` | `@TableField(exist = false)` 的字段不落库，见 §7.1 |
| `model` | 对外出参入参、表单元数据（`*Form`）、快照（`OltVlanInfo`、`OltTemplateInfo`） | 会被序列化进 JSON 缓存的用 `@Data` 类，不用 record |

### 1.2 数据落在哪

| 数据 | 存放位置 | 说明 |
| --- | --- | --- |
| 设备台账、连接信息、型号、状态 | `olt_device` 行 | |
| VLAN 快照 | `olt_device.vlan_json` | 整体覆盖 |
| DBA / 线路模板快照 | `olt_device.template_json` | 整体覆盖 |
| 板卡（槽位、型号、状态、温度、端口 JSON） | `olt_board` 行 | 每次同步**先删后插**；写库前按 SN 回填 MAC / 管理 IP / 模板号，见 §7.6 |
| 采集记录 | `olt_sync_record` | 成功失败、耗时、原始回显 |
| ONT 注册模板 | `olt_ont_reg_template` | 按优先级匹配；`olt_id` 为 NULL 表示所有设备通用 |
| 自动注册参数 | `olt_ont_reg_config` | 一台 OLT 一条：开关、扫描间隔、并发、自动移机、满表清离线 |
| 注册记录 | `olt_ont_reg_record` | 每台光猫一次注册：模板名快照、命令、结果、来源（AUTO / MANUAL）、操作人 |

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
| `addPortVlanCommands(...)` / `removePortVlanCommands(...)` | 上行口放通 / 解除 VLAN | 空 |
| `lineTcontAddCommands(...)` / `lineGemportAddCommands(...)` / `lineGemMappingAddCommands(...)` | 线路模板加 T-CONT / GEM Port / GEM 映射 | 空 |
| `supportsOntAutofind()` / `ontAutofindCommands()` | 采待注册 ONT（`display ont autofind all`） | 不支持 |
| `supportsOntConfirm()` / `ontConfirmCommand(port, authMode, authValue, lineProfileId, srvProfileId)` | `ont confirm` 把一条待注册 ONT 转正 | 不支持 |
| `supportsOntDescription()` / `ontDescriptionCommand(port, ontId, desc)` | 注册成功后把型号写进 ONT 描述 | 不支持 |
| `supportsOntAutoFind()` / `ontAutoFindQueryCommand(port)` / `parseOntAutoFindState(text)` / `ontAutoFindEnableCommands(fspList)` | PON 口自动发现查询与开启 | 不支持 |
| `supportsServicePorts()` / `servicePortCommand(...)` / `servicePortDeleteCommand(index)` | 注册后建业务虚口 / 删旧虚口 | 不支持 |
| `boardConfirmCommand()` | 确认待确认板卡（`board confirm 0`） | `null` |

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
| `POST /{id}/sync-section` | `syncSection` | 按页签只采 `boards` / `ont` / `vlan` / `template` 中的一段，`force` 可跳过新鲜度判断 |
| `POST /{id}/refresh` | `refresh` | 直连采最新，写库后返回 |
| `POST /{id}/boards/confirm` | `confirmBoards` | 下发 `board confirm 0` 再重采板卡 |
| （内部） | `startDeepSync(id, operator)` | 异步补采，`deepSyncing` 去重；`detail` 用 `deepSyncing` 字段告诉页面"还在采"，页面据此轮询 |

三个后台调度（周期写死在类里，不开放配置）：

| 类 | 周期 | 做什么 |
| --- | --- | --- |
| `OltAutoRefreshService` | 启动后 5 秒首轮，之后 30 小时一轮 | 全量采集；设备间并行（上限 20），连续失败退避最多 6 倍 |
| `OltBoardRefreshService` | 2 分钟 | 只发 `display board 0` 刷板卡状态，设备忙则跳过（人工操作优先） |
| `OltOntAutoRegisterService` | 按 `olt_ont_reg_config.scanSeconds` | 扫待注册 ONT 并按模板自动注册 |

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
| POST | `/{id}/sync`、`/{id}/quick-sync`、`/{id}/sync-section`、`/{id}/refresh` | 全量 / 只采板卡 / 按页签 / 实时刷新 |
| POST | `/{id}/boards/confirm` | 确认待确认板卡（`board confirm 0`） |
| POST | `/{id}/ont-query` | 查 ONT 详情（光信息 / 版本 / 注册历史） |
| POST | `/{id}/port-optics` | 端口光模块按需查询 |
| GET | `/options` | 码表与阈值 |
| GET | `/web-accounts` | 允许访问的 Web 登录账号 |
| GET | `/{id}/records?limit=` | 采集记录 |
| GET / POST | `/session-pool`、`/session-pool/keepalive` | 会话池状态 / 保活开关 |

### 下发与预览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/{id}/actions` | 这台设备能执行的命令目录 |
| POST | `/{id}/exec` | 执行一个动作 |
| POST | `/{id}/actions/preview`、`/{id}/actions/previews` | 单条 / 整页批量命令预览 |
| GET | `/{id}/vlan/form` | 新建 VLAN 的约束 |
| POST | `/{id}/vlan/preview` | VLAN 命令预览 |
| POST / DELETE | `/{id}/vlan` | 新建 / 删除 VLAN |
| POST / DELETE | `/{id}/vlan/port-vlan` | 上行口放通 / 解除 VLAN |
| GET | `/{id}/dba-profile/form` | DBA 表单约束 |
| POST | `/{id}/dba-profile/preview` | DBA 命令预览 |
| POST / DELETE | `/{id}/dba-profile` | 新增 / 删除 DBA 模板 |
| GET | `/{id}/line-profile/form` | 线路模板三个弹窗的约束与默认值 |
| POST | `/{id}/line-profile/preview` | 线路模板命令预览 |
| POST | `/{id}/line-profile/tcont`、`/gemport`、`/gem-mapping` | 加 T-CONT / GEM Port / GEM 映射 |
| GET | `/{id}/tr069/form`、`/{id}/tr069-profiles` | TR-069 约束与列表 |
| POST | `/{id}/tr069-profiles` | 新增 TR-069 模板 |
| GET / POST | `/{id}/quick-setup/form`、`/{id}/quick-setup/config` | 开局表单约束 / 已保存的开局参数 |
| POST | `/{id}/quick-setup/preview` | 开局命令预览 |
| POST | `/{id}/quick-setup` | 一键开局 |
| POST | `/{id}/terminal-user` | 在 OLT 上建本地用户 |
| GET | `/{id}/ont-auto-find` | 各 PON 口自动发现开关状态 |
| POST | `/{id}/ont-auto-find/enable` | 一键开启全部 PON 口自动发现 |
| POST | `/{id}/ont/delete-all` | 删除业务板上的全部 ONT（逐口 `ont delete <端口> all`） |

### ONT 注册

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/{id}/ont-autofind` | 待注册 ONT（`display ont autofind all`，实时） |
| GET / POST / DELETE | `/{id}/ont-reg/templates`、`/{id}/ont-reg/template`、`/{id}/ont-reg/template/default` | 模板列表 / 新增更新 / 删除 / 新建默认值 |
| GET / POST | `/{id}/ont-reg/config` | 自动注册参数 |
| POST | `/{id}/ont-reg/scan` | 立刻跑一轮自动注册 |
| GET | `/{id}/ont-reg/records` | 注册记录 |
| POST | `/{id}/ont-reg/preview`、`/{id}/ont-reg/register` | 注册命令预览 / 下发注册 |

注册链路：`ont-autofind` 采待注册 → `match(templates)` 按优先级匹配 → 命令块
`interface gpon 0/<槽位>` → `ont confirm <端口> <认证串> omci [ont-lineprofile-id] [ont-srvprofile-id]`
→（成功）`ont modify … desc "<型号>"` + `service-port …` → `quit`。
失败按回显关键字分支：`exist/duplicate/already` 走自动移机（需开 `autoMove`），
`exceed/full/no more` 走清离线重试（需开 `autoDeleteOffline`），其余原样记入 `message`。

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

### 7.5 配置回显被 `#` 分隔行截断

`SHELL_PROMPT` 最初写成 `(?m)[^\r\n]{0,80}[>#\]]\s*$`——多行 `$` 让**任意一行**的 `#` 都能算提示符。
`display current-configuration` 的输出里有大段 `#` 分段符，设备恰好在某块板卡行停顿时，
120ms 静默一到就判定「命令结束」，回显被截在 1563 字符，后面的 `ont add … ont-lineprofile-id`
全丢了，表现是模板号一台都补不上、库里仍缺几十台。

修法：提示符只匹配**回显最末尾**（`…\s*\z`），中途停顿交给分页看门狗推进。

```java
private static final Pattern SHELL_PROMPT = Pattern.compile("[^\\r\\n]{0,80}[>#\\]]\\s*\\z");
```

排障特征：采集记录里 `display current-configuration` 的回显长度明显偏短、结尾停在 `board add …`。

### 7.6 ONT 明细「只覆盖不删除」

整板表 `display ont info 0 <槽位> all` 不带 MAC 和模板号，点一次页签同步就会把补采到的值覆盖成空。
现在统一在 `writeBoards` 的端口合并里回填（`mergeUnsyncedPortData` → `mergeOntDetails`）：

- 按 SN 对齐，SN 变了按槽位 + 端口 + ONT ID 兜底；
- 新值为空（`null`）保留库里的旧值，采到新值才覆盖；
- 设备回 `-` / `0000-0000-0000` 视为没采到，`macUnavailable` / `profileUnavailable` 标记随之沿用；
- 全量采集（`mergeOld=false`）那条路径另行 `seedOntDetails` 兜底，因为它是整轮覆盖写。

### 7.7 只起一个实例

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

**仓库当前不带单元测试**（`src/test` 为空），改动靠真机回显验证：

```bash
mvn -o -DskipTests compile        # 编译
mvn -o -DskipTests package        # 打包
```

验证方式：

| 改动类型 | 怎么验 |
| --- | --- |
| 命令生成（VLAN / DBA / 线路模板 / TR-069 / 注册） | 先调 `*/preview` 看命令文本，再在真机上执行一次，对照采集记录里的回显 |
| 回显解析（板卡 / ONT / VLAN / 模板 / 业务流） | 采集记录页签拿 `rawPreview`，或临时把 `acs.olt.log-echo` 改成 `true` 看完整回显 |
| 定时任务（全量 / 板卡 / 自动注册） | 启动日志里有一行「已就绪：N 分钟一轮」，再等一轮看采集记录的操作人字段 |

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
