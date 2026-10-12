# javaacs

TR069 ACS（Java 版）—— 基于 CWMP/TR069 协议的光猫远程管理平台，功能对齐仓库根目录的 Python 版 ACS，用 Spring Boot 重写。

- 部署步骤见 **[DEPLOYMENT.md](./DEPLOYMENT.md)**
- 前端模板说明见 [server/web/README-zh.md](./server/web/README-zh.md)
- 仓库根目录的 `README.md` 是 Python 老版本的说明，仅作历史参考

---

## 1. 能做什么

| 能力 | 说明 |
| --- | --- |
| CWMP 接入 | Inform / 空 POST 探活 / RPC 响应三条链路，反向轮询模型，digest 认证，多端口监听，兼容各厂商上报路径 |
| 设备管理 | 设备自动注册、参数入库、在线状态、设备详情、远程指令下发 |
| 工单引擎 | 模板驱动的步骤编排，支持 Get/Set/Add/Del 等 RPC，执行过程与结果全程留痕 |
| 配置模板 | 1820+ 存量模板按业务类型分类浏览与搜索，支持新建、编辑、复制另存、删除，写回文件热加载生效 |
| 业务模板 | 把多个配置模板组合成一个业务包，对设备一键整包下发，并记录每次执行情况 |
| 首页概览 | 品牌分布、在线 / 离线、已下发 / 未下发等统计 |
| 上报削峰 | 上报与下发指令走 Redis 队列，设备高峰不丢消息，Redis 不可用时自动降级为进程内处理 |
| 管理端 | JWT 登录、角色区分、统一响应封装 |

## 2. 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Spring Boot 3.2.5、Java 21、MyBatis-Plus 3.5.7、MySQL Connector/J、Spring Data Redis（Lettuce）、JJWT 0.12.6、Lombok |
| 前端 | Vue 2.6 + vue-router + Vuex + Element UI 2.13 + axios（vue-admin-template） |
| 存储 | MySQL 8 / MariaDB 10.6+，Redis 6+（可选） |

## 3. 端口

| 端口 | 用途 | 配置项 |
| --- | --- | --- |
| 8888 | 管理端页面与 `/api` | `server.port` |
| 9090 / 8082 | 光猫上报 | `acs.cwmp.listen-ports`（按需增删） |

## 4. 目录结构

```
javaacs/
├── README.md                 本文档
├── DEPLOYMENT.md             部署文档
└── server/
    ├── pom.xml
    ├── sql/schema.sql                  主库建表脚本
    ├── deploy/config/application.yml.example   外置配置模板
    ├── configuration/                  工单模板与光猫适配（jar 外，热加载）
    │   ├── operators/                  工单模板 {isp}/{version}/{domain}/{deviceType}/*.xml
    │   └── profiles/                   光猫适配档案 *.yaml
    └── web/                            前端工程（构建产物 dist/ 不入库）
```

## 5. 后端包结构

`com.tr069.acs` 下按职责分包：

| 包 | 职责 |
| --- | --- |
| `acs` | CWMP 接入主体：`controller` 上报入口、`service` 注册与 Inform 处理、`session` 会话与待回应 RPC、`auth` digest 认证 |
| `cwmp` | 协议层：SOAP 解析与封装、RPC 方法枚举、命名空间 |
| `worklist` | 工单：模板加载与热加载（`loader`）、执行引擎（`engine`）、模板/档案注册表（`registry`）、配置服务与接口 |
| `biz` | 业务模板：实体、Mapper、服务与接口 |
| `api` | 管理端接口：设备、登录、统计等 Controller 与 Service |
| `agent` | RPC 下发与结果回填的调度 |
| `queue` | Redis 队列：上报队列、指令队列、消费者、任务登记表 |
| `common` | 通用件：统一响应 `Result`、分页、全局异常处理、启动自检（建表/初始管理员）、Web 配置 |
| `config` | Web 配置：JWT 拦截器、跨域、MyBatis-Plus、SPA 路由回退 |
| `domain` / `mapper` | 实体与 Mapper |

## 6. 前端页面

| 菜单 | 路径 | 说明 |
| --- | --- | --- |
| 首页概览 | `/dashboard` | 品牌、在线/离线、下发统计 |
| 配置模板 | `/template/list` | 分类浏览、搜索、增删改与复制另存 |
| 业务模板 | `/biz/list` | 组合配置模板、整包下发 |
| 设备管理 | `/device/list` | 设备列表与详情（详情不在侧边栏，由列表跳入） |
| 工单记录 | `/worklist/instances` | 工单执行记录 |
| 光猫适配 | `/worklist/profiles` | 光猫适配档案 |

## 7. 本地开发

```bash
# 1) 建库并建表
mysql -u root -p -e "CREATE DATABASE tr_acs DEFAULT CHARACTER SET utf8mb4;"
mysql -u root -p tr_acs < server/sql/schema.sql

# 2) 启动后端（8888），按需改 server/src/main/resources/application.yml
cd server
mvn spring-boot:run

# 3) 启动前端（9528，已配置代理转发到 8888 的 /api）
cd server/web
npm install --legacy-peer-deps
npm run dev
```

浏览器打开 `http://127.0.0.1:9528`，首次启动会创建初始管理员 **admin / admin**（仅当 `sys_user` 为空），登录后请立即改密码。

光猫接入、打包上线、故障排查见 [DEPLOYMENT.md](./DEPLOYMENT.md)。

## 8. 关键配置速查

完整配置表见 [DEPLOYMENT.md 第 5 节](./DEPLOYMENT.md#5-配置参考)，常用的几个：

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `acs.cwmp.listen-ports` | `9090,8082` | 光猫上报端口，逗号分隔 |
| `acs.cwmp.cpe-path` | `/ACS-server/ACS` | 上报主路径 |
| `acs.cwmp.extra-cpe-paths` | `/acs,/cwmp,/tr069,/` 等 | 兼容的上报路径，遇到 405 时在此追加 |
| `acs.auth.cpe-to-acs-username/password` | `hgw/hgw` | 光猫连 ACS 的账号；写 `auto` 或留空即免认证，任何账号密码都放行 |
| `acs.worklist.root-path` | `./configuration` | 工单模板根目录，按进程工作目录解析，改文件热加载 |
| `acs.queue.enabled` | `true` | 关掉则退回进程内处理 |


