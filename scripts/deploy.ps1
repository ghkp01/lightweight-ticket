# MES 一键部署（Windows 本机）
# 流程：构建前端+后端 -> 停掉 8080 上的旧进程 -> 后台启动新 jar -> 等 /actuator/health 变 UP
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\deploy.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\deploy.ps1 -SkipBuild
#   powershell -ExecutionPolicy Bypass -File scripts\deploy.ps1 -WithTests
#
# 数据库连接默认取 application.yml：localhost:5432/mes、mes/mes123；
# 实际环境用环境变量 DB_URL / DB_USER / DB_PASSWORD 覆盖，脚本不会把密码打印出来。

param(
    [switch]$SkipBuild,
    [switch]$WithTests,
    [int]$HealthTimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$buildScript = Join-Path $PSScriptRoot 'build.ps1'
$jar = Join-Path $root 'mes-backend\target\mes-backend-1.0.0.jar'
$logDir = Join-Path $root 'logs'
$stdout = Join-Path $logDir 'app.out.log'
$stderr = Join-Path $logDir 'app.err.log'
$pidFile = Join-Path $logDir 'mes.pid'

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw '未找到 java，请先安装 JDK 25 并加入 PATH'
}

if (-not $SkipBuild) {
    Write-Host '[1/5] 构建前端并打包后端 ...' -ForegroundColor Cyan
    if ($WithTests) {
        & $buildScript -WithTests
    } else {
        & $buildScript
    }
    if ($LASTEXITCODE -ne 0) {
        throw "构建失败（退出码 $LASTEXITCODE）"
    }
} else {
    Write-Host '[1/5] 跳过构建' -ForegroundColor DarkGray
}

if (-not (Test-Path $jar)) {
    throw "未找到 $jar，请先执行不带 -SkipBuild 的部署命令"
}

Write-Host '[2/5] 停止 8080 上的旧进程 ...' -ForegroundColor Cyan
$listeners = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique
foreach ($processId in $listeners) {
    if ($processId -and $processId -ne 0) {
        try {
            $proc = Get-Process -Id $processId -ErrorAction Stop
            Write-Host "      停止 PID $processId ($($proc.ProcessName))"
            Stop-Process -Id $processId -Force
        } catch {
            Write-Host "      跳过 PID $processId：$($_.Exception.Message)" -ForegroundColor DarkYellow
        }
    }
}
for ($i = 0; $i -lt 10; $i++) {
    $still = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    if (-not $still) { break }
    Start-Sleep -Seconds 1
}
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw '8080 仍被占用，请先手动停止占用进程'
}

Write-Host '[3/5] 启动后端 ...' -ForegroundColor Cyan
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$proc = Start-Process -FilePath 'java' `
    -ArgumentList @('-jar', $jar) `
    -WorkingDirectory $root `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr `
    -WindowStyle Hidden `
    -PassThru
Write-Host "      启动进程 PID $($proc.Id)，输出日志 $stdout"

Write-Host "[4/5] 等待健康检查（最多 $HealthTimeoutSeconds 秒）..." -ForegroundColor Cyan
$deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
$healthy = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    try {
        $health = Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 3
        if ($health.status -eq 'UP') {
            $healthy = $true
            break
        }
    } catch {
        # 启动过程中连接失败是正常的，继续等
    }
}

if (-not $healthy) {
    Write-Host '[失败] 健康检查未通过，最近日志：' -ForegroundColor Red
    if (Test-Path $stdout) { Get-Content $stdout -Tail 40 }
    if (Test-Path $stderr) { Get-Content $stderr -Tail 40 }
    throw '部署失败：服务未在超时时间内变为 UP'
}

# java 启动器可能再派生真正的 JVM 进程，以 8080 的实际监听进程为准
$appPid = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty OwningProcess
if (-not $appPid) { $appPid = $proc.Id }
Set-Content -Path $pidFile -Value $appPid -Encoding ascii

Write-Host '[5/5] 部署完成' -ForegroundColor Green
Write-Host '  访问地址: http://localhost:8080'
Write-Host "  进程 PID: $appPid（记录在 $pidFile）"
Write-Host "  应用日志: $stdout"
Write-Host '  停止服务: $appPid = Get-Content logs\mes.pid; Stop-Process -Id $appPid -Force'
