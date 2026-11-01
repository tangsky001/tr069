# PilotACS 一键部署

一条命令把 PilotACS（页面 + 接口 + 光猫上报端口 + 北向接口）连同 MySQL、Redis 一起跑起来。

支持：**Ubuntu / Debian / CentOS / RHEL / Rocky / AlmaLinux / Fedora / Windows / macOS**

## 1. 选择部署方式

### Windows

双击 `scripts/deploy.bat`，或在 PowerShell 里：

```powershell
.\scripts\deploy.ps1
```

### Linux / macOS

```bash
chmod +x scripts/deploy.sh
./scripts/deploy.sh
```

会看到菜单：

```
  ==================  PilotACS 一键部署  ==================

    [1] Docker 部署        容器化启动 app + MySQL + Redis
    [2] 自动打包部署        安装依赖 → 编译前后端 → 初始化库 → 启动

    -------------------  运维  -------------------
    [3] 停止服务
    [4] 查看状态
    [5] 跟踪日志
    [6] 卸载

    [0] 退出
```

也可以跳过菜单直接指定：

```bash
./scripts/deploy.sh docker     # Docker 部署
./scripts/deploy.sh native     # 自动打包部署
```

| 方式 | 适合场景 | 需要本机装 |
| --- | --- | --- |
| **Docker 部署** | 快速试用、环境隔离、生产推荐 | 只要 Docker |
| **自动打包部署** | 没有 Docker、要直接跑 jar、要接已有 MySQL/Redis | 脚本自动装（JDK 21 / Maven / Node / MySQL / Redis） |

选择会记在 `scripts/.deploy-mode`，之后 `stop` / `status` / `logs` 会按对应方式执行。

## 2. 方式一：Docker 部署

### 前置条件

只需要 Docker（含 compose 插件）：

| 系统 | 安装方式 |
| --- | --- |
| Windows / macOS | [Docker Desktop](https://www.docker.com/products/docker-desktop/) |
| Linux | `curl -fsSL https://get.docker.com \| sh`，Ubuntu/Debian 再装 `docker-compose-plugin` |

验证：

```bash
docker --version
docker compose version
```

没装的话脚本会提示，并可以自动用官方脚本安装（Linux）。

### 服务与端口

| 服务 | 容器名 | 说明 |
| --- | --- | --- |
| `app` | `pilotacs-app` | 应用本体（页面 + 接口） |
| `mysql` | `pilotacs-mysql` | MySQL 8.0，首次启动自动执行 `sql/schema.sql`、`sql/schema-tr069tpl.sql` |
| `redis` | `pilotacs-redis` | Redis 7，开启 AOF |

### 数据卷

| 卷 | 内容 |
| --- | --- |
| `mysql-data` | 数据库文件 |
| `redis-data` | Redis 持久化数据 |
| `app-logs` | 应用日志（容器内 `/app/log`） |
| `app-config` | 外部化配置目录（容器内 `/app/config`） |

`docker compose down` 不删卷，数据保留；`down -v`（脚本的 `uninstall`）才会删。

## 3. 方式二：自动打包部署

脚本会**自动识别系统**并安装缺失的依赖，然后编译、建库、启动。

### 系统识别

| 系统 | 包管理器 | 识别依据 |
| --- | --- | --- |
| Ubuntu / Debian | `apt-get` | `/etc/os-release` 的 `ID` |
| CentOS 7 | `yum` | 同上，主版本 < 8 |
| CentOS 8+ / RHEL / Rocky / AlmaLinux | `dnf` | 同上，主版本 ≥ 8 |
| Fedora | `dnf` | 同上 |
| macOS | `brew` | `uname -s = Darwin` |
| Windows | `winget` / `choco` | 依次探测 |

### 会自动安装什么

| 依赖 | 版本要求 | deb | rpm | brew | win |
| --- | --- | --- | --- | --- | --- |
| JDK | ≥ 21 | `openjdk-21-jdk` | `java-21-openjdk-devel` | `openjdk@21` | `Microsoft.OpenJDK.21` |
| Maven | 任意 | `maven` | `maven` | `maven` | `Apache.Maven` |
| Node | ≥ 16 | NodeSource 18.x | NodeSource 18.x | `node@18` | `OpenJS.NodeJS.LTS` |
| MySQL | 8.x | `mysql-server` | `mysql-server`（失败回落 `mariadb-server`） | `mysql` | `Oracle.MySQL` |
| Redis | 任意 | `redis-server` | `redis` | `redis` | `Memurai`（Redis 兼容） |

已经装好且版本达标的会直接跳过，不会重复安装。

### 执行流程

```
第 1/4 步  检查并安装依赖（JDK 21 / Maven / Node / MySQL / Redis）
第 2/4 步  初始化数据库（建库 tr_acs → 导入 schema.sql、schema-tr069tpl.sql）
第 3/4 步  编译前端 web/ → src/main/resources/static
           编译后端      → target/pilotacs-server.jar
第 4/4 步  后台启动应用，等待端口就绪
```

说明：

- 建表脚本本身不建库，脚本会先 `CREATE DATABASE IF NOT EXISTS tr_acs`
- 数据库连不上时会提示输入 root 密码（默认取 `application.yml` 里的 `Qq123456`）
- 前端构建自动带 `NODE_OPTIONS=--openssl-legacy-provider`，兼容新版 Node
- 启动用 `nohup`（Linux/macOS）/ 后台进程（Windows），PID 记在 `scripts/pilotacs.pid`
- 日志落在仓库根的 `log/`（`ACS_LOG.LOG` 是核心事件，`stdout.log` 是完整输出）

### 只用编译不启动

```bash
./scripts/setup-native.sh build
```

## 4. 运维命令

| 操作 | Linux / macOS | Windows |
| --- | --- | --- |
| 交互式菜单 | `./scripts/deploy.sh` | `.\scripts\deploy.ps1` |
| Docker 部署 | `./scripts/deploy.sh docker` | `.\scripts\deploy.ps1 docker` |
| 自动打包部署 | `./scripts/deploy.sh native` | `.\scripts\deploy.ps1 native` |
| 停止 | `./scripts/deploy.sh stop` | `.\scripts\deploy.ps1 stop` |
| 重启 | `./scripts/deploy.sh restart` | `.\scripts\deploy.ps1 restart` |
| 状态 | `./scripts/deploy.sh status` | `.\scripts\deploy.ps1 status` |
| 日志 | `./scripts/deploy.sh logs` | `.\scripts\deploy.ps1 logs` |
| 卸载 | `./scripts/deploy.sh uninstall` | `.\scripts\deploy.ps1 uninstall` |

`uninstall`：Docker 方式会连数据卷一起删（台账、统计、授权记录全丢），需要输入 `yes`；自动打包方式只停进程并清理 `target/`，数据库数据保留。

## 5. 配置

### Docker 方式

改 `.env`（首次运行自动生成，`ACS_PUBLIC_HOST` 会自动填本机 IP）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `MYSQL_ROOT_PASSWORD` | `PilotACS@123` | 数据库 root 密码 |
| `MYSQL_DATABASE` | `tr_acs` | 库名 |
| `MYSQL_PORT` | `3306` | 宿主机映射端口 |
| `ACS_PUBLIC_HOST` | `127.0.0.1` | **光猫回连地址** |
| `APP_PORT` | `8888` | 管理端端口 |
| `OPENAPI_PORT` | `8899` | 北向接口端口 |
| `ACS_CWMP_LISTEN_PORTS` | `9090,8082,6060,8012,8080,8172,5481` | 光猫上报监听端口 |
| `JAVA_OPTS` | `-Xms512m -Xmx1024m` | JVM 参数 |

### 自动打包方式

用环境变量覆盖（不传就用 `application.yml` 的默认值）：

```bash
MYSQL_HOST=192.168.1.5 MYSQL_PASSWORD=yourpwd ACS_PUBLIC_HOST=192.168.1.10 \
  ./scripts/setup-native.sh
```

Windows：

```powershell
$env:ACS_PUBLIC_HOST = '192.168.1.10'
$env:MYSQL_PASSWORD = 'yourpwd'
.\scripts\setup-native.ps1
```

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `MYSQL_HOST` | `127.0.0.1` | 数据库地址 |
| `MYSQL_PORT` | `3306` | 数据库端口 |
| `MYSQL_DATABASE` | `tr_acs` | 库名 |
| `MYSQL_USER` | `root` | 账号 |
| `MYSQL_PASSWORD` | `Qq123456` | 密码 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `127.0.0.1` / `6379` / 空 | Redis 连接 |
| `ACS_PUBLIC_HOST` | 自动探测 | **光猫回连地址** |
| `JAVA_OPTS` | `-Xms512m -Xmx1024m` | JVM 参数 |

想改更细的配置，把 `deploy/config/application.yml.example` 复制成仓库根下的 `config/application.yml`，Spring Boot 会优先读它。

## 6. 端口映射

| 端口 | 用途 |
| --- | --- |
| 8888 | 管理端页面与 `/api` |
| 8899 | 北向接口 `/openapi/v1` |
| 9090 / 8082 / 6060 / 8012 / 8080 / 8172 / 5481 | 光猫 CWMP 上报监听 |

光猫端口由厂商出厂固化，一般不要改；宿主机上这些端口被占用时，要先腾出来。

## 7. 常见问题

**端口被占用**：`address already in use`。改 `.env` 里的 `APP_PORT` / `MYSQL_PORT`，或停掉占用进程。CWMP 端口不能随便改（光猫那边是固化的）。

**应用起不来、数据库连不上**：
- Docker：`docker compose logs app`
- 自动打包：`./scripts/deploy.sh logs`（看 `log/ACS_LOG.LOG`）
- MySQL 首次启动要执行建表脚本，脚本里有 90 秒等待窗口，慢机器可以再等一会

**JDK 21 装不上**：Ubuntu 20.04 及更低版本官方源没有 `openjdk-21-jdk`，脚本会提示加 PPA；CentOS 7 同理，需要从 <https://adoptium.net/> 下载。

**Windows 上 Redis 起不来**：Windows 没有官方 Redis，脚本会装 Memurai（Redis 兼容）并启动服务；装不了就手动确认 6379 端口有 Redis 在跑。

**光猫不上报**：确认 `ACS_PUBLIC_HOST` 是光猫能访问到的地址，且 CWMP 端口（如华为 9090）在防火墙里放行。

**改了代码要重新部署**：`./scripts/deploy.sh restart`（Docker 会 `--build`；自动打包会重新编译）。
