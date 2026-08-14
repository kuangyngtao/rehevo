[CmdletBinding()]
param(
  [switch]$SkipFrontend,
  [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $projectRoot 'docker-compose.dev.yml'
$frontendDir = Join-Path $projectRoot 'frontend'
$logDir = Join-Path $projectRoot 'app\build\dev-logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Get-EnvironmentValue {
  param([string]$Name)

  foreach ($scope in 'Process', 'User', 'Machine') {
    $value = [Environment]::GetEnvironmentVariable($Name, $scope)
    if (-not [string]::IsNullOrWhiteSpace($value)) {
      return $value
    }
  }
  return $null
}

function Test-TcpPort {
  param([int]$Port)

  $client = [System.Net.Sockets.TcpClient]::new()
  try {
    $task = $client.ConnectAsync('127.0.0.1', $Port)
    return $task.Wait(1000) -and $client.Connected
  } catch {
    return $false
  } finally {
    $client.Dispose()
  }
}

function Wait-TcpPort {
  param(
    [int]$Port,
    [int]$TimeoutSeconds = 90,
    [string]$ServiceName
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    if (Test-TcpPort -Port $Port) {
      return
    }
    Start-Sleep -Seconds 2
  }
  throw "$ServiceName 未在 $TimeoutSeconds 秒内监听端口 $Port"
}

function Wait-HttpOk {
  param(
    [string]$Url,
    [int]$TimeoutSeconds = 120
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    try {
      $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri $Url
      if ($response.StatusCode -eq 200) {
        return
      }
    } catch {
      # 应用还在启动，继续等待。
    }
    Start-Sleep -Seconds 3
  }
  throw "后端未在 $TimeoutSeconds 秒内通过健康检查：$Url"
}

function Start-RustFs {
  $existingContainer = & docker ps -a --filter 'name=^/rustfs_local$' --format '{{.Names}}'
  if ($LASTEXITCODE -ne 0) {
    throw '无法查询 Docker 容器，请先启动 Docker Desktop'
  }

  if ($existingContainer -eq 'rustfs_local') {
    if (-not (Test-TcpPort -Port 9000)) {
      Write-Host '启动已有 RustFS 容器 rustfs_local...'
      & docker start rustfs_local | Out-Null
    }
  } else {
    Write-Host '创建并启动项目 RustFS 容器...'
    & docker compose -f $composeFile up -d rustfs
  }

  Wait-TcpPort -Port 9000 -ServiceName 'RustFS'
  Wait-TcpPort -Port 9001 -ServiceName 'RustFS 控制台'
}

function Initialize-RehevoDatabase {
  $databaseName = if ([string]::IsNullOrWhiteSpace($env:POSTGRES_DB)) { 'rehevo' } else { $env:POSTGRES_DB }
  $databaseUser = if ([string]::IsNullOrWhiteSpace($env:POSTGRES_USER)) { 'postgres' } else { $env:POSTGRES_USER }

  if ($databaseName -notmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or $databaseUser -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
    throw 'POSTGRES_DB 与 POSTGRES_USER 只能包含字母、数字和下划线，且不能以数字开头。'
  }

  $postgresContainer = (& docker ps --filter 'publish=5432' --format '{{.Names}}' | Select-Object -First 1)
  if ([string]::IsNullOrWhiteSpace($postgresContainer)) {
    throw '未找到监听 5432 端口的 PostgreSQL 容器。'
  }

  $databaseExists = (& docker exec $postgresContainer psql -U $databaseUser -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$databaseName';")
  if ($LASTEXITCODE -ne 0) {
    throw "无法检查 PostgreSQL 数据库 $databaseName。"
  }
  if ($databaseExists -ne '1') {
    Write-Host "创建 Rehevo 数据库 $databaseName..."
    & docker exec $postgresContainer psql -U $databaseUser -d postgres -c "CREATE DATABASE $databaseName;" | Out-Null
    if ($LASTEXITCODE -ne 0) {
      throw "无法创建 PostgreSQL 数据库 $databaseName。"
    }
  }
}

function Initialize-ApplicationEnvironment {
  $dashscopeKey = Get-EnvironmentValue -Name 'ALI-API-KEY'
  if ([string]::IsNullOrWhiteSpace($dashscopeKey)) {
    $dashscopeKey = Get-EnvironmentValue -Name 'AI_BAILIAN_API_KEY'
  }
  if ([string]::IsNullOrWhiteSpace($dashscopeKey)) {
    throw '未找到 ALI-API-KEY 或 AI_BAILIAN_API_KEY，无法启动 DashScope 聊天、向量和语音服务'
  }
  $env:AI_BAILIAN_API_KEY = $dashscopeKey

  $encryptionKey = Get-EnvironmentValue -Name 'APP_AI_CONFIG_ENCRYPTION_KEY'
  if ([string]::IsNullOrWhiteSpace($encryptionKey)) {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $encryptionKey = [Convert]::ToBase64String($bytes)
    [Environment]::SetEnvironmentVariable('APP_AI_CONFIG_ENCRYPTION_KEY', $encryptionKey, 'User')
    Write-Host '已生成并保存 Provider API Key 加密密钥（不会显示其值）。'
  }
  $env:APP_AI_CONFIG_ENCRYPTION_KEY = $encryptionKey

  $oldConfigDir = Join-Path $env:USERPROFILE '.interview-guide'
  $newConfigDir = Join-Path $env:USERPROFILE '.rehevo'
  if ((Test-Path $oldConfigDir) -and -not (Test-Path $newConfigDir)) {
    New-Item -ItemType Directory -Force -Path $newConfigDir | Out-Null
    Get-ChildItem -Path $oldConfigDir -File -Filter 'llm-providers.*' |
      Copy-Item -Destination $newConfigDir -Force
    Write-Host '已迁移本地模型配置到 ~/.rehevo（原目录保留作备份）。'
  }

  if ([string]::IsNullOrWhiteSpace($env:POSTGRES_PASSWORD)) {
    $env:POSTGRES_PASSWORD = '123456'
  }
  if ([string]::IsNullOrWhiteSpace($env:RUSTFS_ACCESS_KEY)) {
    $env:RUSTFS_ACCESS_KEY = 'rustfsadmin'
  }
  if ([string]::IsNullOrWhiteSpace($env:RUSTFS_SECRET_KEY)) {
    $env:RUSTFS_SECRET_KEY = 'rustfsadmin'
  }
}

Set-Location $projectRoot
Initialize-ApplicationEnvironment

Write-Host '启动 RustFS、PostgreSQL 和 Redis...'
Start-RustFs
if (-not (Test-TcpPort -Port 5432)) {
  & docker compose -f $composeFile up -d postgres
} else {
  Write-Host 'PostgreSQL 已在 5432 运行，复用现有服务。'
}
if (-not (Test-TcpPort -Port 6379)) {
  & docker compose -f $composeFile up -d redis
} else {
  Write-Host 'Redis 已在 6379 运行，复用现有服务。'
}
Wait-TcpPort -Port 5432 -ServiceName 'PostgreSQL'
Wait-TcpPort -Port 6379 -ServiceName 'Redis'
Initialize-RehevoDatabase

if (Test-TcpPort -Port 8080) {
  Write-Host '后端已在 8080 运行，跳过重复启动。'
} else {
  Write-Host '启动 Spring Boot 后端...'
  Start-Process -FilePath (Join-Path $projectRoot 'gradlew.bat') `
    -ArgumentList ':app:bootRun', '--no-daemon' `
    -WorkingDirectory $projectRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDir 'backend.out.log') `
    -RedirectStandardError (Join-Path $logDir 'backend.error.log') | Out-Null
}
Wait-HttpOk -Url 'http://localhost:8080/actuator/health'

if ($SkipFrontend) {
  Write-Host '后端已就绪：http://localhost:8080'
  exit 0
}

if (-not (Test-Path (Join-Path $frontendDir 'node_modules\.bin\vite.cmd'))) {
  Write-Host '安装前端依赖（本次使用官方 npm registry）...'
  Push-Location $frontendDir
  try {
    & pnpm install --frozen-lockfile --registry=https://registry.npmjs.org
  } finally {
    Pop-Location
  }
}

if (Test-TcpPort -Port 5173) {
  Write-Host '前端已在 5173 运行，跳过重复启动。'
} else {
  Write-Host '启动 Vite 前端...'
  Start-Process -FilePath 'pnpm.cmd' `
    -ArgumentList 'dev', '--', '--host', '0.0.0.0' `
    -WorkingDirectory $frontendDir `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDir 'frontend.out.log') `
    -RedirectStandardError (Join-Path $logDir 'frontend.error.log') | Out-Null
}
Wait-TcpPort -Port 5173 -TimeoutSeconds 60 -ServiceName 'Vite 前端'

Write-Host ''
Write-Host '启动完成：'
Write-Host '  前端：http://localhost:5173'
Write-Host '  后端：http://localhost:8080'
Write-Host '  Swagger：http://localhost:8080/swagger-ui.html'
Write-Host "  日志：$logDir"

if (-not $NoBrowser) {
  Start-Process "http://localhost:5173"
}
