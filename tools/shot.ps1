# 抓一张手机截图到 shots\ 目录
#
#   .\shot.ps1              自动按时间命名
#   .\shot.ps1 -Name map    存成 shots\map.png
param(
    [string]$Name = ""
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'

if ([string]::IsNullOrWhiteSpace($Name)) {
    $Name = Get-Date -Format 'yyyyMMdd-HHmmss'
}

$dir = Join-Path $root 'shots'
New-Item -ItemType Directory $dir -Force | Out-Null
$out = Join-Path $dir "$Name.png"

# 二进制重定向必须走 cmd，PowerShell 的 > 会把 PNG 写坏
cmd /c "`"$adb`" exec-out screencap -p > `"$out`""
if (-not (Test-Path $out) -or (Get-Item $out).Length -lt 1000) {
    Write-Host "截图失败，设备连上了吗？" -ForegroundColor Red
    exit 1
}
Write-Host $out
