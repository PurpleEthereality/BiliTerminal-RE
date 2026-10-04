# AGENTS.md

## 语言约定

**与用户交流、代码注释、文档一律中文。**

## 先读架构地图

改任何非平凡代码前先读 **`docs/architecture-map.md`**：真实分层、基类体系、40 个 API 类映射、UI 模板、逐条坑点。本文件只放每次都要遵守的约定。

其他参考：`bilibili-API/`（B 站接口文档快照，改 API 时查）、`docs/superpowers/specs/`（功能设计文档，写新功能前读）、`docs/tutorial-system-redesign.md`（教程系统重做中——**改教程前先读它**，旧链路正在被替换）。

## 项目概况

- RE:哔哩终端（ReBiliClient），第三方 B 站**手表**安卓客户端，`main` 分支。
- 典型 vibe coding 产物：代码大量由 AI 生成。**改动前先读源码核实，注释和文档都可能是错的。**
- 版本号按 YY.MM.DD（`app/build.gradle`）。

## 架构（三条与直觉相反的）

1. **入口是 `BiliTerminal.kt`**（26.09.10 由 `BiliTerminal.java` 迁移而来）。原 `BiliTerminalApp.kt` 已于 26.10.02 整体删除——它从未被实例化，只因 `SplashActivity` 的 UETool 逻辑引用其静态方法而残留；那些方法与常量已移入 `BiliTerminal.kt` 伴生对象。全局 Context 取 `BiliTerminal.context`——它在 companion 里用 `@JvmField` 暴露成**静态字段**，所以 Java 侧仍是 `BiliTerminal.context` 的字段读法，Kotlin 侧按需 `!!`。
2. **没有 DI / Retrofit / ViewModel**——26.09.11 死代码清理后这已是**事实**而非"死依赖残留"：Hilt、Retrofit、kotlinx-serialization、Jetpack Navigation、protobuf-javalite、geetest sensebot、asynclayoutinflater、cardview、lifecycle-viewmodel-ktx 等**已全部从 `app/build.gradle` 移除**，`ksp` 与 `kotlin.plugin.serialization` 两个插件也一并去掉（`@HiltAndroidApp` 随之从 `BiliTerminalApp.kt` 摘除）。原先 24 个空目录（`di/`、`network/`、`data/`、`ui/base` 等）已删除。新功能写进 `api/` + `activity/`，沿用静态方法 + `org.json`。
3. **只有一条链**：`activity/` → `api/`（全同步阻塞）→ `util/` → `model/`。导航由 `MenuActivity.btnNames` + `util/MenuConfig.kt` 决定。

## 硬约定

- 网络请求包在 `CenterThreadPool.run { }` 里。
- 列表页继承 `RefreshMainActivity`（菜单入口页）或 `RefreshListActivity`（返回式）；加载完必须 `setRefreshing(false)`，否则翻页永久卡死。`onLoad(page)` 的 page 已自增。
- 新增菜单页改三处：`MenuActivity.btnNames`、`MenuConfig.ALL_ITEMS`、`AndroidManifest.xml`。
- 新增设置项改三处：`util/SettingsKeys.kt`、设置页 `SettingSection`、`activity/settings/SettingsIndex.kt`。
- 外观设置（配色 / 卡片圆角 / 字体）走 `ui/appearance/` 三模块：**模块只放候选值与纯函数，写入一律走 `AppearanceManager`**（它负责递增外观版本号）；模块里别做几何计算，也别在热路径上缓存。细则见 `docs/architecture-map.md` §8.7。
- 新增设置子页面有两种形态，**先想清楚用哪种**：
  1. **独立 Activity**（如「菜单设置」「我的页面设置」）——有自定义交互/独立布局时用，改三处：`SettingGroupActivity` 对应分组加 `nav`、`SettingsIndex` 加可搜索条目、`AndroidManifest.xml` 注册。
  2. **本页的另一个 `group_type` 分组**——只是若干列表项、无自定义交互时用（如 `GROUP_APPEARANCE`「外观设置」），只需：`buildContent` 加分支 + 写一个 `buildXxxGroup()` + 在上级分组加 `nav` + `SettingsIndex` 加条目。**不需要新 Activity、manifest、布局**。对用户同样是独立一屏。
- 页面级排序/分区配置沿两个既有范式：`MenuConfig`（启用/未启用）与 `MySpaceConfig`（主列表/更多列表）——都是纯 Kotlin 单一数据源 + JVM 单测 + 复用 `MenuSettingAdapter` 式双分区拖拽写法。
- 请求与解析分离，纯解析抽成 `static`/`object` 函数并补 JVM 单测（参考 `HotSearchApi.parseHotSearch`；需要 SharedPreferences 时注入 `SharedPreferencesUtil.sharedPreferences = FakeSharedPreferences()`——该假实现是共享助手 `app/src/test/…/util/FakeSharedPreferences.kt`，**别再抄一份**）。
- **小步提交**：把改动切成小步，每步都能独立验证（`assembleDebug` + 单测通过）并说清楚改了什么，再进入下一步；不要一次性堆大量改动。
- 文案硬编码中文，只有设置页用 `strings.xml`（`desc_*` 惯例）。
- 不轻易引入新第三方库。
- **一切功能优先考虑手表端**，手机等设备只是顺便适配

## 安全与清单硬约定（26.09.13 起）

- **新增 Activity 一律 `android:exported="false"`**；只有确实要被外部应用/系统拉起（LAUNCHER、外链分享）才开 `true`，并说明理由。收官后全应用仅 `SplashActivity` 与 `GetIntentActivity` 导出。
- **只走 https**。新的自建接口不得用 `http://`；明文域名白名单在 `res/xml/network_security_config.xml`，默认禁止明文，只放行 `bilibili.com`/`hdslb.com`/`bilivideo.com`/`afdiancdn.com`。往白名单加域名要写原因。
- **`android:allowBackup` 保持 `false`**，登录 Cookie 存于 SharedPreferences，绝不允许随备份/迁移外流。`:brotlij` 自带 `allowBackup="true"`，故主清单必须保留 `tools:replace="android:allowBackup"`（删了会清单合并失败）。
- **调试页只进 Debug 包**。`TestActivity` 是范例：类留在 `src/main`，清单声明在 `app/src/debug/AndroidManifest.xml`，入口用**编译期常量** `BuildConfig.DEBUG` 包住（不是 `BiliTerminal.isDebugBuild()` 这种运行期判断），这样 R8 才能在 release 折叠分支并 strip 掉整个类。

## 每次改完必须同步文档

- **修 bug** → 在 `docs/review/fix-progress.md` 加记录并勾掉待办。
- **改架构 / 基建 / 新增基类** → 更新 `docs/architecture-map.md` 对应章节。
- **本文件描述的内容变了**（入口、约定、坑清单）→ 顺手改 `AGENTS.md`，别留过时描述。

## 构建

```bash
./gradlew.bat :app:assembleDebug      # 编译验证（无 CI，以此为准）
./gradlew.bat :app:testDebugUnitTest  # 纯 JVM 单测
./gradlew.bat :app:assembleRelease    # 正式包（R8 + ABI 分包）
```

多模块 `:app` / `:ijkplayer-java` / `:DanmakuFlameMaster` / `:brotlij`（后三个别乱动）。Gradle 8.11.1、AGP 8.5.2、Kotlin 2.0.0、JDK 17、minSdk 24 / compileSdk 34、`resConfigs 'zh'`。

**发版与更新检查**：推 tag 或手工触发 `.github/workflows/build-release.yml` —— 构建签名 APK → 创建 GitHub Release 并上传全部附件 → **通知中转服务**（地址与共享密钥都在仓库 Secrets：`RELAY_URL` / `RELAY_SECRET`；请求体经 HMAC-SHA256 签名），由中转服务自己去 GitHub 拉附件并同步到 Gitee `zisekongling/bili-terminal-re`。**Action 侧不要直接访问 Gitee**（网络不通），参见该文件末尾的 Notify relay 步骤；**中转地址与密钥都不要写进代码库或日志**。版本号与是否强制更新由工作流从 `app/build.gradle` 读出、写进 Release 说明的机器可读元数据。客户端更新检查按「Gitee → GitHub」读两边的 `releases/latest`（见 `util/UpdateRelease.kt`），**客户端已不再读 config.json**。发版细则见 `.dsh/skills/rebili-version-release/SKILL.md`；漏同步时用 `.github/workflows/relay-notify.yml` 重新通知（幂等，重复通知无害）。

> **老客户端迁移（一次性）**：26.10.02 及更早的客户端只认 123pan 上那份 `config.json`。发版时若把 `emit_config_json` 打开，工作流会额外产出一份**给老客户端**的 `config.json`（`downloadUrl` = Gitee 上 32 位包的直链、`forceUpdate` 与本次一致）并挂到 Release，需人工把它传到 123pan。**它只用于渠道切换那一次**，新客户端不读它。

坑：`gradle.properties` 第 9 行硬编码 `org.gradle.java.home`（Windows 路径），代理配置在第 88-92 行**是注释状态**；release 签名读 gitignore 的 `local.properties`；`copyApkToDesktop` 未挂在 `assembleRelease` 上，需手动跑。

**更容易踩的坑（build cache 回放陈旧资源）**：`gradle.properties` 开着 `org.gradle.configuration-cache` 与 build cache。只要你改动 `res/` 的**文件集合**（新增/删除/移动资源文件），`:app:mergeDebugResources` 可能 `FROM-CACHE` 回放一份旧结果，症状是编译报莫名其妙的 `Unresolved reference 'R.layout.xxx'` 或新的 string/color 找不到，而源文件明明存在。此时必须：

```bash
./gradlew.bat :app:clean --offline --no-configuration-cache
./gradlew.bat :app:assembleDebug --offline --no-build-cache --no-configuration-cache
```

判断依据：`app/build/intermediates/merged_res/debug/**/` 里找不到该资源对应的 `.flat` 文件，但 `aapt2 compile` 单独编译它又是成功的。

注意两点：
1. **`clean` 和 `assemble` 必须分两次 Gradle 调用**。写在同一次调用里（哪怕顺序正确）可能因配置缓存复用而报 `ManifestMerger2$MergeFailureException: NoSuchFileException: .../navigation_json/debug/extractDeepLinksDebug/navigation.json`。
2. 光加 `--no-build-cache` 不够时，把 `--no-configuration-cache` 也带上——配置缓存会复用过期的任务图，让上面那条 `Unresolved reference 'R.layout.xxx'` 继续复现。

增量编译还可能单独坏掉，报 `org.jetbrains.kotlin.util.FileAnalysisException … FileNotFoundException: app\build\tmp\kotlin-classes\debug\…\Xxx.class`（某个 class 被删了但增量状态没更新）。同样是 `clean` 后重建解决。

**量体积必须先 clean**：debug 包在**增量**构建下会产生大量 `classesN.dex` 分片，体积可以虚高 3~4 MB（实测同一份代码增量构建 19.96 MB、clean 构建 15.44 MB）。任何"改动前后比体积"的结论都必须在 `:app:clean` 之后分别测量。

**APK 体积构成（26.09.11 实测，release arm64 9.73 MB）**：native `.so` **67.3%**（`libijkffmpeg.so` 单文件就占一半）、dex 17.9%、`res/` 8.2%、`resources.arsc` 5.0%。**死代码删除对 release 体积几乎无影响**——R8 本来就会 strip 掉未引用的类；实测整轮死代码+死依赖清理只减了约 0.22 MB（2.2%）。真正的大头是 `libijkffmpeg.so`（5.16 MB），要动它得用 NDK 重编精简编解码器，属于另一件事。

## 改前必查的已知坑

完整清单见 `docs/architecture-map.md` 第 7 节。仍存在的：

- 视频卡片解析**重复 21 处**（`RankingApi`/`RecommendApi`×4/`WatchLaterApi`/`SearchApi`×3/`SeriesApi`/`FavoriteApi`×2/`UserInfoApi`/`HistoryApi`/`BangumiApi`/`MessageApi`×3/`DynamicApi`×2/`VideoInfo.java`），改一处要 grep 其余。旧文档写「7 份」、26.08 快照写「19 处」，26.10.04 实测 21 处（`SearchApi` 的番剧搜索分支与 `DynamicApi:826` 为漏计项）。
- `PlayerApi.java:308` 的 `fnvar` 应为 `fnver`；`DanmakuApi.java:93` 的 `segment_index` 起始值与注释不符（`:80` 的 javadoc 说从 0 开始，调用点 `:133`/`:144` 实际从 1 起）。
- `SettingsKeys.PLAYER` / `PLAY_QN` 已收敛（26.10.04 批次 4）：13 处字面量全部改调常量、`SharedPreferencesUtil.player` 死字段已删，**新代码不要再写 `"player"` / `"play_qn"` 字面量**；`SettingMainActivity.kt:118` 的 `"player"` 是分组 id 不是 SP 键，别顺手替换。其余键（`mid` 等）仍有字面量（详见 `docs/architecture-map.md` §7.12）。
- `BaseActivity.kt:282` 用 `if (this !is InstanceActivity) setTopbarExit()` 做**向下判断**——任何「让 `RefreshMainActivity` 继承 `RefreshListActivity`」的方案都会把顶栏行为从「打开菜单」变成「点击即返回」。
- **`cell_video_list` / `cell_dynamic_video` 的 id 是跨包事实协议**：改 id 会同时打破 `PrivateMsgAdapter`、`DynamicHolder`、`NoticeHolder`、`OpusContentAdapter`。
- **`notifyItemRangeInserted` 的起点由 adapter 的 `getItemCount()` 决定，`+1` 多数是"头部占位"**：`ReplyAdapter` / `UserDynamicAdapter` / 系列详情内部 adapter 的 `getItemCount()` 都是 `data.size + 1`，通知起点要 `sizeBefore + 1`；而 `VideoCardAdapter` / `ArticleCardAdapter` / `LiveCardAdapter` 无头部、起点就是 `sizeBefore`。**看到 `+1` 先读 `getItemCount()` 再判越界**（26.10.04 批次 3 修掉 4 处真 bug，详见 `docs/architecture-map.md` §7.9）。
- **崩溃页跑在 `:error_activity` 独立进程**（26.10.04 起）：`CatchActivity` 不经过 `PerformanceManager.init` / `ErrorCatch.init` / 未读轮询 / 更新检查，加依赖前先确认它不需要这些初始化；判进程用 `BiliTerminal.currentProcessName()`（读 `/proc/self/cmdline`，`minSdk 24` 用不了 API 28 的 `Application.getProcessName()`）。
- **设备档位参数一律从 `PerformanceManager` 取**（图片质量/宽度、分页大小）：`GlideUtil` 里的 `QUALITY_*` / `MAX_W_*` 四个常量已于 26.10.04 删除，**不要在任何调用点重新写死**，否则又变回"两处真相"（详见 `docs/architecture-map.md` §7.10）。
- **任何"把 APK 交给系统安装器"的路径都必须先过 `ApkVerifier.verify(context, apkFile)`**（26.10.04 批次 4 新增）：现有两条链路（`UpdateManager.downloadApk`→`installApk`、`DownloadActivity.installApk`）都已接线；下载链路上校验失败要**删掉残片**，否则下次 `Range` 续传会把它当成"已下载一部分"。判定为「包名一致 且 签名集合一致」，**读不到签名一律失败关闭**（详见 `docs/architecture-map.md` §7.11）。
- **私信发图（`msg_type=2`）的 content 有硬格式**（26.10.04 批次 5 新增）：`url` 必须是 B 站图床地址（否则 21037「图片格式不合法，不要调戏接口啦」），`width`/`height` 必带（缺了接口不报错、客户端显示异常），`size` 单位是 **KB**。组装一律走纯函数 `PrivateMsgApi.buildImageContent(...)`，别手拼 JSON（详见 `docs/architecture-map.md` §7.14）。
- **通知 ID 不要重号**（26.10.04 批次 5 起有三处）：`DownloadService` = 1027、`PlaybackService` = 1028、`util/MsgNotifier.kt` = 1029。**新消息提醒只在"未读变多"时弹一次**（`MsgNotifier.shouldNotify`，纯函数有单测），触发点是 `BiliTerminal` 既有的未读检查，项目**没有也不需要 WorkManager/AlarmManager**；Android 13+ 的 `POST_NOTIFICATIONS` 在消息页申请，未授权时通知静默不弹（不是 bug）（详见 `docs/architecture-map.md` §7.15）。
- **`model/` 里 `VideoFolder`/`VideoMeta`/`LocalVideo` 实现 `Parcelable`，且字段名就是磁盘 JSON 存储格式**：删改字段必须两端同步。
- `player/` 包不是公共层：`VideoPlayerCore.kt` 已是 `IjkPlayerBridge` 的功能超集，但**还不能直接替换**——`PlayerState`/`IjkOption` 仍定义在 `IjkPlayerBridge.kt:17-36`（删旧类前先搬家），且 `VideoPlayerCore.release()` 有一次性 `released` 标志，`onViewRecycled` 后复用会**泄漏 native 播放器**。动手前先决定它的去留，含糊着抽公共层会造出第 3 套实现。
- **手表右滑返回由 `android:windowSwipeToDismiss` 控制**，它在窗口层直接 `finish()`，**不走 `onBackPressed`**，所以只 override `onBackPressed` 拦不住。且 `onCreate` 里的 `setTheme(ColorScheme.themeResId(theme))` 会覆盖清单上声明的 `Theme.NoSwipe*` —— 要真正禁用它，必须调 `ColorScheme.themeResId(theme, noSwipe = true)` 换用 `Theme.*.NoSwipe.AppCompat`（issue #1 的根因）。

## 原生库

`app/libs/{arm64-v8a,armeabi-v7a,mips,x86}/` 是已提交的 `.so`，日常别动；重建需 NDK r21e（见 `rebuild_all.sh` 等脚本）。
