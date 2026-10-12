# javaacs 部署文档

TR069 ACS（Java 版）的构建、部署与验收说明。目标是让一台干净机器按本文步骤走完就能跑起来并接上光猫。

---

## 1. 部署概览

一个 Spring Boot fat-jar 搞定全部，前端页面打进 jar 里由后端托管，不依赖 Nginx。

| 项 | 说明 |
| --- | --- |
| 管理端 | `http://<ip>:8888`（`server.port`，页面在 jar 内 `BOOT-INF/classes/static`） |
| 管理端 API | 同源 `/api/**` |
| CPE 上报 | `http://<ip>:9090`、`:8082`（`acs.cwmp.listen-ports`，按需增删） |
| 数据库 | MySQL 8 / MariaDB 10.6+，库名 `tr_acs` |
| 缓存/队列 | Redis 6+（用于上报削峰与指令队列；无 Redis 可关闭队列降级运行） |
| 工单模板 | jar 外目录 `configuration/`，改文件热加载生效，无需重启 |

---

## 2. 环境要求

| 组件 | 版本 | 备注 |
| --- | --- | --- |
| JDK | **21** | `pom.xml` 里 `java.version=21`，低版本编译不过 |
| Maven | 3.9+ | 构建后端 |
| Node.js | 18 | 仅构建前端需要；Node 17+ 因 OpenSSL 3 需加 `--openssl-legacy-provider` |
| MySQL / MariaDB | 8 / 10.6+ | 字符集 `utf8mb4` |
| Redis | 6+ | 可选，见第 5 节 |

---

## 3. 目录说明

```
javaacs/
└── server/
    ├── pom.xml
    ├── sql/schema.sql                 # 主库建表脚本（部署必需，已入库）
    ├── deploy/config/application.yml.example   # 外置配置模板
    ├── configuration/                 # 工单模板与光猫适配（jar 外，热加载）
    │   ├── operators/                 # 工单模板 {isp}/{version}/{domain}/{deviceType}/*.xml
    │   └── profiles/                  # 光猫适配档案 *.yaml
    ├── src/main/resources/db/biz-template-schema.sql  # 业务模板表，启动时自动建立
    └── web/                           # 前端（vue-admin-template），构建产物 dist/ 不入库
```

> `configuration/` 刻意不打进 jar：现场新增光猫只需改这个目录的文件，热加载即时生效（文件变更后 800ms 去抖，另有 60 秒轮询兜底）。

---

## 4. 首次部署

### 4.1 建库

```sql
CREATE DATABASE tr_acs DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

### 4.2 执行建表脚本

```bash
mysql -u root -p tr_acs < server/sql/schema.sql
```

脚本是 `CREATE TABLE IF NOT EXISTS`，重复执行无害。业务模板的三张表（`biz_template`、`biz_template_item`、`biz_template_execution`）**不用手工执行**，应用启动时会自行补建（开关 `acs.biz.auto-create-table`，默认 true）。

启动时还有一道表结构自检：核心表缺失会打印明确提示，想让它直接中止启动就设 `acs.database.fail-on-missing-table=true`。

### 4.3 准备外置配置

在 **jar 同级目录**放 `config/application.yml`，只写要覆盖的键（Spring Boot 原生优先级高于 jar 内的同名配置，不用重新打包）：

```bash
mkdir -p /opt/javaacs/config
cp server/deploy/config/application.yml.example /opt/javaacs/config/application.yml
```

最小必改项：

```yaml
spring:
  datasource:
    url: jdbc:mysql://<db-ip>:3306/tr_acs?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true&nullCatalogMeansCurrent=true
    username: root
    password: <改成现场口令>

acs:
  public-host: <ACS 的 IP>        # 下发给光猫的连接请求地址，必须填真实地址
  cwmp:
    listen-ports: 9090,8082       # 光猫上报端口，多填几个不影响
```

也可以完全不建文件，用环境变量覆盖（Spring Boot 原生名称映射）：

```bash
export SPRING_DATASOURCE_URL='jdbc:mysql://...'
export SPRING_DATASOURCE_PASSWORD='xxx'
export ACS_CWMP_LISTEN_PORTS='9090,8082'
```

> `config/application.yml` 含数据库口令，已在 `.gitignore` 中排除，切勿提交。

### 4.4 构建

后端（跳过测试）：

```bash
cd javaacs/server
mvn clean package -DskipTests
```

前端必须单独构建 —— `dist/` 不在版本库里，不构建的话打出来的 jar 没有页面：

```bash
cd javaacs/server/web
npm install --legacy-peer-deps --no-audit --no-fund
npm run build:prod          # Windows 先执行: set NODE_OPTIONS=--openssl-legacy-provider
```

也可以让 Maven 一条命令连前端一起构建（Linux/macOS 加 `-Dnpm.executable=npm`）：

```bash
mvn clean package -Pweb -DskipTests
```

产物：`server/target/javaacs-server.jar`。

### 4.5 部署目录

```bash
mkdir -p /opt/javaacs/{config,logs}
cp server/target/javaacs-server.jar            /opt/javaacs/
cp -r server/configuration                     /opt/javaacs/configuration   # 工单模板
cp server/deploy/config/application.yml.example /opt/javaacs/config/application.yml
```

最终布局：

```
/opt/javaacs/
├── javaacs-server.jar
├── config/application.yml
├── configuration/          # 工单模板，热加载目录
└── logs/
```

模板根目录按**进程工作目录**解析，jar 内默认已是 `./configuration`，在 `/opt/javaacs` 下启动就正好读到上面那个目录，无需额外配置。

只有在别的工作目录启动时，才需要显式指定：

```yaml
acs:
  worklist:
    root-path: /opt/javaacs/configuration
```

> 目录为空时会依次回退到 jar 同级的 `configuration/`、`target/configuration/`，并自动释放示例配置。

### 4.6 启动

前台（首次建议，便于看日志）：

```bash
cd /opt/javaacs
java -jar javaacs-server.jar
```

后台：

```bash
nohup java -jar javaacs-server.jar > logs/stdout.log 2>&1 &
```

Windows：

```bat
cd /d D:\javaacs
java -jar javaacs-server.jar
```

systemd（可选）：

```ini
[Unit]
Description=javaacs ACS Server
After=network.target mysql.service redis.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/javaacs
ExecStart=/usr/bin/java -jar /opt/javaacs/javaacs-server.jar
Restart=always
RestartSec=10
StandardOutput=append:/opt/javaacs/logs/stdout.log
StandardError=append:/opt/javaacs/logs/stdout.log

[Install]
WantedBy=multi-user.target
```

防火墙放行 `8888`（管理）、`9090`/`8082`（光猫上报）。

---

## 5. 配置参考

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `8888` | 管理端与前端端口 |
| `spring.datasource.*` | 本机 `tr_acs` | 数据库连接；连接串字符集写 `UTF-8`，写 `utf8mb4` 会抛异常 |
| `spring.data.redis.*` | `127.0.0.1:6379` | 队列存储 |
| `acs.public-host` | `127.0.0.1` | **生产必须改**，ACS 通告给光猫的地址 |
| `acs.cwmp.listen-ports` | `9090,8082` | CPE 上报端口，逗号分隔，与主端口重复会自动跳过 |
| `acs.cwmp.cpe-path` | `/ACS-server/ACS` | CPE 上报主路径 |
| `acs.cwmp.extra-cpe-paths` | `/acs,/ACS,/cwmp,/CWMP,/tr069,/TR069,/CPE,/cpe,/` | 兼容的上报路径，现场遇到新写法在此追加 |
| `acs.auth.cpe-to-acs-username/password` | `hgw/hgw` | 光猫连 ACS 的 digest 账号；写 `auto` 或留空即免认证，任何账号密码都放行 |
| `acs.auth.acs-to-cpe-username/password` | `itms/itms` | ACS 反向连光猫的账号，同样支持 `auto` / 留空 |
| `acs.worklist.root-path` | `./configuration` | 工单模板根目录，按进程工作目录解析；目录为空时回退到 jar 同级 `configuration/` |
| `acs.queue.enabled` | `true` | 关掉则退回进程内同步处理，无 Redis 环境下的降级选项 |
| `acs.biz.auto-create-table` | `true` | 业务模板表自动补建 |
| `acs.database.fail-on-missing-table` | `false` | 表缺失时是否中止启动 |
| `admin.jwt.secret` | 内置占位值 | **生产必须换成随机值** |
| `admin.default-user/password` | `admin/admin` | 仅当 `sys_user` 表为空时创建初始管理员 |

---

## 6. 验收清单

启动日志里应依次看到：

1. `Started JavaAcsApplication in X seconds`
2. `CPE 上报路径：主路径 /ACS-server/ACS，兼容 [...]` —— 路径兼容清单已生效
3. `CPE 上报监听端口：[8888, 9090, 8082]（主端口 8888，额外 [9090, 8082]）`
4. 无 `数据库自检失败` / 无缺表提示
5. 首次部署会有一条告警：`sys_user 为空，已创建初始管理员 [admin]，请登录后立即修改密码`

然后：

```bash
# 管理端页面
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8888/          # 200

# 上报端口：空 POST 应不再是 405
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://127.0.0.1:9090/  # 400 或 204，不能是 405
```

浏览器打开 `http://<ip>:8888`，用 `admin/admin` 登录（首次登录后立即改密码）。

光猫上报验收见下一节。

---

## 7. 光猫接入

光猫侧填写 ACS URL（以 9090 为例）：

```
http://<ACS IP>:9090/
```

ACS 认证账号填 `hgw/hgw`（或现场实际值，对应 `acs.auth.cpe-to-acs-*`）。

正常交互流程：

1. 光猫发第一条 Inform（无认证头）→ ACS 回 **401 + `WWW-Authenticate`**，日志打 `等待设备携带认证重发 Inform: sn=xxx`
2. 光猫带 digest 重发 → 校验通过回 200 + InformResponse，设备写入 `cpe_device`
3. 之后光猫周期性发空 POST 探活，有指令时 ACS 捎带下发

---

## 8. 常见问题排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 光猫一直上报，ACS 收不到，日志 `Request method 'POST' is not supported` / 响应 `Allow: GET, HEAD` | 光猫写的上报路径不在清单里，请求被静态资源处理器吃掉（405） | 日志会打 `收到未匹配的上报请求：端口 xxx 路径 /yyy`，把 `/yyy` 加进 `acs.cwmp.extra-cpe-paths` 重启 |
| 一直 `401`，日志 `设备认证失败: sn=xxx` | 光猫 ACS 账号与 `acs.auth.cpe-to-acs-*` 不一致 | 改光猫侧账号为 `hgw/hgw`，或改配置匹配光猫；注意光猫的业务账号（LOID）与 ACS 认证账号是两套 |
| 启动报缺表 | 漏执行 `sql/schema.sql` | 执行脚本后重启 |
| 页面 404 / 白屏 | 前端没构建，jar 里没有 `static` | 先 `npm run build:prod` 或 `mvn -Pweb`，再 `mvn package` |
| 模板列表为空 | 启动目录下没有 `configuration/`，且回退路径也没有 | 把 `server/configuration` 拷到工作目录，或显式配置 `acs.worklist.root-path` |
| 连接串报 `Unsupported character encoding` | `characterEncoding` 写了 `utf8mb4` | 改成 `UTF-8` |
| 启动即报数据库连接失败 | 库没建 / 账号口令不对 / 网络不通 | 先确认手工能 `mysql -h ... -u ... -p tr_acs` |
| Redis 连不上 | 未部署 Redis | 装 Redis，或设 `acs.queue.enabled=false` 降级（入队失败会自动就地处理） |

---

## 9. 升级与备份

升级：备份配置与模板 → 停进程 → 替换 jar → 启动。

```bash
cp -r /opt/javaacs/config /opt/javaacs/config.bak
cp -r /opt/javaacs/configuration /opt/javaacs/configuration.bak
# 停进程后替换 jar，再启动
```

- 配置文件和模板目录都在 jar 外，替换 jar 不会覆盖它们
- 业务模板表由应用自动补建，升级无需手工执行 SQL
- `sql/schema.sql` 新增表时需手工补执行（脚本幂等，可反复跑）
- 数据库定期备份：`mysqldump -u root -p tr_acs > tr_acs_$(date +%F).sql`

---

## 10. 上线前必改

- [ ] `acs.public-host` 改成真实 IP，否则下发给光猫的连接请求地址是 `127.0.0.1`
- [ ] `admin.jwt.secret` 换成随机值
- [ ] 数据库口令不留在仓库（外置 `config/application.yml` 已被 git 忽略）
- [ ] 登录后修改初始管理员口令
- [ ] 启动目录下已放好 `configuration/`（模板与光猫适配）
- [ ] 防火墙放行 8888 / 9090 / 8082
