# Veyra 本地编译：一键出包
# 用法:
#   .\build-mine.ps1                               # 编手机版，用 build.gradle 里的版本号
#   .\build-mine.ps1 -Tv                            # 编电视版
#   .\build-mine.ps1 -VersionName 0.28.1401 -VersionCode 41401   # 顺带改版本号

param(
    [switch]$Tv,
    [string]$VersionName,
    [int]$VersionCode = 0
)

$ErrorActionPreference = 'Stop'

$env:ANDROID_HOME      = "E:\Android\Sdk"
$env:ANDROID_SDK_ROOT  = "E:\Android\Sdk"
$env:KOTLIN_HOME       = "E:\Android\Studio\plugins\Kotlin\kotlinc"
$env:GRADLE_USER_HOME  = "E:\gradle-home"
$env:JAVA_HOME         = "E:\tools\jdk-25"

# 签名（build.gradle 从这里读，不设就落到占位符路径然后编译失败）
$env:CHENGZI_KEYSTORE   = "E:\code_project\2026-11-08-12-39-47\patch\veyra-mod.keystore"
$env:CHENGZI_STORE_PASS = "veyra123456"
$env:CHENGZI_KEY_PASS   = "veyra123456"
$env:CHENGZI_KEY_ALIAS  = "veyra"

Set-Location "E:\code\Veyra"

# 改版本号（直接改 app/build.gradle，会留在 git 工作区等你提交）
if ($VersionName -or $VersionCode -gt 0) {
    $gb = "E:\code\Veyra\app\build.gradle"
    $raw = Get-Content $gb -Raw
    if ($VersionName) {
        $raw = $raw -replace 'versionName\s+"[^"]*"', "versionName `"$VersionName`""
    }
    if ($VersionCode -gt 0) {
        $raw = $raw -replace 'versionCode\s+\d+', "versionCode $VersionCode"
    }
    Set-Content -Path $gb -Value $raw -Encoding UTF8 -NoNewline
    Write-Output "已改版本号 -> $VersionName / $VersionCode"
}

$flavor = if ($Tv) { "Tv" } else { "Mobile" }
$task   = ":app:assemble${flavor}Release"

Write-Output "编译 $task ..."
& .\gradlew.bat $task --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw "编译失败，看上面的输出" }

$variant = if ($Tv) { "tv" } else { "mobile" }
$outDir  = "E:\code\Veyra\app\build\outputs\apk\$variant\release"
$apk     = Join-Path $outDir "app-$variant-release.apk"

$meta = Get-Content (Join-Path $outDir "output-metadata.json") -Raw | ConvertFrom-Json
$el = $meta.elements[0]
Write-Output ""
Write-Output "版本: $($el.versionName)  (versionCode $($el.versionCode))"
Write-Output "包名: $($meta.applicationId)"

& "E:\Android\Sdk\build-tools\37.0.0\apksigner.bat" verify --print-certs $apk 2>&1 |
    Select-String "certificate DN" | Select-Object -First 1

# 复制到 dist，文件名符合服务端更新模块的正则 Veyra(-TV)?-\d+\.\d+\.\d+\.apk
$dist = "E:\code\Veyra\dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$suffix = if ($Tv) { "-TV" } else { "" }
$final  = Join-Path $dist "Veyra$suffix-$($el.versionName).apk"
Copy-Item $apk $final -Force

$size = [math]::Round((Get-Item $final).Length / 1MB, 1)
$sha  = (Get-FileHash $final -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output ""
Write-Output "产物:   $final"
Write-Output "大小:   $size MB"
Write-Output "SHA256: $sha"
