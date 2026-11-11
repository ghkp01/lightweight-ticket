# 备份 MES：数据库 dump + 附件目录，按保留天数清理
#
# 用法（建议挂到 Windows 计划任务，每天凌晨跑一次）：
#   powershell -ExecutionPolicy Bypass -File scripts\backup.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\backup.ps1 -BackupDir D:\mes-backup -KeepDays 30
#
# 只备份不验证等于没备份，所以脚本最后会：
#   1) 用 pg_restore -l 读一遍 dump 的目录，确认文件是可恢复的；
#   2) 打印恢复命令，出事时照着敲。

param(
    [string]$BackupDir = "E:\mes-backup",
    [int]$KeepDays = 14,
    [string]$DbName = "mes",
    [string]$DbHost = "localhost",
    [int]$DbPort = 5433,
    [string]$DbUser = "postgres",
    [string]$DbPassword = $(if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { "mes" }),
    [switch]$SkipAttachments
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Find-PgTool([string]$name) {
    $bundled = Join-Path $root "tools\pgsql\bin\$name.exe"
    if (Test-Path $bundled) { return $bundled }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "未找到 $name.exe：请把 PostgreSQL 的 bin 目录加入 PATH，或放到 tools\pgsql\bin 下"
}

$pgDump = Find-PgTool 'pg_dump'
$pgRestore = Find-PgTool 'pg_restore'
$env:PGPASSWORD = $DbPassword

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
$dumpFile = Join-Path $BackupDir "$DbName-$stamp.dump"

Write-Host "[1/4] 备份数据库 $DbName -> $dumpFile" -ForegroundColor Cyan
& $pgDump -h $DbHost -p $DbPort -U $DbUser -d $DbName --format=custom --no-owner --file=$dumpFile
if ($LASTEXITCODE -ne 0) {
    throw "pg_dump 失败（退出码 $LASTEXITCODE）：请检查数据库是否在跑、账号密码是否正确"
}

$dbSize = (Get-Item $dumpFile).Length
if ($dbSize -lt 4096) {
    throw "备份文件只有 $dbSize 字节，明显不对，已中止（请检查库名与权限）"
}
Write-Host ("      完成，{0:N1} KB" -f ($dbSize / 1KB))

Write-Host "[2/4] 校验备份可读" -ForegroundColor Cyan
$toc = & $pgRestore --list $dumpFile 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "pg_restore --list 读不了这个 dump，备份不可用：$toc"
}
$tables = ($toc | Select-String -Pattern ' TABLE DATA ' | Measure-Object).Count
Write-Host "      目录可读，含 $tables 张表的数据段"

Write-Host "[3/4] 打包附件" -ForegroundColor Cyan
$attachDir = Join-Path $root 'data\attachments'
$zipFile = $null
if ($SkipAttachments) {
    Write-Host "      已跳过（-SkipAttachments）"
} elseif (Test-Path $attachDir) {
    $items = Get-ChildItem $attachDir -Force
    if ($items.Count -eq 0) {
        Write-Host "      附件目录是空的，跳过"
    } else {
        $zipFile = Join-Path $BackupDir "attachments-$stamp.zip"
        Compress-Archive -Path (Join-Path $attachDir '*') -DestinationPath $zipFile -Force
        Write-Host ("      完成，{0:N1} KB（{1} 个文件）" -f ((Get-Item $zipFile).Length / 1KB), $items.Count)
    }
} else {
    Write-Host "      没有附件目录，跳过"
}

Write-Host "[4/4] 清理 $KeepDays 天前的旧备份" -ForegroundColor Cyan
$limit = (Get-Date).AddDays(-$KeepDays)
$old = Get-ChildItem $BackupDir -File | Where-Object {
    ($_.Extension -eq '.dump' -or $_.Extension -eq '.zip') -and $_.LastWriteTime -lt $limit
}
if ($old) {
    $old | ForEach-Object {
        Remove-Item $_.FullName -Force
        Write-Host "      删除 $($_.Name)"
    }
} else {
    Write-Host "      没有需要清理的文件"
}

Write-Host ""
Write-Host "备份完成：" -ForegroundColor Green
Write-Host "  数据库  $dumpFile"
if ($zipFile) { Write-Host "  附件    $zipFile" }
Write-Host ""
Write-Host "恢复方式（新库或原库，谨慎执行）：" -ForegroundColor Yellow
Write-Host "  createdb -h $DbHost -p $DbPort -U $DbUser ${DbName}_restore"
Write-Host "  pg_restore -h $DbHost -p $DbPort -U $DbUser -d ${DbName}_restore --no-owner `"$dumpFile`""
if ($zipFile) {
    Write-Host "  附件解压回 <部署目录>\data\attachments"
}
Write-Host ""
Write-Host "提醒：升级 jar 之前先跑这个脚本。Flyway 迁移没有 undo，回滚只能靠这份 dump。"
