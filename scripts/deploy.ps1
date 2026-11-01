#!/usr/bin/env pwsh
#
# ==================== PilotACS one-click deployment (Windows) ====================
#
# Two deployment modes:
#   1) Docker deploy   containerized app + MySQL + Redis
#   2) Native deploy   install JDK/Maven/Node/MySQL/Redis, build and run directly
#
# Usage:
#   .\scripts\deploy.ps1                Interactive menu
#   .\scripts\deploy.ps1 docker         Docker deployment
#   .\scripts\deploy.ps1 native         Native build deployment (installs deps)
#   .\scripts\deploy.ps1 stop|restart|status|logs|uninstall
#
# NOTE: this file is ASCII-only on purpose. Windows PowerShell 5.1 parses
# .ps1 files without a BOM as ANSI, so non-ASCII characters would corrupt it.
#

param(
    [Parameter(Position = 0)]
    [ValidateSet('menu', 'docker', 'native', 'stop', 'restart', 'status', 'logs', 'uninstall', 'help')]
    [string]$Command = 'menu'
)

$ErrorActionPreference = 'Stop'

$RootDir = Split-Path -Parent $PSScriptRoot
$ScriptDir = $PSScriptRoot
$ModeFile = Join-Path $ScriptDir '.deploy-mode'

Set-Location $RootDir

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

function Get-OsName {
    try {
        $os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
        return "$($os.Caption) $($os.Version)".Trim()
    } catch {
        return 'Windows'
    }
}

# Pick a non-loopback IPv4 address to advertise to CPE devices.
function Get-LocalIPv4 {
    $addr = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -ne '127.0.0.1' -and
            $_.PrefixOrigin -in @('Dhcp', 'Manual', 'RouterAdvertisement')
        } |
        Select-Object -First 1 -ExpandProperty IPAddress

    if (-not $addr) { $addr = '127.0.0.1' }
    return $addr
}

# ---------------- Docker helpers ----------------
$script:ComposePrefix = @('compose')
docker compose version 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) {
    $script:ComposePrefix = @('compose')
} elseif (Test-HasCommand docker-compose) {
    $script:ComposePrefix = @()
} else {
    $script:ComposeMissing = $true
}

function Assert-Docker {
    if (-not (Test-HasCommand docker)) {
        Write-Fail "docker not found. Install Docker Desktop: https://www.docker.com/products/docker-desktop/"
        exit 1
    }

    docker info 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "Docker daemon is not running. Start Docker Desktop, then retry."
        exit 1
    }
}

function Assert-Compose {
    if ($script:ComposeMissing) {
        Write-Fail "docker compose not found. Install Docker Desktop (includes the compose plugin)."
        exit 1
    }
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $ExtraArgs)

    Assert-Docker
    Assert-Compose

    if ($script:ComposePrefix.Count -gt 0) {
        & docker @script:ComposePrefix @ExtraArgs
    } else {
        & docker-compose @ExtraArgs
    }
}

function Ensure-EnvFile {
    if (Test-Path .env) {
        Write-Ok ".env exists, reusing it"
        return
    }

    if (-not (Test-Path .env.example)) {
        Write-Fail ".env.example is missing"
        exit 1
    }

    $ip = Get-LocalIPv4
    $content = (Get-Content .env.example -Raw) -replace '(?m)^ACS_PUBLIC_HOST=.*', "ACS_PUBLIC_HOST=$ip"
    Set-Content -Path .env -Value $content -Encoding UTF8
    Write-Ok ".env created (ACS_PUBLIC_HOST=$ip)"
}

function Import-EnvFile {
    if (-not (Test-Path .env)) { return }

    foreach ($line in Get-Content .env) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '=') { continue }

        $parts = $line -split '=', 2
        $name = $parts[0].Trim()
        $value = $parts[1].Trim()
        if ($name) {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

function Wait-AppHealthy {
    param([int]$TimeoutSeconds = 240)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $status = 'missing'
        try {
            $status = (docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' pilotacs-app 2>$null)
        } catch {
            $status = 'missing'
        }

        if ($status -eq 'healthy') {
            Write-Ok "Application is ready"
            return $true
        }
        if ($status -eq 'unhealthy') {
            Write-Warn "Health check failed, run 'deploy.ps1 logs' for details"
            return $false
        }

        Start-Sleep -Seconds 5
    }

    Write-Warn "Timed out waiting, run 'deploy.ps1 logs' to check progress"
    return $false
}

function Show-Banner {
    $hostName = $env:ACS_PUBLIC_HOST
    if (-not $hostName) { $hostName = '127.0.0.1' }
    $port = if ($env:APP_PORT) { $env:APP_PORT } else { '8888' }
    $openapi = if ($env:OPENAPI_PORT) { $env:OPENAPI_PORT } else { '8899' }

    Write-Host ""
    Write-Host "  PilotACS is up" -ForegroundColor Green
    Write-Host ""
    Write-Host "  Console   http://$hostName`:$port"
    Write-Host "  API docs  http://$hostName`:$port/doc.html"
    Write-Host "  OpenAPI   http://$hostName`:$openapi/openapi/v1"
    Write-Host ""
}

function Invoke-DockerDeploy {
    Assert-Docker
    Assert-Compose
    Ensure-EnvFile
    Import-EnvFile

    Write-Log "Building images and starting services (first run downloads deps)..."
    Invoke-Compose up -d --build
    Set-Content -Path $ModeFile -Value 'docker' -Encoding ASCII

    Write-Log "Waiting for the application..."
    Wait-AppHealthy 240
    Show-Banner
}

function Invoke-NativeDeploy {
    $script = Join-Path $ScriptDir 'setup-native.ps1'
    if (-not (Test-Path $script)) {
        Write-Fail "Missing $script"
        exit 1
    }

    Set-Content -Path $ModeFile -Value 'native' -Encoding ASCII
    & $script -Command 'start'
}

# ---------------- mode aware ops ----------------
function Get-CurrentMode {
    if (Test-Path $ModeFile) {
        return (Get-Content $ModeFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    }

    if (Test-HasCommand docker) {
        docker inspect pilotacs-app 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { return 'docker' }
    }

    return 'native'
}

function Invoke-Stop {
    $mode = Get-CurrentMode
    if ($mode -eq 'docker') {
        Invoke-Compose down
        Write-Ok "Stopped (Docker)"
    } else {
        $script = Join-Path $ScriptDir 'setup-native.ps1'
        if (-not (Test-Path $script)) { Write-Fail "Missing $script"; exit 1 }
        & $script -Command 'stop'
    }
}

function Invoke-Status {
    $mode = Get-CurrentMode
    if ($mode -eq 'docker') {
        Invoke-Compose ps
    } else {
        $script = Join-Path $ScriptDir 'setup-native.ps1'
        if (-not (Test-Path $script)) { Write-Fail "Missing $script"; exit 1 }
        & $script -Command 'status'
    }
}

function Invoke-Logs {
    $mode = Get-CurrentMode
    if ($mode -eq 'docker') {
        Invoke-Compose logs -f --tail 200 app
    } else {
        $script = Join-Path $ScriptDir 'setup-native.ps1'
        if (-not (Test-Path $script)) { Write-Fail "Missing $script"; exit 1 }
        & $script -Command 'logs'
    }
}

function Invoke-Restart {
    Invoke-Stop
    $mode = Get-CurrentMode
    if ($mode -eq 'docker') { Invoke-DockerDeploy } else { Invoke-NativeDeploy }
}

function Invoke-Uninstall {
    $mode = Get-CurrentMode
    if ($mode -eq 'docker') {
        Write-Warn "This removes containers AND deletes MySQL/Redis volumes (all data)."
        $answer = Read-Host "Type yes to confirm"
        if ($answer -ne 'yes') { Write-Log "Cancelled"; return }
        Invoke-Compose down -v --remove-orphans
        Remove-Item $ModeFile -Force -ErrorAction SilentlyContinue
        Write-Ok "Removed"
    } else {
        $script = Join-Path $ScriptDir 'setup-native.ps1'
        if (Test-Path $script) { & $script -Command 'stop' }
        $target = Join-Path $RootDir 'target'
        if (Test-Path $target) { Remove-Item $target -Recurse -Force }
        Remove-Item $ModeFile -Force -ErrorAction SilentlyContinue
        Write-Ok "Stopped and cleaned build output (database data kept)"
    }
}

# ---------------- menu ----------------
function Show-Menu {
    Write-Host @'

  ==================  PilotACS one-click deploy  ==================

    [1] Docker deploy        Start app + MySQL + Redis in containers
    [2] Native deploy        Install deps, build, init db, run

    -------------------  Ops  -------------------
    [3] Stop
    [4] Status
    [5] Logs
    [6] Uninstall

    [0] Exit

'@
}

function Invoke-Interactive {
    $os = Get-OsName

    while ($true) {
        Show-Menu
        Write-Host "  OS: $os"
        $choice = Read-Host "  Select [1]"
        if ([string]::IsNullOrWhiteSpace($choice)) { $choice = '1' }

        switch ($choice) {
            '1' { Invoke-DockerDeploy; return }
            '2' { Invoke-NativeDeploy;  return }
            '3' { Invoke-Stop }
            '4' { Invoke-Status }
            '5' { Invoke-Logs }
            '6' { Invoke-Uninstall }
            '0' { Write-Log "Bye"; return }
            default { Write-Warn "Invalid choice" }
        }
    }
}

function Show-Usage {
    Write-Host @'
PilotACS deployment script (Windows)

  .\scripts\deploy.ps1 [command]

Commands:
  docker      Docker deployment (app + MySQL + Redis)
  native      Native build deployment (installs JDK / Maven / Node / MySQL / Redis)
  stop        Stop services
  restart     Restart
  status      Show status
  logs        Follow application logs
  uninstall   Uninstall
  help        Show this help

Run without arguments to get the interactive menu.
'@
}

switch ($Command) {
    'menu'          { Invoke-Interactive }
    'docker'        { Invoke-DockerDeploy }
    'native'        { Invoke-NativeDeploy }
    'stop'          { Invoke-Stop }
    'restart'       { Invoke-Restart }
    'status'        { Invoke-Status }
    'logs'          { Invoke-Logs }
    'uninstall'     { Invoke-Uninstall }
    default         { Show-Usage }
}
