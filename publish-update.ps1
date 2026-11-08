# 生成静态更新清单 latest.json，并打印上传命令
# 用法:
#   .\publish-update.ps1                          # 用 dist 里最新的 Binguo APK
#   .\publish-update.ps1 -Notes "修复xx"           # 带更新说明
#   .\publish-update.ps1 -Apk "dist\Binguo-0.28.1408.apk"

param(
    [string]$Apk,
    [string]$Notes = "",
    [string]$Server = "root@101.201.32.104",
    [string]$RemoteDir = "/opt/veyra/nginx/webroot/update"
)

$ErrorActionPreference = 'Stop'

if (-not $Apk) {
    $Apk = Get-ChildItem "E:\code\Veyra\dist\Binguo-*.apk" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Apk -or -not (Test-Path $Apk)) { throw "找不到 APK" }

$name = Split-Path $Apk -Leaf
$m = [regex]::Match($name, '(\d+\.\d+\.\d+)')
if (-not $m.Success) { throw "文件名里没有版本号: $name" }
$version = $m.Groups[1].Value

# 客户端会按 "是否含 tv 记号" 判渠道，手机包不能带 TV
if ($name -match '(?i)(^|[-_.])tv([-_.]|$)') {
    throw "这是电视包，客户端只支持手机渠道的静态更新"
}

$size = (Get-Item $Apk).Length
$sha = (Get-FileHash $Apk -Algorithm SHA256).Hash.ToLowerInvariant()

$manifest = [ordered]@{
    version = $version
    apk     = $name
    sha256  = $sha
    size    = $size
    notes   = $Notes
}

$out = "E:\code\Veyra\dist\latest.json"
$json = $manifest | ConvertTo-Json
Set-Content -Path $out -Value $json -Encoding utf8NoBOM

Write-Output "=== latest.json ==="
Write-Output $json
Write-Output ""
Write-Output "=== 上传到服务器 ==="
Write-Output "ssh $Server `"mkdir -p $RemoteDir`""
Write-Output "scp `"$Apk`" `"${Server}:${RemoteDir}/`""
Write-Output "scp `"$out`" `"${Server}:${RemoteDir}/`""
Write-Output ""
Write-Output "=== 验证 ==="
Write-Output "curl -s http://101.201.32.104/update/latest.json"
Write-Output "curl -I http://101.201.32.104/update/$name"
