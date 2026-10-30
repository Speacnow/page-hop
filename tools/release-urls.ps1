# 生成发布页要用的下载链接
#
#   tools\release-urls.ps1 -Tag v0.9.3
#
# 为什么不直接链到仓库里的 PageHop-latest.apk：
# GitHub 对超过约 10 MB 的文件不给预览，打开是 "can't show files that this big right now"，
# 真正能下载的只有 Raw / 下载按钮。改成 Release 之后，点链接直接落到 Releases 页，
# 那里有绿色的大 "PageHop-vX.Y.Z.apk" 下载按钮，链接也能做成 .../releases/latest/download/名字。

param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [string]$Owner = "Speacnow",
    [string]$Repo = "page-hop",
    [string]$File = "PageHop-latest.apk"
)

# 放到 Release 上的文件名带版本号：下载下来一眼知道是哪一版，
# 也避免"latest 永远同名、浏览器缓存住旧包"
$asset = "PageHop-$Tag.apk"

Write-Host ""
Write-Host "1) 上传（网页版 Releases → Draft a new release）：" -ForegroundColor Cyan
Write-Host "   Tag:    $Tag"
Write-Host "   Title:  PageHop $Tag"
Write-Host "   Asset:  把仓库里的 $File 先复制成 $asset 再拖进去"
Write-Host ""
Write-Host "   cmd 里复制："
Write-Host "     copy $File $asset"
Write-Host ""
Write-Host "2) 发布后可以用的两种链接：" -ForegroundColor Cyan
Write-Host "   直达最新版下载（推荐：点一下就开始下）："
Write-Host "     https://github.com/$Owner/$Repo/releases/latest/download/$asset" -ForegroundColor Green
Write-Host "   落到 Releases 页面（用户能看到所有版本）："
Write-Host "     https://github.com/$Owner/$Repo/releases/latest" -ForegroundColor Green
Write-Host ""
