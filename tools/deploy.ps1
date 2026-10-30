# 一键编译 + 安装 + 启动到已连接的手机
#
#   .\deploy.ps1              编译、安装、启动
#   .\deploy.ps1 -NoBuild     直接用上次的 APK（跳过编译，快一点）
#   .\deploy.ps1 -Log         启动后自动跟一段日志
#   .\deploy.ps1 -Serial 10CF421R610004T   指定设备（默认自动选真机，不用带；无线调试的话先跑 wifi-adb.ps1）
#
# 安装环节会自动处理 vivo/iQOO 的「安全守护」确认弹窗，无需人工点按。
param(
    [switch]$NoBuild,
    [switch]$Log,
    [string]$Serial = ""
)

# adb 会往 stderr 写进度，Stop 模式会误判为失败
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    Write-Host "找不到 adb: $adb" -ForegroundColor Red
    Write-Host "请在 Android Studio 的 SDK Manager 里确认已安装 Android SDK Platform-Tools。"
    exit 1
}

$devices = @(& $adb devices 2>$null | Select-String -Pattern '\sdevice$')
if ($devices.Count -eq 0) {
    Write-Host "没有检测到设备。" -ForegroundColor Yellow
    Write-Host "  无线调试：先跑 .\wifi-adb.ps1（手机会自动发现，连上再回来跑这个）"
    Write-Host "  用数据线：设置 -> 系统管理 -> 开发者选项 -> 打开「USB 调试」，弹窗里勾「一律允许」"
    Write-Host "  若装了 vivo/iQOO 手机助手，先关掉它占用的 ADB"
    exit 1
}

# 序列号里带空格的（无线调试的 adb-XXXX (2)._adb-tls-connect._tcp）不能按空格切
function Get-Serial([string]$line) {
    return (($line -replace '\s+device\s*$', '') -replace '^\s+', '').Trim()
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    # 同时连着手机和模拟器时优先用真机
    $physical = @($devices | Where-Object { (Get-Serial $_.Line) -notmatch '^emulator-' })
    if ($physical.Count -ge 1) { $Serial = Get-Serial $physical[0].Line }
    else { $Serial = Get-Serial $devices[0].Line }
    if ($devices.Count -gt 1) {
        Write-Host "检测到多个设备，自动选了 $Serial（要换用 -Serial 指定）" -ForegroundColor Yellow
    }
}
$sArg = @()
if (-not [string]::IsNullOrWhiteSpace($Serial)) { $sArg = @('-s', $Serial) }

if (-not $NoBuild) {
    Write-Host "== 编译 ==" -ForegroundColor Cyan
    & (Join-Path $root 'gradlew.bat') -p $root assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) {
        Write-Host "编译失败，先看上面的报错。" -ForegroundColor Red
        exit 1
    }
}

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) {
    Write-Host "找不到 APK: $apk" -ForegroundColor Red
    exit 1
}

Write-Host "== 安装 ==" -ForegroundColor Cyan
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'install.ps1') -Apk $apk -Serial $Serial
if ($LASTEXITCODE -ne 0) {
    Write-Host "安装失败。若提示 INSTALL_FAILED_USER_RESTRICTED，去开发者选项打开「USB 安装」。" -ForegroundColor Red
    exit 1
}

Write-Host "== 启动 ==" -ForegroundColor Cyan
& $adb @sArg shell am start -n com.pageflow.reader/.MainActivity 2>$null | Out-Null

if ($Log) {
    Write-Host "== 日志 (Ctrl+C 退出) ==" -ForegroundColor Cyan
    & $adb @sArg logcat -v time AndroidRuntime:E ActivityManager:E System.err:W
}
