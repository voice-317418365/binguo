# 彬果

基于 [Veyra](https://github.com/gybeyond1/Veyra) 二次开发的 Android 短剧客户端。

免登录、无广告、启动即看。内容直连源站，不走中转服务器。

## 与上游的差异

| 项 | 上游 Veyra | 本仓库 |
|---|---|---|
| 登录 | 强制登录才能使用 | **免登录**，直接进首页；登录变成可选 |
| 应用名 | Veyra / Veyra TV | 彬果 / 彬果 TV |
| 图标 | 原版图标 | 苹果 + 播放键 + 字标 |
| 远程服务器 | 支持 SMB / WebDAV / FTP 传输 | 入口已移除 |
| NAS 下载合并 | 支持 | 入口已移除 |
| 私密内容源 | 黄果 / 剧果 / 野果 / 禁果 | 入口已隐藏（红果源保留） |
| 账号管理 | 设置页有入口 | 入口已隐藏（右上角头像仍可用） |
| 外观默认 | 玻璃材质开 | **默认关闭** |
| 热更新 | 官方 GitHub 通道 | **自建静态源**，自签 ECDSA 密钥 |
| 版本更新 | 官方 Release | **自建静态 `latest.json`** |
| 关于页 | 显示项目地址 | 只保留检查更新 |

这些改动集中在 `scripts/brand-patch.py` 里，构建时自动重放，不影响上游代码的其他部分。

## 功能

- 沉浸式刷剧、自动连播、倍速、清晰度切换
- 搜索、分类、排行榜、追更提醒
- 收藏、观看历史、进度记忆
- 下载到本机，离线观看
- 手机 / 平板 / 电视同一套代码（`mobile` / `tv` flavor）
- 外观自定义：配色、圆角、按钮材质、动效

## 构建

### 环境

| 依赖 | 版本 |
|---|---|
| JDK | **25**（AGP 9.4.1 要求，21 不行） |
| Android SDK | Platform `android-37.0` + Build-Tools `37.0.0` |
| Python | 3.8+（构建脚本用） |
| Kotlin 编译器 | 2.2.10（`KOTLIN_HOME` 指向） |

不需要 NDK —— `jniLibs/` 里是预编译好的 `.so`。

### 环境变量

```bash
export ANDROID_HOME=/path/to/Android/Sdk
export KOTLIN_HOME=/path/to/kotlinc
export GRADLE_USER_HOME=/path/to/gradle-home   # 缓存目录，建议独立
export JAVA_HOME=/path/to/jdk-25
```

签名走环境变量（`app/build.gradle` 的 `signingConfigs.release` 读这几个）：

```bash
export CHENGZI_KEYSTORE=/path/to/your.keystore
export CHENGZI_STORE_PASS=your_store_password
export CHENGZI_KEY_PASS=your_key_password
export CHENGZI_KEY_ALIAS=your_alias
```

### 出包

```bash
# 手机版
./gradlew :app:assembleMobileRelease

# 电视版
./gradlew :app:assembleTvRelease
```

产物在 `app/build/outputs/apk/<flavor>/release/`。

Windows 上可以直接用：

```powershell
.\build-mine.ps1                          # 编手机版
.\build-mine.ps1 -Tv                      # 编电视版
.\build-mine.ps1 -VersionName 0.28.1408 -VersionCode 41408 -Publish
```

`-Publish` 会额外生成一份 `Veyra-<版本>.apk` 命名的副本（某些更新分发逻辑依赖这个前缀）。

## 自建更新通道

本仓库把两套更新通道都指向了自建服务器，**需要你自己准备**，否则更新功能不可用。

### 1. APK 更新（换整个包）

客户端在 `UpdateManager.java` 里：

```java
private static final String STATIC_UPDATE = "http://your-server/update/latest.json";
private static final String STATIC_BASE   = "http://your-server/update/";
```

`latest.json` 格式：

```json
{
  "version": "0.28.1408",
  "apk": "Binguo-0.28.1408.apk",
  "sha256": "……64位小写十六进制……",
  "size": 13416244,
  "notes": "更新说明"
}
```

`publish-update.ps1` 可以自动生成这个文件：

```powershell
.\publish-update.ps1 -Notes "修复了xx"
```

注意 `version` 必须严格是三段数字（`\d+\.\d+\.\d+`），客户端用它和本地版本做数值比较——**完全相等时判定"没有新版本"**，所以每次发布都要让版本号真的变大。

### 2. 前端热更新（只换 assets）

`HotUpdateManager.java`：

```java
static final String INDEX = "http://your-server/hot-update/hot-update.json";
```

热更包用 ECDSA P-256 签名，公钥在 `app/src/main/assets/hot-update-public.pem`。本仓库已换成自己的密钥对：

```bash
# 生成密钥对（已经生成过就不必重复）
openssl ecparam -name prime256v1 -genkey -noout -out keys/hot-update-private.pem
openssl ec -in keys/hot-update-private.pem -pubout -out keys/hot-update-public.pem
```

签发：

```bash
python scripts/sign-hot-update.py \
  --private-key keys/hot-update-private.pem \
  --base-url http://your-server/hot-update \
  --revision 1001 \
  --version 2026.11.08.1 \
  --min-version-code 41408 \
  --site app/src/main/assets/site-default.json \
  --theme hot/theme.json \
  --ui-dir hot/ui \
  --out dist/hot-r1
```

`revision` 必须大于 `999`（内置资源版本号），否则客户端直接忽略。

热更新只能改 `assets/` 里的东西（html/css/js/图片/字体），改 Java 必须重新出包。

**私钥不要提交进仓库**，`keys/` 已在 `.gitignore` 里。

## 目录结构

```
app/                     Android 客户端
  src/main/java/         业务逻辑（播放、下载、账号、设备联动）
  src/main/assets/       WebView 前端（app.* 手机 / tv.* 电视）
  src/main/cpp/          合并引擎 C 源码（so 已预编译进 jniLibs）
  src/main/res/          图标与资源
server/                  后端（Node.js，本仓库默认不启用）
  hongguo-signer/        签名器（Go）
desktop/                 Windows 桌面端（Electron + React）
scripts/
  brand-patch.py         品牌补丁（免登录、文案、隐藏入口）
  sign-hot-update.py     热更包签发
docs/                    部署与改造文档
```

## 说明

本项目仅供个人学习与技术研究，不提供、不存储、不传播任何视频内容。所有内容均来自源站，版权归原权利方所有。

二次开发遵循上游的 **GPL-3.0** 许可。使用本代码产生的任何后果由使用者自行承担，请勿用于商业用途或对外分发。

原项目：[gybeyond1/Veyra](https://github.com/gybeyond1/Veyra)

## 许可

GPL-3.0，详见 [LICENSE](LICENSE)。
