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
| **红果账号** | 无 | **扫码登录，同步官方观看历史 / 收藏 / 预约，播放进度回传** |
| **播放器倍速** | 0.5 / 0.75 / 1 / 1.25 / 2 / 3 | **新增 1.5×** |
| 外观默认 | 玻璃材质开 | **默认关闭** |
| 热更新 | 官方 GitHub 通道 | **自建静态源**，自签 ECDSA 密钥 |
| 版本更新 | 官方 Release | **自建静态 `latest.json`**，GitHub 兜底指向本仓库 |
| 关于页 | 项目地址 + 检查更新 | 版本号、检查更新、清除缓存（显示占用）、项目地址、赞助 |

这些改动集中在 `scripts/brand-patch.py` 里，构建时自动重放（幂等，已打过就跳过），`build-mine.ps1` 每次编译前都会跑一遍。当前包含：

| 补丁 | 作用 |
|---|---|
| 免登录 | 去掉启动时的强制登录跳转 |
| 文案 | 应用内 Veyra → 彬果（6 处） |
| 账号管理 | 隐藏设置页入口 |
| 外观 | 玻璃材质默认关闭 |
| 私密源 | 隐藏「私密内容源」「黄果网络通道」 |
| 我的页 | 隐藏登录提示按钮 |

跑完会打印每条规则的命中情况。`git rebase` 上游新版后看这份报告：哪条报"未找到"就说明上游改了那块代码，改一行正则即可。

> 关于弹窗、红果账号、我的页四个 tab 这些结构改动较大，直接写在源码里（不经过补丁脚本），rebase 时如果冲突需要手工合并。

## 功能

- 沉浸式刷剧、自动连播、倍速（含 **1.5×**）、清晰度切换
- 搜索、分类、排行榜、追更提醒
- 收藏、观看历史、进度记忆
- **红果账号扫码登录**：同步官方观看历史 / 收藏 / 预约，播放时把进度回传
- 下载到本机，离线观看
- 手机 / 平板 / 电视同一套代码（`mobile` / `tv` flavor）
- 外观自定义：配色、圆角、按钮材质、动效
- 设置 → 关于：版本号、自建更新检查、清除缓存（显示当前占用）

## 红果账号同步

设置 → 红果账号。登录后，「我的」页的观看历史 / 收藏 / 预约都直接读账号云端数据，不再只看本机记录。

### 扫码为什么走「存相册」这条路

红果的网页登录页对第三方整体封死了：

```
GET hongguoduanju.com/passport/web/login/      → error_code: 16（该应用无权限）
GET hongguoduanju.com/passport/web/sms/send_code/ → 同样 16
GET hongguoduanju.com/passport/web/get_qrcode/  → ✅ 正常返回二维码
```

只有扫码接口是放行的。而手机端没法自己扫自己，所以流程是：

```
App 生成二维码 → 「保存到相册」
                    ↓
红果短剧 App → 扫一扫 → 下方「相册」→ 选中刚存的那张图 → 确认
                    ↓
App 轮询到登录态 → 完成（保存后会自动拉起红果 App）
```

### 用到的接口

| 用途 | 接口 |
|---|---|
| 二维码 / 轮询 | `hongguoduanju.com/passport/web/get_qrcode|check_qrconnect/` |
| 观看历史 | `read_history/list/v1/?book_type=2&count=60` |
| 收藏列表 | `bookshelf/video/info/v1/` |
| 收藏增删 | `bookshelf/video/update/v1/` |
| 预约 / 追更 | `user/subscribe/list/v0/?is_online=true|false` |
| 进度回传 | `read_progress/upload/v1/`（POST） |

账号接口的 host 和播放接口不同：

| 用途 | host | aid |
|---|---|---|
| 播放 / 内容 | `api5-normal-sinfonline**b**.fqnovel.com` | `8662` |
| 账号 / 历史 / 收藏 | `api5-normal-sinfonline**a**.fqnovel.com` | `8662` |

**aid 必须是 8662**。用红果的 `513755` 能拿到账号资料和收藏，但观看历史恒定返回 `101112 READ_HISTORY_GET_ERROR`，很容易误判成"这个账号没有历史"。

### 三个踩过的坑

**1. `read_history` 不是 `read_progress`**

`read_progress/list` 是"阅读进度"，混着小说和视频，只在特定场景回写（实测只有 20 条）；`read_history/list` 才是红果 App「我的 → 历史」那份数据，一次 130+ 条，直接带书名、封面、集号、集数和播放位置，不用再逐条查详情。

`book_type=2` 必须显式传，不传默认按 0（小说）筛，只剩一条。

**2. 封面是 HEIC**

`multi-detail` 返回的 `thumb_url` 是 `~tplv-noop.heic`，Android WebView 解不了 HEIC，下载成功也显示不出来。改用 `thumb_uri` 拼公开 CDN 的 JPEG：

```
https://p6-novel.byteimg.com/{thumb_uri}~tplv-shrink:640:0.image
```

`read_history` 自己返回的 `thumb_url` 就是 JPEG，不受影响。

**3. 签名 URL 里的 `%2B`**

带签名的图片地址里有 `x-signature=...%2B...`。OkHttp 的 `HttpUrl` 解析时会把 `%2B` 规范化成字面 `+`，服务器当空格处理 → 签名失效 → 403。所以封面下载走 `HttpURLConnection`（按字符串原样发）。

### 进度回传的保守设定

- 未登录跳过（不替游客写数据）
- 位置为 0 不报（免得把红果那边的记录改成"未开始"）
- 同一集 3 分钟内只报一次，换集立刻报
- 后台线程执行，失败只记日志

### 限制

- **红果网页版的观看记录不会同步**：网页版没有登录入口，记录只存在浏览器 localStorage
- 红果和番茄小说共用字节的阅读账号体系，历史里可能混入非短剧内容
- 非官方客户端调账号接口有风控风险，账号在做金币提现的话建议别用

### 凭据怎么存

`SecureStore.java`：Android Keystore 生成 AES-256 密钥，AES/GCM 加密后写 SharedPreferences。密钥不出芯片，文件被拷走也解不开。**cookie 不上传任何服务器**，所以这个功能对服务端零依赖。

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
.\build-mine.ps1 -VersionName 0.28.1430 -VersionCode 41430 -Publish
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
  "version": "0.28.1430",
  "apk": "Binguo-0.28.1430.apk",
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

### 检查顺序与 GitHub 兜底

```
静态源 latest.json  →  后端（配了才问）→  GitHub Releases
```

静态源拿到结果就直接返回，不会三条腿混着用（版本号不同步会出现"提示更新到旧版"）。

GitHub 兜底指向本仓库，不是上游（上游包签名不同，下载完装不进去）：

```java
private static final boolean USE_GITHUB_FALLBACK = true;
private static final String RELEASE_API  = "https://api.github.com/repos/voice-317418365/binguo/releases/latest";
private static final String RELEASE_BASE = "https://github.com/voice-317418365/binguo/releases/download/";
```

发 Release 时：

- tag 用 `v<版本>` 格式（如 `v0.28.1430`）
- asset 文件名要和静态源一致（`Binguo-0.28.1430.apk`），下载时文件名是从 URL 末段推出来的
- 仓库还没发过 Release 时 API 返回 404，客户端直接当作"已是最新"，不报错

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
  --min-version-code 41430 \
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
