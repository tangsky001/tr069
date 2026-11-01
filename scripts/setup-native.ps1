#!/usr/bin/env pwsh
#
# ==================== PilotACS native build deployment (Windows) ====================
#
# Flow:
#   1) Detect package manager (winget / choco)
#   2) Install deps: JDK 21 / Maven / Node 16+ / MySQL / Redis
#   3) Build frontend web/ -> src/main/resources/static
#   4) Build backend      -> target\pilotacs-server.jar
#   5) Create database and import sql/schema.sql, sql/schema-tr069tpl.sql
#   6) Start the application in the background
#
# Usage:
#   .\scripts\setup-native.ps1            Install deps and start
#   .\scripts\setup-native.ps1 start      Same as above
#   .\scripts\setup-native.ps1 build      Build only
#   .\scripts\setup-native.ps1 stop|status|logs
#
# NOTE: this file is ASCII-only on purpose. Windows PowerShell 5.1 parses
# .ps1 files without a BOM as ANSI, so non-ASCII characters would corrupt it.
#

param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'build', 'stop', 'status', 'logs', 'help')]
    [string]$Command = 'start'
)

$ErrorActionPreference = 'Stop'

$RootDir = Split-Path -Parent $PSScriptRoot
$ScriptDir = $PSScriptRoot
$PidFile = Join-Path $ScriptDir 'pilotacs.pid'
$LogDir = Join-Path $RootDir 'log'
$StdOutLog = Join-Path $LogDir 'stdout.log'
$StdErrLog = Join-Path $LogDir 'stderr.log'
$Jar = Join-Path $RootDir 'target\pilotacs-server.jar'

Set-Location $RootDir

# Defaults kept in sync with src/main/resources/application.yml
if (-not $env:MYSQL_HOST) { $env:MYSQL_HOST = '127.0.0.1' }
if (-not $env:MYSQL_PORT) { $env:MYSQL_PORT = '3306' }
if (-not $env:MYSQL_DATABASE) { $env:MYSQL_DATABASE = 'tr_acs' }
if (-not $env:MYSQL_USER) { $env:MYSQL_USER = 'root' }
if (-not $env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD = 'Qq123456' }
if (-not $env:REDIS_HOST) { $env:REDIS_HOST = '127.0.0.1' }
if (-not $env:REDIS_PORT) { $env:REDIS_PORT = '6379' }
if (-not $env:APP_PORT) { $env:APP_PORT = '8888' }
if (-not $env:OPENAPI_PORT) { $env:OPENAPI_PORT = '8899' }
if (-not $env:JAVA_OPTS) { $env:JAVA_OPTS = '-Xms512m -Xmx1024m' }

function Write-Log {
    param([string]$Message)
    Write-Host "[PilotACS] $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "[ OK ] $Message" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Write-Fail {
    param([string]$Message)
    Write-Host "[FAIL] $Message" -ForegroundColor Red
}

function Test-HasCommand {
    param([string]$Name)
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

# ---------------- OS / package manager ----------------
function Get-OsName {
    try {
        $os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
        return "$($os.Caption) $($os.Version)".Trim()
    } catch {
        return 'Windows'
    }
}

function Get-PackageManager {
    if (Test-HasCommand winget) { return 'winget' }
    if (Test-HasCommand choco) { return 'choco' }
    throw "No package manager found. Install winget (App Installer) or Chocolatey: https://chocolatey.org/install"
}

function Install-Packages {
    param([string[]]$Ids)

    $mgr = Get-PackageManager
    foreach ($id in $Ids) {
        Write-Log "Installing $id ($mgr)..."
        if ($mgr -eq 'winget') {
            winget install -e --id $id --accept-package-agreements --accept-source-agreements 2>$null | Out-Null
            $code = $LASTEXITCODE
        } else {
            choco install $id -y 2>$null | Out-Null
            $code = $LASTEXITCODE
        }

        if ($code -ne 0 -and $code -ne -1978335189) {
            Write-Warn "Installer exited with code $code for $id"
        }
    }
}

# ---------------- version helpers ----------------
function Get-JavaMajor {
    if (-not (Test-HasCommand java)) { return 0 }
    $raw = (& java -version 2>&1 | Select-Object -First 1)
    $m = [regex]::Match($raw, 'version\s+"?(\d+)')
    if ($m.Success) { return [int]$m.Groups[1].Value }
    return 0
}

function Get-NodeMajor {
    if (-not (Test-HasCommand node)) { return 0 }
    $raw = (& node -v 2>$null)
    $m = [regex]::Match($raw, 'v?(\d+)')
    if ($m.Success) { return [int]$m.Groups[1].Value }
    return 0
}

function Refresh-Path {
    $machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $user = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = "$user;$machine"
}

# ---------------- dependency checks ----------------
function Ensure-Java {
    $jv = Get-JavaMajor
    if ($jv -ge 21) {
        Write-Ok "JDK ready (Java $jv)"
        return
    }

    Write-Log "Installing JDK 21 (current: $jv)..."
    if (Test-HasCommand winget) {
        Install-Packages @('Microsoft.OpenJDK.21')
    } else {
        Install-Packages @('openjdk21')
    }
    Refresh-Path

    $jv = Get-JavaMajor
    if ($jv -lt 21) {
        throw "JDK 21 still unavailable (got $jv). Install it manually: https://adoptium.net/"
    }
    Write-Ok "JDK ready (Java $jv)"
}

function Ensure-Maven {
    if (Test-HasCommand mvn) {
        Write-Ok "Maven ready"
        return
    }

    Write-Log "Installing Maven..."
    if (Test-HasCommand winget) {
        Install-Packages @('Apache.Maven')
    } else {
        Install-Packages @('maven')
    }
    Refresh-Path

    if (-not (Test-HasCommand mvn)) {
        throw "Maven still unavailable. Install it manually: https://maven.apache.org/install.html"
    }
    Write-Ok "Maven ready"
}

function Ensure-Node {
    $nv = Get-NodeMajor
    if ($nv -ge 16) {
        Write-Ok "Node ready (v$(& node -v))"
        return
    }

    Write-Log "Installing Node.js LTS (current: $nv)..."
    if (Test-HasCommand winget) {
        Install-Packages @('OpenJS.NodeJS.LTS')
    } else {
        Install-Packages @('nodejs-lts')
    }
    Refresh-Path

    $nv = Get-NodeMajor
    if ($nv -lt 16) {
        throw "Node still unavailable (got $nv). Install it manually: https://nodejs.org/"
    }
    Write-Ok "Node ready"
}

function Ensure-Redis {
    if (Test-PortOpen -Port $env:REDIS_PORT) {
        Write-Ok "Redis already running on port $($env:REDIS_PORT)"
        return
    }

    Write-Log "Installing Redis (Memurai, Redis-compatible for Windows)..."
    if (Test-HasCommand winget) {
        Install-Packages @('Memurai.Memurai')
    } else {
        Install-Packages @('redis-64')
    }
    Start-ServiceByName -Pattern 'Memurai|Redis'

    if (Test-PortOpen -Port $env:REDIS_PORT) {
        Write-Ok "Redis ready"
    } else {
        Write-Warn "Redis port $($env:REDIS_PORT) not up. The queue feature depends on Redis."
    }
}

function Ensure-MySQL {
    if (Test-PortOpen -Port $env:MYSQL_PORT) {
        Write-Ok "MySQL already running on port $($env:MYSQL_PORT)"
        return
    }

    Write-Log "Installing MySQL..."
    if (Test-HasCommand winget) {
        Install-Packages @('Oracle.MySQL')
    } else {
        Install-Packages @('mysql')
    }
    Start-ServiceByName -Pattern 'MySQL'

    if (-not (Test-PortOpen -Port $env:MYSQL_PORT)) {
        throw "MySQL port $($env:MYSQL_PORT) not up. Start MySQL manually, then rerun."
    }
    Write-Ok "MySQL ready"
}

function Test-PortOpen {
    param([int]$Port)
    $client = New-Object Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $Port)
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-ForPort {
    param([int]$Port, [int]$TimeoutSeconds = 90)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortOpen -Port $Port) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Start-ServiceByName {
    param([string]$Pattern)

    $svc = Get-Service -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match $Pattern -or $_.DisplayName -match $Pattern } |
        Select-Object -First 1

    if ($svc -and $svc.Status -ne 'Running') {
        Write-Log "Starting service $($svc.Name)..."
        try { Start-Service -Name $svc.Name -ErrorAction Stop } catch { Write-Warn "Could not start $($svc.Name)" }
    }
}

# ---------------- database ----------------
function Get-MySqlBaseArgs {
    $args = @('-h', $env:MYSQL_HOST, '-P', $env:MYSQL_PORT, '-u', $env:MYSQL_USER)
    if ($env:MYSQL_PASSWORD) { $args += "-p$($env:MYSQL_PASSWORD)" }
    return $args
}

function Test-MySqlConnection {
    $base = Get-MySqlBaseArgs
    try {
        & mysql @base -e "SELECT 1" 2>$null | Out-Null
        return ($LASTEXITCODE -eq 0)
    } catch {
        return $false
    }
}

function Initialize-Database {
    if (-not (Test-MySqlConnection)) {
        throw "Cannot connect to MySQL as $($env:MYSQL_USER). Check the service and credentials (env MYSQL_USER / MYSQL_PASSWORD)."
    }

    $base = Get-MySqlBaseArgs
    Write-Log "Creating database $($env:MYSQL_DATABASE)..."
    & mysql @base -e "CREATE DATABASE IF NOT EXISTS \`$($env:MYSQL_DATABASE)\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Failed to create database" }

    foreach ($f in @('sql\schema.sql', 'sql\schema-tr069tpl.sql')) {
        if (Test-Path $f) {
            Write-Log "Importing $f ..."
            Get-Content $f -Raw | & mysql @base $env:MYSQL_DATABASE 2>$null
            if ($LASTEXITCODE -ne 0) {
                Write-Warn "$f import failed. Check it manually (the app also auto-creates tables)."
            }
        }
    }

    Write-Ok "Database ready"
}

# ---------------- build ----------------
function Build-Frontend {
    Write-Log "Building frontend web/ ..."
    Push-Location (Join-Path $RootDir 'web')

    if (-not (Test-Path 'node_modules')) {
        Write-Log "  npm install (first run is slow)..."
        & npm install --legacy-peer-deps --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { Pop-Location; throw "npm install failed" }
    }

    $env:NODE_OPTIONS = '--openssl-legacy-provider'
    & npm run build:prod
    $code = $LASTEXITCODE
    Pop-Location

    if ($code -ne 0) { throw "Frontend build failed" }
    Write-Ok "Frontend output: src\main\resources\static"
}

function Build-Backend {
    Write-Log "Building backend (Maven)..."
    & mvn -B -ntp clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Backend build failed" }
    if (-not (Test-Path $Jar)) { throw "Jar not found: $Jar" }
    Write-Ok "Backend output: $Jar"
}

# ---------------- lifecycle ----------------
function Start-App {
    if (-not (Test-Path $Jar)) { throw "Jar not found: $Jar. Run build first." }

    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

    $hostName = $env:ACS_PUBLIC_HOST
    if (-not $hostName) { $hostName = Get-LocalIPv4 }

    $env:ACS_PUBLIC_HOST = $hostName
    $env:SPRING_DATASOURCE_URL = "jdbc:mysql://$($env:MYSQL_HOST):$($env:MYSQL_PORT)/$($env:MYSQL_DATABASE)?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true&nullCatalogMeansCurrent=true"
    $env:SPRING_DATASOURCE_USERNAME = $env:MYSQL_USER
    $env:SPRING_DATASOURCE_PASSWORD = $env:MYSQL_PASSWORD
    $env:SPRING_DATA_REDIS_HOST = $env:REDIS_HOST
    $env:SPRING_DATA_REDIS_PORT = $env:REDIS_PORT

    Write-Log "Starting application (advertised host $hostName)..."
    $javaArgs = @()
    $javaArgs += $env:JAVA_OPTS.Split(' ', [System.StringSplitOptions]::RemoveEmptyEntries)
    $javaArgs += @('-jar', $Jar)

    $proc = Start-Process -FilePath 'java' -ArgumentList $javaArgs `
        -WorkingDirectory $RootDir -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $StdOutLog -RedirectStandardError $StdErrLog

    Set-Content -Path $PidFile -Value $proc.Id -Encoding ASCII
    Write-Ok "PID $($proc.Id)"

    Write-Log "Waiting for port $($env:APP_PORT) ..."
    if (Wait-ForPort -Port ([int]$env:APP_PORT) -TimeoutSeconds 180) {
        Write-Ok "Application is ready"
    } else {
        Write-Warn "Port $($env:APP_PORT) not up. Check logs: .\scripts\deploy.ps1 logs"
    }

    Show-Banner
}

function Stop-App {
    if (Test-Path $PidFile) {
        $procId = (Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        if ($procId) {
            $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
            if ($proc) {
                Write-Log "Stopping application (PID $procId)..."
                Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            }
        }
        Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    } else {
        Write-Warn "No PID file, application may not be running"
    }
}

function Show-Status {
    $procId = $null
    if (Test-Path $PidFile) {
        $procId = (Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    }

    if ($procId -and (Get-Process -Id $procId -ErrorAction SilentlyContinue)) {
        Write-Ok "Running PID=$procId"
        Write-Host "  Console   http://127.0.0.1:$($env:APP_PORT)"
        Write-Host "  OpenAPI   http://127.0.0.1:$($env:OPENAPI_PORT)/openapi/v1"
    } else {
        Write-Warn "Not running"
    }
}

function Show-Logs {
    $coreLog = Join-Path $LogDir 'ACS_LOG.LOG'
    if (Test-Path $coreLog) {
        Get-Content $coreLog -Wait -Tail 100
    } elseif (Test-Path $StdOutLog) {
        Write-Warn "ACS_LOG.LOG not present yet, following stdout instead"
        Get-Content $StdOutLog -Wait -Tail 100
    } else {
        Write-Warn "No log file to follow"
    }
}

function Show-Banner {
    $hostName = $env:ACS_PUBLIC_HOST
    if (-not $hostName) { $hostName = '127.0.0.1' }
    Write-Host ""
    Write-Host "  PilotACS is up" -ForegroundColor Green
    Write-Host ""
    Write-Host "  Console   http://$hostName`:$($env:APP_PORT)"
    Write-Host "  API docs  http://$hostName`:$($env:APP_PORT)/doc.html"
    Write-Host "  OpenAPI   http://$hostName`:$($env:OPENAPI_PORT)/openapi/v1"
    Write-Host ""
    Write-Host "  Logs: $LogDir"
    Write-Host "  Stop: .\scripts\deploy.ps1 stop"
    Write-Host ""
}

function Invoke-Start {
    Write-Log "OS: $(Get-OsName)"
    Write-Log "Step 1/4: checking dependencies"
    Ensure-Java
    Ensure-Maven
    Ensure-Node
    Ensure-MySQL
    Ensure-Redis
    Write-Log "Step 2/4: initializing database"
    Initialize-Database
    Write-Log "Step 3/4: building frontend and backend"
    Build-Frontend
    Build-Backend
    Write-Log "Step 4/4: starting application"
    Start-App
}

function Invoke-Build {
    Write-Log "OS: $(Get-OsName)"
    Ensure-Java
    Ensure-Maven
    Ensure-Node
    Build-Frontend
    Build-Backend
    Write-Ok "Build output: $Jar"
}

function Show-Usage {
    Write-Host @'
PilotACS native deployment (Windows)

  .\scripts\setup-native.ps1 [command]

Commands:
  start     Install deps -> build -> init db -> start (default)
  build     Build only
  stop      Stop the application
  status    Show status
  logs      Follow logs
'@
}

switch ($Command) {
    'start'         { Invoke-Start }
    'build'         { Invoke-Build }
    'stop'          { Stop-App }
    'status'        { Show-Status }
    'logs'          { Show-Logs }
    default         { Show-Usage }
}
