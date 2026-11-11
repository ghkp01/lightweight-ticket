# 构建链：前端打包 -> 同步到后端静态目录 -> 打包可执行 jar
# 用法：powershell -ExecutionPolicy Bypass -File scripts\build.ps1 [-WithTests]
param(
    [switch]$WithTests
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$frontend = Join-Path $root 'mes-frontend'
$backend = Join-Path $root 'mes-backend'
$staticDir = Join-Path $backend 'src\main\resources\static'

$npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $npm) { $npm = Get-Command npm -ErrorAction SilentlyContinue }
if (-not $npm) { throw '未找到 npm，请先安装 Node.js' }

$mvn = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $mvn) { $mvn = Get-Command mvn -ErrorAction SilentlyContinue }
if (-not $mvn) { throw '未找到 Maven，请先安装并把 mvn 加入 PATH' }

Write-Host '[1/4] 构建前端 ...' -ForegroundColor Cyan
Push-Location $frontend
try { & $npm.Source run build } finally { Pop-Location }

Write-Host '[2/4] 同步前端产物到后端 static ...' -ForegroundColor Cyan
if (Test-Path $staticDir) { Remove-Item "$staticDir\*" -Recurse -Force }
New-Item -ItemType Directory -Path $staticDir -Force | Out-Null
Copy-Item "$frontend\dist\*" $staticDir -Recurse -Force

Write-Host '[3/4] 打包后端 jar ...' -ForegroundColor Cyan
Push-Location $backend
try {
    if ($WithTests) {
        & $mvn.Source package
    } else {
        & $mvn.Source package -DskipTests
    }
} finally { Pop-Location }

Write-Host '[4/4] 完成。启动命令：' -ForegroundColor Green
Write-Host '  java -jar mes-backend\target\mes-backend-1.0.0.jar'
Write-Host '  需要覆盖数据库连接时加环境变量 DB_URL / DB_USER / DB_PASSWORD'
