param(
    [string]$Apk = "",
    [string]$Serial = ""
)

# Install an APK to phone / emulator.
#   .\install.ps1
#   .\install.ps1 -Apk D:\x.apk
#   .\install.ps1 -Serial 10CF421R610004T
#
# Two install routes run inside a background job while the main thread
# taps away the vivo "security guard" confirmation dialog:
#   1) push to /data/local/tmp then pm install
#   2) fallback to adb install
#
# Dialog handling: wake + unlock first, then tap by node bounds from the UI
# tree; if the tree is unreadable, tap by screen fraction.

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'

if ([string]::IsNullOrWhiteSpace($Apk)) {
    $Apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
}
if (-not (Test-Path $Apk)) {
    Write-Host "APK not found: $Apk" -ForegroundColor Red
    exit 1
}
$Apk = (Resolve-Path $Apk).Path

if ([string]::IsNullOrWhiteSpace($Serial)) {
    $online = @(& $adb devices 2>$null | Select-String -Pattern '\sdevice$')
    if ($online.Count -gt 1) {
        Write-Host "More than one device, pass -Serial:" -ForegroundColor Yellow
        & $adb devices
        exit 1
    }
    if ($online.Count -eq 1) { $Serial = ($online[0].Line -split '\s+')[0] }
}
if ([string]::IsNullOrWhiteSpace($Serial)) {
    Write-Host "No device. Connect the phone (USB debugging) or start an emulator." -ForegroundColor Red
    exit 1
}

$sArg = @('-s', $Serial)
$leaf = Split-Path -Leaf $Apk
Write-Host "== install: $leaf -> $Serial ==" -ForegroundColor Cyan

$null = & $adb @sArg shell input keyevent KEYCODE_WAKEUP 2>$null
$null = & $adb @sArg shell wm dismiss-keyguard 2>$null

$remote = '/data/local/tmp/pagehop-install.apk'
$job = Start-Job -ScriptBlock {
    param($adbPath, $apkPath, $serialArgs, $remotePath)
    $out = @()
    $out += & $adbPath @serialArgs push $apkPath $remotePath 2>&1
    if (($out -join "`n") -match 'pushed|skipped') {
        $out += & $adbPath @serialArgs shell pm install -r -t $remotePath 2>&1
    }
    if (($out -join "`n") -notmatch 'Success') {
        $out += & $adbPath @serialArgs install -r $apkPath 2>&1
    }
    $out
} -ArgumentList $adb, $Apk, $sArg, $remote

function Get-FocusWindow {
    $f = & $adb @sArg shell dumpsys window 2>$null | Select-String -Pattern 'mCurrentFocus' | Select-Object -First 1
    if ($f) { return ($f.Line).Trim() }
    return 'unknown'
}

function Tap-XY([int]$x, [int]$y) {
    $null = & $adb @sArg shell input tap $x $y 2>$null
}

function Tap-Fraction([double]$fx, [double]$fy) {
    $size = & $adb @sArg shell wm size 2>$null | Select-String -Pattern '(\d+)x(\d+)' | Select-Object -First 1
    if ($size -and $size.Line -match '(\d+)x(\d+)') {
        Tap-XY ([int]($Matches[1] * $fx)) ([int]($Matches[2] * $fy))
    }
}

$deadline = (Get-Date).AddSeconds(120)
$blind = 0
$tappedAny = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 800
    if ($job.State -ne 'Running') { break }

    $uiFile = Join-Path $env:TEMP '_pf_ui.xml'
    Remove-Item $uiFile -ErrorAction SilentlyContinue
    $null = & $adb @sArg shell "rm -f /data/local/tmp/_pf_ui.xml" 2>$null
    $null = & $adb @sArg shell uiautomator dump /data/local/tmp/_pf_ui.xml 2>$null
    $null = & $adb @sArg pull /data/local/tmp/_pf_ui.xml $uiFile 2>$null

    $tapped = $false
    if (Test-Path $uiFile) {
        $ui = $null
        try { $ui = [xml](Get-Content $uiFile -Encoding UTF8 -Raw) } catch { $ui = $null }
        if ($null -ne $ui) {
            $btn = $ui.SelectNodes("//node[contains(@text,'继续安装') or @text='安装' or contains(@text,'仍然安装')]")
            if ($btn.Count -gt 0) {
                $cb = $ui.SelectNodes("//node[@class='android.widget.CheckBox']")
                if ($cb.Count -eq 0) { $cb = $ui.SelectNodes("//node[contains(@text,'已了解')]") }
                if ($cb.Count -gt 0 -and $cb[0].checked -ne 'true') {
                    if ($cb[0].bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                        $cx = [int]((([int]$Matches[1] + [int]$Matches[3]) / 2))
                        $cy = [int]((([int]$Matches[2] + [int]$Matches[4]) / 2))
                        Tap-XY $cx $cy
                        Start-Sleep -Milliseconds 500
                    }
                }
                if ($btn[0].bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                    $bx = [int]((([int]$Matches[1] + [int]$Matches[3]) / 2))
                    $by = [int]((([int]$Matches[2] + [int]$Matches[4]) / 2))
                    Tap-XY $bx $by
                    $tapped = $true
                    $tappedAny = $true
                    Write-Host "tapped install dialog" -ForegroundColor Green
                }
            }
        }
    }

    if (-not $tapped) {
        $f = Get-FocusWindow
        if ($f -match 'packageinstaller|installer') {
            $blind++
            if ($blind -le 6) {
                # 两套候选位置轮流试：不同 ROM/弹窗版本高度不一样
                if ($blind % 2 -eq 1) {
                    Tap-Fraction 0.50 0.875 | Out-Null
                    Start-Sleep -Milliseconds 500
                    Tap-Fraction 0.50 0.936 | Out-Null
                } else {
                    Tap-Fraction 0.20 0.615 | Out-Null
                    Start-Sleep -Milliseconds 500
                    Tap-Fraction 0.43 0.673 | Out-Null
                }
                Write-Host "  blind tap #$blind (dialog controls unreadable)" -ForegroundColor DarkGray
                $tappedAny = $true
            }
        }
    }
    if ($tapped) { Start-Sleep -Milliseconds 900 }
}

if ($job.State -eq 'Running') { $null = Wait-Job $job -Timeout 60 }
$out = ((Receive-Job $job -AutoRemoveJob -Wait) -join "`n")
Write-Host $out

if ($out -match 'Success') {
    if ($tappedAny) { Write-Host "installed (dialog auto-dismissed)" -ForegroundColor Green }
    else { Write-Host "installed (no dialog)" -ForegroundColor Green }
    exit 0
}
Write-Host "install failed. focus: $(Get-FocusWindow)" -ForegroundColor Red
exit 1
