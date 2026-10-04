# 手表端优化调研：竞品对比、B 站功能面与改造清单

> 调研日期：2026-10-04
> 基线版本：26.10.03（versionCode 2610031，见 `app/build.gradle:23-24`）
> 方法：GitHub REST API 一手查询 + Android 官方文档 + 对 `app/src/main` 源码逐条核对
> 配套文档：`docs/architecture-map.md`（架构）、`docs/review/fix-progress.md`（问题台账）
> **决策汇总：§12 决策台账（2026-10-04 逐条拍板）。本文其余章节与 §12 冲突时，一律以 §12 为准。**

---

## 0. 摘要

**三句话结论：**

1. **在"手表原生适配"这一维度，本项目已经领先目前所有可查到的开源同类客户端。** 表冠（旋冠）滚动、圆屏 WindowInsets、杂牌手表 ROM 能力探针、`MediaSession` + 前台播放服务、听视频模式、多账号切换、高能进度条、AI 字幕、互动视频、番剧选集都已落地；而 `SpaceXC/WearBili`（已停更）与 `SpaceXC/Re-WearBili`（两年未更新）源码树里**既没有旋冠也没有 ambient 常亮屏**，`10miaomiao/bilimiao2` 的手表适配只是"应用内 DPI 缩小 + 默认全屏"。
2. **性能上的主要问题不是"没做优化"，而是"做了优化却没接线"和"该增量刷新的地方用了全量刷新"。** `util/PerformanceManager.kt` 有 **8** 个自适应参数声明后从未被任何代码调用（图像质量、图片最大宽度、OkHttp 连接池/保活、图片过渡、视频预加载、分页大小、高性能判定）；三个 `applyXxxPerfSettings()` 函数体只有一行日志；全库 53 处 `notifyDataSetChanged`。→ **26.10.04 批次 3 已处理：4 个接线、5 个删除、4 处增量刷新真 bug（见 §12.3）**。
3. **最值得投入的三个新方向**：把接口里已有数据的 `view_points` 变成**自动跳过片头/片尾**（✅ 26.10.03 已实现）；加一个 **Baseline Profile 模块**做冷启动优化（26.10.04 拍板暂缓）；**补齐 csrf 实时化**（✅ 26.10.04 批次 2 已把 14 个 api 类共 41 处收敛到 `NetWorkUtil.currentCsrf()`，原述"其余 10 个 api 类仍读静态快照"已过时）。

**优先清单（按 收益 ÷ 成本 排序）：**

| # | 事项 | 类型 | 成本 | 说明 |
|---|---|---|---|---|
| 1 | ~~删除 `x86` ABI~~、视发布需要关掉 `universalApk` | 体积 | — | **26.10.04 拍板：x86 ABI 保留、`universalApk` 维持现状，两项均不做**（见 §12.3 B10） |
| 2 | csrf 实时化补齐到全部写操作 api | 正确性 | 半天 | ✅ 26.10.04 批次 2 已实现（见 §12.3 A1） |
| 3 | `PerformanceManager` 死参数接线或删除 | 性能 | 半天 | ✅ 26.10.04 批次 3 已实现（实测 8 个零调用，见 §12.3 B1） |
| 4 | 自动跳过片头/片尾（复用已有 `view_points`） | 功能 | 1 天 | ✅ 26.10.03 已实现（`player/ViewPointSkip.kt` + `PlayerActivity`，见 §12.2 A9） |
| 5 | Baseline Profile 模块 | 启动 | 1~2 天 | 26.10.04 拍板：暂缓（§12.3 B6） |
| 6 | 列表 `notifyDataSetChanged` → DiffUtil/ListAdapter | 流畅度 | 分期 | 全库 53 处；✅ 26.10.04 批次 3 已修 4 处真 bug（见 §12.3 B5） |
| 7 | 图片统一按设备档位下采样 | 内存 | 1 天 | ✅ 26.10.04 批次 3 已实现（见 §12.3 B3） |
| 8 | 崩溃页独立进程 | 稳定性 | 半天 | ✅ 26.10.04 批次 3 已实现（见 §12.3 B8） |
| 9 | 弹幕点击菜单（点赞/复制/举报） | 功能 | 1~2 天 | PiliPlus 有，本项目暂无 |
| 10 | 动态编辑/置顶/定时发布 | 功能 | 2~3 天 | 发布链路已有，改/顶缺接口封装 |

**优先清单的落地情况（截至 26.10.04）**：#2 csrf 实时化、#3 死参数裁决、#6 列表增量 4 处真 bug、#7 图片档位、#8 崩溃页独立进程**均已实现**（批次 2/3，提交 `94a2b80` / `a683954`）；#4 自动跳过片头/片尾**已实现并提交**（`cefd844`，见 `docs/architecture-map.md` §7.7 与 `player/ViewPointSkip.kt`）；#1 已被拍板撤销（x86 保留）；#5 Baseline Profile 暂缓；**E 组（E4/E5/E6，队列第 4 批）也已于批次 4 落地**（见 §12.5/§12.7 与 `docs/review/fix-progress.md` §十四）。其余各项的最终裁决（想要实现 / 暂缓 / 无计划）一律见 §12。

**本文还包含**：§7.4 是把 B 站客户端全部功能模块（视频/番剧/动态/评论/私信/账号/直播/搜索/本地共 9 组）逐条对照本项目覆盖情况的矩阵，标注 ✅已有 / ❌未做 / ➖建议不做，用于把"B 站有哪些功能"收敛成可判定的待办清单；§10 是**手表相关接口速查 + 风控硬约束**（含 11 条实测约束与错误码语义），供动手实现时直接查。

**一条必须知道的外部变化**：一手接口字典 `bilibili-API-collect`（20,191★）**已于 2026-01-28 因律师函永久关停**，文档站 404。本项目仓库内自带快照 `bilibili-API/`（197 个文件）仍可用，但**不要再以"抄现成端点清单"为长期模式**，且该文档集是 CC BY-NC 4.0，合规风险需在发布前评估（详见 §10.1）。

---

## 1. 调研对象总览

数据采集时间 2026-10-04，均为 GitHub REST API 实时查询 + 仓库 README/源码一手核对。

| 仓库 | ★ / fork | 语言 | 最后提交 | 手表适配 | 参照价值 |
|---|---|---|---|---|---|
| [bggRGjQaUbCoE/PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) | 18,999 / 1,424 | Dart(Flutter) | **2026-10-04** | 无专项 | 功能面与 API 实践的"需求样本" |
| [SpaceXC/Re-WearBili](https://github.com/SpaceXC/Re-WearBili) | 141 / 39 | Kotlin | 2024-12-13 | **强** | 手表端最值得对照的现代实现 |
| [luern0313/WristBilibili](https://github.com/luern0313/WristBilibili) | 142 / 26 | Java | 2023-05-25 | 强 | 源码不完整（依赖作者自研 Lson 库） |
| [SpaceXC/WearBili](https://github.com/SpaceXC/WearBili) | 126 / 6 | Kotlin | 2023-08-05 | **强（已停更）** | 圆屏列表曲率避让等小件 |
| [cyq114514/Re-BiliTerminal](https://github.com/cyq114514/Re-BiliTerminal) | — | Java | 2026-10-02 | 强 | **与本项目同源的兄弟分支** |
| [10miaomiao/bilimiao2](https://github.com/10miaomiao/bilimiao2) | 2,939 / 104 | Kotlin | 2026-09-27 | 弱 | 官方自述"优先适配手机和平板" |
| [CryNet-Studio/KiliKili](https://github.com/CryNet-Studio/KiliKili) | — | Kotlin | — | — | BiliClient 的规范重写 fork（Retrofit），架构对照 |
| [gitee.com/RobinNotBad/BiliClient](https://gitee.com/RobinNotBad/BiliClient) | 71 / 31（Gitee） | Java | 状态「暂停」 | 强 | 本项目上游基线（GitHub 同名仓库 404） |
| [yujincheng08/BiliRoaming](https://github.com/yujincheng08/BiliRoaming) | 11,546 | Java | 2026-07-06 | — | **已 archived**（收到律师函），仅参考签名算法 |
| [SocialSisterYi/bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect) | 20,191 | — | **已永久关停** | — | ⚠️ 上游已因律师函关停（2026-01-28），文档站 404；**本项目已在仓库内自带快照 `bilibili-API/`（197 个文件）** |

**结论：Wear OS 生态里目前没有仍在积极维护的原生 B 站第三方客户端。** Re-WearBili 是最后一代，已两年未更新。这既是本项目的机会窗口，也意味着**没有现成代码可抄，只能自己趟**。

---

## 2. 本项目现状：已经做了什么（避免重复投入）

> 这一节专门用来**纠正"以为没做其实做了"的判断**。下列各项均在 `app/src/main` 中实测存在。

### 2.1 手表交互专项

| 能力 | 实现位置 | 说明 |
|---|---|---|
| **表冠（旋冠）滚动** | `ui/widget/RotaryEncoderSupport.kt`、`RotaryScrollView.kt`、`RotaryRecyclerView.kt`、`RotaryNestedScrollView.kt` | 三个控件共用一份实现；`RotaryEncoderSupport.kt:60-62` 判定 `InputDevice.SOURCE_ROTARY_ENCODER` 并取 `AXIS_SCROLL` 取负；`res/layout/` 下 **53 个布局**（占 134 个布局的 40%）已换成这三个控件 |
| 表冠灵敏度可调 | `util/SettingsKeys.kt:50-52`、`activity/settings/SettingPrefActivity.kt:64-67` | `ui_rotatory_enable`、`ui_rotatory_recycler`、`ui_rotatory_scroll` 三个设置 |
| 表冠翻页（教程） | `tutorial/TutorialPagerActivity.kt:216-233` | 与滚动同向，复用同一套开关 |
| 圆屏安全区 | `activity/base/BaseActivity.kt:146` | `ViewCompat.setOnApplyWindowInsetsListener(root)` 统一处理 |
| 杂牌手表 ROM 能力探针 | `util/ViewCapabilityProbe.kt:8-18` | 起因是真实崩溃：某手表 `View.hasOnLongClickListeners()` 不存在；`BaseActivity.kt:173/275/301` 全部走探针 |
| 禁用系统右滑返回 | `activity/base/BaseActivity.kt:79`、`activity/player/PlayerActivity.kt:326/405-407`、`ui/appearance/ColorScheme.kt:521-524` | ⚠️ 已被证实在 `setTheme` 覆盖清单的主主题下失效，这正是 issue #1 的根因 |
| 列表页统一基类 | `activity/base/RefreshMainActivity.kt`、`RefreshListActivity.kt:59,71,168-173` | 分页/刷新/复位集中一处 |

**对比结论：** `WearBili` 与 `Re-WearBili` 的源码树中**检索不到 rotary / ambient 相关实现**（见 §1 表与 §7 来源）。旋冠不是本项目的待办，而是已经领先的部分。

### 2.2 播放与后台

| 能力 | 实现位置 |
|---|---|
| `MediaSession`（锁屏/通知栏控制） | `activity/player/PlayerActivity.kt:14,175,1964-2026`，含 `FLAG_HANDLES_MEDIA_BUTTONS or FLAG_HANDLES_TRANSPORT_CONTROLS` |
| 后台播放前台服务 | `service/PlaybackService.kt:36,57-79,154,170-175`（通知 + TOGGLE/STOP 动作） |
| 听视频模式（关画面只听声） | `PlayerActivity.kt:2373-2454`，设置 `PLAYER_DEFAULT_AUDIO_ONLY` |
| 倍速（含长按 3 倍速） | `player/VideoPlayerCore.kt:333`、`PlayerActivity.kt:724-766,1077-1102,2229-2231` |
| 字幕（多轨 / AI 字幕 / 时间校准 / 自动选中文） | `PlayerActivity.kt:1330-1474`、`player/PlayerDefaults.kt:59-72`、`api/PlayerApi.java:596-708` |
| 高能进度条 | `ui/widget/HighEnergyProgressBar.kt`、`api/PlayerApi.java:712-760`、`model/HighEnergyData.java` |
| 弹幕发送 + 直播弹幕接收 | `api/DanmakuApi.java:27`、`PlayerActivity.kt:1957` |
| 视频分段/看点跳转 | `api/PlayerApi.java:633-650`、`adapter/ViewPointAdapter.kt`、`PlayerActivity.kt:2820-2863` |
| 互动视频分支 | `api/InteractionVideoApi.java`、`model/InteractionVideoData.java` |
| 续播 + 进度上报 | `api/HistoryApi.java:38-44,74-75,107-119`（`currentCsrf()` 从实时 Cookie 派生） |

### 2.3 账号与内容

- **多账号切换**：`activity/settings/login/AccountSwitchActivity.kt`（`AndroidManifest.xml:557`），点按切换、长按删除。
- **三种登录 + Cookie 导入导出**：扫码（WEB/TV）、密码、短信、`SpecialLoginActivity`（`AndroidManifest.xml:256`）。
- **番剧选集**：`activity/video/info/BangumiInfoFragment.kt:258,263,337-339,357-359,368`、`api/BangumiApi.java:59,221-224`、`model/Collection.java`。
- **动态发布（含话题、可见范围）**：`api/DynamicApi.java:84-136` `publishComplex(contents, pics, option, topic, scene, attachCard, otherArgs)`；`:267` `private_pub`。
- **图片上传带手表保护**：`activity/reply/WriteReplyActivity.kt:48-54,236,306`、`api/ImageApi.java:113,247` 按最长边 2048 采样，避免手表解码大图 OOM。

**结论：** §0 表中"功能新增"一节**已经剔除了所有本项目已实现的能力**（后台播放、通知栏控制、多账号、高能进度条、字幕、番剧选集、旋冠、圆屏适配都不再列为待办）。竞品调研中列出的 13 条"可借鉴点"，有 5 条本项目已经做了或做得更好。

### 2.4 风控与签名基础设施（已做得相当完整）

这一节同样是"避免重复投入"——B 站第三方客户端最容易翻车的地方，本项目**基本都已实现**：

| 能力 | 实现位置 | 说明 |
|---|---|---|
| **WBI 签名** | `api/ConfInfoApi.signWBI(url)`（`api/PlayerApi.java:630` 等多处在用） | 对应官方 `wbi_img.img_url/sub_url` → 拼 `img_key+sub_key` → 64 位重排表取前 32 位得 `mixin_key` → 参数升序 + 过滤 `!'()*` + 拼 `mixin_key` → MD5 得 `w_rid`。**密钥每日更替，需缓存与刷新** |
| **buvid3 / buvid4** | `api/CookiesApi.java:89-195`（`x/web-frontend/getbuvid`、`x/frontend/finger/spi`） | ⚠️ 这是关键：点赞/投币/一键三连等写接口**不带 buvid3 会直接触发风控**。本项目已生成并持久化 |
| **bili_ticket** | `api/CookiesApi.java:111-216`（`GenWebTicket`，`key_id=ec02` + `hexsign` + `context[ts]`） | 带过期时间（写入 `bili_ticket_expires`，+3 天）自动续期 |
| **b_nut / buvid_fp 设备指纹** | `api/CookiesApi.java:175-177,224-226,281,310` | BUVID 与设备绑定（首次安装上报 AndroidID 等换回云端 BUVID，**重装仍被识别**）→ 持久化是对的，**不要每次重装随机换指纹** |
| **游客态 Cookie 构建** | `util/NetWorkUtil.java:208`《从完整 Cookie 字符串构建游客 Cookie：剔除登录相关项，保留 buvid3/buvid4/bili_ticket/_uuid》 | 未登录也能带着设备身份请求，降低 `-352` 概率 |
| **Cookie 刷新链** | `api/CookieRefreshApi.java:81,94` | 对应 `cookie/info` → `correspond/{correspondPath}` → `cookie/refresh` → `confirm/refresh` 四步；不做这条链用户就要反复重新扫码 |
| **写接口携带设备身份** | `api/UserInfoApi.java:285,312,340-351` | 显式把 `buvid3/buvid4/bili_ticket` 拼进请求 |

**结论：** 本项目在"不被风控打死"这件事上的投入已经超过多数第三方客户端。**唯一仍缺的是 §5.1 的 `csrf` 实时化**——而 `csrf`（`bili_jct`）恰恰也会随 Cookie 轮换而变，两者是同一类问题的两个面。

---

## 3. 手表端硬约束（Android 官方，必须满足）

来源：[Wear OS app quality](https://developer.android.com/docs/quality-guidelines/wear-app-quality)、[Rotary input](https://developer.android.com/training/wearables/user-input/rotary-input)、[Layouts](https://developer.android.com/training/wearables/views/layouts)。

**必须满足项：**

- **WO-V2**：触控目标 ≥ 48×48dp。
- **WO-V1**：适配系统字号放大后不得重叠/裁切（本项目已有"界面缩放 0.25~5 倍"+ 自定义字体，需真机复核）。
- **WO-V3**：几乎全部页面必须支持右滑关闭（地图平移、进行中运动除外）。
- **WO-V8**：滚动时必须显示滚动条。
- **WO-V13**：背景一律纯黑。
- **WO-V14**：关键文本 ≥ 12sp、非关键 ≥ 10sp。
- **WO-V15**：启动闪屏为黑底 + 48×48dp 图标，须与启动器图标一致。
- **WO-V16**：内容不小于 192dp 圆，文字/控件不得互相重叠或被边缘裁切。
- **WO-V4**：长时操作须用 OngoingActivity / Live Update 通知（本项目后台播放已有通知，下载服务需复核）。
- **WO-V5**：离开前台保存状态，数分钟内恢复须还原。
- **WO-P6**：**认证不得要求手表端输入账号密码**。本项目扫码登录满足该项；但**密码/短信登录入口的存在是否会触发审核问题，需要在发布前确认**（合规上通常是"必须提供免输入路径"，而非"禁止存在输入路径"）。
- WO-V7（必须实现旋冠滚动）已于 **2024-02-14 移除**，本项目已自行实现，属超额满足。

**旋冠实现要点（对照本项目）：**

- 旋转事件**不沿视图树冒泡**，API 28+ 视图不再隐式获焦 → 无焦点或返回 false 时会落到 `Activity.onGenericMotionEvent()`。`RotaryEncoderSupport` 走的是控件级 `onGenericMotionEvent`，需确认焦点策略（`RotaryScrollView.kt:25` 传了 `requestFocus = true`）。
- 多滚动视图只能指定一个焦点，**旋冠不支持嵌套滚动** → `activity_player.xml` 内有 4 个 `RotaryRecyclerView`、`fragment_media_info.xml` 是 `RotaryNestedScrollView` 套 `RotaryRecyclerView`，属高风险点，建议在真机上逐个验证手势归属。
- 输入框抢焦点后要提供夺回手段。

**圆屏：**

- 官方推荐 `BoxInsetLayout` + `app:layout_boxedEdges="all"`（外层 15dp + 内层 5dp）；`WatchViewStub` **已废弃**。
- 本项目未用 `BoxInsetLayout`，走的是 `BaseActivity.kt:146` 的 WindowInsets → padding 手写等价物，方向正确；**单屏页面**（非滚动列表）最容易被裁角，需按 WO-V16 逐页复核。

---

## 4. 本项目性能体检（实测数据）

### 4.1 体积构成（实测）

对已构建的 `app/build/outputs/apk/release/app-arm64-v8a-release.apk`（9.64 MB）解包统计：

| 项 | 原始大小 | 占比 |
|---|---|---|
| `lib/`（native） | 6.70 MB | **56.5%** |
| `classes.dex` | 3.34 MB | 28.2% |
| `res/` | 1.28 MB | 10.8% |
| `resources.arsc` | 0.49 MB | 4.1% |

单文件 Top：`lib/arm64-v8a/libijkffmpeg.so` **5.04 MB**、`classes.dex` 3.34 MB、`libbrotli.so` 0.78 MB、`libijkplayer.so` 0.51 MB。

各产物实测大小：`arm64-v8a` 9.64 MB / `armeabi-v7a` 8.21 MB / `x86` 10.63 MB / **`universal` 22.63 MB**。

**结论：**
- 公共代码只有约 3.3 MB dex + 1.3 MB res，**"清理死代码"对体积几乎没有收益**；真要减体积必须重编 ffmpeg（裁剪解码器）。这一点与 §1 中 `Re-WearBili` 保留 ijkplayer 独立 so 模块的做法一致。
- `app/build.gradle:140-146` 打包了 `armeabi-v7a / arm64-v8a / x86` 并额外生成 universal 包。**Wear OS 手表全是 ARM，`x86` 只服务模拟器**，属发布噪音；`universalApk true` 产出的 22.63 MB 包也没有分发价值。

### 4.2 `PerformanceManager` 的"自适应"名不副实

> **26.10.04 批次 3 已按 §12.3 B1/B2/B3 处理完毕，本节描述的是处理前的状态。** 结论：死参数实测 **8 个**（多一个 `isHighPerformanceMode()`）；`getImageQuality`/`getImageMaxWidth` 已接进 `GlideUtil`、`getPageSize` 已接进 3 处分页，其余 5 个已删除；首次检测已挪到 `CenterThreadPool`；顺带删掉了下面提到的 `LOGI_ENABLED` 短路绕法。

`util/PerformanceManager.kt`（320 行）设计上是按硬件打分（RAM/CPU 核数/主频/SDK，阈值 65 高、35 中）分档调节运行时参数。实际情况：

**已接线（真的在用）：**

| Getter | 调用点 |
|---|---|
| `getGlideDiskCacheSizeMB` / `getGlideMemoryCacheSizeMB` / `isHardwareBitmapEnabled` | `helper/CustomGlideModule.kt:27,28,38` |
| `isLowPerfDevice` | `activity/MenuActivity.kt:166` |
| `getRecyclerViewCacheSize` / `getRecyclerViewPrefetchCount` | `activity/base/RefreshListActivity.kt:59,71` |
| `setHighPerformanceMode` / `getCurrentPerfLevel` / `KEY_HIGH_PERFORMANCE_MODE` | `activity/settings/SettingGroupActivity.kt:434-438` |

**声明后零调用点（死参数）：**

`getImageQuality()`（:233）、`getImageMaxWidth()`（:240）、`getOkHttpConnectionPoolSize()`（:247）、`getOkHttpKeepAliveMinutes()`（:253）、`isImageTransitionEnabled()`（:259）、`isVideoPreloadEnabled()`（:265）、`getPageSize()`（:268）。

**三个策略函数是空壳：**

```kotlin
private fun applyLowPerfSettings() {          // :189  注释写着"低性能设备优化策略（手表等）"
    Logu.i("PerformanceManager", "应用低性能优化策略")
}
```

`applyMediumPerfSettings()`（:194）、`applyHighPerfSettings()`（:198）同样只有一行日志。也就是说**"高性能模式"开关除了改变 Glide/RecyclerView 的取值外，不做任何事**。

**主线程 IO：** `init()`（:110-143）在 `BiliTerminal.onCreate`（`BiliTerminal.kt:209`）同步调用；首次冷启动时 `getPerformanceLevel()` → `getCpuMaxFreqMHz()`（:289-319）会读 `/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq`，失败时回退逐行解析 `/proc/cpuinfo`。第二次起命中 `KEY_DEVICE_PERFORMANCE_LEVEL` 缓存不再执行。**:136-141 已经有一处刻意优化**（用 `LOGI_ENABLED` 短路，避免 release 版每次冷启动白跑一次 `getHardwareScore()`），说明作者已经注意到这条路径的代价——**把首次检测整体挪到后台线程即可彻底解决**。

### 4.3 列表刷新

- 全库 `notifyDataSetChanged` **53 处**。
- 全库 `.override(` / `.thumbnail(` 仅 **6 处**，而 `Glide.with` 有 54 处。

### 4.4 图片链路（比预想的好）

`util/GlideUtil.java`：`url()` 会给 http 图拼 `@0e_{q}q_{w}w.webp`（`q` 由 `image_request_jpg` 开关决定，`w` 取 `QUALITY_HIGH=80 / MAX_W_HIGH=1024` 或 `QUALITY_LOW=60 / MAX_W_LOW=512`），已跳过 gif / `@` 签名 / afdian 图；`request()` 用 `DecodeFormat.PREFER_RGB_565` + `DiskCacheStrategy.AUTOMATIC` + 300ms 淡入。

**所以"图片没下采样、内存爆"的判断是不成立的**——服务端已经按 512w 压过、客户端按 RGB_565 解码。真正缺的是**按设备档位把 `w` 降到 256~320**（400×400 的手表屏封面 256px 足够），而这正好是那个没人调用的 `getImageMaxWidth()`。

### 4.5 构建配置

- `app/build.gradle:59-60` release 开 `minifyEnabled true` + `shrinkResources true`（正确）。
- `app/build.gradle:114-118` **`viewBinding false`、`dataBinding false`** → 全库 `findViewById`。这不仅是性能问题（每次 bind 都要查视图树），更是稳定性问题：`adapter/SettingsAdapter.kt:108`、`activity/base/BaseActivity.kt:204,238` 的注释里都记录着真实发生过的 `ClassCastException` 崩溃。
- `app/src/main/AndroidManifest.xml:36` **`android:largeHeap="true"`**。注意：`Re-WearBili` 也开了这一项，所以不能简单判定为错误；但本项目的图片链路已经比它保守（RGB_565 + 服务端缩图），**建议实测内存曲线后再决定是否移除**。

---

## 5. 代码正确性风险（影响"看起来有但用不了"的体验）

### 5.1 csrf 实时化只修了一半 ⚠️

兄弟分支 `Re-BiliTerminal` 修过的 ① 号坑是"进度上报读本地快照 csrf/mid → Cookie 轮换后所有 POST 返 `-111`"。

**本仓库只修了 `api/HistoryApi.java`**（`:38-44,74-75,107-119` 的 `currentCsrf()` 从实时 Cookie `bili_jct` 派生，并在为空时 `Logu.e` 明确提示"上报必被服务端拒绝(-111)"）。

**其余写操作 api 仍读静态快照 `SharedPreferencesUtil.getString("csrf","")`：**

| 文件 | 行号 |
|---|---|
| `api/DanmakuApi.java` | 32, 44, 55, 67 |
| `api/FavoriteApi.java` | 295, 307, 320, 334, 345 |
| `api/LikeCoinFavApi.java` | 21, 30, 39, 50 |
| `api/WatchLaterApi.java` | 49, 60 |
| `api/ReplyApi.java` | 182, 279, 313, 333 |
| `api/DynamicApi.java` | 64, 90, 340, 447, 464 |
| `api/OpusApi.java` | 282, 283, 288 |
| `api/PrivateMsgApi.java` | 229, 244, 251, 252 |
| `api/VoteApi.java` | 45, 76, 78, 92, 93, 233, 235, 240, 241 |
| `api/ArticleApi.java` | 120, 146, 167, 184 |
| `api/EmoteApi.java` | 52, 92, 118, 154 |

已改用实时 Cookie 的：`api/MessageApi.java:406,423`、`api/UserInfoApi.java:207,228,246,263,282,337`、`api/ReplyApi.java:234`、`api/VipApi.java:72`、`api/CookieRefreshApi.java:81,94`。

**影响：** 点赞、投币、收藏、加稍后再看、发弹幕、发评论、发动态、投票、私信、专栏/表情操作。手表上用户很难判断是"网不好"还是"没登上"，属于最伤的体验级 bug。

### 5.2 未接线的 P0 项（来自 `docs/review/fix-progress.md` §五）

已迁移到台账，此处不重复，仅提示这批问题**集中在"接口调用了但 UI 状态没回"**这一类，对手表小屏的影响比手机更大（用户没有其他途径确认操作是否生效）。

### 5.3 网络层（本仓库比兄弟分支更保守，属优点）

`util/NetWorkUtil.java`：

- `executeWithDoctypeRetry`（:299-350）**只被 `get()`（:296）调用**；
- `post()` / `postJson()`（:367-395）**不走重试**；
- 重试上限 `api_retry_max_times` 默认 5、间隔 `api_retry_interval_seconds` 默认 0.1s；`"Canceled"` 时不重试。

**结论：兄弟分支的 ⑥ 号坑（弱网下写操作被自动重发导致重复点赞/投币）在本仓库不成立。** 后续若要给 POST 加重试（弱网手表确实需要），**必须显式排除写操作**。

同一分支的 ⑤ 号坑（搜索 suggest 词表从 `data` 移到 `result`）**本仓库也已修**：`api/SearchApi.java:234-243` 直接读 `result.tag[].value`。

### 5.4 其他"有 UI 无逻辑"项

- **评论点踩按钮是死视图**：`adapter/ReplyAdapter.kt:495` 只做了 `findViewById(R.id.dislikeBtn)`，全库再无引用 —— 无点击监听、无状态绑定，用户点了没有反应。属 §5.2 同一类问题，建议并入台账。

---

## 6. 竞品借鉴点逐条裁决

对调研收集到的 13 条借鉴点，逐条给出"本仓库是否已有"的裁决（**已有的不再列为待办**）：

| # | 借鉴点 | 来源 | 本仓库状态 |
|---|---|---|---|
| 1 | 禁右滑退出主题 | WearBili `theme_without_swipe.xml` | ✅ 已有（`ColorScheme.kt:521-524`），但存在 `setTheme` 覆盖失效 bug（issue #1） |
| 2 | **Baseline Profile 模块** | Re-WearBili `baselineprofile/` | ❌ **未做**（`app/` 下无 source baseline profile，只有 AGP 在 `build/intermediates` 生成的） |
| 3 | 全局密度缩放（372dp 基准） | Re-WearBili `DensityProvider.kt` | ✅ 已有等价物（"界面缩放 0.25~5 倍"）；可参考它的 `widthPixels / 372.0f` 算法 |
| 4 | 圆屏列表曲率避让 + 跑马灯标题 | WearBili | ⚠️ 部分：有 WindowInsets 安全区，**无列表项曲率避让、无跑马灯** |
| 5 | 观看进度同步 | Re-BiliTerminal | ✅ 已有（续播 + 上报 + `HistoryApi.currentCsrf()`） |
| 6 | 番剧选集 | Re-BiliTerminal | ✅ 已有 |
| 7 | 后台/熄屏播放 + 通知栏遥控 | Re-WearBili / Re-BiliTerminal | ✅ 已有（`PlaybackService` + `MediaSession` + 听视频模式） |
| 8 | 列表复用结构（Holder 抽离） | huanli233 BiliClient | ⚠️ 部分：已有 `adapter/video/VideoCardHolder.kt`，但 `new VideoCard(` 仍有 21 处解析重复 |
| 9 | 播放器交互增强 | Re-BiliTerminal | ✅ 大部分已有（长按倍速、双击快进退、听视频、字幕、看点） |
| 10 | WebDAV 备份/恢复设置 | PiliPlus | ❌ 未做（手表端价值低，**建议不做**） |
| 11 | 多账号切换 | Re-BiliTerminal / PiliPlus | ✅ 已有 |
| 12 | **崩溃页独立进程 + 崩溃上报** | Re-WearBili / WearBili | ❌ 未做（本项目用 `ErrorCatch`，未隔离进程） |
| 13 | 旋冠全列表接入 | 调研认为"无现成实现" | ✅ **本仓库已实现且做得比调研对象都好** |

---

## 7. 待办清单

### 7.1 性能 / 体积 / 启动

| 优先级 | 事项 | 位置 | 做法 |
|---|---|---|---|
| P0 | 去掉 `x86` ABI | `app/build.gradle:140-146` | `include 'armeabi-v7a', 'arm64-v8a'`；`universalApk` 按发布需要保留 |
| P0 | csrf 实时化 | 见 §5.1 清单 | ✅ 26.10.04 批次 2 已实现：收敛到 `util/NetWorkUtil.java:432 currentCsrf()`（+ `:443 pickCsrf()` 纯逻辑），并在落 Cookie 时回写快照 |
| P0 | `PerformanceManager` 死参数 | `util/PerformanceManager.kt` | ✅ 26.10.04 批次 3 已实现：`getImageQuality`/`getImageMaxWidth` 接进 `GlideUtil`、`getPageSize` 接进 3 处分页，其余 5 个删除 |
| P1 | Baseline Profile | 新增 `baselineprofile` 模块 | 26.10.04 拍板**暂缓**（§12.3 B6）；用 `androidx.baselineprofile` 插件 + `BaselineProfileGenerator`，注意 AGENTS.md 的 clean 构建约定 |
| P1 | 首次硬件检测挪后台 | `util/PerformanceManager.kt` | ✅ 26.10.04 批次 3 已实现：无缓存时先落中档立即返回，检测丢 `CenterThreadPool` |
| P1 | 图片按档位降 `w` | `util/GlideUtil.java:url()` | ✅ 26.10.04 批次 3 已实现，但**粒度按手表口径修正**：列表图低端 320w/50q、其余 512w/60q；大图低端 512w/60q、其余 1024w/80q（不是原述的 256/320/512） |
| P1 | 列表增量刷新 | 53 处 | ✅ 26.10.04 批次 3 已修 4 处真 bug（3 个搜索页起点 `+1`、`UserSeriesActivity` 根本没加数据）；`DiffUtil.ItemCallback` 化仍未做 |
| P2 | 打开 viewBinding | `app/build.gradle:114-118` | 分期迁移；先对新代码启用 |
| P2 | OkHttp 连接池接线 | `NetWorkUtil` 构建 OkHttpClient 处 | 26.10.04 拍板**暂缓**（§12.3 B4）；相关两个 getter 已在批次 3 删除 |
| P2 | 崩溃页独立进程 | `AndroidManifest.xml` | ✅ 26.10.04 批次 3 已实现：`:error_activity` 独立进程 + 错误进程最小初始化 + 删掉 300ms 硬等 |
| P2 | 更新包完整性校验 | `util/UpdateManager.kt` / `activity/DownloadActivity.kt` | ✅ 26.10.04 批次 4 已实现：新增 `util/ApkVerifier.kt`（**包名一致 + 签名集合与已安装应用一致**，读不到签名失败关闭），两条安装链路都已接线；**未用哈希**（与安装包同一响应，无增量价值，见 §12.5 E6） |
| P3 | ffmpeg 裁剪重编 | `ijkplayer-java` / so | 唯一能显著减体积的手段，成本高，需 NDK 工具链 |

### 7.2 功能新增（已剔除本项目已有项）

| 优先级 | 功能 | 依据 | 说明 |
|---|---|---|---|
| **P0** | **自动跳过片头/片尾** | 数据已就位：`api/PlayerApi.java:627-651` 从 `x/player/wbi/v2` 解析 `view_points`，`model/ViewPoint.java:7` 的 `type` 字段（1=片头 / 2=片尾，语义以 bilibili-API-collect 为准）**已被解析并存下，但全库无任何地方读取它做跳转** | 在播放位置进入 `type==1/2` 区间时自动 `seekTo(to)`；加设置开关 + "每段只跳一次"保护 + 用户手动拖回后不再跳 |
| **P0** | 弹幕点击菜单 | PiliPlus | 点弹幕 → 悬停 → 点赞/复制/举报；手表上"复制"价值有限，"举报/屏蔽"可留 |
| P1 | ~~SponsorBlock~~ **自动空降** | PiliPlus | ⚠️ **纠偏**：SponsorBlock 官方 README 只服务 YouTube（支持 Invidious），**全文未提 B 站**。B 站生态的"空降"只能靠 ①官方 PGC 片头片尾/章节看点（`x/player/wbi/v2` 的 `view_points`）②高能进度条 `https://bvc.bilivideo.com/pbp/data`（返回 `step_sec` + `events.default[]`）③自建众包。→ **不要引入 SponsorBlock 依赖** |
| P1 | 画中画（PiP） | PiliPlus | 全库无 `enterPictureInPicture`；手表上价值中等（小屏 PiP 体验有限），可延后 |
| P1 | 评论点踩 | PiliPlus | ✅ **26.10.04 批次 2 已实现**（原为死视图）：`adapter/ReplyAdapter.kt` 已绑监听与「已踩」高亮，走 `ReplyApi.dislikeReply()` → `x/v2/reply/hate`；`model/Reply.java` 新增 `disliked` 与 `parseAction()`（原来把 `action==2`「已踩」错当成「无操作」）。点踩/点赞服务端互斥，客户端同步撤另一侧状态 |
| P1 | 评论举报 / 删除 / 置顶自己的评论 | PiliPlus | 接口 `x/v2/reply/report`、`/del`、`/top`（后两个需 csrf） |
| P1 | 评论楼中楼排序/定位 | PiliPlus | 已有 `activity/reply/ReplyInfoActivity.kt`（楼中楼），排序/定位待补；游标接口 `x/v2/reply/dialog/cursor` |
| P1 | 动态编辑 / 置顶 / 定时发布 | PiliPlus | 发布链路已有（`DynamicApi.publishComplex`）；缺的接口都已确认存在：置顶 `x/dynamic/feed/space/set_top` + `/rm_top`、删除 `dynamic_svr/rm_dynamic`、传图 `x/dynamic/feed/draw/upload_bfs`、投票 `vote_svr/create_vote`（后三者需 csrf） |
| P1 | 私信：发图 / 撤回 / 置顶 / 折叠消息 | PiliPlus | 文本收发已有（`api/PrivateMsgApi.java`）；缺 `session_svr/remove_session`、`/set_top`、`batch_rm_dustbin`、`batch_update_dustbin_ack`（均需 csrf），发图需先走 upload 拿 url |
| P1 | 关注主播开播提醒 | —— | 接口现成且轻量：`live.bilibili.com/room/v1/Room/get_status_info_by_uids`（按 uid 批量查开播状态，免登录）；配合 WorkManager 定期轮询 → 本地通知。**手表高价值、低实现成本** |
| P2 | 弹幕点赞 / 撤回自己的弹幕 | PiliPlus | `x/v2/dm/thumbup/add`、`x/v2/dm/thumbup/stats`、`x/dm/recall` |
| P2 | 稍后再看「未看完」分类 | PiliPlus | 列表已有（`api/WatchLaterApi.java:23` 走 `x/v2/history/toview/web`），仅缺按 `progress` 分组 |
| P2 | 笔记 | PiliPlus | 全库无实现；手表端输入体验差，建议不做或只做"查看" |
| P2 | 搜索建议 `data` / `result` 双字段兼容 | 兄弟分支 ⑤ 号坑 | ✅ **已有**：`api/SearchApi.java:234-243` 已按 `result.tag[].value` 解析 |
| P2 | 收藏夹排序 / 多选删除 | PiliPlus | 手表上多选操作可用表冠，成本中 |
| P2 | 滑动跳转预览缩略图 | PiliPlus | 需 `storyboard` 接口 + 每帧图片，手表功耗敏感，**建议不做** |
| P3 | WebDAV 备份 / 恢复设置 | PiliPlus | 与"轻量手表客户端"定位不符 |
| P3 | DLNA 投屏、超级分辨率、Live Photo、AI 原声翻译、互动视频增强 | PiliPlus | 手表端明确不适合 |

### 7.3 明确不做

创作中心、会员购、漫画、课堂、直播礼物/舰长、多窗口、桌面小组件 —— 与手表使用场景不匹配，做了只会增加体积与维护面。

### 7.4 B 站客户端功能全景对照（"B 站有哪些功能" × 本项目覆盖情况）

> 功能项取自 PiliPlus README 的实战清单（功能面最全的开源客户端），逐条对照本项目源码判定。✅=已有，❌=未做，➖=建议不做。

**视频播放**

| 功能 | 本项目 | 功能 | 本项目 |
|---|---|---|---|
| 多清晰度 / 音质切换 | ✅ | 自动跳过片头/片尾 | ❌ 见 7.2 P0 |
| 倍速（含长按） | ✅ | SponsorBlock | ❌ |
| 弹幕开关 / 透明度 / 字号 / 速度 | ✅ | 高能进度条 | ✅ |
| 弹幕发送 / 屏蔽词 | ✅ | 视频分段 / 看点 | ✅ |
| 弹幕点击点赞/复制/举报 | ❌ 见 7.2 P0 | 滑动跳转预览缩略图（storyboard） | ➖ |
| 高级弹幕 / 合并弹幕 / 彩色弹幕 | ❌/➖ | 超分辨率 | ➖ |
| 字幕（多轨 / AI / 校准） | ✅ | 视频截图 / 截取动图 | ❌/➖ |
| 互动视频 | ✅ | AI 原声翻译 | ➖ |
| 画中画（PiP） | ❌ | 听视频（纯音频） | ✅ |
| DLNA 投屏 | ❌ | 视频 TAG / staff | ❌ |
| 外挂播放器（FileProvider 授权） | ✅ | 亮度 / 音量手势 | ❌ |

**番剧 / 影视**

| 功能 | 本项目 | 功能 | 本项目 |
|---|---|---|---|
| 番剧选集 | ✅ | 追番 / 取消追番 | ✅ |
| 时间表 | ✅（`TimelineActivity`） | 多季 / OVA 直达 | ✅ |

**动态**

| 功能 | 本项目 | 功能 | 本项目 |
|---|---|---|---|
| 浏览器动态流 / 图文动态 | ✅ | 编辑动态 | ❌ |
| 动态发布（文字 / 图 / 话题 / 可见范围） | ✅ | 置顶 / 删除动态 | ❌ 部分 |
| 转发动态 | ✅ | 投票创建 / 参与投票 | ✅ 参与 |
| 带图动态、图片评论 | ✅ | 动态话题页 | ❌ |
| 屏蔽带货动态 | ❌ | 互动抽奖 / 预约 | ➖ |

**评论**

| 功能 | 本项目 | 功能 | 本项目 |
|---|---|---|---|
| 评论列表 / 楼中楼 | ✅ | 评论点踩 | ❌ 死视图 |
| 发评 / 回复 / @用户 / 表情 | ✅ | 楼中楼排序 / 定位 | ❌ |
| 评论点赞 | ✅ | 保存评论 | ❌ |
| 评论举报 | ❌ | 评论图片（发图） | ❌ 部分 |
| 取消 / 置顶自己的评论 | ❌ | 评论反诈提示 | ➖ |

**私信 / 消息**

| 功能 | 本项目 | 功能 | 本项目 |
|---|---|---|---|
| 私信列表 / 收发文本 | ✅ | 私信发图 | ❌ |
| 消息未读数 | ✅ | 删除 / 撤回 / 置顶私信 | ❌ |
| 回复我的 / @我的 / 收到的赞 | ✅ | 消息设置 / 聊天设置 | ✅（`MessageSettingsActivity`） |
| 系统通知 / 公告 | ✅ | 分享视频/番剧/动态/专栏/直播至消息 | ❌ |

**账号 / 收藏 / 历史**

| 功能 | 本项目 | 功能 | 本项目 |
|---|---|---|---|
| 扫码 / 密码 / 短信登录 | ✅ | 收藏夹排序 / 复制 / 移动 | ❌ |
| Cookie 导入导出 | ✅ | 收藏夹多选删除 | ❌ |
| 多账号切换 | ✅ | 稍后再看 +「未看完」分类 | ✅ 基础 |
| 观看历史 / 进度同步 | ✅ | 关注分组增删改 | ❌ |
| 观看记录（登录设备 / 硬币 / 经验） | ✅ | 移除粉丝 | ❌ |
| 个人空间 / 编辑资料 / 头像 | ✅ | 记笔记 | ❌ |
| 勋章墙 / 大会员 | ✅ | WebDAV 备份 / 恢复 | ➖ |

**直播**

| 功能 | 本项目 | 功能 | 本项目 |
|---|---|---|---|
| 直播列表 / 分区 / 关注直播 | ✅ | 直播弹幕发表情 | ❌ |
| 直播播放 + 弹幕接收 | ✅ | SuperChat | ❌ |
| 直播礼物 / 舰长 | ➖ | 开播 | ➖ |

**搜索 / 发现**

| 功能 | 本项目 | 功能 | 本项目 |
|---|---|---|---|
| 搜索（视频 / 番剧 / 用户 / 专栏） | ✅ | 热搜 | ✅ |
| 搜索建议 | ✅ | 筛选 / 排序搜索 | ✅（`SearchSortActivity`） |
| 搜索用户动态 | ❌ | 排行榜 / 热门 / 推荐 | ✅ |

**本地 / 系统**

| 功能 | 本项目 | 功能 | 本项目 |
|---|---|---|---|
| 离线缓存 / 本地播放 | ✅（`DownloadService`） | 后台 / 熄屏播放 + 通知栏遥控 | ✅ |
| 外部播放器接管 | ✅ | 崩溃页独立进程 | ❌ |
| 主题 / 外观 / 字体 / DPI | ✅ | 教程 / 更新检查 / 更新日志 | ✅ |

**该表的用途**：把"B 站功能"从"多到不知道选什么"收敛成**可判定的待办**——❌ 且不在 7.3 的项就是候选；➖ 的项直接排除，避免无效投入。

---

## 8. 交互与无障碍建议（手表专项）

- **旋冠优先于拖动**：表冠在圆形小屏上是精度最高的连续输入，优先用它做列表滚动、进度调节（本项目已有旋冠，**建议把"音量/进度"也接入 `RotaryEncoderSupport`，而不只是滚动**）。
- **反模式**：小圆屏不做边缘侧滑抽屉（与系统 swipe-to-dismiss 冲突）；长按不作唯一入口；双击缩放/双指捏合/精确拖拽选值在手表上不适用。
- **跑马灯**：只用于"当前聚焦的单条标题"，不用于多行正文或批量列表（`WearBili` 的 `MarqueeTextView.kt` 是这条的现成范式）。
- **弱网写操作**：一律不自动重试，只对 GET 重试（本项目现状已符合，见 §5.3，需保持）。
- **无障碍**：所有图标按钮补 `contentDescription`；TalkBack 走通主流程；无键盘场景用"旋冠选择 + 单击确认"作主路径，语音（`RecognizerIntent`）仅兜底；搜索优先点选历史/热搜候选词而非手输。
- **后台任务**：官方倾向 `WorkManager`/`JobScheduler` 而非自建 Service（本项目 `DownloadService` 是自建前台服务，功能上没错，但需确保 `foregroundServiceType` 声明完整：`AndroidManifest.xml:19-22` 已有 `FOREGROUND_SERVICE` / `_DATA_SYNC` / `_MEDIA_PLAYBACK`）。
- **常亮屏（ambient）**：本项目未实现；但官方原则是"尽量减少 always-on 特性使用"，**考虑到手表电量，不建议新增**。

---

## 9. 建议的落地顺序

> **本节已被 §12.7「已确认的 8 批落地顺序」取代**，保留仅作调研记录；与 §12 冲突一律以 §12 为准。下面这段里的「x86 ABI」「11 个 api 类」「接线 `getOkHttpConnectionPoolSize`」等表述均为 26.10.04 拍板前的原稿。

**迭代 1（1~2 天，纯收益、低风险）**
1. 去掉 `x86` ABI。
2. `PerformanceManager` 死参数裁决（接线 `getImageMaxWidth` / `getOkHttpConnectionPoolSize`，删除或标 TODO 其余）。
3. 首次硬件检测挪到后台线程。

**迭代 2（2~3 天，正确性）**
4. csrf 实时化铺开到 §5.1 的 11 个 api 类（抽公共工具，配 JVM 单测）。
5. 自动跳过片头/片尾（复用 `view_points`，加设置开关与"仅跳一次"保护）。

**迭代 3（1 周，体验）**
6. Baseline Profile 模块 + 冷启动基准测试。
7. 列表 DiffUtil 改造（先做推荐/热门/搜索三个高频页）。
8. 弹幕点击菜单。

**暂缓**：WebDAV、多账号增强（已有）、PiP、笔记、投屏。

---

## 10. B 站公开接口面与风控要点（一手来源）

### 10.1 来源状况：上游接口字典已永久关停 ⚠️

- `SocialSisterYi/bilibili-API-collect`（20,191★）README 已改为 `# Deprecated` / "本仓库停止维护并永久关停"，并附**律师函**措辞（指控"对非公开 API 及其调用逻辑、参数结构、访问控制及安全认证机制进行系统性收集并传播"），落款 **2026-01-28**；官方文档站 `socialsisteryi.github.io/bilibili-API-collect/` 现返回 404。
  来源：<https://github.com/SocialSisterYi/bilibili-API-collect>、<https://raw.githubusercontent.com/SocialSisterYi/bilibili-API-collect/master/README.md>
- **本项目已经在仓库根目录自带快照 `bilibili-API/`（197 个文件，`docs/` 下 195 篇 md）**，它是关停前的版本。另有贡献者复刻仓库 <https://github.com/pskdje/bilibili-API-collect>（master 同步至 2026-01-25），本文接口路径以这两份快照为准。
- **战略含义**：不要再把"抄现成端点清单"当长期模式。应把本项目**实际依赖的端点、参数、错误码固化为仓库内自有契约文档**，并建立"上游变更 → 快速自检"的机制。另外注意：该文档集为 **CC BY-NC 4.0**，且上游已收到律师函，仓库内自带快照的**合规风险**需要在发布前评估。

### 10.2 手表相关接口速查（三列含义：登录 = 需 SESSDATA；wbi = 需 `w_rid`+`wts`；csrf = 需 `bili_jct`）

**播放 / 字幕**

| 端点 | 用途 | 登录 | wbi | csrf |
|---|---|---|---|---|
| `x/player/wbi/playurl` | 取流（**现行唯一 Web 取流**） | 可选 | 是 | 否 |
| `x/player/wbi/v2` | 字幕 / 章节看点 / 播放器元数据 | 字幕需登录 | 是 | 否 |
| `x/web-interface/view/conclusion/get` | AI 总结 / AI 字幕（`part_subtitle[].timestamp/content` 带时间戳） | — | — | 否 |
| `bvc.bilivideo.com/pbp/data` | 高能进度条（`step_sec` + `events.default[]`） | 否 | 否 | 否 |
| `x/v2/history/report` | 观看进度上报 | 是 | 否 | 否 |
| `x/web-interface/archive/like`、`coin/add`、`like/triple` | 点赞 / 投币 / 一键三连 | 是（**且需 buvid3**） | 否 | **是** |
| `x/v3/fav/resource/deal`、`medialist/gateway/coll/resource/deal` | 收藏 / 取消收藏 | 是 | 否 | **是** |

**列表 / 内容**

| 端点 | 用途 | 登录 | wbi | csrf |
|---|---|---|---|---|
| `x/v2/history/toview`(+`/add` `/del` `/clear`) | 稍后再看 | 是 | 否 | 写操作**是** |
| `x/web-interface/history/cursor`、`x/v2/history` | 历史（游标翻页） | 是 | 否 | 否 |
| `x/web-interface/wbi/index/top/feed/rcmd` | 首页推荐流 | 否 | 是 | 否 |
| `polymer/web-dynamic/v1/feed/all` | 关注动态流 | 是 | 否 | 否 |
| `x/v2/reply/wbi/main` | 新版评论主楼（旧 `x/v2/reply/main` 已废弃） | 是 | **是** | 否 |
| `x/v2/reply/reply`、`x/v2/reply/dialog/cursor` | 楼中楼 / 游标翻页 | 是 | 否 | 否 |
| `x/v2/reply/add`、`/action`、`/hate`、`/report`、`/del`、`/top` | 评论写操作 | 是 | 否 | **是** |
| `pgc/web/timeline`、`pgc/review/user`、`pgc/web/season/section` | 番剧时间表 / 我的追番 / 分集 | 是 | 否 | 否 |
| `pgc/player/web/playurl` | PGC 取流（**必须带 Referer `https://www.bilibili.com`**） | 大会员决定清晰度 | — | — |
| `x/web-interface/wbi/search/all/v2`、`search/type`、`search/square` | 搜索 / 热搜 | 是（Cookies 需足量） | **是** | 否 |
| `s.search.bilibili.com/main/suggest` | 搜索建议（**根字段是 `result.tag[]`，不是 `data`**） | 否 | 否 | 否 |
| `live.bilibili.com/room/v1/Room/get_status_info_by_uids` | 按 uid 查开播状态（开播提醒首选） | 否 | 否 | 否 |
| `live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo` | 直播弹幕 WS 地址 + token | 否 | 否 | 否 |

### 10.3 必须知道的硬约束

1. **`playurl` 取回的 url 有效期 120 分钟**（`bilibili-API/docs/video/videostream_url.md:102`）→ 手表端必须"播放前校验 + 失败重取"，**不能把 url 缓存进离线队列复用**。
2. **FLV 已下线**（同上 `:104`），**分 P 视频只返回单 P url**（`:106`），换 P 必须带对应 `cid` 重新取。
3. **`qn` 在 DASH 格式下无效**（`:117`）；未登录默认 `qn=32`（480P）、登录 `qn=64`（720P）；720P 以上需登录，1080P60/HDR/杜比/会员内容需大会员。
   → **手表端策略**：`fnval=16`（DASH）只取**音频轨 + 低清视频轨**；音频轨最高 192K 正好适合弱网省电；UI 上直说"登录可解锁 720P"。
4. **字幕需要登录**：`x/player/wbi/v2` 的 `data.subtitle.subtitles[]` 在未登录时为空数组，`subtitle_url` 指向 `//aisubtitle.hdslb.com/...`。→ 想把"听视频 + AI 字幕"做成核心体验，**登录是前置条件**。
5. **搜索风控很凶**：`bilibili-API/docs/search/search_request.md:3` 原文——"B站于2022年8月24日更新了搜索api……如果Cookies不足会返回 `-412` 搜索被拦截。**如果没有cookies的话，请在搜索之前先GET一遍 `https://bilibili.com` 以获取cookies**"。
   → 可直接照抄的低成本缓解：**搜索/空间类接口前先"暖一次首页"**。另外实测 `search.bilibili.com/all?keyword=...` 直接返回 `<title>验证码_哔哩哔哩</title>` → 手表端**绝不要走 Web 页面模拟**，并把"需要人工过验证码"当成**不可自动恢复的失败态**，直接给用户提示。
6. **错误码语义（不要硬编码直觉映射）**：`-352` = 风控校验失败（UA 或 wbi 参数不合法）；`-412` = 客户端 IP 被风控拦截；`-111` = csrf 校验失败；`-101` = 账号未登录；**`-403` 在评论场景是"Wbi 签名错误"而非无权限**（`bilibili-API/docs/comment/list.md:867` 原文："Wbi 签名错误时返回 -403 而非 -352"）。
7. **`-352` 与设备指纹是同一件事的两面**：`-352` 时响应体带 `v_voucher`、响应头带 `x-bili-gaia-vvoucher`，处置链路是 `x/gaia-vgate/v1/register` → `/validate` 换 `gaia_vtoken`。**持久化 buvid3/buvid4/UA、不随机换指纹**是根本解法（本项目 §2.4 已做到）。
8. **弹幕协议取舍**：protobuf 版 `x/v2/dm/web/seg.so`（及 wbi 变体、BFS 直链 `i0.hdslb.com/bfs/dm/{data}.bin`）字段丰富但**必须引入 protobuf 运行时并维护 `.proto`**；xml 版 `x/v1/dm/list.so`（**deflate 压缩，必须解压**）与 `comment.bilibili.com/{cid}.xml` 简单。
   → **手表端一屏只显示 3~5 行滚动弹幕，用 xml 即可**，不要为弹幕引入 protobuf 编解码链路；只有要做彩色/高级弹幕/按类型过滤/弹幕点赞数时才值得上 protobuf。
9. **弹幕池是栈语义**：`bilibili-API/docs/danmaku/danmaku_xml.md:3` 原文——"实时弹幕池容量有限（根据视频类型 500-8000 条不等），占满后再发送会使实时弹幕池底部的弹幕压入历史弹幕池（类似于堆栈）"；弹幕池类型 `0 普通 / 1 字幕 / 2 特殊(代码/BAS) / 3 互动池`。
10. **登录方式**：官方扫码登录（Web）为 `passport-login/web/qrcode/generate` + `/poll`；**TV 端另有独立链路** `x/passport-tv-login/qrcode/auth_code` + `/poll`（备用域 `passport.snm0516.aisee.tv`）。→ 手表**无键盘，密码/短信登录不可行**；**TV 端扫码比 Web 端扫码更贴合"无键盘设备授权"的心智模型**，建议手表端引导用户用 TV 端链路的二维码。密码登录还需先取 `passport-login/web/key`（返回 hash 盐 + 公钥，**有效期仅 20 秒**）。
11. **反向纠偏：SponsorBlock 不是 B 站的方案。** SponsorBlock 官方 README 原文定义其目标是"skip sponsor segments in **YouTube** videos"，支持 Invidious，**未提及 bilibili**。→ 本项目的"空降/跳过片头片尾"应走 `view_points` / `pbp/data` / 自建众包，不要引入 SponsorBlock 依赖。

### 10.4 手表端"值得做 / 不值得做"（按接口可行性收敛）

**值得做（且本项目的接口基础已经具备）：**

| 方向 | 为什么 | 本项目现状 |
|---|---|---|
| 稍后再看队列 | 本质是离线播放队列，无键盘 + 弱网点播 + 碎片时间三件事同时满足 | ✅ 列表/增删已有（`api/WatchLaterApi.java:23,48,59`），仅缺"未看完"分组 |
| 历史续播 | "打开就接着看/听" | ✅ 已有（`api/HistoryApi.java:37,174,257` + PGC 专用心跳） |
| 后台听视频 + AI 字幕 | 圆屏天生适合"一屏一句"；音频轨省流省电 | ✅ 听视频、字幕、AI 字幕已有；**缺的是二者组合的"车载/播客式"体验打磨** |
| 私信通知 + 速回 | 手表最强场景 | ✅ 文本收发已有；缺通知栏速回、发图、折叠消息 |
| 关注主播开播提醒 | 接口免登录、一屏一条 | ❌ 未做，**高价值低成本** |
| 收藏/投币/一键三连快捷操作 | 播放中一个手势完成 | ✅ 已有（注意 §5.1 的 csrf 问题） |
| 榜单（热门/排行/每周必看） | 有限枚举、无输入需求 | ✅ 已有 |
| 追番更新提醒 | "今天更了什么"一屏可读完 | ✅ 时间表已有，缺提醒 |

**明确不值得做**：创作中心、会员购、漫画、课堂、发布动态/评论/弹幕（需输入）、礼物面板/舰长榜（涉消费决策）、收藏夹批量整理、关注/粉丝列表管理、登录密码/短信、风纪委员与入站考试。

**一个额外结论**：从接口可行性看，**腕上场景的终点不是"把 B 站功能搬过来"，而是"把 B 站内容变成一条可离线消费的队列"**——稍后再看 + 历史续播 + 音频轨 + AI 字幕，这四样凑齐就是手表上最完整的产品形态，其余都是锦上添花。

---

## 11. 来源

**官方文档**
- Wear OS app quality guidelines — https://developer.android.com/docs/quality-guidelines/wear-app-quality
- Rotary input — https://developer.android.com/training/wearables/user-input/rotary-input
- Wear layouts（BoxInsetLayout / WatchViewStub 废弃）— https://developer.android.com/training/wearables/views/layouts

**竞品仓库**
- PiliPlus — https://github.com/bggRGjQaUbCoE/PiliPlus
- Re-WearBili — https://github.com/SpaceXC/Re-WearBili
- WearBili — https://github.com/SpaceXC/WearBili
- WristBilibili — https://github.com/luern0313/WristBilibili
- Re-BiliTerminal（兄弟分支）— https://github.com/cyq114514/Re-BiliTerminal
- bilimiao2 — https://github.com/10miaomiao/bilimiao2 ；手表说明 https://github.com/10miaomiao/bilimiao2/blob/master/doc/手表使用说明.md
- KiliKili — https://github.com/CryNet-Studio/KiliKili
- BiliClient 上游（Gitee）— https://gitee.com/RobinNotBad/BiliClient
- bilibili-API-collect（**已永久关停，2026-01-28**）— https://github.com/SocialSisterYi/bilibili-API-collect ；关停说明 https://raw.githubusercontent.com/SocialSisterYi/bilibili-API-collect/master/README.md
- bilibili-API-collect 贡献者复刻（快照 2026-01-25，用于本次接口核对）— https://github.com/pskdje/bilibili-API-collect
- BiliRoaming（已 archived，收到律师函）— https://github.com/yujincheng08/BiliRoaming ；https://m.ithome.com/html/973202.htm
- SponsorBlock 官方 README（用于纠偏"是否支持 B 站"）— https://raw.githubusercontent.com/ajayyy/SponsorBlock/master/README.md

**B 站官方口径（正文可读的少数来源）**
- 官方下载中心（客户端矩阵）— https://app.bilibili.com/
- 腾讯应用宝官方应用介绍 — https://sj.qq.com/appdetail/tv.danmaku.bili
- 小米应用商店官方应用介绍 — https://app.mi.com/details?id=tv.danmaku.bili
- 说明：`openhome.bilibili.com/doc`、`open-live.bilibili.com/document/`、`www.bilibili.com/blackboard/help.html` 等官方页均为 JS 渲染空壳，**只能读到标题，读不到正文**。

**本仓库实测**
- 体积数据：对 `app/build/outputs/apk/release/app-arm64-v8a-release.apk`（2026-10-02 构建）解包统计
- 其余结论均标注了 `文件:行号`，可直接核对

---

## 12. 决策台账（2026-10-04 逐条拍板）

本文 §5 / §7 / §8 / §10.4 里的未落地项已整理成清单，逐条向项目所有者确认，结论如下。
**拍板档位**：想要实现 / 暂缓 / 无计划 / 记得已经实现（核实后修正）/ 不清楚实现了没（核实后回报）。

> **前提**：**x86 ABI 保留，不进本轮**。`universalApk` 的取舍单列为 B10，已裁决。
> **注意**：凡结论为「已经实现」的条目，同时视为**对本文旧表述的勘误**（见 §12.6）；属缺陷的条目另在 `docs/review/fix-progress.md` 建立待修记录。

### 12.1 图例

| 结论 | 含义 |
|---|---|
| 想要实现 | 进待做队列，按「收益 ÷ 成本」排期 |
| 暂缓 | 进暂缓池，本轮不排期（不否定） |
| 无计划 | 明确不做，理由保留备查 |
| 已经实现 | 代码中已存在并接线，原条目作废 |

### 12.2 A 组：正确性 / 有 UI 无逻辑

| 编号 | 事项 | 结论 | 依据 / 备注 |
|---|---|---|---|
| A1 | csrf 实时化铺开（原述「11 个 api 类」） | **已实现（26.10.04 批次 2）** | **勘误**：实际是 **14 个 api 类共 41 处**（ArticleApi 4 / CookiesApi 1 / DanmakuApi 4 / DynamicApi 5 / EmoteApi 4 / FavoriteApi 5 / LikeCoinFavApi 4 / LoginApi 1 / OpusApi 1 / PrivateMsgApi 2 / ReplyApi 4 / VoteApi 3 / WatchLaterApi 2），另有 `util/AccountManager.java:135`、`activity/settings/SettingGroupActivity.kt:208`。全部收敛到唯一入口 `util/NetWorkUtil.java:432 currentCsrf()`（纯逻辑抽成 `:443 pickCsrf()`），并在 `saveCookiesLocked()` 落 Cookie 时回写快照（治根，不再依赖「登录那一刻」）；`api/HistoryApi.java` 的私有实现已删除 |
| A2 | `AnnouncementsActivity` 下拉刷新卡死 | **已实现（26.10.04 批次 1）** | catch 分支缺 `setRefreshing(false)`（§5.2）；顺带发现该页**从未接下拉刷新**（`RefreshListActivity.kt:53` 默认 disabled），已一并接上 `setOnRefreshListener` + `setOnEmptyRetry` |
| A3 | `PopularActivity` 不复位 refreshing | **已实现（26.10.04 批次 1）** | catch 只 `MsgUtil.err(e)`（§5.2）；已同时复位 `refreshing` 与转圈，并清掉 3 处 `Log.e("debug", …)` |
| A4 | `CollectionInfoActivity` 缺 onFailure + 死变量 | **已实现（26.10.04 批次 1）** | `:42-71` 只有 `onSuccess`；`:37-39` 的 `seasonId`/`mid` 是死变量（全库无 putExtra 方）；已补 `onFailure` + `collection` 空值防御（原来 `collection!!` 在无合集视频上会崩）+ `setOnEmptyRetry` |
| A5 | `SeriesInfoActivity` 封面/简介/总数恒空 | **已实现（26.10.04 批次 1）** | `adapter/video/SeriesCardAdapter.kt` 增加 `cover`/`intro`/`total` 三个 putExtra，详情页读回 + 补 `setOnEmptyRetry` |
| A6 | `OpusInfoActivity` 幽灵空 `@Subscribe` | **已实现（26.10.04 批次 1）** | 空函数体订阅已改为 `replyFragment?.notifyReplyInserted(event)`（与 `DynamicInfoActivity.kt:77-80` 对齐）。**勘误**：原依据「漏 `leaveDetailPage()`」不成立——全库不存在该方法（`TerminalContext` 只有 `enterXxxDetailPage`），该半句作废 |
| A7 | `SetupUIActivity` WebView 输入校验 | 暂缓 | `activity/settings/setup/SetupUIActivity.kt:79,86,92` |
| A8 | `TutorialManagerActivity` 读写 `tutorial_ver_$tag` 污染新教程系统 | 暂缓 | 与 `tutorial/Tutorials.kt` 的已读记账互相覆盖 |
| A9 | `TestActivity:105` 硬编码专栏 id | 无计划 | 仅调试页，收益极低 |
| A10 | 评论点踩死视图 | **已实现（26.10.04 批次 2）** | `app/src/main/res/layout/cell_reply_list.xml:70-82` 的 `dislikeBtn` 原来只有 `adapter/ReplyAdapter.kt` 里的 `findViewById`、从不绑定监听；已补点击（`ReplyApi.dislikeReply()` → `x/v2/reply/hate`）、已踩高亮、与点赞**互斥**（服务端点踩会同时消去点赞，本地两个状态一起改）、错误码翻译 `ReplyApi.actionErrorMsg()`。配套：`model/Reply.java` 补 `disliked` 字段 + `parseAction()`（原实现把「已踩(2)」与「无操作(0)」混为一谈，踩过的评论重进页面显示成没操作过） |
| A11 | 禁右滑主题被 `setTheme` 覆盖失效 | **核实为已实现，原条作废** | 已由 issue #1 修复：`activity/base/BaseActivity.kt:78-82` 按「禁用返回键」改用 noSwipe 主题，`ui/appearance/ColorScheme.kt:527`/`:544` 提供 `themeResId(theme, noSwipe)` 与 7 套 `*.NoSwipe.AppCompat`；`activity/player/PlayerActivity.kt:414-419` 同样处理。提交 `25e6886`、`20e69c1` |
| A12 | `AsyncLayoutInflaterX` 生命周期 | **核实为已实现，原条作废** | `activity/base/BaseActivity.kt:66-70` 持有 `pendingAsyncInflater`，`:353-358 onDestroy()` 调用 `cancel()`；`util/AsyncLayoutInflaterX.java:261 public void cancel()`、`:78-84` 已 cancel/已销毁时丢弃结果。提交 `d676a77` |

### 12.3 B 组：性能 / 体积 / 启动

| 编号 | 事项 | 结论 | 依据 / 备注 |
|---|---|---|---|
| B1 | `PerformanceManager` 死参数裁决 | **已实现（26.10.04 批次 3）** | **勘误**：实测是 **8 个** getter 零调用点，不是 7 个。按「分类处理」裁决：`getImageQuality` / `getImageMaxWidth` 接进 `GlideUtil`（即 B3）、`getPageSize` 接进列表分页；其余 **5 个直接删除** = `isHighPerformanceMode` / `getOkHttpConnectionPoolSize` / `getOkHttpKeepAliveMinutes` / `isImageTransitionEnabled` / `isVideoPreloadEnabled`。分页只接了「纯追加列表」的 3 处（`api/RecommendApi.java:84` popular、`:106` precious、`api/SeriesApi.java:28`），其余硬编码 ps **故意不接**（各有语义：`FavoriteApi.java:106 ps=100` 收藏夹一次拉全、`MessageApi.java:380 page_size=35` 是 cursor 分页、`EmoteApi.java:94/120 ps=12` 等），已在 `getPageSize()` KDoc 写明 |
| B2 | 首次硬件检测挪到后台 | **已实现（26.10.04 批次 3）** | `PerformanceManager.init()` 改为「双重检查 + 有缓存直接读；**无缓存先置中档立即返回**，`getHardwareScore()` 丢进 `CenterThreadPool`，算完写 `KEY_DEVICE_PERFORMANCE_LEVEL` 再 `applyPerformanceSettings()`」。顺带删掉「`if (Logu.LOGI_ENABLED)` 才打日志」的绕法——原写法为了省一次 `getHardwareScore()` 反而使冷启动必须同步求值 |
| B3 | 图片按档位降 w | **已实现（26.10.04 批次 3）** | **口径修正**：台账原样接会让中/高端列表图从 512 变 1024（像素 ×4，手表纯浪费）。最终粒度 = **列表图 低端 320w/50q、其余 512w/60q**（中档与现状一致）；**大图 `url_hq()` 低端 512w/60q、其余 1024w/80q**。`GlideUtil` 的 `QUALITY_HIGH/LOW`、`MAX_W_HIGH/LOW` 四个常量已删除（避免两处真相），档位统一由 `PerformanceManager` 的 `@JvmStatic` 纯函数给出 |
| B4 | OkHttp 连接池 / 保活接线 | 暂缓 | §5.3 |
| B5 | 列表增量刷新 | **已实现（26.10.04 批次 3）** | **勘误**：`RecommendActivity.kt:85` 与 `PopularActivity.kt:96` **本来就是** `notifyItemRangeInserted`，台账那两条已达标。**真 bug 4 处**：`SearchVideoFragment.kt:55` / `SearchArticleFragment.kt:53` / `SearchLiveFragment.kt:64` 起点写成 `lastSize + 1`（这三个 adapter 用列表直接构造、**无头部**，`VideoCardAdapter.getItemCount() = videoCardList.size`）→ 改为 `lastSize`；`UserSeriesActivity.kt:50-56` 更严重——**从未把新数据加进 adapter 的列表**，只报 `notifyItemRangeInserted(oldSize, seasonList.size)`，必然撞 RecyclerView "Inconsistency detected"。顺带删掉各搜索页的 `Log.e("debug","加载下一页")`、`RecommendActivity` 的 3 处 `Log.e("debug")` 与失效的 `@SuppressLint("NotifyDataSetChanged")`。**判定坑（已踩）**：`SeriesInfoActivity.kt:86` / `ReplyFragment.kt:250` / `UserDynamicFragment.kt:89` 里的 `+1` **是对的**——对应 adapter 的 `getItemCount()` 都带一个头部占位（`data.size + 1`）；本项目 `notifyItemRangeInserted` 的 `+1` 大多是头部，**必须先看 getItemCount() 再判越界** |
| B6 | Baseline Profile 模块 | 暂缓 | §7.1 |
| B7 | 打开 viewBinding | 无计划 | 用户口径：**老代码不动**，新代码可自行采用 |
| B8 | 崩溃页独立进程 | **已实现（26.10.04 批次 3）** | `AndroidManifest.xml` 给 `.activity.CatchActivity` 加 `android:process=":error_activity"`；`BiliTerminal.onCreate()` 用 `/proc/self/cmdline` 判进程名（minSdk 24 用不了 API 28 的 `Application.getProcessName()`），错误进程只做最小初始化（SharedPreferences / 适配 Context / 日志开关）后 `return`，不碰性能检测、强制更新、`ErrorCatch.init`、未读轮询；`ErrorCatch.uncaughtException` 里等崩溃页起来的 `Thread.sleep(300)` 已删除 |
| B9 | ffmpeg 裁剪重编 | 无计划 | 体积收益不足以抵消风险 |
| B10 | `universalApk` 是否只 release 关 | 无计划 | 单 arm64 9.64MB vs 通用 22.63MB（保留现状） |

### 12.4 C 组：功能新增

| 编号 | 事项 | 结论 |
|---|---|---|
| C1 | 弹幕点击菜单（点赞/复制/举报） | 无计划 |
| C2 | 评论举报 | 无计划 |
| C3 | 评论删除 / 置顶自己的评论 | **删除已实现**（`adapter/ReplyAdapter.kt:385` → `api/ReplyApi.java:327` `x/v2/reply/del`）；**置顶 = 想要实现** |
| C4 | 评论楼中楼排序 / 定位 | 想要实现 |
| C5 | 评论保存（收藏评论） | 无计划（接口亦未核实） |
| C6 | 评论图片（发图） | **已实现**（`activity/reply/WriteReplyActivity.kt:127`；`api/ReplyApi.java:218/231`；`:40` `BIZ_REPLY= new_reply`）；**「上传/发送无进度无反馈」= 想要实现（独立 bug）** |
| C7 | 动态编辑 | 想要实现（发布链路已有 `DynamicApi.publishComplex`） |
| C8 | 动态置顶 / 删除 | **删除已实现**（`adapter/dynamic/DynamicHolder.kt:104,153` → `api/DynamicApi.java:460` `rm_dynamic`）；**置顶 = 想要实现** |
| C9 | 动态定时发布 | 想要实现 |
| C10 | 动态话题页 | 想要实现 |
| C11 | 屏蔽带货动态 | 暂缓（用户「先等等」） |
| C12 | 私信发图 | **已实现（26.10.04 批次 5）**：`api/PrivateMsgApi.java` 的 `buildImageContent`/`sizeToKb`/`imageTypeOf` + `PrivateMsgActivity` 的选图入口（`ACTION_GET_CONTENT` → `ImageApi.prepareImage` → `upload_bfs` → `msg_type=2`）；**拍照不做**（用户拍板）；已知取舍：不处理 EXIF 旋转 |
| C13 | 私信删除 / 置顶 / 折叠 | **删除 + 置顶/取消置顶已实现（26.10.04 批次 5）**：`api/PrivateMsgApi.java` 的 `removeSession`/`setSessionTop`（注意 `op_type` 0=置顶 1=取消置顶）+ `model/PrivateMsgSession.topTs`，会话项长按弹菜单；**折叠消息（`batch_rm_dustbin`）不做**（用户拍板） |
| C14 | 私信通知栏速回（`RemoteInput`） | 想要实现 |
| C15 | 关注主播开播提醒 | 无计划 |
| C16 | 追番更新提醒 | 想要实现（与 C15 共用通知基建，C15 不做则另起） |
| C17 | 弹幕点赞 / 撤回自己的弹幕 | 无计划 |
| C18 | 稍后再看「未看完」分类 | 想要实现（`api/WatchLaterApi.java:23`） |
| C19 | 收藏夹排序 / 复制 / 移动 | 想要实现 |
| C20 | 收藏夹多选删除 | 想要实现（与 C19 共用接口封装） |
| C21 | 关注分组增删改 | 想要实现 |
| C22 | 移除粉丝 | 暂缓 |
| C23 | 搜索用户动态 | 无计划 |
| C24 | 分享视频/番剧/动态/专栏/直播至站内消息 | 无计划 |
| C25 | 直播弹幕发表情 / SuperChat | 无计划 |
| C26 | 画中画 PiP | 无计划 |
| C27 | 笔记 | 想要实现（**范围限定：仅「查看」**） |
| C28 | 自建众包「自动空降」 | 无计划（需要服务端，与纯客户端定位冲突） |
| C29 | WebDAV 备份 / 恢复设置 | 无计划 |
| C30 | 滑动跳转预览缩略图（storyboard） | 无计划 |
| C31 | 亮度 / 音量滑动手势 | 无计划 |
| C32 | 视频截图 / 截取动图 | 无计划 |
| C33 | 视频 TAG / staff 展示 | 无计划 |
| C34 | 高级弹幕 / 合并弹幕 / 彩色弹幕 | 无计划 |

### 12.5 D / E / F 组

| 编号 | 事项 | 结论 | 依据 / 备注 |
|---|---|---|---|
| D1 | 旋冠接入「音量/进度」调节 | 无计划 | 旋冠现仅用于滚动 |
| D2 | 跑马灯标题 | **已经实现**（勘误） | `ui/widget/MarqueeTextView.kt:21-31`（`marquee_enable` 控制 `ellipsize=MARQUEE` + `marqueeRepeatLimit=-1`）；开关 `activity/settings/SettingGroupActivity.kt:281`；约 20 个布局已改用。例外：`BaseActivity.kt:191-193` 把页面标题栏强制 `TruncateAt.END`，故标题栏不跑马灯 |
| D3 | 圆屏列表项曲率避让 | **已经实现**（勘误） | `util/SettingsKeys.kt:20` `UI_ROUND = "player_ui_round"`；开关 `SettingGroupActivity.kt:234`；`BaseActivity.kt:188-204` 给标题栏加 18% 横向内边距并护住时钟；播放器另有 `PlayerActivity.kt:839/859/1129/2071-2072`。设置页与代码读同一 key，无冲突 |
| D4 | 图标按钮补 `contentDescription` / TalkBack | 暂缓 | §8 |
| D5 | 搜索优先点选候选词 | **基本已实现**（勘误）：建议列表 `SearchActivity.kt:232-239` 点击即 `setText` + 直接搜索；输入框 `:197-202` 会主动弹键盘。**仅剩「搜索历史点击只填入、不触发搜索」（`:213`），该项 = 暂缓** |
| D6 | 常亮屏 ambient | 无计划 | 官方不建议，且视频场景本就亮屏 |
| E1 | 拆分 `PlayerActivity` | 暂缓 | 实测 **3494 行**，大重构且无界面测试兜底 |
| E2 | 拆分 `DownloadService` | 想要实现 | 65KB |
| E3 | 补单元测试 | 想要实现 | 持续投入；现状见 §12.6 |
| E4 | `SettingsKeys` 收敛收尾 | **已实现（26.10.04 批次 4）** | 勘误：字面量是 **13 处**不是 14 处（`SettingMainActivity.kt:118` 的 `"player"` 是分组 id 不是 SP 键；`SharedPreferencesUtil.java:59` 是定义）。13 处全部改调 `SettingsKeys.PLAYER`/`PLAY_QN`，并删掉 `SharedPreferencesUtil.player` 死字段；`SettingsKeysTest` 2 例钉死键名 |
| E5 | 删死方法 `SharedPreferencesUtil.beginBatchEdit` | **已实现（26.10.04 批次 4）** | 连同同类的 `applyBatch(Runnable)` 一起删（两个都零调用且都是"拿到 editor 就丢"的假批量 API）；保留真正在用的 `edit(Consumer<Editor>)` |
| E6 | 更新 APK 签名 / 哈希校验 | **已实现（26.10.04 批次 4）** | 新增 `util/ApkVerifier.kt`：客户端校验**包名一致 + 签名与已安装应用一致**，接进 `UpdateManager.downloadApk` 与 `DownloadActivity.installApk` 两条安装链路。**发布侧零改动**。未做哈希校验的理由：MD5/SHA-256 与安装包来自同一响应，能改包的人也能改元数据；签名才是无密钥伪造不了的。边界：防不住"同一签名者发布的坏包"；系统安装器本身也会拒绝换签名包，本校验的价值是早失败 + 说清原因 |
| F1 | 创作中心 / 会员购 / 课堂 / 直播礼物 / 舰长 / 桌面小组件 / 多窗口 | 无计划 | 手表端无场景或成本极高 |
| F2 | DLNA 投屏 / 超分辨率 / Live Photo / AI 原声翻译 / 互动视频增强 | 无计划 | 需解码或服务端能力，超出纯客户端 |
| F3 | 关注粉丝列表管理 / 登录密码短信 / 风纪委员 / 入站考试 | 无计划 | 低频 / 与手表定位不符 |
| F4 | 漫画（**F1 中单独捞回**） | 想要实现 | 范围 = 追漫列表 + 漫画详情 + 长条阅读器；工期约 5~6 天 |

### 12.6 勘误与台账数字更新

- **D2 / D3 / D5 原被本文 §8 列为待办，核实后为「已实现」**，相应条目作废（D5 只剩历史点击一个 10 分钟小项，已裁为暂缓）。
- `activity/player/PlayerActivity.kt` 实际 **3494 行**（原述 3090 行）。
- 单元测试实际 **26 个测试类 / 211 个用例**（26.10.03 原述 16 类 / 112 例；26.10.04 批次 1 后 21 类 / 165 例；批次 2 新增 `api/ReplyApiTest` 9 例、`model/ReplyParseActionTest` 4 例、`util/NetWorkUtilTest` +6 例；批次 3 新增 `util/PerformanceManagerTest` 10 例；批次 4 新增 `util/SettingsKeysTest` 2 例、`util/ApkVerifierTest` 8 例；批次 5 的 C13 在 `api/PrivateMsgApiTest` 内 +3 例、C12 同文件 +4 例）；`app/build.gradle` 已含 `testImplementation 'org.json:json:20231013'`，JVM 单测可直接用 `org.json`，但**纯解析函数里不得调用 `android.util.Log`**（未开 `returnDefaultValues`，会抛 not-mocked）。
- **本文 §10.4 与用户裁决存在三处冲突，以本台账为准**：① §10.4 把「关注主播开播提醒」列为"高价值低成本、值得做"，用户裁为**无计划**；② §10.4 把「漫画」列入"明确不值得做"，用户裁为**想要实现（F4）**；③ §10.4 把「收藏夹批量整理」「发布动态/评论/弹幕」列入"明确不值得做"，用户分别裁为**想要实现（C19/C20）**与**想要实现（C7/C9/C10）**——即"需要输入"不是本项目的否决理由（无键盘只影响输入方式，不影响功能取舍）。

### 12.7 量化汇总与建议顺序

| 结论 | 项数 |
|---|---|
| 想要实现 | 36（其中 A1 / A10 已于 26.10.04 批次 2 落地、B1 / B2 / B3 / B5 / B8 已于批次 3 落地、E4 / E5 / E6 已于批次 4 落地、C12 / C13 已于批次 5 落地，剩 24） |
| 暂缓 | 10 |
| 无计划 | 25 |
| 已经实现（勘误） | 4（D2 / D3 / D5 主体，另 C3·C6·C8 的「已实现」子项） |
| **26.10.04 批次 1/2/3 落地** | 6（A2 A3 A4 A5 A6 + A10）+ 5（B1 B2 B3 B5 B8）；2 项勘误作废（A11 A12） |
| **26.10.04 批次 4 落地** | 3（E4 E5 E6），另有 2 项勘误（E4 字面量 13 处而非 14 处；`applyBatch` 与 `beginBatchEdit` 同类一并删） |
| **26.10.04 批次 5 落地** | 2（C13 会话删除 + 置顶/取消置顶；C12 私信发图），本批共 4 项，其余见 C14/C16 |

**已确认的 8 批落地顺序（用户 26.10.04 拍板，取代下面这段原「建议顺序」）**：① A2 A3 A4 A5 A6（✅已提交 `9580705`）→ ② A1 + A10（✅已提交 `94a2b80`，见 `docs/review/fix-progress.md` §十二）→ ③ B1 B2 B3 B5（限推荐/热门/搜索）B8（✅已提交 `a683954`，见 §十三）→ ④ E4 E5 E6（✅已提交 `35fc507`，见 §十四）→ ⑤ C12 C13 C14 C16（C13 ✅ 已完成，见 §十五；C12 ✅ 已完成，见 §十六；C14/C16 待做，各自独立提交）→ ⑥ C3 C4 C6b C7 C8 C9 C10 C27 → ⑦ C18 C19 C20 C21 → ⑧ E2 DownloadService + F4 漫画；E3 补单测贯穿每一批。**写操作类（C3/C7/C8/C9/C10/C13/C19/C20）必须排在 A1 之后**，否则 csrf 用旧快照会被风控回 -111/-412。

---

## 附：本次调研中未能核实的部分

- GitHub `RobinNotBad/BiliClient` 返回 404（上游主仓只在 Gitee）；Gitee 无公开 API，`71★ / 1322 commits / 状态「暂停」` 取自网页。
- `Darock-Studio/Darock-Bili`（Apple Watch 版）因匿名 API 限流（HTTP 403）**未能核实**。
- `qingyiwebt/Biliw`、`nonomal/bilibili-for-AppleWatch` 仅见搜索结果中的个位数 star，**未核实**。
- Wear OS 是否存在其他仍在维护的 B 站客户端：**未查到**。
- **「Android 9+ 默认禁止明文 HTTP」未取到可引用的官方原文**：`developer.android.com/privacy-and-security/security-config` 与 `/about/versions/pie/android-9.0-changes-all` 均只读到导航或被截断（入口存在）。本仓库相关的依据只有 `bilibili-API` 快照 README 的"强制使用 https 协议"。
- **SponsorBlock API 正文与"B 站是否被其服务端收录"**：`wiki.sponsor.ajay.app` 抓取失败；其 README 全文未提 B 站 → 只能确认"官方目标平台是 YouTube"，不能确认"B 站被收录"。
- **官方 App 底部 Tab 的具体数量与顺序**：官方页面全是 JS 空壳，商店页仅能确认存在"我的"Tab。§7.4 的模块划分是按官方子站与接口域反推的**模块划分**，不是 Tab 顺序断言。
- **动态发布的完整参数取值表**（定时 / 可见范围的字段枚举）：只读到接口与部分字段名，未逐字段核对 `bilibili-API/docs/dynamic/publish.md` 全文。
- **`-352` / `-412` / `-111` 之外的错误码语义**：快照 `docs/misc/errcode.md` 有更长列表，本文只逐字核对了 §10.3 提到的几条。
