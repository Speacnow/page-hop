# 无线调试连接助手（不需要 Android Studio，也不需要数据线）
#
#   .\wifi-adb.ps1                        自动发现并连接（手机开着「无线调试」时最常用）
#   .\wifi-adb.ps1 -Address 192.168.0.106:37123   手机屏幕上显示的 IP:端口，直接连
#   .\wifi-adb.ps1 -Pair 192.168.0.106:41234 -Code 123456   首次配对（换电脑/重置授权时）
#   .\wifi-adb.ps1 -Reset                 断掉现有连接再重连
#
# 配对成功一次以后，密钥存在手机里，以后只要开着「无线调试」就能直接连，
# 端口每次都会变，但脚本会通过 mDNS 自己找到它。

param(
    [string]$Address = "",
    [string]$Pair = "",
    [string]$Code = "",
    [switch]$Reset,
    [int]$WaitSeconds = 20
)

$ErrorActionPreference = 'Continue'

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    Write-Host "找不到 adb：$adb" -ForegroundColor Red
    Write-Host "装个 Android SDK platform-tools，或设置 ANDROID_HOME 环境变量。" -ForegroundColor Yellow
    exit 1
}

function Get-TlsServices {
    # 名字里可能带空格和括号，所以名字用 (.+?) 懒匹配：
    #   adb-10CF421R610004T-LERvzf (2)\t_adb-tls-connect._tcp\t192.168.0.100:36077
    $out = & $adb mdns services 2>$null
    $rows = @()
    foreach ($line in $out) {
        if ($line -match '^\s*(.+?)\s+_adb-tls-connect\._tcp\s+(\S+:\d+)\s*$') {
            $ep = $Matches[2]
            if (-not ($rows | Where-Object { $_.Endpoint -eq $ep })) {
                $rows += [pscustomobject]@{ Name = $Matches[1]; Endpoint = $ep }
            }
        }
    }
    return $rows
}

if ($Pair -ne "" -and $Code -ne "") {
    Write-Host "== 配对 $Pair ==" -ForegroundColor Cyan
    & $adb pair $Pair $Code
    Start-Sleep -Seconds 1
}

if ($Reset) {
    Write-Host "== 断开现有连接 ==" -ForegroundColor Cyan
    & $adb disconnect | Out-Null
} elseif ($Address -eq "" -and -not $Pair) {
    # 已经连着就别再 connect 一次，否则 adb devices 里同一台手机会出现两条
    $already = @(& $adb devices 2>$null | Select-String -Pattern '\sdevice$')
    if ($already.Count -gt 0) {
        Write-Host "== 已经连着 ==" -ForegroundColor Green
        $already | ForEach-Object { Write-Host $_.Line -ForegroundColor Green }
        Write-Host ""
        Write-Host "直接编译安装：.\deploy.ps1（想重连就加 -Reset）" -ForegroundColor Green
        exit 0
    }
}

if ($Address -ne "") {
    Write-Host "== 连接 $Address ==" -ForegroundColor Cyan
    & $adb connect $Address
} else {
    Write-Host "== 用 mDNS 找手机（最多等 $WaitSeconds 秒）==" -ForegroundColor Cyan
    $found = @()
    for ($i = 0; $i -lt $WaitSeconds; $i += 2) {
        $found = Get-TlsServices
        if ($found.Count -gt 0) { break }
        Start-Sleep -Seconds 2
    }
    if ($found.Count -eq 0) {
        Write-Host "没发现设备。检查这几件事：" -ForegroundColor Yellow
        Write-Host "  1) 手机「设置 - 开发者选项 - 无线调试」是开着的" -ForegroundColor Yellow
        Write-Host "  2) 手机和电脑在同一个 Wi-Fi，手机没熄屏（熄屏后 Wi-Fi 会休眠）" -ForegroundColor Yellow
        Write-Host "  3) 手机那页上有「IP 地址和端口」，把它填进来：" -ForegroundColor Yellow
        Write-Host "     .\wifi-adb.ps1 -Address 192.168.0.106:37123" -ForegroundColor Yellow
        Write-Host "  4) 路由器开了「AP 隔离」的话，两台设备互相看不见" -ForegroundColor Yellow
        exit 1
    }
    # 同一个手机常常广播两条（名字带 (2) 的和不带），连上一条就够了
    foreach ($d in $found) {
        Write-Host "== 连接 $($d.Endpoint)（$($d.Name)）==" -ForegroundColor Cyan
        & $adb connect $d.Endpoint
        Start-Sleep -Seconds 2
        if (@(& $adb devices 2>$null | Select-String -Pattern '\sdevice$').Count -gt 0) { break }
    }
}

Start-Sleep -Seconds 1
Write-Host ""
$devices = & $adb devices
($devices | Select-String -Pattern '\sdevice$') | ForEach-Object { Write-Host $_.Line -ForegroundColor Green }
$online = @($devices | Select-String -Pattern '\sdevice$')
if ($online.Count -eq 0) {
    Write-Host "还没连上。把手机「无线调试」页上的 IP:端口发我，用 -Address 直接连。" -ForegroundColor Yellow
    exit 1
}
Write-Host ""
Write-Host "好了，可以编译安装了：.\deploy.ps1" -ForegroundColor Green
