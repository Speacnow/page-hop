# 看 App 的日志（崩溃、异常、ANR）
#
#   .\log.ps1            抓一段当前日志
#   .\log.ps1 -Follow    实时跟日志 (Ctrl+C 退出)
#   .\log.ps1 -All       不过滤，全部打出来
param(
    [switch]$Follow,
    [switch]$All
)

$ErrorActionPreference = 'Stop'
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'

if ($All) {
    if ($Follow) { & $adb logcat -v time } else { & $adb logcat -d -v time | Select-Object -Last 200 }
    exit 0
}

$appPid = (& $adb shell pidof -s com.pageflow.reader) -join ""
if ([string]::IsNullOrWhiteSpace($appPid)) {
    Write-Host "App 没在运行，先跑 tools\deploy.ps1 启动它。" -ForegroundColor Yellow
    if ($Follow) {
        & $adb logcat -v time AndroidRuntime:E ActivityManager:E System.err:W
    } else {
        & $adb logcat -d -v time AndroidRuntime:E ActivityManager:E System.err:W | Select-Object -Last 120
    }
    exit 0
}

if ($Follow) {
    & $adb logcat --pid $appPid.Trim() -v time
} else {
    & $adb logcat -d --pid $appPid.Trim() -v time | Select-Object -Last 120
}
