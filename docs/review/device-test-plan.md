# 真机测试计划 · B 站手表客户端（26.09.24 → 待发布版）

> 适用对象：`ReBiliClient` / RE:哔哩终端 Android 手表客户端
> 被测基线：`2d2b63b`（v26.09.24）之后的全部改动
> 编写时间：2026-10-02
> 配套文档（三份来源报告均已归档）：`docs/archive/review/upstream-fork-audit.md`（缺陷清单与处置）、`docs/archive/review/fork-fix-worklog.md`（实现取舍与复验清单）、`docs/archive/review/audit-2026-09-24.md`（本机深度审计与修复轮次）
> **状态（2026-10-10 复核）：88 条用例一条都还没执行**——它是一份「待执行清单」而非历史记录，所以留在 `docs/review/` 保持可见；真正在用的发版前清单是 `docs/review/real-device-regression-checklist.md`。

**本计划合并自两份来源**：
- `docs/review/device-test-plan.md`（提交 `e7d182c`）——平台上行/功能组改动（T0–T15）
- `docs/review/device-test-plan-2026-10-02.md`——本机审计修复轮次（D0–D7）
合并后本文档为唯一真机测试计划，原 `device-test-plan-2026-10-02.md` 已删除。

---

## 〇、这份计划为什么必须存在

本轮修复**只过了编译与单测**（`assembleDebug` + `assembleRelease` 全绿、112 个单测 0 失败），**没有做过任何一次真机或联网联调**。

这不是偷懒，是这些改动**在现有验证手段下根本验不出来**：

| 改动类型 | 编译能查 | Lint 能查 | 单测能查 | 只有真机能查 |
|---|---|---|---|---|
| 平台 API 版本误用（`List.of` → API 30） | ❌ | ◐ | ❌ | ✅ 直接 `NoSuchMethodError` |
| 反射调框架方法（`hasOnLongClickListeners`） | ❌ | ❌ | ❌ | ✅ 部分手表裁掉了该方法 |
| 前台服务类型/权限（Android 14） | ❌ | ◐ | ❌ | ✅ `SecurityException` / 直接崩 |
| 服务端交互格式（`scene=2`、type9 表情节点） | ❌ | ❌ | ❌ | ✅ 发出去了才知道对不对 |
| 跨线程可见性（`@Volatile`、`bottom`） | ❌ | ❌ | ◐ | ✅ 低压测不出来，要真机跑 |
| 通知栏遥控 / 点通知回播放页 | ❌ | ❌ | ❌ | ✅ task 栈行为只在真机确定 |
| 文件路径 / FileProvider 授权 | ❌ | ❌ | ❌ | ✅ 路径与跨进程读写在真机才成立 |
| 文件系统副作用（下载目录、临时文件替换） | ❌ | ❌ | ◐ | ✅ 只有真机有真实增量下载 |

**单测 112 个全绿只证明"纯解析函数没写错"，不证明任何一条改动在真机上可用。**

### 已由构建与静态检查覆盖（不用真机）

| 项目 | 证据 |
|---|---|
| 编译通过 | `:app:compileDebugKotlin` + `:app:compileDebugJavaWithJavac` ✅ BUILD SUCCESSFUL in 58s |
| 单元测试 | `:app:testDebugUnitTest` ✅ 通过 |
| Debug 打包 | `:app:assembleDebug` ✅ BUILD SUCCESSFUL in 1m 22s |
| 发行版打包 | `:app:assembleRelease` ✅ BUILD SUCCESSFUL in 1m 57s，R8 + 资源压缩 + baseline profile 正常 |
| 版本号一致 | `:app:verifyVersionConsistency` ✅ `versionName=26.09.24 versionCode=2609240` |
| 签名 | APK 含 v2/v3 签名块，`key.jks` 条目 `alias=biliclient` |
| 删除死代码后无引用残留 | `SSLSocketFactoryCompat` / `BiliTerminalApp` / `getTerminalKey` / `leaveDetailPage` 删除后全量编译通过 |

### 我这边验不了、必须上手做的

| 原因 | 受影响项 |
|---|---|
| Linux 侧没有可执行的 `adb`（SDK 里只有 Windows 的 `adb.exe`），无法连设备、抓日志、看 UI 树 | **全部**用例 |
| 需要真机行为 | 权限弹窗、跨进程（外部播放器）、文件路径、前台服务、native Surface/播放器 |
| 需要真手表 | 表冠（旋转编码器）、圆屏布局、震动 |

---

## 〇之二、只有一条时间时：先测这 12 条

按"不过就是真问题、且后果最重"排序。**前 6 条必测。**

| # | 用例 | 操作 | 为什么排这里 |
|---|---|---|---|
| **R1** | T10-1 | 下载中断/杀 App 后已下好的文件还在 | ★数据丢失，修前会递归删成品 |
| **R2** | T2 | 后台/熄屏继续播放（**Android 14**） | 平台硬校验，缺一个类型/权限就崩 |
| **R3** | D4-3 | 下载中**切后台再回前台**，任务不被重置为「未开始」 | S6 未修净会**两个线程写同一文件** |
| **R4** | D4-1 | 下载进度**不先满后回退** | S7 字节双重计数（本轮修的） |
| **R5** | T5 / T4-5 | 表情渲染 + 转发视频带图被拒 | 服务端格式没联调过，且**静默**退化 |
| **R6** | T11-1 | 断网启动**不掉登录态** | 静默失败，用户直接掉号 |
| R7 | D3-8 / T3-3 | 番剧进度上报（`sid` 非 0） | 修前整条链路静默失效 |
| R8 | T1 / D1-3 | 播放失败不再伪装成"播放完毕" | 表现为"莫名跳集" |
| R9 | D7-2 | 自定义下载目录到**外置卡**再下载 | `file_paths.xml` 收窄后的已知风险面 |
| R10 | D7-3 | 外部播放器播**本地已缓存文件** | 跨进程 URI 授权，本轮新路径 |
| R11 | D8-1 | 短视频页滑 10+ 个再返回 | Surface 释放逻辑改了，黑屏/泄漏都在这 |
| R12 | D6-6 | 中文关键词搜索翻到第 2、3 页 | `seid` 改成按关键词隔离的 LRU |

---

## 一、测试环境

### 1.1 主机侧（Windows）

Android SDK 在 `D:\Program Files\android-sdk`，`adb.exe` 在 `platform-tools\` 下。

```powershell
# 建议先设别名，后面所有命令都用 $adb
$adb = 'D:\Program Files\android-sdk\platform-tools\adb.exe'
& $adb devices -l
```

> 仓库里已有一批 PowerShell 辅助脚本（`.dsh/dump-screen.ps1`、`.dsh/tap-text.ps1`），
> 默认参数指向的就是这套 SDK 路径，可以直接复用。

### 1.2 设备侧

- 一台 Android 手表（圆形屏优先，本项目 `resConfigs 'zh'`、`screenOrientation="locked"`）
- **至少一台 Android 14（API 34）设备** —— 前台服务类型强制校验是从 14 开始的，这是 F1 的最高风险点
- 如果可以，另外准备一台 **Android 7~10（API 24~29）** —— 用于验 P34 那类"高版本平台 API 在低版本 `NoSuchMethodError`"
- 手表上先装好正式版并**保持登录态**（很多用例依赖已登录；未登录时进度上报会被前置检查拦掉）

> **待补**：手表型号与系统版本尚未确定。确定后回来把本节写实 —— T2（前台服务）与 T12（平台 API 兼容）的参数最依赖它。

### 1.3 待测 APK

| 文件 | 大小 | 用途 |
|---|---|---|
| `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | 17 657 017 B | 主测包（含 `TestActivity` 调试页、UETool 悬浮窗） |
| `app/build/outputs/apk/release/app-arm64-v8a-release.apk` | 10 110 640 B | 发布包验证（R8 混淆后的行为差异、`TestActivity` 是否已被 strip） |
| `app/build/outputs/apk/release/app-armeabi-v7a-release.apk` | 8 610 409 B | 老手表（32 位）用 |
| `app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk` | 16 156 786 B | 老手表调试用 |

> 手表坐标确定后用对应 ABI 的那个。arm64 是绝大多数；老手表请看 `adb shell getprop ro.product.cpu.abi`。

### 1.4 两批改动的来源与分工

本计划的用例分两组，来自两条独立的改动线，请**分别复验**：

| 组 | 改动内容 | 用例 | 来源 |
|---|---|---|---|
| **T 组** | 平台上行 / 功能移植：前台服务、番剧进度上报、动态配图与表情、图文直取、搜索番剧、更新日志页、评论点赞、下载与后台、登录 Cookie | T0–T15 | `docs/archive/review/upstream-fork-audit.md` / `docs/archive/review/fork-fix-worklog.md` |
| **D 组** | 本机深度审计修复：Response 泄漏、下载并发与字节计数、Cookie 解析、字符集、权限收敛、FileProvider、播放器 Surface/scope、搜索 `seid`、取消重试、异常链 | D0–D8 | `docs/archive/review/audit-2026-09-24.md` 第六节 |

**两组会互相干扰**，测试时留意：D 组改了 `DownloadService` 的并发守卫与恢复逻辑，T10 的下载用例要按 D 组的严格判据执行（见 D4）。

---

## 二、装包与冒烟（P0，先跑这一组）

**任何一条不过，后面的用例都不用做** —— 直接提崩溃日志。

### T0-1 装包

```powershell
& $adb install -r -t "app\build\outputs\apk\debug\app-arm64-v8a-debug.apk"
```

- 预期：`Performing Streamed Install` → `Success`
- 失败形态与含义：
  - `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → 签名不一致。**先别卸载**，提出来（卸载会清登录态，很多用例依赖已登录）
  - `INSTALL_FAILED_VERSION_DOWNGRADE` → 装的版本比机上旧

### T0-2 冷启动到首屏

```powershell
& $adb logcat -c
& $adb shell am force-stop com.RobinNotBad.BiliClient
& $adb shell am start -n com.RobinNotBad.BiliClient/.activity.SplashActivity
```

- 预期：打字机启动动画 → 自动进入用户菜单里的第一个启用项
- 判定：**启动后 10 秒内 `logcat` 里没有 `FATAL EXCEPTION`**
- 若第一条就是 `NoSuchMethodError: No static method hasOnLongClickListeners` → `util/ViewCapabilityProbe` 在这台手表上没兜住，属已知高风险面

### T0-3 冒烟扫一遍

```
菜单 → 每一页都进一次再返回
```

本项目注册了约 100 个 Activity。重点覆盖：

- [ ] 推荐 / 热门 / 排行榜 / 动态 / 消息中心 / 我的 / 设置
- [ ] 视频详情页 → 播放页 → 返回
- [ ] 搜索（普通搜索 + 番剧 tab）
- [ ] 下载列表
- [ ] 关于 → **更新日志页**（F5 改动）
- [ ] 设置 → **播放设置**（F1 文案改动）
- [ ] 设置 → **搜索设置**（F2 新增开关）

- 判定：全程无崩溃、无白屏、无"加载失败"卡死

### T0-4 发布包验证（R8 是否把不该 strip 的东西 strip 了）

```powershell
& $adb uninstall com.RobinNotBad.BiliClient
& $adb install -r "app\build\outputs\apk\release\app-arm64-v8a-release.apk"
```

- [ ] 同样扫一遍 T0-3 的页面
- [ ] 确认设置页里**没有「测试页面」入口**（`TestActivity` 只在 debug 源集注册）
- [ ] 确认通知栏没有被要求 `SYSTEM_ALERT_WINDOW`
- [ ] 权限面回归（D 组改动）：release 包**不应**再声明以下权限
      ```powershell
      & $adb shell dumpsys package com.RobinNotBad.BiliClient | Select-String -Pattern "READ_PHONE_STATE|ACCESS_WIFI_STATE|SYSTEM_ALERT_WINDOW"
      ```
      预期：**零命中**（这三条已在 D 组从主清单删除，`SYSTEM_ALERT_WINDOW` 只留在 debug 清单）
- [ ] 保留项确认（**有意保留，不要报为多余**）：`REQUEST_INSTALL_PACKAGES`（应用内更新）、`READ_EXTERNAL_STORAGE`（minSdk 24 读已下载文件）

---

## 三、T 组用例：平台上行 / 功能移植

### T1 播放失败不再伪装成"播放完毕"（P27）

**为什么重要**：这是本轮修的最隐蔽的一条。修之前 `onError` 返回 `false`，IJK 会改发 `onCompletion`，于是"播放失败"表现得和"播放完了"一模一样 —— 自动跳下一 P。用户看到的是"视频莫名跳集"。

**怎么造失败**：断网，或让视频地址失效。最稳的做法是断网后从缓存列表点一个**未下载完**的条目。

- [ ] 步骤：断网 → 进任意视频 → 点播放
- [ ] 预期：提示**播放失败/加载失败**，**停在当前 P 不动**
- [ ] **反例（不通过）**：自动跳到下一 P、或进度条走完复位、或按钮变成"重播"
- [ ] 补充：有"重试"入口的话点一次，看是否能恢复（P27 的上游实现带 `retryAfterPlayerError`）

### T2 后台/熄屏继续播放（F1）★最高风险

**为什么重要**：上游 targetSdk 26，本项目 targetSdk 34。Android 12 起有后台启动前台服务的限制，Android 14 起前台服务**必须**声明 `foregroundServiceType` 且**必须**持有对应权限，否则直接抛异常。这些在编译期完全查不出来。

**前置**：设置 → 播放设置 → 打开「后台/熄屏继续播放」

- [ ] **T2-1 退后台继续播**：播放中按 Home/上划退出 → 应能继续听到声音
- [ ] **T2-2 通知挂出来**：通知栏出现「后台播放」通知，**标题是视频名**，带进度条
- [ ] **T2-3 熄屏继续播**：播放中让屏幕熄灭 → 声音应继续
      （本轮**没有加 wakelock**，如果这里断了，就是需要补 `WAKE_LOCK` + `IjkMediaPlayer.setWakeMode` 的信号）
- [ ] **T2-4 通知栏「暂停/播放」**：点一下 → 暂停；再点 → 继续。**通知的图标要跟着变**
- [ ] **T2-5 通知栏「关闭」**：点一下 → 播放停止、通知消失
- [ ] **T2-6 回到播放页通知消失**：从最近任务切回播放页 → 通知栏那条应被撤掉
- [ ] **T2-7 点通知回播放页** ★最容易出问题的一条
      - 预期：回到播放页，且**播放页不被关掉**
      - **已知风险**：实现走的是 `getLaunchIntentForPackage` + `FLAG_ACTIVITY_REORDER_TO_FRONT`（与上游同款）。如果它触发了 `PlayerActivity.onNewIntent`，而 `onNewIntent` 里有一句 `finish()`，**点通知会把播放页关掉**。当前判断是"没有 launchMode，`onNewIntent` 不会来，那句 `finish()` 是死代码"，但**这条只在真机上能确认**
      - 若真的被关掉：记录现象，修法是给 `PlayerActivity` 加 `launchMode="singleTask"` 并把 `onNewIntent` 的 `finish()` 改成"不 finish，只把任务调到前台"
- [ ] **T2-8 关掉开关后不再保活**：关掉「后台/熄屏继续播放」→ 退后台 → 应暂停（这是原有行为）

**本组的日志抓取重点**：

```powershell
& $adb logcat -c
# ... 执行 T2-1 ~ T2-7 ...
& $adb logcat -d | Select-String -Pattern "PlaybackService|ForegroundService|SecurityException|MissingForegroundServiceType|startForeground"
```

- [ ] 不应出现 `android.app.ForegroundServiceStartNotAllowedException`
- [ ] 不应出现 `SecurityException: Starting FGS with type mediaPlayback ... requires permissions`
- [ ] 不应出现 `MissingForegroundServiceTypeException`

### T3 番剧进度上报（P23 / P24）

**为什么重要**：这是整条链路重写的部分（新增心跳接口 `reportHistoryPgc`、多P cid 校验、周期上报）。修之前番剧进度是**静默失效**的 —— 用户看完一集，服务端什么都没记，下次进去还从第 1 集开始。

**日志开关**：实现里打了带 `进度上报` tag 的日志，直接抓这几行就能看出链路走没走通。

- [ ] **T3-1 投稿视频周期上报**：登录后播任意普通视频 **≥ 20 秒**，然后退出
      ```powershell
      & $adb logcat -d | Select-String -Pattern "进度上报"
      ```
      预期看到类似 `周期上报 aid=... cid=... epid=0 sid=0 progress=17s`
- [ ] **T3-2 上报真的成功**（不只发了，还要服务端认）
      - 去网页端/B站App 查该视频的观看记录，进度应该对得上
      - 如果 `logcat` 里出现 `进度上报失败 code=-111` → csrf 又不对了（P1 修的就是这个，回看 `HistoryApi.currentCsrf()`）
- [ ] **T3-3 番剧心跳上报**：播一集番剧 ≥ 20 秒后退出
      - 预期日志是 `epid=<非0> sid=<非0>` 且走的是 heartbeat 分支
      - **若日志里 `sid=0`** → `PlayerData` 的 seasonId 没传到播放器，属接线漏了
- [ ] **T3-4 历史列表点番剧能定位到上次那集**
      - 播完某番剧第 N 集退出 → 回首页 → 历史记录 → 点这条番剧
      - 预期：进入番剧详情页，**自动定位到第 N 集**并提示「已定位到上次观看的「…」」
      - 反例：定位到第 1 集（说明 `epid` 没随卡片传出来，或 `getMdidFromEpid` 反查失败）
- [ ] **T3-5 多P视频不串进度**（P24）
      - 找一个多P视频，播 P2 到 30 秒 → 退出 → 重进 → **选 P3**
      - 预期：P3 从 0 开始
      - **反例**：P3 从 30 秒开始（说明 `cid` 校验没生效，拿 P2 的位置续了 P3）
- [ ] **T3-6 切P上报**：播放中切到下一 P → 日志里应有一条切P前的上报
- [ ] **T3-7 进度不被清零**：找一个已经看到一半的视频，进去播 2 秒就退出
      - 预期：观看记录**仍是之前的大进度**，不被 2 秒覆盖

### T4 动态配图（F3-b）

**为什么重要**：`scene=2`、`new_dyn` 这个 biz、`pics` 字段格式全是从上游抄的，没有联调过。

前置：登录。

- [ ] **T4-1 发一条带 1 张图的动态**
      - 预期：动态发出、能立即看到自己的动态、图片正常显示
- [ ] **T4-2 带多张图**（实现里上限 9 张）
- [ ] **T4-3 大图自动压缩**：选一张 > 8MB 的图，应能上传成功（不能是"卡住不动"或直接失败）
- [ ] **T4-4 GIF**：选一张动图 —— 按键在 `WriteReplyActivity` 那边是原样透传，动态这边若被压成静态属可接受，但**不应报错**
- [ ] **T4-5 转发视频时不允许带图**：进视频详情 → 转发 → 选图
      - 预期：提示「视频转发不支持带图，请去掉图片后重试」
      - **反例（重要）**：图被静默丢弃，动态照发（修之前就是这个行为）
- [ ] **T4-6 转发动态的预览卡**：普通动态转发时预览卡应显示原标题与原作者

### T5 表情（F3-i）★最需要真机确认的一条

**为什么重要**：`buildContents` 靠正则 `\[[^\[\]]{1,32}\]` 从正文里抠表情名，再去 `EmoteApi.getEmoteTexts` 的白名单里比对。**如果服务端返回的 `emote.name` 不带方括号**，那么一个都匹配不上，而代码会**静默退化成纯文本、不报任何错** —— 表现就是"表情发出去变成了一串 `[doge]` 文字"。

- [ ] **T5-1 发一条带表情的动态**：插入 2~3 个不同表情 → 发出
- [ ] **T5-2 看渲染结果**
      - 通过：表情以**图片**形式显示
      - 不通过：显示成 `[doge]` 这样的**纯文本**
- [ ] **T5-3 若不通过**，抓证据：`EmoteApi` 返回的 name 是否含方括号
      ```powershell
      & $adb logcat -d | Select-String -Pattern "emote|表情"
      ```
      （若日志不足，临时在该接口加一行 `Logu.d` 打印 `emote.name` 原始值再验）
- [ ] **T5-4 转发时原文里的表情**：转发一条含表情的动态，引用的原文里表情也应正确渲染

### T6 图文详情接口直取（F3-g）

- [ ] **T6-1 打开一条图文动态**（id > 1e8 的那种，即"动态"而非"专栏"）
      - 预期：**明显比之前快**（原来是抓页面，现在是接口直取）
- [ ] **T6-2 打开一篇专栏**（cv 号，id ≤ 1e8）
      - 预期：仍能正常打开（专栏走的是抓页面这条老路，**没改**）
- [ ] **T6-3 风控场景**：如果接口返回非 0，应能回退到抓页面，最终仍能看到内容
      - 反例：白屏 / 直接抛错

### T7 搜索番剧（F2）

- [ ] **T7-1 新 tab 存在**：搜索页应有「番剧」分类，位置在「视频」之后
- [ ] **T7-2 能搜出结果**：搜一个番剧名 → 出卡片（封面、标题、评分/集数）
- [ ] **T7-3 点卡片能进详情页**
- [ ] **T7-4 设置能开关**：设置 → 搜索设置 → 关掉「显示"番剧"搜索」→ 回搜索页，番剧 tab 应消失
- [ ] **T7-5 老用户排序不丢** ★这是本轮修的一个隐蔽回归
      - 前提：这台机**之前用过旧版本**并调过搜索 tab 顺序
      - 预期：升级后**原来的自定义顺序还在**，番剧被补在末尾
      - 反例：顺序被重置回默认（说明容忍式解析没生效）
- [ ] **T7-6 长按番剧卡片**：应提示「番剧暂不支持快速缓存」
      - **反例**：开始下载并失败（因为番剧卡片的 `aid` 位装的是 media_id）

### T8 更新日志页（F4 / F5）

- [ ] **T8-1 版本选项卡**：关于 → 更新日志 → 应看到可横向滚动的**版本选项卡**，当前版本在最左且默认选中
- [ ] **T8-2 每个 tab 有内容**：逐个点过去，都应有对应版本的日志正文
- [ ] **T8-3 滚动到底**：最后一页能滚到最底部（布局加了 `layout_alignParentBottom`，就是为了修这个）
- [ ] **T8-4 覆盖安装后自动弹一次**（F4）
      - 步骤：先装旧版（或先清掉 `last_version`）→ 覆盖安装新版 → 启动
      - 预期：**自动打开更新日志页，且只弹一次**；下次冷启动不再弹
      - 反例：弹两次（说明 `pendingUpdateLog` 的去重没生效 —— UETool 悬浮窗授权会二次进入启动流程）
- [ ] **T8-5 不再弹旧「更新公告」**：同一次升级启动里**不应该**再叠一个全屏「更新公告」页

### T9 评论点赞类型（F3-h）

- [ ] **T9-1 视频评论点赞**：点赞 → 变红；再点 → 取消
- [ ] **T9-2 动态评论点赞**：同上（修之前这里硬编码 `type=1`，点动态评论的赞会**静默失败**）
- [ ] **T9-3 专栏评论点赞**

### T10 下载与后台（B 组回归）

- [ ] **T10-1 下载中断不删已下好的**（P16）★数据丢失类
      - 步骤：下完一个视频 → 再下**另一个**视频并**中途取消/杀掉 App**
      - 预期：**已经下好的那个视频还在**
      - 反例：整个下载文件夹被删（修之前 `onDestroy` 里 `deleteFolder` 会递归删掉成品）
- [ ] **T10-2 下载进度通知不静默**（P17）：下载中长时间看通知栏，进度应持续更新
- [ ] **T10-3 消息中心首屏失败不卡死**（P18）
      - 步骤：断网 → 进消息中心 → 恢复网络 → 下拉刷新
      - 预期：能恢复；**不能出现"一直转圈、怎么拉都不动"**
- [ ] **T10-4 后台异常不整个杀进程**（P21）
      - 步骤：制造一个后台线程异常（例如断网下反复刷新一个列表）
      - 预期：提示错误，**App 不整体退出**
- [ ] **T10-5 下载并发**（P20）：快速连点两次同一个下载任务，不应产生两个任务写同一文件
      - 详细判据见 **D4**（D 组对这条做了更严格的实现改动）

### T11 登录与 Cookie（Wave 1 回归）

- [ ] **T11-1 网络波动不清登录态**（P29）
      - 步骤：断网启动 App
      - 预期：仍保持登录；**不能**出现"登录信息过期，请重新登录"并清掉登录态
- [ ] **T11-2 退出登录真的退出**（P31）
      - 步骤：我的 → 退出登录
      - 预期：本地登录态清掉；**再启动不自动登录**
      - 服务端会话是否失效：用网页端验证（修之前是 GET 且不带 csrf，服务端其实没注销）
- [ ] **T11-3 搜索建议乱序/输入法**（P32）
      - 快速输入一串字符，建议列表应是**最后一次输入**的结果，不能被慢响应覆盖
      - 点搜索框应能**弹出输入法**（修之前全工程没有 `showSoftInput`）

---

## 四、D 组用例：本机审计修复轮次复验

> 本组对应 `docs/archive/review/audit-2026-09-24.md` 第六节。改动共 38 个文件，重点在**下载、登录、播放器、网络层、权限**。
> 与 T 组重叠的用例（如下载）以 **D 组的更严判据**为准。

### D1 网络层（Response 关闭 / 重试 / Cookie）

| # | 操作 | 判据 |
|---|---|---|
| D1-1 | 打开 5~6 个不同页面（推荐/热门/排行榜/收藏/历史/我的） | 全部正常加载，无 `java.io.IOException: closed` / `ConnectionShutdownException` |
| D1-2 | 弱网（限速/丢包）下反复刷新列表 | 能重试成功，不出现"一失败就永久失败" |
| D1-3 | 播放中**主动取消**一个正在进行的请求（如快速返回） | 日志里**不应**出现该请求被重试的痕迹（本轮专门加了取消不重试） |
| D1-4 | 重启 App 后确认登录态还在 | 未掉登录；若掉了 → `getInfoFromCookie` 的精确匹配出问题 |
| D1-5 | 用**含特殊字符**的账号/密码登录 | 不因字符集失败（7 处 `.getBytes()` 已显式指定 UTF-8） |

### D2 登录与账号

| # | 操作 | 判据 |
|---|---|---|
| D2-1 | **扫码登录**（TV 端 + Web 端两种都试） | 扫码 → 确认 → 成功进入已登录状态，不卡在「等待确认」 |
| D2-2 | 检查 logcat | **不得出现完整 Cookie / `access_token` / `refresh_token` 明文**（QRLogin 的 4 行敏感日志已删） |
| D2-3 | **密码登录** | 点击登录后界面**不卡死**（原来整段登录流程跑在 UI 线程，含多次同步网络请求） |
| D2-4 | 密码登录时故意输错密码 | 正常报错，**不 ANR** |
| D2-5 | 登录后看「我的」页 | 昵称/头像/粉丝数正常加载 |
| D2-6 | 退出登录 → 再登录 | 正常 |

### D3 播放器

| # | 操作 | 判据 |
|---|---|---|
| D3-1 | 普通视频播放（App 内播放器） | 出画出声，进度条正常走 |
| D3-2 | 拖动进度条到最前 / 最后 | **不崩溃、不跳到非法位置**（`IjkPlayerBridge.seekTo` 已钳制到 `[0, duration]`） |
| D3-3 | 拖动进度条到**超出末尾**（快速连续拖） | 播放器不报错（原实现会乐观更新到非法值） |
| D3-4 | 外部播放器播放（如有该选项） | 能拉起并播放 |
| D3-5 | **外部播放器播本地已缓存文件** ★ | 能播。这是 `getVideoUri` + `FLAG_GRANT_READ_URI_PERMISSION` 的新路径，**跨进程读取**，最可能失败 |
| D3-6 | 全屏 / 退出全屏 | 画面正常，不黑屏 |
| D3-7 | 弹幕开关、发送弹幕 | 正常；`DanmakuManager.release()` 后重新进入应能正常加载（`isPrepared` 已复位） |
| D3-8 | 直播：进一个**已下播/不存在**的直播间 | **30 秒内**给出失败提示，不无限转圈（`fetchLiveInfo` 已加 30s 超时） |
| D3-9 | 直播：网络很慢时进直播间 | 30s 超时后返回失败，不 ANR |
| D3-10 | 动态详情页 → 返回 / 直播间详情 → 返回 | 不崩溃（两页的 `onDestroy()` 覆写与 `leaveDetailPage()` 已删除） |

### D4 下载与文件 ★数据安全

> 本轮 `DownloadService.kt` 改了 136 行，是最可能出问题的一块。**D4-3 与 D4-4 出现反例时立刻停止测试并反馈**。

| # | 操作 | 判据 |
|---|---|---|
| D4-1 | 正常下载，观察进度条 | **不得出现「先涨到 100% 再退回」**（S7 字节双重计数已修，若仍回退说明没修净） |
| D4-2 | 下载中再点一次「缓存」同一个视频 | 应提示「下载队列已在进行中」，**不得出现第二个进度通知**（S6 幂等守卫） |
| **D4-3** | 下载中**切后台再回前台** | **正在下载的任务绝不能被重置为「未开始」**（S6 修的就是这个：原来会把运行中的任务当"遗留"重置，导致两个线程写同一文件）→ 反例立刻反馈 |
| **D4-4** | 下载中**强杀 App** → 重开 | 下载列表里该任务**仍是「未完成」**（不是消失、不是显示完成）；已有成品文件完好 |
| D4-5 | 下载中断后**续传** | 从断点继续，不从头重下 |
| D4-6 | 下载**带字幕**的视频 | 下载成功，字幕文件存在于下载目录 |
| D4-7 | **同一个视频重下第二次**（删掉再下，或换目录） | 仍成功 —— 原来 `mkdirs()`/`createNewFile()` 返回 false 就报 `ERR_FILE`，**重下必失败**（M11-e 已修） |
| D4-8 | 下载弹幕，然后播放看弹幕有没有 | 弹幕正常显示（M11-f 补了 `isSuccessful` 检查） |
| D4-9 | 下载一个**视频不存在**的地址（或断网中途） | 应失败并提示，**不得把错误响应当成弹幕写进文件** |
| D4-10 | 下载中**断开网络**，随后检查 cache 目录 | 只删 `_new.mp4`/`_new.m4a`/`.bak`/`.DOWNLOADING`，**已完成的成品文件必须保留** |

### D5 搜索

| # | 操作 | 判据 |
|---|---|---|
| D5-1 | 搜索**中文关键词**，翻到第 2、3 页 | 结果不重复、不跳回第一页（`seid` 已按关键词隔离缓存） |
| D5-2 | 搜索 A → 搜索 B → **再搜回 A** | 结果正常（LRU 容量 16，回搜 A 应重新取 seid） |
| D5-3 | 各分类页签（视频/番剧/直播/用户/专栏）各搜一次 | 都能出结果 |
| D5-4 | 快速连续搜两个不同词 | 结果**不串台**（原来全局静态字段会互相踩踏） |
| D5-5 | 搜索一个**没有结果**的词 | 正常显示空，不崩溃（`getString("seid")` 已改 `optString`，原来缺字段会抛异常把整页结果带走） |

### D6 私信、权限与异常处理

| # | 操作 | 判据 |
|---|---|---|
| D6-1 | 打开私信列表 | 正常渲染，**会话不缺失** |
| D6-2 | 检查 logcat 收私信 | **不得打印私信正文**（改为只记条数） |
| D6-3 | 故意制造一次崩溃（debug 包：设置 → 测试页面） | 崩溃页正常弹出 |
| D6-4 | 若手表/系统自带崩溃上报 | **应仍能收到上报**（本轮新加的链式调用；原来会切断系统的 `UncaughtExceptionHandler`） |
| D6-5 | 崩溃后进程正常结束 | 不出现「卡在崩溃页无法退出」 |
| D6-6 | 设置 → 播放设置 / 搜索设置 各项开关 | 改动后重启仍生效 |

### D7 文件路径与存储 ★最容易漏

| # | 操作 | 判据 |
|---|---|---|
| D7-1 | 保持**默认下载目录**下载一个视频 | 成功（默认目录已被新 `file_paths.xml` 覆盖） |
| **D7-2** | 把下载目录改成**外置卡 / SD 卡**上的自定义路径 ★ | 能下载成功 |
| D7-3 | 若 D7-2 失败 | **这是已知风险**——`file_paths.xml` 删掉了 `<root-path path="" />`，自定义目录不再被 FileProvider 覆盖。**把具体路径发出来**，我加对应的 `<path>` 条目 |
| D7-4 | 图片保存（`Pictures/哔哩终端/`） | 保存成功 |
| D7-5 | 应用内更新（检查更新 → 下载 APK） | APK 能下载并唤起安装 |
| D7-6 | 读取已下载的本地视频 | 能读到 |

### D8 短视频页（Surface 生命周期）

| # | 操作 | 判据 |
|---|---|---|
| D8-1 | **上下滑 10+ 个视频** | 每个都能播、**不出黑屏**、内存不持续上涨 |
| D8-2 | 滑到第 10 个后**返回再进入** | 仍能正常播放（`IjkPlayerBridge.release()` 现在会 cancel 并**重建** scope） |
| **D8-3** | 滑动时看**进度条** | **进度条要动**。若某些视频进度条卡在 0，说明 scope 重建逻辑有问题 → **立刻反馈** |
| D8-4 | 快速反复进出短视频页 5 次 | 无 `IllegalStateException`（Surface 已 release 仍被使用） |

---

## 五、顺带复验（P2：不专门排时间，走到就测）

### T12 平台 API 兼容（Android 7~10 设备）

- [ ] 打开**用户信息页**（`UserInfoApi` 路径，曾误用 `List.of`）
- [ ] 打开任意**动态**（`DynamicApi` 路径）
- 判定：不出现 `NoSuchMethodError: No static method of(...)`

### T13 播放失败重试与端点续播

- [ ] 播放中拖进度条 → 拖动过程不卡顿
- [ ] 双击快进 / D-pad 左右键快进
- [ ] 切换「听视频」（音频模式）→ 进度不跳
- [ ] 切换清晰度 → 进度不跳
- [ ] 长按倍速
- 说明：这几处本轮把"实时读 native 位置"改成了"读最多 250ms 前的缓存位置"，理论上无感，但**如果有明显跳变就记下来**

### T14 弹幕

- [ ] 弹幕正常滚动、不卡死（本轮改了弹幕回调线程，不再在回调里直接调 JNI）
- [ ] 打开/关闭弹幕开关
- [ ] 退出播放后 App 不卡死

### T15 安全项回归

- [ ] 下载弹幕/视频在**弱网**下不卡死（P15 解压死循环修的就是这个：原来损坏数据会让 CPU 跑到 100%）
- [ ] 日志里不应出现 Cookie / SESSDATA 明文
      ```powershell
      & $adb logcat -d | Select-String -Pattern "SESSDATA|bili_jct|DedeUserID="
      ```
      预期：**零命中**

### D9 表冠 / 圆屏（仅手表可测）

- [ ] 表冠滚动视频列表、设置列表 → 灵敏度与改动前一致
- [ ] 表冠滚动长页面（设置主页、我的、关于）
- [ ] 圆屏适配开启后的顶栏 → 无 `ClassCastException`
- [ ] 震动反馈正常

### D10 回归冒烟（顺路看一眼）

- [ ] 打开短视频页 → 返回 → 再开（`CenterThreadPool` 删了整条裸线程池路径，所有异步任务现在走协程）
- [ ] 关于页版本号显示 `26.09.24`
- [ ] 检查更新能正常请求到配置

---

## 六、怎么抓证据

### 6.1 崩溃日志

```powershell
& $adb logcat -c
# ... 复现 ...
& $adb logcat -d > crash.log
Select-String -Path crash.log -Pattern "FATAL EXCEPTION" -Context 0,40
```

### 6.2 本项目的错误日志

`Logu` 的 tag 前缀统一，直接按业务 tag 过滤最有效：

```powershell
# 进度上报链路
& $adb logcat -d | Select-String -Pattern "进度上报|history-last"
# 后台播放服务
& $adb logcat -d | Select-String -Pattern "PlaybackService|ForegroundService"
# 播放器错误
& $adb logcat -d | Select-String -Pattern "ijk-err|playerError"
# 表情名原始值（T5 不过时用）
& $adb logcat -d | Select-String -Pattern "emote|表情"
```

### 6.3 截图与界面结构

```powershell
& $adb shell screencap -p /sdcard/s.png
& $adb pull /sdcard/s.png .dsh\device-test\

# 界面文本（仓库已有脚本，能直接列出当前屏所有 text + resource-id + 前台 Activity）
& .\.dsh\dump-screen.ps1 -Tag t8-updatelog
```

### 6.4 每个用例的记录格式

```
[id] 结论（通过/不通过/阻塞）
设备：<型号> / Android <版本>
操作：<做了什么>
现象：<看到了什么>
证据：<截图文件名 / logcat 关键行>
```

### 6.5 ★「一出现就立刻反馈」的判据

这几条不是"不好用"，而是**说明修复没修净或引入了回归**：

| 现象 | 指向 |
|---|---|
| 下载进度**先满后回退** | S7 字节双重计数未修净（D4-1） |
| 下载中任务被重置为「未开始」 | S6 未修净，**会损坏文件**（D4-3） |
| 短视频进度条卡在 0 不动 | `IjkPlayerBridge` scope 重建有问题（D8-3） |
| 点通知后**播放页被关掉** | `onNewIntent` 的 `finish()`（T2-7） |
| 重启 App 后**掉登录态** | `getInfoFromCookie` 或 Cookie 并行修改（D1-4） |
| 表情发出去变成 `[doge]` 纯文本 | `emote.name` 不含方括号（T5） |
| 番剧日志 `sid=0` | seasonId 没传到播放器（T3-3） |
| `AndroidRuntime` / `FATAL` | 任何崩溃，直接发我 |
| `code=-111` | csrf 失效（T3-2） |

---

## 七、优先级与时间盒

| 优先级 | 用例 | 为什么排这个顺序 |
|---|---|---|
| **P0** | T0 装包冒烟 | 崩在这里，后面全是白测 |
| **P1** | **T2 后台播放（尤其 Android 14）**、T1 播放失败、T3 番剧进度 | 本轮改动的核心，且是"用户可感知损失最大"的三条 |
| **P1** | T4 动态配图、T5 表情、T10-1 下载不误删、**D4 全部** | 前两个是"发了才知道对不对"，后两个是数据丢失 |
| **P1** | D7-2 自定义下载目录、D3-5 外部播放器播本地文件、D8 短视频 | D 组引入的新风险面 |
| **P2** | T6 / T7 / T8 / T9 / T11、D1 / D2 / D5 / D6 | 功能正确性，风险中等 |
| **P3** | T12~T15、D9 / D10 | 回归观察项 |

**建议节奏**：T0 全绿后再动 T1~T3；**T2、T5、D4-3、D8-3 如果不过，需要立刻反馈**（均属代码改动而非配置）。

---

## 八、已知的"不用报为 bug"

- **熄屏后播放停止** —— 本轮**有意没加 wakelock**，先看真机表现再决定是否补
- **通知栏是普通通知，不是 MediaStyle** —— 没有 `MediaSessionCompat`，因此锁屏/蓝牙耳机的媒体键控制不生效；这与上游一致
- **没有音频焦点处理** —— 来电、拔耳机不会自动暂停；上游也没有
- **番剧详情页没有季选项卡、「正在播放」标记** —— 有意未做（纯 UI 增量）
- **发布选项 / 动态置顶 / 可见范围 / 编辑动态 / 评论数入口 / `#话题#` 话题页** —— 有意未做
- **点通知回播放页可能新建一个启动实例** —— 与上游一致，属已知限制（但如果**播放页被关掉**，那是 bug，要报）
- **`REQUEST_INSTALL_PACKAGES` / `READ_EXTERNAL_STORAGE` 仍在清单** —— 有意保留（应用内更新、读已下载文件）
- **`TV_APP_SEC` 常量留在代码里** —— B 站 TV 端公开常量，非泄漏
- **弱网下大文件下载不设 `callTimeout`** —— 有意为之：本 App 只有一个 OkHttpClient，同时服务大文件流式下载，设了会掐断正常下载

---

## 九、测完之后

1. 把这份文档里没勾上的项补勾，不通过的项填现象与证据
2. 需要改代码的项 → 回到 `docs/archive/review/upstream-fork-audit.md`（T 组）或 `docs/archive/review/audit-2026-09-24.md`（D 组）对应条目下补记
3. 全部通过后，`docs/archive/review/fork-fix-worklog.md` 的「真机复验清单」可以标完成
4. 再走发版流程（`docs/review/` 下的发版技能与 CI）

---

## 附一、一次性把关键日志抓全的命令

```powershell
$adb = 'D:\Program Files\android-sdk\platform-tools\adb.exe'
& $adb logcat -c
# ... 手工执行 T0~T15 与 D1~D8 ...
& $adb logcat -d > (Join-Path $PWD 'device-test-all.log')

foreach ($p in @('FATAL EXCEPTION','NoSuchMethodError','SecurityException',
                 'ForegroundService','进度上报','PlaybackService','ijk-err',
                 'SESSDATA','bili_jct')) {
  "===== $p ====="
  Select-String -Path 'device-test-all.log' -Pattern $p | Select-Object -First 20 | ForEach-Object { $_.Line }
}
```

## 附二、安装命令速查

```powershell
$adb = 'D:\Program Files\android-sdk\platform-tools\adb.exe'

& $adb devices -l
& $adb -s <序列号> shell getprop ro.product.cpu.abi   # 决定装哪个 ABI 包

# 主测包（debug，含 TestActivity / UETool）
& $adb -s <序列号> install -r -t app\build\outputs\apk\debug\app-arm64-v8a-debug.apk

# 发布包验证（先 uninstall 才能验 R8 strip 与权限面）
& $adb -s <序列号> uninstall com.RobinNotBad.BiliClient
& $adb -s <序列号> install -r app\build\outputs\apk\release\app-arm64-v8a-release.apk
```

> `-r` = 覆盖安装、保留数据。若报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名不同），**先别卸载**，提出来。
> 32 位老手表把 `arm64-v8a` 换成 `armeabi-v7a`。

## 附三、测试结果记录表（直接回填）

| 章节 | 通过 / 未通过 / 未测 | 备注 |
|---|---|---|
| §二 T0 装包与冒烟 | | |
| T1 播放失败 | | |
| T2 后台/熄屏播放 ★ | | |
| T3 番剧进度上报 | | |
| T4 动态配图 | | |
| T5 表情 ★ | | |
| T6 图文直取 | | |
| T7 搜索番剧 | | |
| T8 更新日志页 | | |
| T9 评论点赞 | | |
| T10 下载与后台 ★ | | |
| T11 登录与 Cookie | | |
| D1 网络层 | | |
| D2 登录与账号 | | |
| D3 播放器 | | |
| D4 下载与文件 ★ | | |
| D5 搜索 | | |
| D6 私信/权限/异常 | | |
| D7 文件路径与存储 ★ | | |
| D8 短视频页 | | |
| T12~T15 顺带复验 | | |
| D9 表冠/圆屏 | | |
| D10 回归冒烟 | | |

**最低限度**：T2、T5、T10-1/D4、D7-2、D8 这几块过了，本轮改动的主要风险就基本覆盖了。
