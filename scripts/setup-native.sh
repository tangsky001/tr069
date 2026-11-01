#!/usr/bin/env bash
#
# ==================== PilotACS 自动打包部署 ====================
#
# 支持：Ubuntu / Debian / CentOS / RHEL / Rocky / AlmaLinux / Fedora / macOS
#
# 流程：
#   1) 识别系统与包管理器
#   2) 安装依赖：JDK 21 / Maven / Node 16+ / MySQL / Redis
#   3) 编译前端 web/  -> src/main/resources/static
#   4) 编译后端       -> target/pilotacs-server.jar
#   5) 建库并导入 sql/schema.sql、sql/schema-tr069tpl.sql
#   6) 后台启动应用
#
# 用法：
#   ./scripts/setup-native.sh            安装依赖并启动
#   ./scripts/setup-native.sh start      同上
#   ./scripts/setup-native.sh build      只编译，不启动
#   ./scripts/setup-native.sh stop       停止
#   ./scripts/setup-native.sh status     状态
#   ./scripts/setup-native.sh logs       跟踪日志
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$ROOT_DIR/scripts"
PID_FILE="$SCRIPT_DIR/pilotacs.pid"
LOG_DIR="$ROOT_DIR/log"
STDOUT_LOG="$LOG_DIR/stdout.log"
JAR="$ROOT_DIR/target/pilotacs-server.jar"

cd "$ROOT_DIR"

C_CYAN=$'\033[36m'; C_GREEN=$'\033[32m'
C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'; C_OFF=$'\033[0m'

log()  { printf '%s[PilotACS]%s %s\n' "$C_CYAN" "$C_OFF" "$*"; }
ok()   { printf '%s[ OK ]%s %s\n' "$C_GREEN" "$C_OFF" "$*"; }
warn() { printf '%s[WARN]%s %s\n' "$C_YELLOW" "$C_OFF" "$*"; }
die()  { printf '%s[FAIL]%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }

# 默认值与 src/main/resources/application.yml 保持一致
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_DATABASE="${MYSQL_DATABASE:-tr_acs}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-Qq123456}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_PASSWORD="${REDIS_PASSWORD:-}"
APP_PORT="${APP_PORT:-8888}"
OPENAPI_PORT="${OPENAPI_PORT:-8899}"
ACS_PUBLIC_HOST="${ACS_PUBLIC_HOST:-}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1024m}"

need_cmd() { command -v "$1" >/dev/null 2>&1; }

# ==================== 1) 系统识别 ====================
OS_ID="unknown"     # ubuntu | debian | centos | rhel | fedora | macos | unknown
OS_NAME="unknown"
PKG="apt"           # apt | dnf | yum | brew
SUDO=""

detect_os() {
  case "$(uname -s)" in
    Darwin)
      OS_ID="macos"
      OS_NAME="macOS $(sw_vers -productVersion 2>/dev/null || echo '')"
      PKG="brew"
      need_cmd brew || die "macOS 上请先安装 Homebrew：https://brew.sh/"
      ;;
    Linux)
      if [ -r /etc/os-release ]; then
        # shellcheck disable=SC1091
        . /etc/os-release
        OS_NAME="${PRETTY_NAME:-${ID:-Linux}}"
        local id="${ID:-unknown}"
        local ver="${VERSION_ID:-}"
        local major="${ver%%.*}"
        case "$id" in
          ubuntu)                OS_ID="ubuntu"; PKG="apt" ;;
          debian)                OS_ID="debian"; PKG="apt" ;;
          centos)                OS_ID="centos"; if [ "${major:-0}" -ge 8 ] 2>/dev/null; then PKG="dnf"; else PKG="yum"; fi ;;
          rhel|rocky|almalinux)  OS_ID="rhel";   if [ "${major:-0}" -ge 8 ] 2>/dev/null; then PKG="dnf"; else PKG="yum"; fi ;;
          fedora)                OS_ID="fedora"; PKG="dnf" ;;
          *)                     OS_ID="$id";    PKG="apt"; warn "发行版 $id 未适配，按 apt 处理；安装失败请手动装依赖" ;;
        esac
        if [ "$PKG" = "yum" ] && need_cmd dnf; then PKG="dnf"; fi
      else
        OS_NAME="Linux"
        PKG="apt"
        warn "缺少 /etc/os-release，按 apt 处理"
      fi
      ;;
    *)
      die "不支持的系统：$(uname -s)"
      ;;
  esac

  if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo"; fi
}

# ==================== 2) 依赖安装 ====================
pkg_update() {
  case "$PKG" in
    apt)  $SUDO apt-get update -y ;;
    dnf)  $SUDO dnf -y makecache ;;
    yum)  $SUDO yum -y makecache ;;
    brew) brew update >/dev/null 2>&1 || true ;;
  esac
}

pkg_install() {
  local pkgs=("$@")
  case "$PKG" in
    apt)  $SUDO DEBIAN_FRONTEND=noninteractive apt-get install -y "${pkgs[@]}" ;;
    dnf)  $SUDO dnf install -y "${pkgs[@]}" ;;
    yum)  $SUDO yum install -y "${pkgs[@]}" ;;
    brew) brew install "${pkgs[@]}" ;;
    *)    die "包管理器 $PKG 不支持，请手动安装：${pkgs[*]}" ;;
  esac
}

java_major() {
  need_cmd java || { printf '0'; return; }
  local v
  v="$(java -version 2>&1 | head -n 1)"
  v="${v#*\"}"; v="${v%%.*}"
  case "$v" in ''|*[!0-9]*) printf '0' ;; *) printf '%s' "$v" ;; esac
}

node_major() {
  need_cmd node || { printf '0'; return; }
  local v
  v="$(node -p "process.versions.node.split('.')[0]" 2>/dev/null || echo 0)"
  case "$v" in ''|*[!0-9]*) printf '0' ;; *) printf '%s' "$v" ;; esac
}

ensure_java() {
  local jv
  jv="$(java_major)"
  if [ "$jv" -ge 21 ]; then
    ok "JDK 已就绪（Java $jv）"
    return
  fi

  log "安装 JDK 21（当前：${jv:-未安装}）..."
  case "$PKG" in
    apt)     pkg_install openjdk-21-jdk || die "JDK 21 安装失败。Ubuntu 20.04 及以下请：sudo add-apt-repository ppa:openjdk-r/ppa && sudo apt-get update && sudo apt-get install -y openjdk-21-jdk" ;;
    dnf|yum) pkg_install java-21-openjdk-devel || die "JDK 21 安装失败。CentOS 7 请从 https://adoptium.net/ 下载 JDK 21 并配置 JAVA_HOME" ;;
    brew)    pkg_install openjdk@21 || die "JDK 21 安装失败"
             $SUDO ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
               /Library/Java/JavaVirtualMachines/openjdk-21.jdk 2>/dev/null || true
             export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
             export PATH="$JAVA_HOME/bin:$PATH" ;;
  esac

  jv="$(java_major)"
  [ "$jv" -ge 21 ] || die "JDK 21 仍不可用（当前 $jv），请手动安装后重试"
  ok "JDK 就绪（Java $jv）"
}

ensure_maven() {
  if need_cmd mvn; then
    ok "Maven 已就绪：$(mvn -v 2>/dev/null | head -n 1)"
    return
  fi

  log "安装 Maven..."
  case "$PKG" in
    apt|dnf|yum) pkg_install maven || die "Maven 安装失败，请手动安装：https://maven.apache.org/install.html" ;;
    brew)        pkg_install maven || die "Maven 安装失败" ;;
  esac

  need_cmd mvn || die "Maven 安装后仍不可用"
  ok "Maven 就绪"
}

ensure_node() {
  local nv
  nv="$(node_major)"
  if [ "$nv" -ge 16 ]; then
    ok "Node 已就绪（v$(node -v 2>/dev/null)）"
    return
  fi

  log "安装 Node.js 18（当前：${nv:-未安装}）..."
  case "$PKG" in
    apt)
      pkg_install ca-certificates curl gnupg || die "基础工具安装失败"
      curl -fsSL https://deb.nodesource.com/setup_18.x | $SUDO -E bash - || die "Node 源配置失败"
      pkg_install nodejs || die "Node 安装失败"
      ;;
    dnf|yum)
      curl -fsSL https://rpm.nodesource.com/setup_18.x | $SUDO -E bash - || die "Node 源配置失败"
      pkg_install nodejs || die "Node 安装失败"
      ;;
    brew)
      pkg_install node@18 || die "Node 安装失败"
      ;;
  esac

  nv="$(node_major)"
  [ "$nv" -ge 16 ] || die "Node 仍不可用（当前 $nv），请手动安装：https://nodejs.org/"
  ok "Node 就绪（v$(node -v 2>/dev/null)）"
}

start_service() {
  local name="$1"
  case "$PKG" in
    brew)
      brew services start "$name" >/dev/null 2>&1 || true
      ;;
    *)
      if need_cmd systemctl; then
        $SUDO systemctl enable "$name" >/dev/null 2>&1 || true
        $SUDO systemctl start "$name" >/dev/null 2>&1 || true
      elif need_cmd service; then
        $SUDO service "$name" start >/dev/null 2>&1 || true
      fi
      ;;
  esac
}

wait_port() {
  local port="$1" timeout="${2:-120}" waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if (echo > "/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
      return 0
    fi
    sleep 2
    waited=$((waited + 2))
  done
  return 1
}

ensure_redis() {
  if need_cmd redis-cli && redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" ping >/dev/null 2>&1; then
    ok "Redis 已在运行（$REDIS_HOST:$REDIS_PORT）"
    return
  fi

  if ! need_cmd redis-server; then
    log "安装 Redis..."
    case "$PKG" in
      apt)     pkg_install redis-server || die "Redis 安装失败" ;;
      dnf|yum) pkg_install redis || die "Redis 安装失败" ;;
      brew)    pkg_install redis || die "Redis 安装失败" ;;
    esac
  fi

  log "启动 Redis..."
  start_service redis-server
  start_service redis

  if wait_port "$REDIS_PORT" 60; then
    ok "Redis 已就绪"
  else
    warn "Redis 端口 $REDIS_PORT 未就绪，应用可能启动失败（队列功能依赖 Redis）"
  fi
}

ensure_mysql() {
  if ! need_cmd mysql; then
    log "安装 MySQL..."
    case "$PKG" in
      apt)     pkg_install mysql-server || die "MySQL 安装失败" ;;
      dnf|yum) pkg_install mysql-server || pkg_install mariadb-server || die "MySQL 安装失败" ;;
      brew)    pkg_install mysql || die "MySQL 安装失败" ;;
    esac
  fi

  log "启动 MySQL..."
  start_service mysqld
  start_service mysql

  if ! wait_port "$MYSQL_PORT" 90; then
    die "MySQL 端口 $MYSQL_PORT 未就绪，请检查 MySQL 服务状态"
  fi
  ok "MySQL 已就绪"
}

# ==================== 3) 数据库初始化 ====================
mysql_exec() {
  local sql="$1"
  if mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -e "$sql" >/dev/null 2>&1; then
    return 0
  fi
  if mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u"$MYSQL_USER" -e "$sql" >/dev/null 2>&1; then
    return 0
  fi
  if $SUDO mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u"$MYSQL_USER" -e "$sql" >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

mysql_import() {
  local file="$1"
  if mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" < "$file" >/dev/null 2>&1; then
    return 0
  fi
  if mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u"$MYSQL_USER" "$MYSQL_DATABASE" < "$file" >/dev/null 2>&1; then
    return 0
  fi
  if $SUDO mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u"$MYSQL_USER" "$MYSQL_DATABASE" < "$file" >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

ask_mysql_password() {
  printf '  请输入 MySQL %s 密码：' "$MYSQL_USER"
  read -rs input_pwd || true
  printf '\n'
  MYSQL_PASSWORD="$input_pwd"
}

init_database() {
  if ! mysql_exec "SELECT 1"; then
    warn "无法用默认凭据连接 MySQL（默认密码取自 application.yml）"
    ask_mysql_password
    mysql_exec "SELECT 1" || die "仍无法连接 MySQL，请确认服务已启动且账号密码正确"
  fi

  log "创建库 $MYSQL_DATABASE ..."
  mysql_exec "CREATE DATABASE IF NOT EXISTS \`$MYSQL_DATABASE\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" \
    || die "建库失败"

  for f in sql/schema.sql sql/schema-tr069tpl.sql; do
    if [ -f "$f" ]; then
      log "导入 $f ..."
      mysql_import "$f" || warn "$f 导入失败，请手动检查（应用启动也会自动补表）"
    fi
  done

  ok "数据库初始化完成"
}

# ==================== 4) 构建 ====================
build_frontend() {
  log "编译前端 web/ ..."
  (
    cd web
    if [ ! -d node_modules ]; then
      log "  npm install（首次较慢）..."
      npm install --legacy-peer-deps --no-audit --no-fund
    fi
    # 旧版 webpack 在 OpenSSL 3 下需要 legacy provider
    export NODE_OPTIONS=--openssl-legacy-provider
    npm run build:prod
  )
  ok "前端产物：src/main/resources/static"
}

build_backend() {
  log "编译后端（Maven）..."
  mvn -B -ntp clean package -DskipTests
  [ -f "$JAR" ] || die "打包失败，未找到 $JAR"
  ok "后端产物：$JAR"
}

# ==================== 5) 启动 / 停止 / 状态 / 日志 ====================
start_app() {
  [ -f "$JAR" ] || die "找不到 $JAR，请先执行 build"

  mkdir -p "$LOG_DIR"

  local host="$ACS_PUBLIC_HOST"
  if [ -z "$host" ]; then
    host="$(detect_ip)"
  fi

  log "启动应用（通告地址 $host）..."
  # shellcheck disable=SC2086
  ACS_PUBLIC_HOST="$host" \
  SPRING_DATASOURCE_URL="jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true&nullCatalogMeansCurrent=true" \
  SPRING_DATASOURCE_USERNAME="$MYSQL_USER" \
  SPRING_DATASOURCE_PASSWORD="$MYSQL_PASSWORD" \
  SPRING_DATA_REDIS_HOST="$REDIS_HOST" \
  SPRING_DATA_REDIS_PORT="$REDIS_PORT" \
  SPRING_DATA_REDIS_PASSWORD="$REDIS_PASSWORD" \
  nohup java $JAVA_OPTS -jar "$JAR" >> "$STDOUT_LOG" 2>&1 &

  printf '%s\n' "$!" > "$PID_FILE"
  ok "PID $(cat "$PID_FILE")"

  log "等待端口 $APP_PORT ..."
  if wait_port "$APP_PORT" 180; then
    ok "应用已就绪"
  else
    warn "端口 $APP_PORT 未就绪，查看日志：./scripts/deploy.sh logs"
  fi

  ACS_PUBLIC_HOST="$host" print_banner
}

detect_ip() {
  local ip=""
  if need_cmd ip; then
    ip="$(ip route get 1.1.1.1 2>/dev/null |
      awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')"
  fi
  if [ -z "$ip" ] && need_cmd hostname; then
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  fi
  if [ -z "$ip" ] && need_cmd ipconfig; then
    ip="$(ipconfig getifaddr en0 2>/dev/null ||
      ipconfig getifaddr en1 2>/dev/null || true)"
  fi
  printf '%s' "${ip:-127.0.0.1}"
}

print_banner() {
  local host="${ACS_PUBLIC_HOST:-127.0.0.1}"
  printf '\n'
  printf '  %sPilotACS 启动完成%s\n\n' "$C_GREEN" "$C_OFF"
  printf '  管理端    http://%s:%s\n' "$host" "${APP_PORT}"
  printf '  接口文档  http://%s:%s/doc.html\n' "$host" "${APP_PORT}"
  printf '  北向接口  http://%s:%s/openapi/v1\n' "$host" "${OPENAPI_PORT}"
  printf '\n  日志目录：%s\n' "$LOG_DIR"
  printf '  停止命令：./scripts/deploy.sh stop\n\n'
}

cmd_start() {
  detect_os
  log "系统：$OS_NAME（包管理器：$PKG）"
  log "第 1/4 步：检查并安装依赖"
  ensure_java
  ensure_maven
  ensure_node
  ensure_mysql
  ensure_redis
  log "第 2/4 步：初始化数据库"
  init_database
  log "第 3/4 步：编译前后端"
  build_frontend
  build_backend
  log "第 4/4 步：启动应用"
  start_app
}

cmd_build() {
  detect_os
  log "系统：$OS_NAME"
  ensure_java
  ensure_maven
  ensure_node
  build_frontend
  build_backend
  ok "构建产物：target/pilotacs-server.jar"
}

cmd_stop() {
  if [ -f "$PID_FILE" ]; then
    local pid
    pid="$(cat "$PID_FILE" 2>/dev/null || echo '')"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      log "停止应用（PID $pid）..."
      kill "$pid" 2>/dev/null || true
      local i
      for i in $(seq 1 20); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
      done
      kill -9 "$pid" 2>/dev/null || true
      ok "已停止"
    fi
    rm -f "$PID_FILE"
  else
    warn "没有 PID 文件，应用可能未运行"
  fi

  pkill -f 'pilotacs-server\.jar' 2>/dev/null || true
}

cmd_status() {
  local pid=""
  [ -f "$PID_FILE" ] && pid="$(cat "$PID_FILE" 2>/dev/null || echo '')"

  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    ok "运行中 PID=$pid"
    printf '  管理端    http://127.0.0.1:%s\n' "$APP_PORT"
    printf '  北向接口  http://127.0.0.1:%s/openapi/v1\n' "$OPENAPI_PORT"
  else
    warn "未运行"
  fi
}

cmd_logs() {
  if [ -f "$LOG_DIR/ACS_LOG.LOG" ]; then
    tail -f "$LOG_DIR/ACS_LOG.LOG"
  elif [ -f "$STDOUT_LOG" ]; then
    warn "还没有 ACS_LOG.LOG，先跟标准输出"
    tail -f "$STDOUT_LOG"
  else
    warn "没有可跟踪的日志文件"
  fi
}

usage() {
  cat <<'EOF'
PilotACS 自动打包部署（Linux / macOS）

用法：./scripts/setup-native.sh [命令]

命令：
  start    安装依赖 → 编译 → 建库 → 启动（默认）
  build    只编译，不启动
  stop     停止
  status   状态
  logs     跟踪日志
EOF
}

main() {
  local cmd="${1:-start}"
  case "$cmd" in
    start)          cmd_start ;;
    build)          cmd_build ;;
    stop)           cmd_stop ;;
    status)         cmd_status ;;
    logs)           cmd_logs ;;
    help|-h|--help) usage ;;
    *)              usage; die "未知命令：$cmd" ;;
  esac
}

main "$@"
