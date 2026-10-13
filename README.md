# javaacs

TR069 ACS（Java 版）—— 基于 CWMP/TR069 协议的光猫远程管理平台，Spring Boot 3 + Vue 2 实现。

- 部署步骤见 **[DEPLOYMENT.md](./DEPLOYMENT.md)**
- 前端工程见 [web/](./web)

> 当前处于**配置下发链路重构**阶段：模板已从「文件 + 步骤编排」改为**入库的意图模板**，
> 下发由「意图 + 型号映射」在运行时生成真实 CWMP 参数（详见第 3 节待实现）。

---

## 1. 已实现

### 1.1 后端

| 能力 | 说明 | 主要位置 |
| --- | --- | --- |
| CWMP 接入 | Inform / 空 POST 探活 / RPC 响应三条链路，digest 认证（可 `auto` 免认证），多端口 + 多路径兼容各厂商上报 | `acs/controller/CwmpController`、`acs/auth/CpeAuthenticator`、`common/web/CwmpMultiPortConfig` |
| 协议层 | SOAP 解析/封装、RPC 方法枚举、命名空间 | `cwmp/*` |
| 会话管理 | 会话状态机、待回应 RPC、超时与双会话容错 | `acs/session/*` |
| 设备管理 | 设备自动注册、在线状态、分页详情、在线会话明细 | `acs/service/DeviceRegistryService`、`api/controller/DeviceController` |
| 能力树采集 | GetParameterNames 拉路径 + GetParameterValues 分批读值，Inform 后自动采集、BOOTSTRAP 强制重采、NextLevel 兜底 | `acs/service/DeviceCapabilityService` |
| 参数树导出 | 按型号导出能力树到 `configuration/models/`，并可按设备定位模型文件 | `worklist/service/ModelTreeStore` |
| 业务快照 | WAN / WLAN / SIP 采集与解读，支持单台与整页批量读 | `acs/service/DeviceBusinessService` |
| 型号语义映射 | `cpe_model` + `model_param_mapping` / `model_object_mapping`：业务字段 ↔ 型号真实 CWMP 路径，支持按 SN 推荐候选路径 | `worklist/controller/ModelMappingController`、`ModelMappingService` |
| 光猫适配档案 | `configuration/profiles/*.yaml` 加载、匹配、WatchService 热加载 + 周期兜底扫描 | `worklist/loader`、`worklist/registry`、`WorklistConfigService` |
| RPC 下发（异步） | 下发返回任务号，按任务号轮询结果；超时统一收尾 | `agent/RpcDispatchService`、`queue/RpcTaskQueue`、`api/controller/RpcTaskController` |
| 队列削峰 | 上报与下发走 Redis 队列，Redis 不可用时降级为进程内处理 | `queue/*` |
| **配置意图模板** | 入库 `config_template`，只描述开通意图（不下发任何数据）：7 类（WAN DHCP / WAN PPPoE / WAN 静态IP / WAN 桥接 / 2.4G WiFi / 5.8G WiFi / SIP），各类自带意图字段；支持 `/meta` 字段定义、列表、详情、保存、删除 | `template/*`（`ConfigTemplateService`、`ConfigTemplateController`） |
| 业务模板 | 多张意图模板编排成一个业务包：列表、详情、保存、删除、下发前自检 `/validate`、下发记录查询 | `biz/*`（`BizTemplateService`、`BizTemplateExecutionService`） |
| 工单记录 | 工单与步骤记录的查询（**只读**，执行引擎已移除，等新下发链路重建） | `worklist/controller/WorklistExecuteController` |
| 首页概览 | 在线/离线、品牌分布、模板统计（模板数改为统计意图模板） | `api/service/DashboardService` |
| 管理端鉴权 | JWT 登录、`sys_user` 查库、首次启动写入初始管理员、JWT 拦截器 | `api/controller/AuthController`、`config/AdminAuthInterceptor` |
| 启动自检 | 各业务表补建/初始化、可选只读表存在性校验 | `common/startup/*` |

### 1.2 前端（`web/`）

| 菜单 | 路径 | 状态 |
| --- | --- | --- |
| 首页概览 | `/dashboard` | 已实现 |
| 配置模板 | `/template/list` | 已实现（意图模板，按类型渲染意图字段；**高级模式/文件模板已彻底删除**） |
| 业务模板 | `/biz/list` | 已实现（编排 + 成员视图 + 自检，整包下发等待新执行器） |
| 设备管理 | `/device/list` | 已实现（列表/详情/业务信息/WAN 参数弹窗/在线明细 + **右下角批量工具条**） |
| 设备详情 | `/device-detail/:sn` | 已实现（能力树、参数树、RPC 调试） |
| 工单记录 | `/worklist/instances` | 已实现（只读记录查询） |
| 光猫适配 | `/worklist/profiles` | 已实现（档案列表、匹配、重载） |
| OLT 管理 | `/olt/management` | **界面已实现，全量为前端模拟数据** |

设备列表右下角工具条（`web/src/views/device/list.vue`，需先勾选设备）：

| 分组 | 已接通 | 占位（待后端） |
| --- | --- | --- |
| 模版关键字 + 批量下发模版 | 模板下拉已接意图模板 | 批量下发待新执行器 |
| 一键工单 ▲ | 工单记录跳转 | 批量开通/检修工单 |
| 更多… ▲ | 恢复出厂 / 深度恢复出厂 / 清零类 → `FactoryReset` | 超管密码、Telnet 开关、WAN 侧访问、一键 WiFi、禁用 SIP、删除 WAN 连接、解除上网限制、删除缓存、标记置换 |
| 高级… ▲ | 导出设备（CSV）、下发自定义 TR069 参数（弹窗 → `SetParameterValues`）、调试指令（弹窗 → 任意 RPC）、刷新状态 | Telnet 命令、去除 SSID 前缀、修复 SIP/桥接、导出/克隆配置文件、强制上报 |
| OLT融合管理 ▲ | OLT/ONU 管理（跳转 OLT 页） | OLT 拓扑 |
| 重启 | 批量 `Reboot`（带二次确认） | — |

---

## 2. 待实现 / 进行中

| # | 事项 | 当前状态 | 说明 |
| --- | --- | --- | --- |
| 1 | **意图模板下发执行器**（核心） | 未开始 | 读取模板意图字段 → 按 `model_param_mapping` / `model_object_mapping` 解析该型号的真实路径 → 生成 `SetParameterValues`（含 Enable / AddressingType / VLANID / NATEnabled / Name / X_ZTE-COM_PortBinding 等），先行 WAN 类，再扩 WiFi / SIP |
| 2 | 批量下发模版 | 前端占位 | 依赖 #1；需补：按型号逐个设备生成、批量任务、进度与结果回写、失败重试 |
| 3 | 业务模板整包下发 | 已删待重建 | 原文件模板整包执行入口已移除，待 #1 落地后按同样机制重建，并恢复 `biz_template_execution` 写入 |
| 4 | 一键工单（开通 / 检修） | 前端占位 | 需要新的批量工单模型与执行记录，当前仅有历史工单查询 |
| 5 | 设备批量运维指令后端 | 部分接通 | 见上表：超管密码、Telnet 开关与命令执行、WAN 侧访问、WiFi 开关、禁用 SIP、删除 WAN 连接、解除上网限制、SSID 前缀清理、配置文件导出/克隆、强制上报等需逐个按厂商/型号补齐参数路径（统一走型号映射，不写死路径） |
| 6 | OLT 融合管理真实数据 | 模拟数据 | `web/src/views/olt/management.vue` 全为前端 mock；需接 SNMP/Telnet 采集：OLT 资源、板卡、槽位、PON 口（Tx/Rx 光功率、MAC、VLAN、ONU 数），数据替换集中在页面顶部 `resources` / `olt` 两个对象 |
| 7 | 存量数据迁移 | 未开始 | 存量 `biz_template_item.template_key` 仍是旧文件 key，`BizTemplateService.parseId` 会判定为“配置模板不存在”，需迁移脚本改为 `config_template.id` |
| 8 | 意图字段扩展 | 进行中 | 现 WAN 类仅 IP 协议 / VLAN 使用与否 / 是否绑定端口；待补 WAN IP 获取方式、PPPoE 账号、SIP 服务器/账号、WiFi SSID/密码等意图（VLAN ID、具体端口仍不落库，由下发时决定） |
| 9 | 旧机制彻底清理 | 基本完成 | 旧文件模板 / 向导编译 / 工单执行引擎已删；`worklist` 包中仅保留档案、型号映射、能力树导出与工单查询，收尾时可再审视命名 |
| 10 | 其它 | 未开始 | 下发操作审计日志细化、权限角色细化、设备告警（光功率劣化、离线批量提醒）等 |

---

## 3. 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Spring Boot 3.2.5、Java 21、MyBatis-Plus 3.5.7、MySQL Connector/J、Spring Data Redis（Lettuce）、JJWT 0.12.6、Lombok、Jackson、SnakeYAML（光猫档案） |
| 前端 | Vue 2.6 + vue-router + Vuex + Element UI 2.13 + axios（vue-admin-template） |
| 存储 | MySQL 8 / MariaDB 10.6+，Redis 6+（可选，关闭则进程内处理） |

## 4. 端口

| 端口 | 用途 | 配置项 |
| --- | --- | --- |
| 8888 | 管理端页面与 `/api` | `server.port` |
| 9090 / 8082 | 光猫上报监听 | `acs.cwmp.listen-ports` |
| 9528 | 前端开发服务器（代理 `/dev-api` → `http://127.0.0.1:8888/api`） | `web/vue.config.js` |

## 5. 目录结构

```
javaacs/
├── README.md                 本文档
├── DEPLOYMENT.md             部署文档
├── pom.xml                   后端（Maven）
├── sql/schema.sql            主库建表脚本（设备、参数、会话、RPC、用户、工单等）
├── deploy/
│   └── config/application.yml.example   外置配置模板
├── src/main/java/com/tr069/acs/         后端源码
├── src/main/resources/
│   ├── application.yml                  默认配置
│   └── db/                              分模块建表脚本（启动时按需自动补建）
│       ├── biz-template-schema.sql      业务模板 biz_template / item / execution
│       ├── capability-schema.sql        能力树 cpe_parameter
│       ├── config-template-schema.sql   意图模板 config_template
│       └── model-mapping-schema.sql     cpe_model / model_param_mapping / model_object_mapping
└── web/                      前端工程（构建产物 dist/ 不入库）
```

外部配置目录（默认 `./configuration`，相对进程工作目录，见 `acs.worklist.root-path`）：

```
configuration/
├── profiles/    光猫适配档案 *.yaml（改文件热加载生效）
└── models/      型号能力树 {isp}/{型号标识}.xml（由能力树导出生成）
```

> 旧版的 `operators/` 工单模板目录已废弃，模板改为入库。

## 6. 后端包结构

`com.tr069.acs` 下按职责分包：

| 包 | 职责 |
| --- | --- |
| `acs` | CWMP 接入主体：`controller` 上报入口、`service` 注册/Inform/能力采集/业务快照、`session` 会话与待回应 RPC、`auth` digest 认证 |
| `cwmp` | 协议层：SOAP 解析与封装、RPC 方法枚举、命名空间、结构体模型 |
| `queue` | Redis 队列：上报队列与消费者、RPC 指令队列与任务登记表 |
| `agent` | RPC 下发与结果回填调度（异步任务号） |
| `template` | **配置意图模板**：实体、Mapper、Service、Controller（意图字段定义集中在 `ConfigTemplateService#FIELD_DEFS`） |
| `biz` | 业务模板：编排实体、成员视图、执行记录、下发前自检 |
| `worklist` | 光猫档案（`loader`/`registry`/`model`）、型号语义映射（`ModelMappingService`）、能力树导出（`ModelTreeStore`）、工单记录查询（`controller/WorklistExecuteController`） |
| `api` | 管理端接口：设备、能力、业务、RPC 任务、统计、登录及其 Service |
| `domain` / `mapper` | 主库实体与 Mapper |
| `common` | 统一响应 `Result`、分页、全局异常、启动自检、Web 配置（多端口、路径分发） |
| `config` | JWT 拦截器、跨域、MyBatis-Plus、SPA 路由回退 |

## 7. 前端结构

```
web/src/
├── api/            device / rpc / configTemplate / bizTemplate / dashboard / worklist / user
├── views/
│   ├── dashboard/  首页概览
│   ├── device/     设备列表 list.vue、设备详情 detail.vue
│   ├── worklist/   templates.vue（意图模板）、instances.vue（工单记录）、profiles.vue（光猫适配）
│   ├── biz/        业务模板 template.vue
│   ├── olt/        OLT/ONU 管理 management.vue（模拟数据）
│   └── login/ 404.vue
├── components/     DeviceBusinessPanel、DeviceIdentityCell 等
└── router/         菜单与路由
```

## 8. 本地开发

```bash
# 1) 建库并建表
mysql -u root -p -e "CREATE DATABASE tr_acs DEFAULT CHARACTER SET utf8mb4;"
mysql -u root -p tr_acs < sql/schema.sql
# 其余分模块表（业务模板 / 能力树 / 意图模板 / 型号映射）首次启动自动补建

# 2) 启动后端（8888），按需改 src/main/resources/application.yml
mvn spring-boot:run

# 3) 启动前端（9528，已配置代理转发到 8888 的 /api）
cd web
npm install --legacy-peer-deps
npm run dev
```

浏览器打开 `http://127.0.0.1:9528`，首次启动会创建初始管理员 **admin / admin**（仅当 `sys_user` 为空），登录后请立即改密码。

构建校验：

```bash
mvn -q compile                 # 后端
cd web && npx vue-cli-service lint --no-fix    # 前端 lint
```

光猫接入、打包上线、故障排查见 [DEPLOYMENT.md](./DEPLOYMENT.md)。

## 9. 关键配置速查

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `acs.cwmp.listen-ports` | `9090,8082` | 光猫上报端口，逗号分隔 |
| `acs.cwmp.cpe-path` | `/ACS-server/ACS` | 上报主路径 |
| `acs.cwmp.extra-cpe-paths` | `/acs,/cwmp,/tr069,/` 等 | 兼容的上报路径，遇到 405 时在此追加 |
| `acs.auth.cpe-to-acs-username/password` | `auto` | 光猫连 ACS 的账号；`auto`/留空即免认证，上线前改回真实账号 |
| `acs.capability.enabled` / `collect-on-inform` | `true` | 能力树采集总开关与 Inform 后自动采集 |
| `acs.config-template.auto-create-table` | `true` | 启动时补建 `config_template` |
| `acs.worklist.root-path` | `./configuration` | 外部配置根目录（`profiles/`、`models/`），改文件热加载 |
| `acs.queue.enabled` | `true` | 关掉则退回进程内处理 |
| `acs.agent.rpc-timeout` | `510` | 单条 RPC 等待设备回应的超时（秒） |
| `admin.jwt.secret` | 默认占位值 | 生产环境务必替换 |
