# 彬果

> 当前版本 **0.28.1435**（versionCode 41435）· 变更记录见 [CHANGELOG.md](CHANGELOG.md)

基于 [Veyra](https://github.com/gybeyond1/Veyra) 二次开发的 Android 短剧客户端。

免登录、无广告、启动即看。内容直连源站，不走中转服务器。

## 上游基线

本仓库从上游 `v0.28.12` 的 zip 快照重新 `git init` 起步，**与上游不共享 commit**，所以不能用 `merge-base` / `merge` / `rebase` 判断差异，只能靠 GitHub compare API。基线分两条，各管一件事：

| 基线 | 值 | 用途 |
|---|---|---|
| 源码快照 | `v0.28.12`（`89932e67a0`） | git 里的分叉起点，只用来算「我方改了哪些文件」 |
| **上游对齐基线** | **`v0.28.18`** | 已经逐条搬完的版本，巡检的 compare 从这里起算 |

`0.28.13`~`0.28.18` 已全部对齐（本机播放网关、禁果源、六源体检、直连优先取流、设备号缓存、预热实例池、点开即播、0.3s 起播门槛、进程级缓存与 `PlayerKeeper`）。`0.28.19`~`0.28.26` 是**有意跳过**，不是漏合并：

| 版本 | 内容 | 不跟的理由 |
|---|---|---|
| `0.28.19` | 推荐卡模糊遮罩 | 纯视觉，我方卡片样式已单独改过 |
| `0.28.20` | 接黄豆/帝果、下架野果与禁果 | 与「隐藏黄果入口、保留现有私密源」冲突 |
| `0.28.21` | 电视包不内置 arm64 so | 等效已满足：`jniLibs` 只挂在 mobile flavor 下 |
| `0.28.22` | 私密源改手机端直接取数 | 我方走 `LocalPlaybackGateway` 本机网关，实现不同但目标一致 |
| `0.28.23`~`0.28.26` | 帝果分集 / 防盗链 / 取流兜底 | 未接帝果源，搬过来是死代码 |

`docs/upstream-watch/baseline.json` 是唯一事实源（巡检报告和 `state.json` 是运行产物，不入库）。以后策略变了改这个文件即可，`scripts/upstream-watch.py` 会自动用新的 `synced_tag` 起算 compare；临时复盘可以用 `--since v0.28.10` 覆盖，不写回文件。

## 与上游的差异

| 项 | 上游 Veyra | 本仓库 |
|---|---|---|
| 登录 | 强制登录才能使用 | **免登录**，直接进首页；登录变成可选 |
| 应用名 | Veyra / Veyra TV | 彬果 / 彬果 TV |
| 图标 | 原版图标 | 苹果 + 播放键 + 字标 |
| 远程服务器 | 支持 SMB / WebDAV / FTP 传输 | 下载卡片上的「传到服务器」按钮已移除（设置页配置与「下载到远程服务器」入口保留） |
| NAS 下载合并 | 支持 | 入口已移除 |
| 私密内容源 | 黄果 / 剧果 / 野果 / 禁果 | 入口已隐藏（红果源保留） |
| 账号管理 | 设置页有入口 | 入口已隐藏（右上角头像仍可用） |
| **红果账号** | 无 | **扫码登录，同步官方观看历史 / 收藏 / 预约，播放进度回传** |
| **官方弹幕** | 无 | **只读接入红果官方弹幕**：匿名、不发送、可开关与调样式 |
| **榜单类别** | 官方榜单 | 新增《最新上架》《最高热度》《最多收藏》三个类别 |
| **上游同步基线** | 主线 `v0.28.x` | **已对齐到 `v0.28.18`**；`0.28.19`~`0.28.26` 明确不跟，理由见 `docs/upstream-watch/baseline.json` |
| **播放性能** | 起播缓冲 2.5s | 合入上游 0.28.15~0.28.18：直连优先取流、起播门槛 0.3s、详情页预热、播放器跨页复用 |
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
- 搜索、分类、排行榜、追更提醒；「全部类型」里另有《最新上架》《最高热度》《最多收藏》
- 收藏、观看历史、进度记忆
- **红果官方弹幕（只读）**：匿名读取、按 30 秒窗口对齐、可开关与调整样式，不发送弹幕
- **红果账号扫码登录**：同步官方观看历史 / 收藏 / 预约，播放时把进度回传
- 批量下载到本机：不限制集数，实时进度与取消、单集取流失败自动跳过；下载完成后可「合并成 1 个视频」把分集拼成单个 mp4
- 手机 / 平板 / 电视同一套代码（`mobile` / `tv` flavor）
- 外观自定义：配色、圆角、按钮材质、动效
- 设置 → 关于：版本号、自建更新检查、清除缓存（显示当前占用）、项目地址、赞助

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

不需要 NDK —— 预编译好的 `.so` 放在 `app/src/mobile/jniLibs/arm64-v8a/`，只打进手机版；电视包是纯 Java 包，任何 ABI 都能装。

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
.\build-mine.ps1 -VersionName 0.28.1435 -VersionCode 41435 -Publish
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
  "version": "0.28.1435",
  "apk": "Binguo-0.28.1435.apk",
  "sha256": "……64位小写十六进制……",
  "size": 13622738,
  "notes": "一句话更新说明（会显示在更新弹窗里，最多 1200 字）"
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

- tag **必须**用 `v<版本>` 格式（如 `v0.28.1435`）。**不要**写成 `Binguo-0.28.1435`：`manifestOf()` 只剥首字母 `v`，紧接着 `parseVersion()` 要求三段纯数字，带 `Binguo-` 前缀会解析失败，客户端把这次 Release 当成「没有新版本」。1431/1432 的历史 Release 就是这么失效的，静态源正常时看不出来
- asset 文件名要和静态源一致（`Binguo-0.28.1435.apk`），下载时文件名是从 URL 末段推出来的
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
  --min-version-code 41433 \
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
  src/mobile/jniLibs/    合并引擎预编译 so（仅手机版打包）
  src/main/res/          图标与资源
server/                  后端（Node.js，本仓库默认不启用）
  hongguo-signer/        签名器（Go）
desktop/                 Windows 桌面端（Electron + React）
scripts/
  brand-patch.py         品牌补丁（免登录、文案、隐藏入口）
  sign-hot-update.py     热更包签发
docs/upstream-watch/     上游巡检：baseline.json（基线配置）+ 每日报告/state（不入库）
```

## 更新日志

完整变更记录见 [CHANGELOG.md](CHANGELOG.md)。当前版本 **0.28.1435**：

- **红果取流直连优先**：`HongguoRepository.resolveStream` 先本机签名拿 CENC 直链，6 秒拿不到才落回后端转封装，连续两次失败冷却 5 分钟。首播不再等后端把整集转完，实测约 2 秒出画；下载与合并链路已回归验证
- **上游对齐基线改为 `v0.28.18`**：巡检不再把早已合并的 `0.28.13`~`0.28.18` 天天重报一遍，累计「未合并」从 21 提交 / 41 文件降到 11 提交 / 26 文件；不跟的版本逐条写明理由

### 0.28.1434 及之前的累计改动

- **合并视频衔接点花屏 / 整片发卡**：多集合并改用纯 Java 的 `Mp4Concat` 直接重写容器（按集切 chunk、重排 stbl 四表），不再走会把时间轴压成「15 帧 100µs + 1 帧 640ms」的 native 封包循环；合入前校验画幅，比例不一致直接拒绝
- **单集发卡**：分片先字节串成 `full.ts` 再整体重封装一次，片边界不再逐个重新对齐
- **本地播放**：新增断点续播（退出/切集落盘，看完自动清），切集按当时那集的键存，不会串集
- **相册**：导出不再通知媒体库，`Download/Veyra` 放 `.nomedia`；「关于 → 清理相册」可清掉老版本扫进去的条目，且不会删文件
- **检查更新**：自动检查 12h→4h，成功才占用窗口（以前一次断网锁死一整天）、失败退避 10 分钟；红点点到底部「我的」，冷启动发现新版给 toast
- 修复旧内核 WebView（< 111，Android 8~12 原厂内核常见）不支持 `color-mix()` 导致的整页看不清：弹窗背板全透明、默认「彩色背景」下标题与底色同色。启动时做能力检测，不支持才用纯色令牌降级，支持的内核观感零变化
- 合并成功后不再冒出红色的「复制合并日志」告警
- 新增红果官方弹幕（只读）、《最新上架》《最高热度》《最多收藏》三个榜单类别
- 批量下载去掉集数上限，改为实时进度 / 可取消 / 单集失败跳过；下载卡片去掉「传到服务器」按钮，「合并」改为「合并成 1 个视频」并补上用途说明
- 合入上游 0.28.15~0.28.18 播放性能批次：起播门槛 0.3s、详情页预热、播放器跨 Activity 复用
- 电视端补齐 0.28.1431 的检查更新修复：不再后台静默下载，只亮红点、手动才弹窗

## 说明

本项目仅供个人学习与技术研究，不提供、不存储、不传播任何视频内容。所有内容均来自源站，版权归原权利方所有。

二次开发遵循上游的 **GPL-3.0** 许可。使用本代码产生的任何后果由使用者自行承担，请勿用于商业用途或对外分发。

原项目：[gybeyond1/Veyra](https://github.com/gybeyond1/Veyra)

## 许可

GPL-3.0，详见 [LICENSE](LICENSE)。
