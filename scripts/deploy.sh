#!/usr/bin/env bash
#
# ==================== PilotACS 一键部署入口 ====================
#
# 支持：Ubuntu / Debian / CentOS / RHEL / Rocky / AlmaLinux / Fedora / macOS
# （Windows 请用 scripts\deploy.bat 或 scripts\deploy.ps1）
#
# 两种部署方式：
#   1) Docker 部署      容器化启动 app + MySQL + Redis，环境隔离
#   2) 自动打包部署      自动安装 JDK/Maven/Node/MySQL/Redis，编译前后端并直接运行
#
# 用法：
#   ./scripts/deploy.sh                交互式菜单
#   ./scripts/deploy.sh docker         Docker 部署
#   ./scripts/deploy.sh native         自动打包部署（含依赖安装）
#   ./scripts/deploy.sh stop           停止服务
#   ./scripts/deploy.sh status         查看状态
#   ./scripts/deploy.sh logs           跟踪应用日志
#   ./scripts/deploy.sh uninstall      卸载
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$ROOT_DIR/scripts"
MODE_FILE="$SCRIPT_DIR/.deploy-mode"

cd "$ROOT_DIR"

C_CYAN=$'\033[36m'; C_GREEN=$'\033[32m'
C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'; C_OFF=$'\033[0m'

log()  { printf '%s[PilotACS]%s %s\n' "$C_CYAN" "$C_OFF" "$*"; }
ok()   { printf '%s[ OK ]%s %s\n' "$C_GREEN" "$C_OFF" "$*"; }
warn() { printf '%s[WARN]%s %s\n' "$C_YELLOW" "$C_OFF" "$*"; }
die()  { printf '%s[FAIL]%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }

# ==================== 系统识别 ====================
OS_ID="unknown"     # ubuntu | debian | centos | rhel | fedora | macos
OS_NAME="unknown"
PKG="apt"           # apt | dnf | yum | brew
SUDO=""

need_cmd() { command -v "$1" >/dev/null 2>&1; }

detect_os() {
  case "$(uname -s)" in
    Darwin)
      OS_ID="macos"
      OS_NAME="macOS $(sw_vers -productVersion 2>/dev/null || echo '')"
      PKG="brew"
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
          *)                     OS_ID="$id";    PKG="apt"; warn "发行版 $id 未适配，按 apt 处理" ;;
        esac
        if [ "$PKG" = "yum" ] && need_cmd dnf; then PKG="dnf"; fi
      else
        OS_NAME="Linux"
        PKG="apt"
        warn "缺少 /etc/os-release，按 apt 处理"
      fi
      ;;
    *)
      die "不支持的系统：$(uname -s)。Windows 请运行 scripts\\deploy.bat"
      ;;
  esac

  if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo"; fi
}

# ==================== Docker 部署 ====================
compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    printf 'docker compose'
  elif need_cmd docker-compose; then
    printf 'docker-compose'
  else
    die "未找到 docker compose，请先安装 Docker Desktop 或 docker-compose 插件"
  fi
}

install_docker() {
  log "开始安装 Docker..."
  case "$PKG" in
    brew)
      die "macOS 请手动安装 Docker Desktop：https://www.docker.com/products/docker-desktop/"
      ;;
    apt)
      $SUDO apt-get update -y
      $SUDO apt-get install -y ca-certificates curl
      curl -fsSL https://get.docker.com | $SUDO sh
      ;;
    dnf|yum)
      curl -fsSL https://get.docker.com | $SUDO sh
      ;;
    *)
      die "请手动安装 Docker：https://docs.docker.com/engine/install/"
      ;;
  esac

  if need_cmd systemctl; then
    $SUDO systemctl enable --now docker 2>/dev/null || true
  fi
  $SUDO usermod -aG docker "${USER:-root}" 2>/dev/null || true
  ok "Docker 安装完成（docker 组需重新登录终端后生效）"
}

ensure_docker() {
  if ! need_cmd docker; then
    warn "未检测到 Docker"
    printf '  是否自动安装？[y/N] '
    read -r ans || true
    case "$ans" in y|Y) install_docker ;; *) die "已取消，请手动安装 Docker 后重试" ;; esac
  fi

  docker info >/dev/null 2>&1 || die "Docker 守护进程未运行，请先启动 Docker 服务"
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

ensure_env() {
  if [ -f .env ]; then
    ok ".env 已存在，直接使用"
    return
  fi
  [ -f .env.example ] || die "缺少 .env.example"
  local ip
  ip="$(detect_ip)"
  sed "s|^ACS_PUBLIC_HOST=.*|ACS_PUBLIC_HOST=${ip}|" .env.example > .env
  ok "已生成 .env（ACS_PUBLIC_HOST=${ip}）"
}

load_env() {
  [ -f .env ] || return 0
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
}

wait_healthy() {
  local timeout="${1:-240}" waited=0 status
  while [ "$waited" -lt "$timeout" ]; do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
      pilotacs-app 2>/dev/null || echo missing)"
    case "$status" in
      healthy)   ok "应用已就绪"; return 0 ;;
      unhealthy) warn "健康检查未通过，执行 deploy.sh logs 看日志"; return 1 ;;
    esac
    sleep 5
    waited=$((waited + 5))
  done
  warn "等待超时，应用可能还在启动，执行 deploy.sh logs 看进度"
}

print_banner() {
  local host="${ACS_PUBLIC_HOST:-127.0.0.1}"
  local port="${APP_PORT:-8888}"
  local openapi="${OPENAPI_PORT:-8899}"
  printf '\n'
  printf '  %sPilotACS 启动完成%s\n\n' "$C_GREEN" "$C_OFF"
  printf '  管理端    http://%s:%s\n' "$host" "$port"
  printf '  接口文档  http://%s:%s/doc.html\n' "$host" "$port"
  printf '  北向接口  http://%s:%s/openapi/v1\n' "$host" "$openapi"
  printf '\n  初始账号见 sql/schema.sql 写入的 sys_user\n\n'
}

docker_deploy() {
  detect_os
  log "系统：$OS_NAME（包管理器：$PKG）"
  ensure_docker
  local dc
  dc="$(compose_cmd)"
  ensure_env
  load_env
  log "构建镜像并启动（首次需下载依赖，请耐心等待）..."
  $dc up -d --build
  printf 'docker\n' > "$MODE_FILE"
  log "等待服务就绪..."
  wait_healthy 240
  print_banner
}

# ==================== 自动打包部署 ====================
native_deploy() {
  [ -f "$SCRIPT_DIR/setup-native.sh" ] || die "缺少 $SCRIPT_DIR/setup-native.sh"
  chmod +x "$SCRIPT_DIR/setup-native.sh" 2>/dev/null || true
  printf 'native\n' > "$MODE_FILE"
  exec "$SCRIPT_DIR/setup-native.sh" "$@"
}

# ==================== 运维命令（按部署方式分发） ====================
current_mode() {
  if [ -f "$MODE_FILE" ]; then
    cat "$MODE_FILE"
    return
  fi
  # 没记录就猜：容器在跑就是 docker，否则 native
  if need_cmd docker && docker inspect pilotacs-app >/dev/null 2>&1; then
    printf 'docker'
    return
  fi
  printf 'native'
}

cmd_stop() {
  local mode
  mode="$(current_mode)"
  if [ "$mode" = "docker" ]; then
    ensure_docker
    local dc
    dc="$(compose_cmd)"
    $dc down
    ok "已停止（Docker）"
  else
    [ -f "$SCRIPT_DIR/setup-native.sh" ] || die "缺少 $SCRIPT_DIR/setup-native.sh"
    "$SCRIPT_DIR/setup-native.sh" stop
  fi
}

cmd_status() {
  local mode
  mode="$(current_mode)"
  if [ "$mode" = "docker" ]; then
    ensure_docker
    local dc
    dc="$(compose_cmd)"
    $dc ps
  else
    [ -f "$SCRIPT_DIR/setup-native.sh" ] || die "缺少 $SCRIPT_DIR/setup-native.sh"
    "$SCRIPT_DIR/setup-native.sh" status
  fi
}

cmd_logs() {
  local mode
  mode="$(current_mode)"
  if [ "$mode" = "docker" ]; then
    ensure_docker
    local dc
    dc="$(compose_cmd)"
    $dc logs -f --tail=200 app
  else
    [ -f "$SCRIPT_DIR/setup-native.sh" ] || die "缺少 $SCRIPT_DIR/setup-native.sh"
    "$SCRIPT_DIR/setup-native.sh" logs
  fi
}

cmd_restart() {
  local mode
  mode="$(current_mode)"
  cmd_stop
  if [ "$mode" = "docker" ]; then docker_deploy; else native_deploy; fi
}

cmd_uninstall() {
  local mode
  mode="$(current_mode)"
  if [ "$mode" = "docker" ]; then
    warn "将删除容器并清空 MySQL / Redis 数据卷（台账与统计丢失）"
    printf '  确认请输入 yes：'
    read -r answer || true
    [ "$answer" = "yes" ] || { log "已取消"; exit 0; }
    docker compose down -v --remove-orphans
    rm -f "$MODE_FILE"
    ok "已删除容器与数据卷"
  else
    [ -f "$SCRIPT_DIR/setup-native.sh" ] || die "缺少 $SCRIPT_DIR/setup-native.sh"
    "$SCRIPT_DIR/setup-native.sh" stop
    rm -rf "$ROOT_DIR/target"
    rm -f "$MODE_FILE"
    ok "已停止并清理构建产物（数据库数据保留）"
  fi
}

# ==================== 交互菜单 ====================
prompt() {
  local default="$1" choice=""
  if [ -t 0 ]; then
    printf '  请选择 [%s]: ' "$default"
    read -r choice || true
  fi
  printf '%s' "${choice:-$default}"
}

show_menu() {
  cat <<'EOF'

  ==================  PilotACS 一键部署  ==================

    [1] Docker 部署        容器化启动 app + MySQL + Redis
    [2] 自动打包部署        安装依赖 → 编译前后端 → 初始化库 → 启动

    -------------------  运维  -------------------
    [3] 停止服务
    [4] 查看状态
    [5] 跟踪日志
    [6] 卸载

    [0] 退出

EOF
}

interactive() {
  detect_os
  while true; do
    show_menu
    printf '  当前系统：%s\n' "$OS_NAME"
    case "$(prompt 1)" in
      1) docker_deploy;  return 0 ;;
      2) native_deploy;  return 0 ;;
      3) cmd_stop ;;
      4) cmd_status ;;
      5) cmd_logs ;;
      6) cmd_uninstall ;;
      0) log "已退出"; return 0 ;;
      *) warn "无效选择" ;;
    esac
  done
}

usage() {
  cat <<'EOF'
PilotACS 一键部署脚本（Linux / macOS）

用法：./scripts/deploy.sh [命令]

命令：
  docker      Docker 部署（app + MySQL + Redis）
  native      自动打包部署（自动安装 JDK / Maven / Node / MySQL / Redis）
  stop        停止服务
  restart     重启
  status      查看状态
  logs        跟踪应用日志（Ctrl+C 退出）
  uninstall   卸载
  help        显示本帮助

不带参数运行进入交互式菜单。
EOF
}

main() {
  local cmd="${1:-menu}"
  case "$cmd" in
    menu)          interactive ;;
    docker)        docker_deploy ;;
    native)        shift; native_deploy "$@" ;;
    stop)          cmd_stop ;;
    restart)       cmd_restart ;;
    status)        cmd_status ;;
    logs)          cmd_logs ;;
    uninstall)     cmd_uninstall ;;
    help|-h|--help) usage ;;
    *)             usage; die "未知命令：$cmd" ;;
  esac
}

main "$@"
