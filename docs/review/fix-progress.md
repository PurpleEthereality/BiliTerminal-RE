# ReBiliClient 修复进度报告

> 更新日期：2026-10-05（含批次 8 + 线上崩溃修复 + 第三批真机反馈）
> 基线：26.08.27 快照的 286 条问题清单（原 `docs/review/00-summary.md` 已删除）；后续轮次见 §七/§八/§九/§十/§十一/§十二/§十三/§十四/§十五/§十六/§十七/§十八/§十九/§二十/§二十一/§二十二/§二十三/§二十四/§二十五/§二十六/§二十七/§二十八/§二十九/§三十/§三十一/§三十二/§三十三/§三十四/§三十五/§三十六/§三十七/§三十八/§三十九
> 状态：Critical 抽查项已全部确认/修复，High/Medium 待继续
> 真机回归：本文各节「真机验证清单」已按操作顺序合并成 `docs/review/real-device-regression-checklist.md`（214 条，发版前逐条勾；批次 8 的两条口头需求见该文件第十章第 207-209 条，线上崩溃回归见第十一章第 210-211 条，第三批真机反馈见第十一章第 212-214 条）
>
> ⚠️ **章节编号有两处历史重号**：`## 二十二` 与 `## 二十九` 各出现两次（不同轮次独立编号所致），`## 30.` 为另一轮次遗留的阿拉伯数字写法。**为避免打断既有交叉引用（§二十二/§二十九/§30.x 被多处正文引用），已保持原编号不动**；本轮新增章节统一编号为 **§三十三～§三十九**。索引见 §三十六.2。

---

## 一、修复总览

| 严重度 | 基线数量 | 已确认修复 | 待处理 |
|---|---|---|---|
| Critical | 23 | 17 | 剩余 6 待排查 |
| High | 52 | 4（分页 2 + 菜单键/Cookie 锁 2） | 48 待处理 |
| Medium | 105 | 0 | 105 待处理 |
| Low/Info | 106 | 0 | 106 待处理 |

### 2026-09-24 深度审查新增（见 `docs/review/audit-2026-09-24.md`）

> 四个方向独立审计 + 逐条回读核实。以下为**新增发现**，与上方 286 问题基线**独立计数**。

> **2026-10-02 修复轮次更新**：下表条目已全部处理完毕。每条的最终状态、涉及文件，以及
> **两条审计结论的更正**（S3 的泄漏前提不成立、`IjkPlayerBridge.release()` 不能简单 cancel `job`），
> 见 `docs/review/audit-2026-09-24.md` 的「六、修复轮次后的状态」。

| 严重度 | 新增数量 | 已修复 | 待处理 |
|---|---|---|---|
| 严重 | 10 | 10 | 0 |
| 中等 | 13 组 | 12 组（M6 大部分） | 1 组（M9 属分层重构） |
| 轻微 | 11 | 11 | 0 |

> 另有四项**有意不修**并已在代码内留注释/说明：M6 的 `callTimeout`（同一 OkHttpClient 兼服务大文件流式下载，设了会掐断正常下载）、M6 的 `api_retry_max_times` 设置项（属设置页功能新增）、M9（API 层直调 `MsgUtil`/全局 `Context`，属分层重构）、M12-b 的 `TV_APP_SEC`（B 站 TV 端公开常量，非可撤销凭证）。

**P0 新增（已全部修复）**：

- [x] S1 WBI 签名「先写日期戳后取 key」→ 首调失败则**当天全部 17 处核心接口 403**（`ConfInfoApi.java:74-83`）— 复查轮次前已修
- [x] S2 `sortUrlParams` 丢弃含 `=` 的参数（`ConfInfoApi.java:108-116`）— 同源的 `Cookies.java` 漏修点已在修复轮次补上
- [x] S4 下载失败时删除已完成视频文件夹（`DownloadService.kt:1487-1500`）— 复查轮次前已修（改调 `FileUtil.cleanDownloadTempFiles`）
- [x] S9 完整 Cookie 经 Intent extra 传给第三方播放器（`PlayerApi.java:399-404`）— 已修，跨进程读取待真机验证

**P1 新增（已全部修复）**：

- [x] S3 所有 API 的 `Response` 从不 `close()` — **前提已更正**：OkHttp 的 `body.string()`/`bytes()` 内部即 `source().use { }`，读完就归还连接；实际只泄漏「body 从未读完」的 10 处，已全部关闭
- [x] S5 `started` 无同步竞态（`DownloadService.kt:430-435`）— 已改 `@JvmStatic @Volatile` + `@Synchronized`
- [x] S6 `recoverStuckSections()` 把正在下载的任务重置为 `none`（`DownloadService.kt:689-699`）
- [x] S7 分片回退字节双重计数（`DownloadService.kt:1356-1358`）
- [x] S8 登录成功流程跑在 UI 线程（`PasswordLoginFragment.kt:213-237`）
- [x] S10 ProGuard 整包 keep 使 R8 失效（`proguard-rules.pro:101-103`）— 三条整包规则已删，规则文件整体重写
- [x] M13 CI 不跑任何测试（`.github/workflows/build-release.yml:104`）— 已新增 `ci.yml`，发版前也先跑 `testDebugUnitTest`

---

## 二、已修复问题明细

### 第一轮（构建前修复）

| 文件 | 问题 | 修复方式 |
|---|---|---|
| `RefreshListActivity.kt` | `isLoading` 加载成功后永不复位，导致 10+ 页面翻页卡死 | `setRefreshing(false)` 时复位 `isLoading` |
| `RefreshMainActivity.kt` | `goOnLoad()` 未置成员 `isRefreshing`，滚动持续触发并发分页 | `goOnLoad()` 同步置 `isRefreshing = true` |

### 第二轮（审查后修复）

| 文件 | 问题 | 修复方式 |
|---|---|---|
| `QRLoginFragment.kt:309,310,383,384` | access_token / refresh_token / 完整 Cookie 明文打 logcat | 删除 4 行敏感日志 |
| `CaptchaWebViewActivity.kt:93-112` | WebView 开启全部危险开关 + JS 桥 | 关闭文件访问 4 项开关，混合内容改 NEVER_ALLOW |
| `LocalListActivity.kt:513` | 虚拟合集用全局索引访问过滤列表导致越界 | 改用 `videoList[startVideoIdx]` + 边界检查 |
| `VideoInfoFragment.kt:522-524` | 三连成功回调在 IO 线程 setImageResource | 包裹 `runOnUiThread { }` |

### 第三轮（剩余 Critical 排查）

| 文件 | 问题 | 修复方式 |
|---|---|---|
| `PrivateMsgActivity.kt:139` | 私信 JSON 注入（用户输入直接拼 JSON 字符串） | 改用 `JSONObject().put("content", content)` 安全构造 |
| `PrivateMsgActivity.kt:200` | 空会话列表 `list[list.size - 1]` 越界崩溃 | 加 `if (list.isEmpty()) return` 防护 |
| `NetWorkUtil.java:96` | 相对路径 Location 时 `getScheme()` NPE | scheme/host 判空后再比较 |
| `NetWorkUtil.java:100-109` | 手动重定向未 close 原响应导致连接泄漏 | 重定向前 `response.close()` |

### 第四轮（26.09.08 架构通读后修复）

| 文件 | 问题 | 修复方式 |
|---|---|---|
| `PrivateMsgApi.java:159` | `!has && isNull` 恒等于 `!has`，account_info 为 null 的普通会话被误过滤 | 改用 `isNull`；解析抽成 `parseSessionsList()` 纯函数 + 新增 `PrivateMsgApiTest`（6 例） |
| `InstanceActivity.kt:32` | MENU 键打开菜单后继续走 super，被 BaseActivity 又 finish 当前页 | 消费按键 `return true` |
| `InstanceActivity.kt:16` | `from` 参数判的是新建空 Intent，恒 false | 改判 `getIntent().getStringExtra("from")` |
| `NetWorkUtil.java:403` | `saveCookiesFromResponse` 无锁，Cookie 并发丢失 | 抽 `saveCookiesLocked()` 纳入 `NetWorkUtil.class` 锁 |
| `ConfInfoApi.java:41-43` | WBI 缓存三字段非原子写 | 合并为不可变 `WbiCache`，整体替换 |
| `MsgUtil.java:89-94` | sticky SnackEvent 显示后不移除，同页弹两次 | 显示分支同时 `removeStickyEvent` |
| `TerminalContext.java:118-133` | 视频缓存 aid/bvid 键不一致，bvid 路径永远 miss | 新增 `cacheVideo()` 双写；LruCache 10→20 |
| `ToolsUtilTest.kt:33` | 期望值笔误（`0x00123456` 剥离 alpha 写成 `0x000000`） | 改为 `0x123456` |

> 验证：`:app:testDebugUnitTest` 38 个测试全绿 + `:app:assembleDebug` 通过。

### 第五轮（登录页二维码缩放 bug）

| 文件 | 问题 | 修复方式 |
|---|---|---|
| `res/layout/fragment_qr_login.xml:34-53` | 二维码卡片 `layout_height="wrap_content"` 且无底部约束，改宽度后高度不跟着变（配合 `adjustViewBounds`+`scaleType=fitXY` 甚至可能整体尺寸不变） | 加 `app:layout_constraintDimensionRatio="1:1"`，ImageView 改 `match_parent` + `fitCenter` |
| `QRLoginFragment.kt:113-148` | 点二维码只改 `setGuidelinePercent` 不生效：Guideline 自身 `onMeasure` 恒 `setMeasuredDimension(0,0)`、尺寸不参与布局变化，外层 ConstraintLayout 又是 `wrap_content`，不触发父级重新测量；且 `findViewById` 用非空 `view` 但无判空、Toast 放在分支末尾，异常时既不变大也不提示 | 改 percent 后显式 `view?.requestLayout()`；Guideline 判空早退；提示文案统一在末尾触发 |

> 根因用 `constraintlayout-2.1.4.aar` 内 `Guideline.class` 字节码核实：`setGuidelinePercent` 仅写 `guidePercent` 字段并 `setLayoutParams`，`onMeasure` 第 2315 字节处为 `setMeasuredDimension(0,0)`。

### 第六轮（视觉体验优化 · 批次 0，见 `docs/visual-experience-report.md`）

| 文件 | 问题 | 修复方式 |
|---|---|---|
| `ThemeManager.kt:405-407` | `flags or SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()` 等于把 `0xFFFFDFFF` 全部置位：清掉 `LIGHT_STATUS_BAR`（状态栏图标恒浅色，压在亮色品牌底上对比度不足）、打开 `LIGHT_NAVIGATION_BAR`（导航栏图标恒深色，压在纯黑导航栏上不可见），并附带无条件打开 `HIDE_NAVIGATION`/`IMMERSIVE`/`IMMERSIVE_STICKY` | 删掉整段，改用 `WindowInsetsControllerCompat.isAppearanceLightStatusBars/NavigationBars`，按系统栏底色亮度决定图标深浅 |
| `BaseActivity.kt` | `setDecorFitsSystemWindows(false)` 后全工程零 insets 处理，贴底控件与列表最后一项被导航栏压住 | 新增 `applySystemBarInsets()`，把 `systemBars()` inset 叠加到根布局已有 padding 上（保留用户的界面边距设置） |
| `GlideUtil.java:32-41` | 列表图一律 `25q/512w`，1080p 屏上封面放大后发虚、渐变出现色带 | `QUALITY_LOW` 25→60；新增 `url_hq()` 用于大封面（16 处调用点从 `url()` 切到 `url_hq()`） |
| `VideoCardHolder.kt:147-154` | `.override(400,225)` + `.sizeMultiplier(0.85)` 把解码锁在约 340×191 绝对像素，而显示区约 519px，必然上采样糊掉 | 删除两个调用，交给 Glide 按 ImageView 实测尺寸解码；封面改 `PREFER_ARGB_8888` |
| `GlideUtil.java` + 21 个 adapter/activity | 全工程 `.error()` **0 处**，图片加载失败时 RecyclerView 复用会显示上一项的封面（串图） | 三个封装方法补 `.error(safePlaceholder(placeholder))`；21 个链式调用点批量补 `.error()` |
| `GlideUtil.java:92-103` | `transitionEnabled` 用 static 缓存，设置里改「加载渐入渐出动画」必须重启才生效 | 去掉缓存，每次读 SharedPreferences |
| 19 个布局 | 36 处把 `sp` 当 `layout_width/layout_height`（32 处 `35sp` + 40sp/30sp），系统字体放大即压扁/溢出 | 全部改为 dp（`fragment_qr_login.xml`、`activity_write_reply.xml`、`activity_send_dynamic.xml` 等） |
| `layout-v17/`、`layout-v22/` | minSdk 24 下永不被选用（`item_hot_search.xml` 死变体） | 删除两个目录，并把 v22 里唯一生效的 `layout_marginEnd="8dp"` 合并进 `layout/item_hot_search.xml` |
| `cell_dynamic.xml`、`cell_reply_list.xml`、`cell_user_list.xml` | 根布局不是 CardView 却在代码里设了点击，完全没有按压反馈（"点了没反应"） | 补 `android:foreground="?attr/selectableItemBackground"`（用户列表另加 `clipToOutline`） |
| `RefreshMainActivity.kt` | 一级页完全没有空态能力，`activity_simple_main_refresh.xml` 里的 `emptyTip` 是死视图，空数据时用户看到纯黑列表 | 补 `emptyView` + `showEmptyView()/hideEmptyView()`（对齐 `RefreshListActivity`）；`RecommendActivity`/`DynamicActivity`/`RecommendLiveActivity`/`HotSearchActivity` 四个子类接线（`RecommendActivity` 此前还会静默吞掉空结果） |
| 3 个 `<emptyTip>` 布局 + `item_hot_search.xml` | 空态/热搜条目字号与颜色未走语义色 | 空态文字改 14sp + `?android:attr/textColorSecondary`；热搜排名色改主题色 |
| `activity_player.xml`、`bottom_bar_multi_select.xml`、`fragment_qr_login.xml`、`dialog_new_folder.xml`、`dialog_folder_settings.xml`、`bar_quality_select.xml` | 播放器 28dp 按钮 ×11 + 弹幕发送 35dp + 进度条 20dp；多选底栏 32dp ×4；二维码帮助键 28dp；弹窗按钮 36dp —— 均低于 48dp 最小触控规范 | 保留视觉尺寸，补 `minWidth/minHeight="48dp"`（共 28 个控件）；进度条高度 20dp→48dp + `paddingVertical="14dp"` 保住原波形区 |
| `themes.xml`（默认主题） | `Theme.ClassicTerminal` 是 7 套里唯一缺 `colorSurface` / `android:colorBackground` / `colorSecondary` / `colorPrimaryVariant` 的主题，Material 控件回退默认值；`colorPrimaryDark` 用 `terminal_pink(#FF6699)` 与 Kotlin 表的 `#E84B85` 不一致 | 补齐 4 个 item（新增 `terminal_deep`）；`colorOnPrimary` 统一到新增的 `text_on_primary` |
| `themes.xml`（知乎蓝） | `colorControlNormal/Highlight` 误用粉色 `@color/color_ripple`，其余 5 套都用了各自的 `*_color_ripple` | 改 `@color/zhihu_color_ripple` |
| `ThemeManager.kt`（五彩斑斓/经典终端） | Rainbow 的 `PRIMARY_LIGHT`/`SECONDARY` 与 xml 的 `colorPrimaryVariant`/`colorSecondary` 恰好互换；终端主题 `CARD` 丢了 alpha（`#FF262626` vs xml `#CC262626`） | 按「PRIMARY_LIGHT == colorPrimaryVariant、SECONDARY == colorSecondary」约定对齐 |
| `ThemeManager.kt` + `colors.xml`（终端文字层级） | 默认主题把三档文字色设成同一个 `#EBE0E2`，层级只能靠 alpha 硬凑 | 拆为 `#EBE0E2` / `#B8AEB2`(≈9:1) / `#8C868A`(≈6:1) |
| 36 个 `cell_*`/`item_*`/详情布局 | 次要文字 `alpha=0.5`（对卡片底约 4.24:1，不到 AA）20 处；11sp 及以下小字 46 处；分割线写死 `#318C8C8C` 9 处（对比度约 1.3:1，近乎不可见） | alpha → 0.7（约 7:1）；11sp→12sp、≤10sp 提一档；分割线统一 `@color/list_divider`（对卡片底约 3:1+） |
| `fragment_video_info.xml` + `VideoInfoFragment.kt` | 三连卡用 `?attr/colorPrimary` 亮粉打底 + 主题近白文字，对比度约 2.1:1（低于 AA）；且只在经典终端主题下用代码改暗底打补丁 | 改为「`?attr/colorSurface` 底 + `?attr/colorPrimary` 1dp 描边」，删掉主题特判代码 |
| 6 个 Kotlin/Java 文件 + `HighEnergyProgressBar`/`HotSearchAdapter`/`PageSelectorAdapter`/`DynamicHolder` | 跨文件写死的强调色：`Color.rgb(207,75,95)` ×7、`#FE679A`、`#FB7299`、`0xffff6699`、`#88FFFFFF` 等，切主题不变 | 收敛到 `ThemeManager.PRIMARY` / `LIKE_COLOR` / `TEXT_*`；高能进度条按主题主色 + 原不透明度合成 |

> 验证：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过（`--no-build-cache`；详见下方构建陷阱）。
> ⚠️ 陷阱：改 `res/` 的文件**集合**（增删/移动）后，Gradle build cache 可能回放陈旧的 merged-resources，表现为莫名其妙的 `Unresolved reference 'R.layout.xxx'`。必须 `:app:clean` + `--no-build-cache` 才能恢复。

### 第七轮（视觉体验优化 · 批次 1 + 批次 2 前半）

| 文件 | 问题 | 修复方式 |
|---|---|---|
| `ui/theme/BiliColors.kt`、`ui/theme/ThemeUtils.kt` | 与 `ThemeManager` 并行的第二套色板：`BiliColors` 152 处 `parseColor` 里实际只有 3 个调用点；`BiliDimens` 全工程 0 引用；`ThemeUtils` 18 个方法只用 1 个 | 把 3 个真实调用点迁到 `ThemeManager`（新增 `withPrimaryAlpha()`），删除两个文件 |
| `res/values/modern_styles.xml` + `styles.xml` | `ModernWindowAnimation`/`ModernButton`/`ModernCard` 与 `ButtonSecondaryStyle`/`ButtonSecondaryStyleLight`/`ButtonDangerStyle`/`TextViewSytle` 全部 0 引用 | 删除整个 `modern_styles.xml` 与 4 个死样式 |
| `ThemeManager.kt` + `SharedPreferencesUtil.java` | "外观风格 modern/classic"开关：无设置入口、两个消费方法 0 调用，纯幽灵功能 | 删除 `APPEARANCE_*`/`CLASSIC_CARD_BG`/4 个方法/`BiliColorScheme`/`APPEARANCE_STYLE` |
| `SettingsKeys.kt` + `ThemeManager.kt` + `SettingGroupActivity.kt` | `theme_selector` 在 `SettingsKeys.THEME` 与 `ThemeManager.PREF_KEY_THEME` 各定义一次；设置页绕过 `ThemeManager.setTheme()` 直接写 SharedPreferences（且用 `commit()`） | 常量合并为单一真源；`setTheme()` 改 `putStringSync`；设置页改调 `ThemeManager.setTheme()` |
| `BaseActivity.kt`、`PlayerActivity.kt`、`BiliTerminalApp.kt` | 三处各复制一份相同的「主题 key → style」`when` | 收敛为 `ThemeManager.themeResId()` |
| `activity_player.xml:5`、`activity_image_viewer.xml:7`、`cell_episode.xml:4` | 布局级 `android:theme="@style/Theme.BiliClient"` 覆盖运行时主题 —— 播放器/图片查看器/选集按钮无论选什么主题都渲染成 B站粉 | 移除三处 `android:theme` |
| `ImageViewerActivity.kt:28` | `setTheme(Theme_BiliClient)` 覆盖基类已按用户选择设置的主题 | 删除该调用 |
| `MediaEpisodeAdapter.kt:48`、`QualitySelectorAdapter.kt:56` | `ContextThemeWrapper(Theme_BiliClient)` 强制把卡片/按钮渲染成 B站粉主题 | 改用 `parent.context` 直接 inflate |
| `RefreshMainActivity.kt`、`RefreshListActivity.kt` | 下拉刷新转圈用 SwipeRefreshLayout 默认色，与 7 套主题都无关 | 补 `setColorSchemeColors(ThemeManager.PRIMARY)` |
| `res/values/dimens.xml` | 只有 8 个 token，字面量遍地（446 处 textSize 无一条走 dimen、圆角 9 档） | 扩到 30 个语义 token（间距/圆角/字号/触控/卡片/顶栏/行高/图标/分割线）；批次 0 的 56 处 48dp 改用 `@dimen/touch_min` |

> 验证：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过（`--offline --no-build-cache`）。
> 结果：`ui/theme/` 从 3 个文件收敛为 1 个（`ThemeManager.kt`）；`BiliColors|ThemeUtils|BiliDimens|ModernCard|ModernButton|APPEARANCE_STYLE|BiliColorScheme` 全工程 0 命中。

### 第八轮（视觉体验优化 · 批次 2：主题切换全应用生效）

| 文件 | 问题 | 修复方式 |
|---|---|---|
| 22 个布局（`panel_video_settings`/`cell_video_folder`/`dialog_new_folder`/`activity_update`/`activity_player` 等） | 76 处引用**静态**调色板（`pink_light`/`card_dark*`/`divider_dark`/`bgblack`…），切到知乎蓝/爱奇艺绿/紫色空灵时这些元素仍是 B站粉 | 按语义映射到 `?attr/colorPrimary`/`?attr/colorSurface`/`?android:attr/textColor*`/`?android:attr/colorBackground`/`?attr/colorAccent`/`@color/list_divider` |
| 21 个布局 | 56 处硬编码 `#fff`/`#ffffff`/`@android:color/white` 文字色（浅色主题下的白底白字地雷） | 改 `?android:attr/textColorPrimary` |
| 23 个布局 | 33 处杂项 hex：`#999999`、`#dd262626`、`#FE679A`、`#00000000`、`#6000`、`#FF5722`、`#74a864`、播放器进度条 `#aa44aaff`/`#eeFEFEFE`、二维码 `#fff` | 分别改主题属性/新增 `@color/scrim`、`@color/qr_plate`/语义状态色/`@color/player_progress_bg` |
| `drawable/zoom_btn_bg.xml` | 写死 B站旧蓝 `#00a1d6`，与 7 套主题都不一致 | 改 `?attr/colorPrimary`，按钮文字改 `?attr/colorOnPrimary` |
| `drawable/splash_text.xml` | 冷启动窗口底色 `#1B1B24` 与默认主题纯黑不一致，可见"深蓝灰→纯黑"跳变 | 改纯黑（对 6 套深色主题都最不突兀，默认主题完全一致） |
| `BaseActivity.applySystemBarInsets()` | 只消费 `systemBars()`，横屏挖孔机型内容可能压在刘海下 | 扩为 `systemBars() or displayCutout()` |

> 量化：布局内硬编码 hex **137 → 49**（剩余全是压在视频画面上的叠层/蒙层/验证码页，不应随主题变）；静态 `@color/*` 95 → 29（剩 `list_divider`、`status_*`、专栏代码块色）。
> 验证：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过。
> ⚠️ 事故：`activity_vote_info.xml` 曾被 PowerShell 文本往返写成乱码，已 `git checkout` 恢复重做。**含中文的源码/资源一律不要用 PowerShell 读写。**

### 第九轮（视觉体验优化 · 批次 3：文字层级 / 卡片规格 / 封面比例）

| 文件 | 问题 | 修复方式 |
|---|---|---|
| `cell_setting_nav.xml` | 设置条目标题 12sp 比分组标题 14sp 还小（层级倒置）；描述与标题同字号同色；图标 `layout_height=0dp` + `constraintHeight_percent=0.4` 挂在 `wrap_content` 父级上（循环依赖） | 标题 → 14sp、描述 → 12sp + `?android:attr/textColorSecondary`、图标 → `@dimen/icon_lg`(24dp) |
| `cell_setting_title.xml` | 分组标题与条目标题无区分 | 改 `?attr/colorPrimary` + 加粗 |
| `fragment_video_info.xml` | 视频标题 13sp **小于 UP 主名 14sp**；简介折叠外无展开入口（后者留批次 4） | 标题 → `@dimen/text_title`(16sp) + 加粗 |
| `cell_video_list.xml`、`cell_video_local.xml` | 封面行的 `app:layout_constraintDimensionRatio` 写在 **LinearLayout** 里完全失效 → 行高由每张图自身比例决定，列表滚动持续抖动（CLS）；本地卡还有 3 行标题、11–12sp 小字、写死的紫色速度文字 | 封面行重构为 ConstraintLayout：48% 宽 + 16:9 + `centerCrop`；标题 → 13sp/2 行；速度色 → `?attr/colorPrimary` |
| `cell_user_info.xml`、`cell_up_list.xml` | 头像 `0dp` 无基准 / 「父高 70% + 1:1」约束环，尺寸不可预期 | 分别固定 56dp / 40dp + `centerCrop` |
| `cell_favorite_folder_list.xml` | 内层 `match_parent` 挂在 `wrap_content` 卡片上；两个 Guideline 的 id 与实际方向相反；收藏夹名只给 1 行 | `match_parent` → `wrap_content`；Guideline 按实际方向改名（`guide_cover_end`/`guide_title_bottom`，代码无引用）；标题改 2 行 + 13sp |
| `cell_recent_up_list.xml` | `android:orientation` 写在 RecyclerView 上（无效属性） | 删除并加注释说明由 LayoutManager 决定 |
| `cell_article_head.xml` | 子元素挂在 LinearLayout 上的一堆 `app:layout_constraint*` 全部无效；引用了本文件不存在的视图 id | 删除死约束；标题 15sp → `@dimen/text_title` |
| `cell_private_msg.xml` | include `cell_video_list` 时宽 `wrap_content`，而内部按父宽百分比布局 → 失去基准 | 改 `match_parent` |
| `dimens.xml` | `card_round` 6dp 与 CardView 的 12dp 并存；列表左右仅 6dp、卡片间距 2dp | `card_round` → 12dp、`activity_padding_horizontal` → 12dp、`list_margin_vertical` → 6dp |
| `activity_search.xml`、`background_searchbar.xml` | 搜索框 42dp 高、6dp 圆角（非胶囊）、底/描边写死 `#80242424`/`#cc808080` | 48dp + 24dp 胶囊圆角 + 主题表面色/描边色，字号 14sp、内边距 16dp |

> 验证：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过。
> 施工中一次失误：`cell_video_local.xml` 重构时漏删原 `</LinearLayout>`，AAPT 报「必须由匹配的结束标记终止」，已修。

### 第十轮（视觉体验优化 · 批次 4 前半：公共顶栏 / 空态 / 菜单）

| 文件 | 问题 | 修复方式 |
|---|---|---|
| `cell_topbar.xml` | 名为公共顶栏但 **0 处 `<include>`**，47 个布局各自手抄；顶栏整条约 23dp 高（不足 48dp 的一半），而 `BaseActivity.setTopbarExit()` 让**整条顶栏**点击就 `finish()`；标题仅 14sp、无背景、无分割线、时钟与标题同字号同字重 | 重写为真实公共组件：`minHeight=48dp`、标题 16sp、时钟 12sp 次要色、`?attr/colorSurface` 背景 + 1dp 分割线 |
| `activity_simple_refresh/main_refresh/list/viewpager/text`、`activity_loading`（6 个） | 手抄顶栏 | 改为 `<include layout="@layout/cell_topbar" />`；`@id/top`、`@id/pageName`、`@id/timeText` 三个 id 保持不变，基类 `setPageName()`/`setTopbarExit()`/`setRound()` 无需改动 |
| `BaseActivity.kt`、`InstanceActivity.kt` | 顶栏左侧箭头（`arrow_back` / `arrow_up`）写死在每个布局里，47 份各写各的 | 新增 `BaseActivity.setTopbarIcon()`：二级页由 `setTopbarExit()` 设 `arrow_back`、一级页由 `setMenuClick()` 设 `arrow_up` |
| 5 个空态布局（`activity_simple_refresh/main_refresh/main_list`、`fragment_simple_refresh/list`） | 空态是"一行纯文字 `啥都木有~`"，无图标、无色（一级页此前根本没接线，见第六轮） | 加 `drawableTop="@mipmap/loading_2233_error"` + 间距 + 内边距；不动 id/层级，基类逻辑不变 |
| `MenuActivity.kt` | 14 个菜单按钮**完全紧贴**成一整块，无间隔、无左对齐 | 加 8dp 按钮间隔 + 2dp 上边距、文字左对齐、`minHeight=48dp` |
| `cell_reply_list.xml` | 评论头像 35dp 与用户列表/UP 列表的 40dp 不一致 | 统一 40dp + `centerCrop` |

> 验证：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过。
> 顶栏改动**建议优先真机确认**：这是每个页面都会看到的组件，且顺带把热区从 ~23dp 提到 48dp。

### 第十一轮（视觉体验优化 · 批次 4 后半：闪屏 / 转场 / 占位图 / 二维码 / 横屏）

| 文件 | 问题 | 修复方式 |
|---|---|---|
| `activity_splash.xml` | `splash_text.xml`（窗口背景）已经把 128dp 应用图标居中铺好，布局里的启动文字也是整屏居中 → **文字正好压在图标上** | 文字改贴底居中（`paddingBottom=96dp`），形成"图标居中 + 文案在下" |
| `anim_activity_out_down.xml`（新增）、`styles.xml`、`themes.xml` | 全工程只有 `InstanceActivity.kt` 一处 `overridePendingTransition`，其它页面各用系统默认动画，进出场不一致 | 新增退场动画 + `BiliWindowAnimation`（开/退场各两个），挂到 **8 个主题基类**的 `android:windowAnimationStyle` |
| `mipmap-nodpi/placeholder*.png`、`article_placeholder.png` | 占位图平均色 #DFDFDF（专栏版还是浅蓝 #90E4FD），在纯黑默认主题上每次进列表都闪一块白 | 用自写 PNG 编码脚本重做为深色中性占位（底色与卡片一致的 #2A2A35 + 略亮图形），4.7KB → 0.6KB，**无需改任何代码**（覆盖同名资源） |
| `QRLoginFragment.kt`、`fragment_qr_login.xml` | 默认档 `0.01/0.99` 卡片几乎占满屏宽，而二维码卡片是 1:1 正方形 → 横屏/平板上高度超出视口把状态文字挤出屏；且档位命名与百分比矛盾（LARGE 比 SMALL 小） | 默认改中档 `0.15/0.85`；三档重排为 50%/70%/90% 并同步 Guideline 初值；卡片加 `layout_constraintWidth_max="280dp"` |
| `BaseActivity.kt` | 横屏固定 3 列：窄屏每列不到 210dp（卡片被压扁），大屏又太空 | 按「每列 ≥220dp」换算列数，下限 2 列 |

> 验证：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过。
> 说明：占位图是覆盖同名二进制资源，AAPT2 对 PNG 的校验由资源合并任务通过佐证；如对观感不满意，`git checkout app/src/main/res/mipmap-nodpi/placeholder*.png` 即可回退。

### 第十二轮（视觉体验优化 · 批次 4 收尾：加载更多 footer / 菜单当前页高亮）

| 文件 | 问题 | 修复方式 |
|---|---|---|
| `adapter/LoadMoreFooterAdapter.kt`（新增）、`RefreshMainActivity.kt`、`RefreshListActivity.kt` | 翻页加载复用了 `swipeRefreshLayout.isRefreshing = true`，于是"加载下一页"表现为**顶部弹出下拉刷新转圈**：既误导（看着像在刷新），也完全看不出还有没有更多 | 新增 footer adapter，基类用 `ConcatAdapter(业务 adapter, footer)` 包装；`goOnLoad()` → 「正在加载…」、`setRefreshing(false)` → 按 `bottom` 显示「没有更多了」/ 不占位。footer 在末尾，业务 `adapterPosition` 语义不变；`notifyItemChanged` 统一判定线程（`setRefreshing(false)` 常来自后台线程） |
| `MenuActivity.kt` | 打开菜单看不出当前在哪一页 | 用 `from` extra 给对应当前页的按钮加主色描边 + 主色文字 |

> 验证：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过。
> 风险提示：`ConcatAdapter` 会给 `itemCount` +1，翻页触发阈值（`itemCount - 4`/`itemCount - 3`）会比原来早一项触发，属可接受偏差；直接 `recyclerView.adapter = x` 的页面（如热搜）不会显示 footer（不影响功能）。

### 第十三轮（视觉体验优化 · 公共顶栏全量推广）

| 文件 | 问题 | 修复方式 |
|---|---|---|
| 38 个布局（`activity_search`/`activity_myspace`/`activity_message`/`activity_vote_info`/`activity_setting_*`/`activity_update`/`activity_menu` 等） | 顶栏仍是各自手抄的副本：约 23dp 高、标题 14sp、无背景无分割线；改一次要改 38 处 | 用结构探测脚本（仅当顶栏块内除 `top`/`pageName`/`timeText`(`menuArea`) 之外没有其它控件时才替换）批量改为 `<include layout="@layout/cell_topbar" />`；共 **44 个布局**共用公共顶栏 |
| `MenuActivity.kt` | 菜单页顶栏改用公共顶栏后，图标需要是"收起菜单"的箭头（它不走 `InstanceActivity.setMenuClick`） | 显式 `setTopbarIcon(R.drawable.arrow_up)` |

> 保留自有顶栏的仅剩 2 个：`activity_player.xml`（顶栏含电量/时钟/标题）、`fragment_short_video_page.xml`（含标题）——属合理差异。
> 验证：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过（XML 结构正确性由 AAPT2 解析通过佐证）。

### 第十四轮（真机验证发现回归：顶栏吃满整屏）

| 文件 | 问题 | 修复方式 |
|---|---|---|
| `cell_topbar.xml` | 第十三轮把 44 个布局换成 `<include layout="@layout/cell_topbar" />` 后，**真机实测顶栏占满整个内容区**（`top` bounds = `[0,114][1080,2394]`，标题被挤到屏幕正中、列表被顶到屏幕外）。根因：顶栏根节点是 `RelativeLayout`，而我给它加的 1dp 分割线用了 `android:layout_alignParentBottom="true"` —— **RelativeLayout 只要含"贴底子元素"，自身 `wrap_content` 就会被撑成父容器高度** | 顶栏根节点改为**竖向 LinearLayout**（高度 `wrap_content`，天然不依赖父容器类型），内层保留一个 RelativeLayout 放标题与时钟（`BaseActivity.setRound()` 会把 `pageName` 的 layoutParams 强转为 `RelativeLayout.LayoutParams`）。同时给 44 个 `<include>` 补上显式 `layout_width/layout_height` 参数 |
| 44 个布局 | `<include>` 未写布局参数（Android 最佳实践要求显式声明） | 批量补 `layout_width="match_parent"` / `layout_height="wrap_content"` |

> **真机验证证据**（设备 `10AC9S2M4L000QL`，1080×2520 / density 480）：
> - 修复后 `top` = `[0,114][1080,261]` → **147px = 49dp** ✓（顶栏 `topbar_height` 48dp + 1dp 分割线）
> - 推荐页：`swipeRefreshLayout` 紧接顶栏（`[0,261]` 起）；视频卡左右留白 36px = 12dp、卡间距 36px = 12dp；`img_cover` = 449×253px → **16:9** ✓；标题 2 行 ✓
> - 视频详情页顶栏同为 49dp ✓（include 跨页面生效）
> - 安装方式：`adb install -r app-arm64-v8a-debug.apk`（覆盖安装，登录状态保留）

### 第十五轮（用户反馈回滚：顶栏 / 占位图 / 转场动画 / 尺寸基准）

用户在真机上确认：**这是手表端应用**，且更满意修改前的观感。按反馈回滚：

| 回滚项 | 处理 | 证据 |
|---|---|---|
| **顶栏恢复修改前样式** | 44 个布局的顶栏块用 `git archive HEAD` 导出的原始文件逐块还原（只替换顶栏块，本轮其它改动保留）；`cell_topbar.xml` 也还原为原来的未被使用模板；移除上一轮新增的 `BaseActivity.setTopbarIcon()` 与各页调用（箭头重新由各布局自己的 `drawableStartCompat` 决定） | `activity_menu.xml` 相对 HEAD **零差异**；设置页真机实测 `top` = `[0,114][1080,190]` → **76px = 25dp**（与原来一致） |
| **占位图恢复原图** | `placeholder.png` / `placeholder_noround.png` / `article_placeholder.png` 全部 `git checkout` 还原 | 字节数回到 4756 / 4327 / 5589，`git status` 该目录无改动 |
| **砍掉页面切换动画** | 移除 8 个主题基类的 `android:windowAnimationStyle`、删除 `BiliWindowAnimation` 样式与 `anim/anim_activity_out_down.xml` | 全工程 grep `BiliWindowAnimation\|anim_activity_out_down\|windowAnimationStyle` 仅命中注释 |
| **菜单恢复居中与原始间隔** | 撤销菜单按钮的 8dp/2dp 间隔与左对齐、去掉强制 `minHeight`（保留"当前页高亮"这一项） | `MenuActivity.kt` |
| **尺寸基准改为手表紧凑** | 基础 token 按手表给（顶栏 30dp、列表留白 6dp、卡片间距 2dp、圆角 6dp、标题 14/15sp）；新增 `values-w300dp/dimens.xml` 给宽屏放大一档（顶栏 36dp、留白 8dp、间距 3dp） | 真机（360dp 宽，命中 w300dp）：顶栏 36dp + 分割线、卡片左右 8dp |

> 验证：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过；安装后无崩溃日志。
> 教训：顶栏这类"每页都有"的组件，在没有真机确认前不该做视觉改版；`wrap_content` 的 RelativeLayout 不能放 `layout_alignParentBottom` 子元素（会把高度撑满父容器，见 `docs/architecture-map.md` 第 8.5 节）。

### 第十六轮（两个播放器的性能与逻辑优化）

普通播放器（`activity/player/PlayerActivity.kt`）：

| 位置 | 问题 | 修复方式 |
|---|---|---|
| `:955` | `showLoadingSpeed()` 每次缓冲开始都 `new Timer()` 且不 cancel 旧的，连续缓冲会叠加多个 Timer 线程同时写同一个 `loading_text1`，旧线程永不退出 | 改主线程 `Handler` 自循环（500ms），重复调用先 `removeCallbacks` |
| `:1015` | `progressChange()` 用 `java.util.Timer` 每 250ms tick，tick 内再 `runOnUiThread`（两层跨线程投递）；且每 250ms 一次 `MediaSession.setPlaybackState` 的 Binder IPC | 改主线程 `Handler` 自循环（250ms），去掉全部 `runOnUiThread`；MediaSession 改为**整秒去重**上报 |
| `:1560` | `cancelAllTimers()` 漏 `speedTimer`，退出页面后该 Timer 线程仍在 | 补 `speedTimer?.cancel()`；显式清理 progress/loading/resize 三个 Runnable |
| `:812` | `setDisplay()` 两个分支各自 `new Timer()` 都不 cancel 旧的，旧 timer 会和新的一起调 `MPPrepare` → 重复 `setDataSource`/`prepareAsync` | 分支前统一 `surfaceTimer?.cancel()` |
| `:1114` | `showSubtitle()` 每 tick 都 `setText`/`setVisibility`（各自还带 `runOnUiThread`） | 用 `subtitle_shown_index` 去重；切换字幕轨时复位该标记；去掉 `runOnUiThread`；补循环内 `subtitleCurr` 收敛赋值 |
| `:1005` | `changeVideoSize()` 的 `postDelayed(…, 60)` 无 `removeCallbacks`，快速旋转会叠加 | 改用可复用 `resizePostRunnable` + 先移除（移除时用同一 View） |
| `:1856` | `onProgressChanged` 里包了多余的 `runOnUiThread`（回调本就在主线程） | 去掉包装 |

短视频播放器（`activity/video/ShortVideoPlayerActivity.kt` + `util/VideoPreloadManager.kt`）：

| 位置 | 问题 | 修复方式 |
|---|---|---|
| `:233` | `holders` map 只增不减：holder 被复用到新 position 后旧 key 仍指向它，`pausePlayer(旧pos)` 会暂停错对象（表现为上一页不停、两路声音） | `onViewRecycled` 摘除 map 条目 + 复位 `boundPosition`；回收的恰是活跃页时同步清 `activeHolder` |
| `:245` | `setupPlayerAtPosition` 不更新 `activeHolder`，`pauseCurrent()`/`isCurrentPlaying()` 可能指向过期页面 | 补 `activeHolder` 归位，并先停掉旧活跃页 |
| `:103` | 追加分页用 `notifyDataSetChanged()`：ViewPager2 不保证保持当前页（可能跳页）且会重建全部页面 | 改 `notifyItemRangeInserted(start, items.size)` |
| `:172` | `onStop` 只在 `isFinishing` 时释放播放器，被系统因内存压力回收时播放器泄漏 | `onDestroy` 补 `releaseAll()` 兜底 |
| `:282` | `releaseAll()` 不清 `activeHolder`/`lastVisiblePosition`，释放后仍指向已释放的 holder | 一并复位 |
| `VideoPreloadManager.kt:11` | `allItems` 是普通 `mutableListOf`，后台 `addAll` 与主线程读并存（数据竞争）；`isLoading` 非 `volatile` 导致 `loadMore()` 可能重复拉取；`preloadedItems` 是死字段 | 改 `CopyOnWriteArrayList`；`isLoading` 加 `@Volatile` 并把"检查+置位"前移到调用方线程；删死字段；载体加 `try/finally`（否则 fetch 抛异常会让 `isLoading` 永久为 `true`，之后再也拉不到数据）；`release()` 置 `released` 拦住回流 |

`player/PlayerControlDelegate.kt`：

| 位置 | 问题 | 修复方式 |
|---|---|---|
| `:215` | **长按后手势全失效**（原 P0 待办）：`onLongPress` 置 `isLongPressing = true` 后无任何地方复位，`onScroll` 首行直接 return，音量/亮度/进度手势再也无法恢复 | `onDown` 里复位；顺带去掉 6 处冗余 `abs(...).toFloat()` |

> 验证：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过（11 个测试类 / **59 个用例 / 0 失败**）；产出 4 个 debug APK。

> **本轮有一条假设被推翻，记录以免后人重犯**：起初判断"短视频弹幕 XML 解析跑在主线程、是滑动卡顿主因"，并据此把 `DanmakuManager.createParser()` 挪到了后台线程。追库源码后确认该判断**错误**——
> - `BiliDanmakuLoader.load(InputStream)` 只做 `new AndroidFileSource(stream)`（`BiliDanmakuLoader.java:44-46`）；
> - `AndroidFileSource(InputStream)` 只存引用（`AndroidFileSource.java:44-46`）；
> - `BaseDanmakuParser.load(IDataSource)` 只存 `mDataSource`（`BaseDanmakuParser.java:67-70`）；
> - 真解析在 `getDanmakus()` → `parse()`（`BaseDanmakuParser.java:81-89`）**懒执行**，调用点全工程只有 `DrawTask.java:283`，跑在 `DanmakuView` 自己的渲染线程上。
>
> 也就是说 `createParser()` 里根本没有耗时操作，挪到后台**零收益**，反而引入新竞态：`DanmakuLoaderFactory.create(TAG_BILI)` 返回的是**进程级单例** `BiliDanmakuLoader.instance()`，`dataSource` 是它的**实例字段**，`load()` 写字段 + `loader.dataSource` 读字段是一段 check-then-act，并发时两个 holder 会拿到同一个数据源（弹幕串台/解析为空）。
> **已回退该改动**，并在 `DanmakuManager.createParser()` 上补了中文警示注释说明"只能主线程、不可并发"及其原因。

### 第十七轮（播放器性能优化 + 播放内核合并，方案 A + 方案 B）

本轮目标：**以保证播放性能为最高优先级**，把两个播放器的播放内核与弹幕栈合并为一套。
设计文档：`docs/superpowers/specs/2026-09-10-player-core-merge.md`。

**S1 · Surface 就绪事件化（新增 `player/PlayerSurfaceBinder.kt`）**

| 位置 | 问题 | 修复方式 |
|---|---|---|
| `PlayerActivity.kt` `setDisplay()` | 用 `java.util.Timer` 每 200ms 轮询 surface 是否就绪。首播/切清晰度/切分页/切听视频模式**每次都新建一个 Timer 线程**；surface 未就绪时最长等满 200ms 才开始 `prepareAsync`，**直接拖慢首帧** | 新增 `PlayerSurfaceBinder`，改用 `SurfaceTextureListener` / `SurfaceHolder.Callback` 事件回调；已就绪则同步立即回调。删除 `surfaceTimer`、`mSurfaceTexture` 字段与 `Surface`/`SurfaceHolder`/`SurfaceTexture` 三个 import |
| 同上（隐性 bug） | 旧代码是**轮询命中之后**才 `holder.addCallback(...)`，若 surface 早已 created，`surfaceCreated` 再不会触发 —— 那段"重建时重新 setDisplay"的逻辑实际是死的 | 回调在 `initUI` 就注册，现在能正常触发 |

> **线程决策（有意为之）**：`PlayerSurfaceBinder` 保证 `await()` 与所有回调都在主线程执行（`setDisplay()` 会从 onCreate 的 `CenterThreadPool.run` 块里被调用，而 View 状态只能主线程读）。因此 `MPPrepare()` 从 Timer 线程变为**主线程**。保留该选择是因为主线程执行**天然串行化**，能避免用户快速连点切清晰度时两路并发对同一个 `IjkMediaPlayer` 调 `setDataSource`/`prepareAsync`；而 `ijkplayer-java` 的 `setDataSource` 最终只走 native `_setDataSource`（`IjkMediaPlayer.java:400-403`），短视频播放器本来也就在主线程这么做。

**S2 · 新增公共播放内核 `player/VideoPlayerCore.kt`（纯新增，零回归风险）**

多实例安全（非单例、不持有 Activity、`release()` 幂等）、内部自管 Surface、`reload()` 复用同一个 `IjkMediaPlayer` 只 `reset()`+重设 options（省掉切清晰度/切分页的一次 native 播放器创建）、`onPosition(pos, duration)` 高频回调。**目前尚无消费方**，价值待 S4 接入后兑现。

**S3 · 弹幕栈合并（方案 B）**

| 位置 | 问题 | 修复方式 |
|---|---|---|
| `DanmakuManager.kt` | 新版 protobuf 分段弹幕能力只内联在 `PlayerActivity.downdanmuNew()` 里；两个播放器各有一套弹幕配置与回调 | 新增 `loadFromProtobufSegments()`、`prepareEmpty()`（直播空 parser）；`addDanmaku` 扩成与原 `PlayerActivity.addDanmaku(text,color,textSize,type,backgroundColor)` **完全一致**的签名（`PlayerDanmuClientListener` 依赖该签名，一致才能零改动迁移） |
| `PlayerActivity.kt` | 内联 `createParser`×2 + `streamDanmaku`×2 + `mContext` 字段，与 `DanmakuManager` 重复实现同一套 `DanmakuContext` 配置 | 全部删除（含 12 个已无用的 parser import），改为 `bindDanmakuView()` / `releaseDanmaku()` / `prepareDanmaku {}` 三个小助手委托给 `DanmakuManager` |

> **行为保持**：`addDanmaku` 的 time/priority/textSize 公式、六项 `DanmakuContext` 配置、`setMaximumLines`/`preventOverlapping` 映射均与原实现逐项对齐。
> **刻意差异**：`prepared()` 系统提示文案由"弹幕君准备完毕～(是新来的哦～)/(*≧ω≦)"统一为"弹幕准备完毕"。纯文案。

**S5 · 短视频滑动期开销（部分完成）**

| 位置 | 问题 | 修复方式 |
|---|---|---|
| `ShortVideoPlayerActivity.kt` `bind()` | 每次绑定都重建 `GestureDetector` + `ScaleGestureDetector`、重设 `setOnTouchListener` 与全部按钮/SeekBar 监听器 ——滑动时纯浪费的分配 | 全部移到 `PageHolder` 的 `init {}` 一次性注册，`bind()` 只做条目数据绑定 |
| `IjkPlayerBridge.release()` | `stop()` → `reset()` → `release()`，而 `stop()` 是等待型调用，这段跑在主线程的 `onViewRecycled` 上，每滑一页阻塞一次 | 去掉 `stop()`（`reset()` 已足够） |
| `ShortVideoPlayerActivity` | 缓冲指示器每次 state 发射都无条件 `setVisibility`；进度文本每 250ms 都拼字符串 + `setText`（而显示值一秒才变一次） | 按"目标可见性是否变化"/"整秒是否变化"才刷；`releasePlayer()` 一并复位这些标记 |

> 验证：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过（11 个测试类 / **59 个用例 / 0 失败**），APK 与 class 时间戳均为当次构建产物。

### 第十八轮（第十七轮引入的弹幕回归修复 + 短视频弹幕接入新版接口）

**回归根因（真机复现："普通视频的弹幕间歇性不出现"）**

S3 把 `PlayerActivity` 内联的弹幕栈换成 `DanmakuManager` 时，我删掉了一个**载荷性的守卫**：

```kotlin
// 改前（PlayerActivity 内联）：播放器未就绪时根本不更新 timer
override fun updateTimer(timer: DanmakuTimer) {
    if (ijkPlayer != null && isPrepared) timer.update(ijkPlayer!!.currentPosition)
}

// 改后（DanmakuManager）：无条件读位置
override fun updateTimer(timer: DanmakuTimer) { timer.update(onCurrentPositionMs()) }
// 而 lambda 写成了 { ijkPlayer?.currentPosition ?: 0L }
```

`updateTimer` 运行在 **DanmakuView 的渲染线程**上，而主线程在切清晰度/切分页/切听视频模式时会 `release()` 并重建 `IjkMediaPlayer`。`IjkMediaPlayer` 的 native 层**不是线程安全的**，在那个窗口期从渲染线程读 `currentPosition` 可能拿到脏值；一旦脏值被灌进 `DanmakuTimer`，整批弹幕会被判定为"已过期"而**一条都不显示**。因为是竞态，所以表现为**间歇性**——这也是为什么中途某次重装后"看起来修好了"，其实从未修复。

| 位置 | 修复方式 |
|---|---|
| `DanmakuManager.updateTimer` | 回调返回负数时**跳过**本次更新；KDoc 里写明"播放器未就绪/正在重建必须返回负数" |
| `PlayerActivity.bindDanmakuView` | 位置回调改为 `if (isPrepared) ijkPlayer?.currentPosition ?: -1L else -1L` |
| `ShortVideoPlayerActivity` 的 `danmakuManager` 位置回调 | 同样加 `if (isPrepared) ... else -1L` 守卫 |
| `PlayerActivity.isPrepared` / `PageHolder.isPrepared` | 加 `@Volatile`（主线程写、弹幕渲染线程读，原代码就缺这个可见性保证） |
| `DanmakuManager.configureAndPrepare` 的 `prepared()` 回调 | 包 try/catch：该回调同样在渲染线程上，异常逸出会打断渲染线程，症状同样是"一条弹幕都不显示"且上层无任何报错 |

**短视频"一直没有弹幕"的根因（既有问题，非本轮引入）**

`PlayerApi` 给短视频设的弹幕地址是 `https://comment.bilibili.com/{cid}.xml`（**旧版 XML 接口**，B站已基本停用），而普通播放器早已切到 `DanmakuApi.getAllVideoDanmaku()` 的 **protobuf 分段接口**（`NEW_DANMAKU_API` 默认开启），短视频从未跟上。

| 位置 | 修复方式 |
|---|---|
| `ShortVideoPlayerActivity.loadDanmaku` | 改为优先走 `DanmakuApi.getAllVideoDanmaku(aid, cid, 时长)` + `loadFromProtobufSegments()`；分段接口确实拿不到数据时才回退旧版 XML（回退逻辑抽成 `loadDanmakuFromXml`） |

> 这正是"合并弹幕栈（方案 B）"应该带来的收益：能力合并之后，短视频补新版弹幕只需要换一个调用。

**本轮其他真机发现（未修，另案）**：短视频 story feed 接口在真机上返回 `code=-400 请求错误`（`ShortVideoFeedApi.fetchStoryFeed`），目前靠 `fetchIndexFeed` 兜底才有内容。

> 验证：`:app:assembleDebug` + `:app:testDebugUnitTest` 通过（59 个用例 / 0 失败），已安装到真机 `10AC9S2M4L000QL`。

### 第十九轮（设置页二级列表崩溃 + 视觉批次遗留项按用户反馈回滚）

**1. 设置页二级列表必崩（用户报"修复设置页面第二级列表报错"）**

| 位置 | 问题 | 修复方式 |
|---|---|---|
| `adapter/SettingsAdapter.kt` | 第十二轮为做"加载更多 footer"给基类套了 `ConcatAdapter`。`ConcatAdapter` 会按**包装顺序重新映射** `getItemViewType()` 的返回值，导致原本的 `-1`/`-2` 负值 viewType 在 `onBindViewHolder` 里落到错误的 Holder 上，抛 `ClassCastException: SettingsAdapter$NavHolder cannot be cast to SettingsAdapter$SwitchHolder`（`SettingsAdapter.kt:131`，由 `CustomLinearManager.onLayoutChildren` 触发） | 去掉基类的 `ConcatAdapter` 包装；`viewType` 改为非负常量 `TYPE_SWITCH=0 … TYPE_TITLE=9`；`onBindViewHolder` 改为 `when (holder) { is NavHolder -> … }` **按类型分派**，不再盲转 |
| `activity/base/RefreshListActivity.kt`、`RefreshMainActivity.kt` | 去掉 footer adapter 后，"没有更多了"没有承载者 | 改为布局里放一个 `@+id/loadMoreTip` TextView（`gone` / 11sp / 次要色），`setRefreshing(false)` 按 `bottom` 置「没有更多了」，`goOnLoad()` 置「正在加载…」 |
| `adapter/LoadMoreFooterAdapter.kt` | 已无引用 | 删除 |

> 顺带修掉 `ConcatAdapter` 的两个副作用：`itemCount` 不再 +1（翻页触发阈值回到原语义）、直接 `recyclerView.adapter = x` 的页面也不再被排除在 footer 之外。
> 用户真机验证：「测试 ok」。

**2. 播放器进度条与周边控件间距变大（用户报"间距太大，还原原来的间距"）**

批次 0 曾把进度条 `layout_height` 由 `20dp` 抬到 `48dp` 并加 `paddingVertical="14dp"`，同时给播放器 30 个按钮补 `minWidth/minHeight="@dimen/touch_min"`。真机上表现为进度条与上下控件之间多出明显空隙。

| 位置 | 处理 |
|---|---|
| `layout/activity_player.xml` | 进度条还原为 `layout_height="20dp"`、去掉 `paddingVertical`；移除全部 30 行 `minWidth/minHeight="@dimen/touch_min"`。相对 HEAD 仅剩主题色差异（`@color/player_progress_bg` 等） |

> 注：48dp 最小触控仍在 `bottom_bar_multi_select.xml` / `fragment_qr_login.xml` / `bar_quality_select.xml` / 两个 dialog 中保留（用户本次只指出播放器，且手表端以小屏可点为准）。

**3. 菜单页"当前所在页面"按钮变色（用户报"选择某个页面，页面按钮会变色"）**

第十二轮加的「当前页高亮」（`btn == from` 时设主色描边 + 主色文字）在实际使用中被判定为 bug：从某页返回菜单时，该页按钮**长期带色**，看着像被选中/坏掉。

| 位置 | 处理 |
|---|---|
| `activity/MenuActivity.kt` | 删除 `if (btn == from)` 整段（`strokeWidth` / `strokeColor` / `setTextColor`）及相关 `ThemeManager`、`ToolsUtil` import；菜单恢复"零间隔 + 文字居中 + 无高亮"的原始观感，`MenuActivity.kt` 相对 HEAD **零差异** |

> 顶栏上的 `setPageName(from)` 保留（它不是按钮变色，只是标题跟着当前页走）。
> 真机验证（设备 `10AC9S2M4L000QL`，以 `--es from recommend` 冷启动）：菜单 12 个按钮正常渲染、`推荐` 按钮无描边、无 `FATAL EXCEPTION` / `ClassCastException`。

> 构建提示：本轮踩到 AGENTS.md 记录的 build cache 坑两次——一次是 `:app:clean` 与 `:app:assembleDebug` **写在同一次 Gradle 调用**里会因配置缓存复用出现 `navigation.json NoSuchFileException` 竞态；一次是资源合并 `FROM-CACHE` 回放陈旧结果，报一片 `Unresolved reference 'R.layout.xxx'`。可行命令是**分两次调用 + 关掉配置缓存**：
> ```bash
> ./gradlew.bat :app:clean --offline --no-configuration-cache
> ./gradlew.bat :app:assembleDebug --offline --no-build-cache --no-configuration-cache
> ```

**4. 搜索框样式（用户报"搜索框的样式给我完全回退"）**

批次 3 把搜索框改成了"胶囊形 + 主题色"的新样式，真机上用户要求完全回退。搜索框**只出现在搜索页**（全工程 grep `background_searchbar` / `keywordInput` 仅命中 `activity_search.xml`），涉及两个文件：

| 位置 | 我改成了 | 回退为（= HEAD） |
|---|---|---|
| `drawable/background_searchbar.xml` | `<solid ?attr/colorSurface>` + `1dp ?attr/colorControlNormal` 描边 + `corners 24dp`（胶囊） | `#80242424` 填充 + `2dp #cc808080` 描边 + `corners @dimen/card_round`（圆角矩形） |
| `layout/activity_search.xml` 的 `keywordInput` | `minHeight=@dimen/touch_min`(48dp)、`paddingStart/End=16dp`、`textSize=@dimen/text_subtitle`(13/14sp) | `minHeight=42dp`、`paddingStart/End=8dp`、`textSize=13sp` |

> `card_round` 在 HEAD 与当前 `values/dimens.xml` 中都是 6dp（`values-w300dp` 的 10dp 只在手机生效），所以回退后手表端观感与改动前完全一致。
> 同文件里 `#6000` → `@color/scrim` 那处**一并回退**（属于"完全回退"范围）。两者视觉等价：`#6000` 是 `#ARGB` 写法 = 40% 黑，`@color/scrim` = `#60000000` = 37.6% 黑，差值不可辨。
> 真机验证：`keywordInput` bounds 高 **126px = 42dp**（改后为 48dp=144px），左边距 18px = 6dp，无异常日志。

> 本轮**未动**这三处（不属"搜索框"本身）：`activity_setting_search.xml`（4 处 `#00000000`→`@android:color/transparent`，颜色等价、零视觉差异）、`item_hot_search.xml`（热搜条目的榜单序号色改主题主色 + 图标加 8dp 右间距）、`layout-v17/-v22/item_hot_search.xml`（我删掉了这两个旧副本，而 `layout-v22` 在 API≥22 的机器上**优先级高于 `layout/`**，等于让热搜条目换了实现——如需还原请告知）。

### 第二十轮（手表端启动路径优化 + MultiDex 死代码清理）· 26.09.10

| 文件 | 问题 | 修复方式 |
|---|---|---|
| `PerformanceManager.kt:136` | `Logu.i(..., "…score=${getHardwareScore()}")` 的开关在 `Logu` **函数体内**判断，参数先求值——release 版日志已关闭，每次冷启动仍在主线程重跑 `getHardwareScore()`（`RandomAccessFile` 读 `/sys/.../cpuinfo_max_freq`，失败再读 `/proc/cpuinfo` 全文并现场 `Pattern.compile`），而该结果只进日志、不参与任何逻辑 | 包一层 `if (Logu.LOGI_ENABLED)`，让求值真正惰性；debug 诊断信息不变 |
| `app/build.gradle`、`BiliTerminal.java`、`BiliTerminalApp.kt` | `minSdk 24` 下 ART 原生支持 multidex，`MultiDex.install()` 在 API 21+ 首行即返回；依赖与覆写均为死代码 | 移除 `androidx.multidex` 依赖、两处 `MultiDex.install` 及对应 `attachBaseContext` 覆写 |

> `multiDexEnabled true` 保留未动（minSdk ≥ 21 下无副作用，删它没有收益）。
> 同轮做了后续 `BiliTerminal.java → BiliTerminal.kt` 迁移的前置改动：22 个 Kotlin 文件共 35 处 `BiliTerminal.context` 补 `!!`（`Glide.with/get` 的参数带 `@NonNull`，字段改可空后会全部编译失败）。Java 侧 20 处字段访问保持不变。（Kotlin 转换见第二十一轮。）

### 第二十一轮（`BiliTerminal.java` → `BiliTerminal.kt`）· 26.09.10

| 项 | 处理方式 |
|---|---|
| `context` / `DPI_FORCE_CHANGE` | companion + `@JvmField` → 仍生成真正的 `public static` 字段，Java 侧 20 处 `BiliTerminal.context` 字段访问**零改动**；不引入 getter 包装层，避免每次访问多一次静态方法调用（`@JvmField` 与 `lateinit` 互斥，编译器明确报 `JvmField cannot be applied to lateinit property`，故选可空字段 + Kotlin 侧 `!!`） |
| `forceUpdateBlocking` 等 5 个可变状态 | companion 内 `@Volatile private var`（原为 `private static volatile`） |
| `getVersion()` | 加 `@Throws(PackageManager.NameNotFoundException::class)` 保留受检异常签名；`versionCode` 加 `@Suppress("DEPRECATION")` 消除每次构建的告警 |
| `getFitDisplayContext(old)` | 签名改为 `(Context?) -> Context?`——旧 Java 无注解属平台类型，而 `SplashActivity.attachBaseContext` 的入参声明为 `Context?`，写成非空会编译失败；内部 `old!!` 仍在 `try` 内，NPE 被 `catch (e: Exception)` 吞掉后返回 `old`，与 Java 行为逐字一致 |
| `registerActivityLifecycleCallbacks` | 改为 `object : Application.ActivityLifecycleCallbacks`（8 个方法须全实现，不能用 SAM lambda） |
| `checkAppUpdate` | 改为 `UpdateManager.checkUpdate(onResult = …, onError = {})`，`kotlin.Unit.INSTANCE` 互操作样板消失；空 `catch (Exception)` 原样保留 |
| 文件 | 新增 `BiliTerminal.kt` 并删除 `BiliTerminal.java`（同名同类不可并存，否则 duplicate class） |

**互操作契约用 `javap` 核实**（`app/build/tmp/kotlin-classes/debug`）：`public static android.content.Context context;`、`public static boolean DPI_FORCE_CHANGE;`、`getVersion() throws PackageManager$NameNotFoundException`、`jumpToVideo/jumpToArticle/jumpToUser/getFitDisplayContext/setInstance/getInstanceActivityOnTop/clearForceUpdate/isDebugBuild` 全部为 `public static final`。

### 第二十二轮（主题系统：色表缓存 + 主题单测网）· 26.09.11

主题系统重构（拆成「配色 / 卡片圆角 / 字体」三个独立模块）的**前置两步**。只做两件小步、可独立验证的事，**不改任何视觉**。

| 文件 | 问题 | 修复方式 |
|---|---|---|
| `ui/theme/ThemeManager.kt:351` | `getCurrentTheme()` 每次调用都读一遍 SharedPreferences 再跑一遍 `when`，而 `PRIMARY` 等 **36 个属性 getter 全部走它**；列表滚动时一个 item 就要调多次（`setTextColor` 40 处、其中 26 处取自 ThemeManager）——热路径上的重复 IO | 加 `@Volatile` 色表缓存；主题 key 的**唯一写入点** `setTheme()` 里置 null 失效；进程被杀后缓存为 null 自动重算 |
| `ui/theme/ThemeManager.kt:496` | 注释称「用 `apply()` 存在读到旧值的窗口」——**该说法不成立**：`SharedPreferences.Editor.apply()` 会**同步更新内存映射**，只把落盘放到异步 | 仅订正注释，**不改行为**（保留 `commit()`；代价是每个用户动作一次同步写盘，可忽略）。订正理由与代价已写进注释 |
| `app/src/test/…/util/NetWorkUtilTest.kt` | 私有内部类 `FakeSharedPreferences` 即将被第二个测试复用，否则又是一份拷贝 | 提取为共享助手 `app/src/test/…/util/FakeSharedPreferences.kt`，并加 `stringReadCount` 计数供性能断言 |

**新增 `app/src/test/java/com/RobinNotBad/BiliClient/ui/theme/ThemeManagerTest.kt`（14 个用例）**：此前主题/配色相关单测为 **0** 个，而该系统的历史 bug 全是「改一处漏一处」型（4 份 `key → 值` 的映射各写各的）。钉住的事：

| 钉住的事 | 为什么值钱 |
|---|---|
| 7 套 key → 7 个**互不相同**的 style | `themeResId` 漏一条分支会**静默回落到 else 的 B站粉**，不报任何错 |
| 7 套 key → 各自色表（比 PRIMARY/BACKGROUND/TEXT_PRIMARY/CARD/STATUS_BAR_COLOR） | 同上，`getCurrentTheme` 的静默回落 |
| 7 套中文显示名逐条比对 | 防分支互换（知乎蓝被标成「爱奇艺绿」） |
| 无 key 时默认值 = **经典终端** | **关键**：经典终端与 B站粉的 `PRIMARY` 恰巧都是 `0xFFFF6699`，只比 PRIMARY 发现不了「默认值静默变成 B站粉」，必须比 `BACKGROUND` |
| 7 份色表两两不同 | 防「复制一个 object 却忘了改值」 |
| `PREF_KEY_THEME == SettingsKeys.THEME`、`THEME_DEFAULT ∈ 可选主题` | key 单一真源；默认主题必须能被选回来 |
| 换主题后色表跟着变且已落盘 | 步骤 1 缓存失效的守卫 |
| 501 次 getter 只读 **1** 次 SharedPreferences | 把步骤 1 的性能目标本身变成断言，防将来被改回直读 |

> 缓存失效的**不变量**已写进 `docs/architecture-map.md` §7.3：将来若给主题 key 增加第二个写入路径，必须同步失效缓存，否则改主题后色表不跟着变、且在 `onResume` 重建后依然错。

### 第二十三轮（外观三模块重构 · 步骤 2：`ui/appearance/` 门面骨架）· 26.09.11

外观系统拆成「配色 / 卡片圆角 / 字体」三个独立模块的第 2 步。**本步只新增文件与 key，不移动任何既有逻辑、不改任何视觉**——圆角与字体尚无消费方，配色仍由 `ThemeManager` 拥有。

| 文件 | 内容 |
|---|---|
| `ui/appearance/AppearanceManager.kt`（新增） | 门面：`Appearance` 快照 + 外观版本号（`appearance_version`）+ **唯一写入入口**（`setTheme`/`setCornerRadius`/`setFontScale`/`setFontFamily`，各自递增版本号）。**刻意不缓存快照**——见下 |
| `ui/appearance/CornerStyle.kt`（新增） | 圆角两档 `square`（默认）/ `rounded` + 候选值/显示名/`normalize`/`current`。只放纯逻辑与读取，**不放写入** |
| `ui/appearance/FontStyle.kt`（新增） | 字号 4 档 + 字族 2 选，两个独立 key；`scaleFactor`/`fontFamilyValue` 为纯函数 |
| `util/SettingsKeys.kt` | 加 `UI_CORNER_RADIUS` / `UI_FONT_SCALE` / `UI_FONT_FAMILY`（配色沿用既有 `THEME`，不另开 key 以保持存档向前兼容） |
| `ui/theme/ThemeManager.kt:517` | `setTheme` 改为转发给 `AppearanceManager.setTheme`（落盘 + 递增版本号），自己仍负责清色表缓存 |

**分层约定（已写进 `docs/architecture-map.md` §8.7 与 `AGENTS.md`）**：模块只放候选值常量/显示名/纯函数/读取，**写入一律走门面**——否则「递增版本号」迟早漏一处；门面只做「快照 + 写入 + 版本号」，**绝不做几何计算**。

**三个刻意的设计决定**

1. **`snapshot()` 不缓存**。它是「每次 Activity 创建读一次」的冷路径，缓存收益为零；而缓存失效点会随模块增加而变多（配色已有 `setTheme`，圆角/字体还会有各自的写入点），是一类只会引入 bug 的复杂度。热路径的重复读取问题已由第二十二轮的色表缓存单独解决。守卫测试：`snapshot_isNotCached_staleReadsAreImpossible`。
2. **版本号用 Int 而非「每模块一个字段」**。现有 `BaseActivity` 只记一个 `appliedTheme` 字符串，加到第 4 个模块时那种写法必然要改 `BaseActivity`；版本号让 Activity 只比一个 Int，新增模块不需要动基类。
3. **`FontStyle.scaleFactor(SCALE_DEFAULT)` 恒为 1.0f**。下游靠这个短路来兑现「默认档位零运行时开销（不遍历视图树）」；这个不变量一旦被破坏不会有任何报错，故单独设守卫测试 `scaleFactor_standardIsExactlyOne`。

**新增测试 30 个用例**（`CornerStyleTest` 7 / `FontStyleTest` 11 / `AppearanceManagerTest` 12）。重点钉住：档位与显示名逐项对应（错位会让用户选「方角」得到「圆角」）、未知存档值回落默认（否则设置页显示空白）、**每个写入点都必须递增版本号**（漏了就是「改了设置但页面不刷新」，且手工测试时容易被设置页自身的 `recreate()` 掩盖）。

> 尚未接入：`BaseActivity` 仍在比 `appliedTheme` 字符串，**未使用版本号**；接入随圆角模块落地一起做（届时一次改动即可）。

### 第二十四轮（外观三模块重构 · 步骤 3：配色模块迁入 `ui/appearance/`）· 26.09.11

把配色从 `ui/theme/ThemeManager.kt` 迁到 `ui/appearance/ColorScheme.kt`（用 `git mv`，历史保留），并确立**「模块只读、门面写入」**的分工。**不改任何视觉**。

| 项 | 处理方式 |
|---|---|
| 文件迁移 | `ui/theme/ThemeManager.kt` → `ui/appearance/ColorScheme.kt`，`object ThemeManager` → `object ColorScheme`；`ui/theme/` 目录删除 |
| 写入收口 | `ColorScheme.setTheme()` **删除**，写入统一走 `AppearanceManager.setTheme()`（落盘 + `ColorScheme.invalidateCache()` + 递增版本号）——避免「模块自己写、门面不知道」导致版本号漏记 |
| 缓存失效 | 新增 `ColorScheme.invalidateCache()`，唯一调用者是 `AppearanceManager.setTheme()`；不变量已写进 `architecture-map.md` §7.3 |
| 依赖方向 | `AppearanceManager.snapshot()` 改读 `ColorScheme.getCurrentThemeName()`，此前对 `ThemeManager` 的反向引用消失 |
| 调用点迁移 | 27 个文件的 import 与引用由 `ui.theme.ThemeManager` 改为 `ui.appearance.ColorScheme`；`StringUtil.java` 的 `ThemeManager.INSTANCE.` 一并处理；`SettingGroupActivity` 的主题写入改调 `AppearanceManager.setTheme()` |
| 测试 | `ThemeManagerTest.kt` → `ui/appearance/ColorSchemeTest.kt`（类名与包名同步） |

> 「配色模块」的**功能**部分（把 7 套重复的 `themes.xml` 组件样式合并为一份 `?attr/` 版本）尚未开始，留待后续，需真机逐套验证。
> 注意：本轮**没有**采用「保留 `ThemeManager` 作转发壳」的方案——转发需要手写 ~70 个成员且易错，直接迁移调用点由编译器兜底，且不留过渡代码。

### 第二十五轮（外观三模块重构 · 步骤 4：圆角模块落地）· 26.09.11

**第一个用户可见的外观模块**。「方角 / 圆角」两档可在设置页切换并即时生效。

**机制（为什么走主题属性）**：`dimen` 编译期固定、`shape drawable` 读不到主题，
**只有主题属性 `?attr/` 能被 `theme.applyStyle()` 覆盖**。所以：

| 文件 | 改动 |
|---|---|
| `res/values/styles.xml` | 声明 `<attr name="appCornerRadius" format="dimension"/>`；定义两个覆盖样式 `Appearance_CornerSquare`/`Appearance_CornerRounded`；`CardStyle`/`CardStyleLight`/`ButtonStyle`/`ButtonStyleLight` 的圆角改引用 `?attr/appCornerRadius`（放在已有文件里，**不新增 res 文件**，避免 build cache 回放坑） |
| `res/values/themes.xml` | 5 套主题的 10 处硬编码 `12dp` + `CardStyleTerminal` 的 2 处 `@dimen/card_round` 全部改为 `?attr/appCornerRadius`；**每套主题补一条 `appCornerRadius=@dimen/card_round` 作兜底**（8 处），使不走 `BaseActivity` 的裸 Activity 拿到「方角」而非解析失败的 0dp |
| `res/values/dimens.xml`、`values-w300dp/dimens.xml` | 新增 `card_round_large`（手表 12dp / 宽屏 16dp）；**删除三个 0 引用的死 token** `radius_card`/`radius_small`/`radius_chip`（含宽屏覆盖） |
| `ui/appearance/CornerStyle.kt` | 新增 `overlayStyleResId()`：档位 → 覆盖样式 |
| `activity/base/BaseActivity.kt` | `setTheme()` 之后、inflate 之前 `theme.applyStyle(CornerStyle.overlayStyleResId(), true)`（`force=true` 必需，属性已在主题里定义过）；**`appliedTheme` 字符串换成 `appliedAppearanceVersion` Int**，`onResume` 只比一个 Int |
| `SettingGroupActivity` + `SettingsIndex` | 新增「卡片圆角」设置行（两处；独立「外观设置」页面留待步骤 6） |
| `CornerStyleTest` | 新增 3 个用例覆盖 `overlayStyleResId` 的映射/回落/跟随设置 |

**性能**：圆角模块的运行时成本是 `applyStyle` **一次 O(1) 调用**，无任何视图遍历，
不碰 `RecyclerView` 绑定路径（手表性能优先）。

**默认档位的观感影响（订正早先「零变化」的说法）**：默认 `square` 对**默认主题「经典终端」零变化**；
但**另外 6 套主题的卡片圆角会从 12dp 变为 6dp**——那 12dp 是主题化改造时各抄一份 `CardStyle`
引入的漂移，不是刻意取值，本次借模块化收敛回原项目取值。

**遗留（明确登记，见 `architecture-map.md` §8.7.1）**：
- **10 个 shape drawable 仍直接用 `@dimen/card_round`，不跟随档位**（搜索框、输入框、灰卡、私信发送框等）。
  `shape` 的 `<corners>` 读不到主题属性；修法需先真机验证（改控件 or 一次条件性遍历），本轮不做。
- layout 级内联圆角（头像 28dp、投票按钮 18dp、三个 8dp cell、`item_account` 12dp）按设计豁免，不跟随档位。

### 第二十五轮补充（圆角模块真机排障与返工）· 26.09.11

**真机实测发现第二十五轮的圆角机制整体失效**，已返工。

| 发现 | 证据 | 处理 |
|---|---|---|
| 主题属性的**维度**间接层解析为 0 | 真机像素测量：`?attr/appCornerRadius` 方案下卡片左上角 (24,581) 就是卡片填充色 R31，顶行无任何裁切——半径 0；连原 `@dimen/card_round`(宽屏 10dp≈27px) 都丢了 | 回退整个方案：删 `appCornerRadius` 属性、两个 `Appearance_Corner*` 覆盖样式、8 处主题兜底；`cardCornerRadius`/`cornerRadius` 全部改回具体 dimen `@dimen/card_round` |
| 本工程**并非只有 `MaterialCardView`** | `adb shell dumpsys activity top` 里 `androidx.cardview.widget.CardView` 与 `MaterialCardView` **同时存在**（设置索引页的卡片是普通 CardView）。此前"138 个 MaterialCardView、0 个 CardView"的勘察只看了 layout 文件，结论不完整 | `AppearanceApplier.applyRadius` 增加 `is CardView` 分支——普通 CardView 不读 `materialCardViewStyle`，XML 的 `cardCornerRadius` 对它无效，只有 `setRadius()` 能改 |
| 只在 `onContentChanged` 走一遍会漏掉**程序化添加**的视图 | 设置索引页 `SettingMainActivity` 的卡片是在 `asyncInflate` 回调里 `addView` 加进容器的，发生在遍历之后 → 整页不生效 | 改为给每个 `ViewGroup` 挂 `OnHierarchyChangeListener`（同时覆盖 `RecyclerView` 的 item 挂载，去掉了原来的 `OnChildAttachStateChangeListener` 专用钩子）。`setOnHierarchyChangeListener` 无公开 getter 无法链式保留，已 grep 确认全工程无其它使用点 |
| 字体与圆角各做一次遍历 | — | 合并为 `AppearanceApplier`（删除 `CustomFont.kt`），两项共用同一次遍历 |

**新机制**：XML 写具体 `@dimen/card_round`（方角档即默认，零遍历）；只有选「圆角」档时
`CornerStyle.needsRuntimeOverride()` 为 true，才在遍历里把 `card_round_large` 套上去。

**后续修正：方角改为全平台统一 6dp**。上面测出「切换生效但方角仍是 10dp」后，确认根因是
`values-w300dp/dimens.xml` 把 `card_round` 从手表的 6dp 放大到了 10dp——用户在手机上看到的
「方角」其实是 10dp 圆角，与设置项语义冲突。按「去掉宽屏放大」处理：

- `values-w300dp/dimens.xml` 删除 `card_round`(10dp) 与 `card_round_large`(16dp) 两个覆盖；
- `values/dimens.xml` 保留 `card_round` 6dp / `card_round_large` 12dp，**全平台统一**。
- 依据：原项目 BiliClient **只有一个 `values/` 目录、`card_round` 全局 6dp**（上游实测），
  宽屏放大是本项目后加的，本就不属于「还原原项目」。

复测：方角档角落缺失像素 701 → **427**，反推半径 27px → **16px ≈ 6dp**，改动生效。

**返工前的真机验证记录（保留，作为「切换确实生效」的证据）**：
- ✅ 方角档（当时=10dp）：测得卡片顶行填充色从最左边缘开始、半径≈27px ≈ 宽屏 `card_round`(10dp) → **XML dimen 路径正常**。
- ✅ 圆角档：同一张 DialogActivity 卡片在 `rounded` 档下顶行 160px 内**完全没有填充色**（方角档则从 x=0 就有）→ 形状确实随档位改变。
- ⚠️ **未能得到精确半径数值**：卡片的绘制形状相对视图边界有阴影内缩，按行采样不可靠。
- ⚠️ **未能逐页确认**：设备前台页面不稳定（多次落在 `DialogActivity`），且注入的 `ui_corner_radius` 曾被回写为 `square`（`appearance_version` 6→7），一度导致测量结论无效。
- ⚠️ **我无法看到屏幕**（当前模型不支持图像输入），最终观感判定需人工。

---

### 第二十六轮（外观三模块重构 · 步骤 6：独立「外观设置」页面）· 26.09.11

把外观设置从「界面与外观」分组里拆出来，成为独立一屏。

**形态选择**：本仓库的「独立设置子页面」有两种形态——
① 独立 Activity（「菜单设置」`SettingMenuActivity`，改三处含 manifest 注册）；
② **`SettingGroupActivity` 的另一个 `group_type` 分组**。
外观设置只有若干列表项、无自定义交互，故走 ②：**不需要新 Activity、manifest、布局**，
对用户同样是独立一屏。这是本轮唯一需要判断的地方。

| 文件 | 改动 |
|---|---|
| `activity/settings/SettingGroupActivity.kt` | 新增文件级常量 `GROUP_APPEARANCE = "appearance"`；`buildContent` 加分支；新增 `buildAppearanceGroup()`（`title("配色")` + 主题配色、`title("圆角")` + 卡片圆角）；`buildUIGroup()` 里原来那两处 `listChoose` **删除**，改为一个 `nav(R.drawable.icon_ui, "外观设置", …)` 跳转本页的 appearance 分组 |
| `activity/settings/SettingsIndex.kt` | 新增「外观设置」条目；「主题配色」「卡片圆角」两项由 `openGroup("ui", …)` 改指 `openGroup(GROUP_APPEARANCE, …)`，全局搜索仍能直接定位到项 |
| `AGENTS.md` | 「新增设置子页面」约定补充为**两种形态**，并写明选择依据 |
| `docs/architecture-map.md` §8.6 | 补充「放哪个分组」与「外观类设置的写入必须走门面」 |

> **字体模块的设置项刻意没放**：`FontStyle`（字号 4 档 + 字族 2 选）尚未接入渲染路径，
> 放出来就是一个点了没反应的开关。接入后在本页追加 `title("字体")` 一段即可。
> 第一版按设计**不做实时预览**——预览必须复用与真实页面同一套应用逻辑，否则会骗人。

### 第二十七轮（外观三模块重构 · 步骤 5：自定义字体）· 26.09.11

**需求变更**：原计划的「字号 4 档 + 字族 2 选 + 416 处 textSize 收敛」**整体取消**，
改为只做一个功能——**用户从文件管理器选一个字体文件并全局应用**。

| 文件 | 改动 |
|---|---|
| `ui/appearance/FontStyle.kt` | **重写**为自定义字体模块：私有目录存储（`filesDir/custom_font/`）、文件头校验、`Typeface` 进程级缓存、`shouldLoad` 纯函数 |
| `ui/appearance/CustomFont.kt` | **新增**：把字体套到视图树 + `RecyclerView` 新挂 item 上，保留粗体/斜体 |
| `activity/base/BaseActivity.kt` | 新增 `onContentChanged()` 覆写 → `CustomFont.applyToContentView(this)` |
| `activity/settings/SettingGroupActivity.kt` | 「外观设置」页新增「自定义字体」段：`nav` 选文件（`ACTION_GET_CONTENT`，选完立刻拷贝，无需持久化 URI 权限）+ 已装时显示「恢复系统字体」按钮；拷贝与校验走 `CenterThreadPool` |
| `ui/appearance/AppearanceManager.kt` | `setFontScale`/`setFontFamily` → `setFontPath`/`clearFontPath`；`Appearance` 的快照字段同步 |
| `util/SettingsKeys.kt` | 删 `UI_FONT_SCALE`/`UI_FONT_FAMILY`，加 `UI_FONT_PATH` |
| `FontStyleTest` / `AppearanceManagerTest` | 按新语义重写（13 + 11 用例） |

**为什么是「拷贝到私有目录」**：`minSdk 24` 用不了 `Typeface.Builder(FileDescriptor)`（API 26），
只能 `Typeface.createFromFile(File)`，需要真实路径；且用户可能删源文件，记 URI 还得处理持久化权限。

**为什么不用 `LayoutInflater.Factory2`（更漂亮的做法）**：`setFactory2()` 只在从未设过 factory 时可用，
而 `BaseActivity : AppCompatActivity` 已让 AppCompat 装好了自己的 factory——**它同时承担 Material
控件替换，`<Button>` 的圆角依赖它**。顶掉它会让圆角模块一起失效，代价远大于收益。
公开 API 没有干净办法串联两个 factory，故走遍历。

**性能代价（手表优先）**：未启用自定义字体时 `typeface()` 返回 null，`applyToContentView` 立即 return，
**零遍历零开销**（守卫测试 `shouldLoad_isFalseWhenNoFontConfigured`）；启用后每次
`onContentChanged` 跑一次遍历，列表项靠 `RecyclerView` 的 attach 钩子覆盖。这套代价由用户主动开启换来。

**已知边界**（已写进 `architecture-map.md` §8.7.2）：不走 `BaseActivity` 的三个界面
（开屏/外链/播放器）不生效；遍历之后动态创建的、非 `RecyclerView` 的 TextView 不生效；只改字体不改字号。

**待真机验证**：在手表上挑一个 TTF 应用，检查列表/设置页/详情页是否都换了字体、粗体标题是否仍为粗体、
点「恢复系统字体」是否回到系统字体、以及选一个非字体文件时是否给出可读的拒绝提示。

### 第二十八轮（圆屏适配开启后顶栏崩溃）· 26.09.11

**现象**（用户真机报告 + 完整堆栈）：开启「圆屏适配」后重进页面立刻崩溃。

```
java.lang.ClassCastException: android.widget.RelativeLayout$LayoutParams
        cannot be cast to android.widget.LinearLayout$LayoutParams
    at android.widget.LinearLayout.measureHorizontal(LinearLayout.java:1199)
```

**根因**：`BaseActivity.setRound()` 里硬编码 `RelativeLayout.LayoutParams` 赋给顶栏控件，
没管控件的**真实父容器**是什么类型。`@id/timeText` 在个别布局里挂在 `@id/menuArea`
（**LinearLayout**）里，于是 LinearLayout 下一帧 `measureHorizontal` 强转崩溃。

三处细节：

1. 崩在 **measure 阶段**，调用处那个 `try { } catch (e: Throwable)` 兜不住——`setRound()`
   正常返回，异常是下一帧遍历抛的，进程直接挂。
2. 只在**开启圆屏适配**时触发（方法开头有 `player_ui_round` 守卫），所以一直没被发现。
3. 时钟容器不统一：`activity_simple_main_refresh.xml` 是 `top(RelativeLayout) > menuArea(LinearLayout) > timeText`，
   其余布局多为 `top(RelativeLayout) > timeText`。

| 文件 | 改动 |
|---|---|
| `activity/base/BaseActivity.kt` | `setRound()` 不再直接 new `RelativeLayout.LayoutParams`；新增私有 `newTopbarParams(view)`，按 `view.parent` 的真实类型产出 `RelativeLayout.LayoutParams` / `LinearLayout.LayoutParams`（其余退化为 `MarginLayoutParams`），再仅当类型是 RelativeLayout 时 `addRule(CENTER_HORIZONTAL)` |

> 没能用更优雅的 `parent.generateLayoutParams(child.layoutParams)`：该方法是
> `ViewGroup` 的 **protected** 成员，Activity 里访问不到（编译器直接报
> "Cannot access ... it is protected"），所以改成显式 when 分支。

**验证**：`:app:assembleDebug` 通过；`:app:testDebugUnitTest` 通过。
**待真机验证**：开启圆屏适配后重进首页（带菜单区，时钟在 LinearLayout 里）不再崩，
且顶栏标题/时钟仍居中；普通页面（时钟直挂 RelativeLayout）视觉不回退。

### 第二十九轮（顶栏监听器探测降级）· 26.09.13

**现象**（用户真机崩溃截图 + 完整堆栈）：应用一起来就崩，**所有页面都打不开**。

```
java.lang.NoSuchMethodError: No virtual method hasOnLongClickListeners()Z
        in class Landroid/view/View; or its super classes
    at com.RobinNotBad.BiliClient.activity.base.BaseActivity.setupTopbarLongPressToHome
    at com.RobinNotBad.BiliClient.activity.base.BaseActivity.onStart
```

**根因**：`View.hasOnLongClickListeners()` 是 API 15 就有的公开 API，`android-34` 的 class
文件里确实存在（`javap` 实测），但该手表的 `/system/framework/framework.jar` 把它裁掉了。
编译期、Lint、单测全查不出来 —— 只有真机运行到 `onStart` 才炸。`setTopbarExit()` 里的
`hasOnClickListeners()` 属同一类隐患。

| 文件 | 改动 |
|---|---|
| `util/ViewCapabilityProbe.kt` | **新增**：反射探测框架方法是否存在，探测结论进程级缓存；`probeBoolean()` 返回 `null` 表示「本机没这个能力，请走降级」 |
| `activity/base/BaseActivity.kt` | `setTopbarExit()` / `setupTopbarLongPressToHome()` 改为「探测成功按框架判断、探测失败按自己的标志位判断」，两条路都保证同一 Activity 实例只装一次监听器 |
| `ViewCapabilityProbeTest.kt` | **新增** 4 用例：方法存在、方法不存在（只上报一次）、目标抛普通异常、目标抛 Error |
| `docs/architecture-map.md` §8.5 | 新增第 11 条「别裸调理论上一定存在的框架方法」 |

**降级边界**（同时写进代码注释与架构地图）：

- 方法不存在（`LinkageError`：`NoSuchMethodError` / `AbstractMethodError` / `NoClassDefFoundError`）→ 缓存为不可用，返回 `null` 降级；
- 框架方法自己炸成普通异常（`Exception`）→ 也返回 `null` 降级；
- 其他 `Error`（`StackOverflowError`、`OOM` 等）→ **原样抛出**，不静默吞掉真 bug。

> 单测在这里抓到了我第一版实现里的一个真 bug：缓存命中那条路原本直接
> `return invokeBoolean(...)`，没包 try/catch，导致同一个方法「第一次调用降级、第二次调用
> 把异常漏出去」。现在两条路径合并到 `handleProbeFailure()`，行为一致。
> 另外第一版只 `catch (e: Error)`，而反射拆包后抛出的通常是普通 `Exception`，会直接穿透 ——
> 改成按 `Throwable` 分派。

**验证**：`:app:testDebugUnitTest` 全绿（16 个测试类 / 112 用例 / 0 失败，新增 4 例）；
`:app:assembleDebug` 通过。**待真机验证**：应用能正常起来、顶栏点击返回与长按回主页都还在。

### 第三十轮（安全加固：备份 / 明文 / 导出面 / 调试页）· 26.09.13

**背景**：本轮不是修崩溃，而是独立安全排查发现的四条问题。均已在合并后的清单里实测确认。

| # | 问题 | 改动 |
|---|---|---|
| S1 | `android:allowBackup="true"`，而 `SESSDATA` / `bili_jct` / `DedeUserID` 等**登录 Cookie 就存在 SharedPreferences**，可被 `adb backup` 或云备份导出 → 账号被接管 | 主清单改 `allowBackup="false"`；新增 `res/xml/backup_rules.xml`（API ≤30）与 `res/xml/data_extraction_rules.xml`（API 31+），云备份与设备迁移都排除 `sharedpref` / `database`（防将来有人重新打开 allowBackup）。`:brotlij` 自带 `allowBackup="true"`，故加 `tools:replace="android:allowBackup"`，否则清单合并直接失败 |
| S2 | 全局 `android:usesCleartextTraffic="true"` + `AppInfoApi` 4 处 `http://api.biliterminal.cn` | 4 处改 `https://`（实测该域 https 返回 200）；删掉全局明文开关，改走 `res/xml/network_security_config.xml`：`base-config` 禁止明文，仅对 `bilibili.com`/`hdslb.com`/`bilivideo.com`/`afdiancdn.com` 放行。**不搞一刀切**是因为解析层仍有 `"http:" + url` 拼接（`LiveCardAdapter` 直播封面、`SearchApi` 封面/头像、`OpusParagraph` 图片），一刀切会挂图 |
| S3 | 24 个 Activity 里 **22 个无 `<intent-filter>` 却 `exported="true"`**，全清单 `android:permission` 计数为 0 | 22 个一律改为 `exported="false"`；只保留真正需要外部拉起的 `SplashActivity`（LAUNCHER）与 `GetIntentActivity`（外链/分享）。内部 `startActivity` 不受影响 |
| S4 | 开发者测试页 `TestActivity`（含「崩溃」按钮、能读 Cookie）随 release 发布且对外导出 | 清单声明移到 `app/src/debug/AndroidManifest.xml`；两处入口（`SettingGroupActivity.buildDevGroup`、`SettingsIndex`）改用**编译期常量** `BuildConfig.DEBUG` 而非 `BiliTerminal.isDebugBuild()`，release 下 R8 折叠分支并 strip 该类 |

**验证**（clean 后重建，避开 build cache 回放陈旧资源）：

- `:app:assembleDebug` + `:app:testDebugUnitTest` ✅ BUILD SUCCESSFUL；16 个测试类 / 112 用例 / 0 失败。
- debug 合并清单：`allowBackup="false"`、`dataExtractionRules` / `fullBackupContent` / `networkSecurityConfig` 均在，`usesCleartextTraffic` 已消失；`TestActivity` 有注册；仅 2 个组件 `exported="true"`。
- release 合并清单（`:app:processReleaseMainManifest`）：安全属性同上，**`TestActivity` 出现 0 次**。
- **待真机验证**：图片/头像/直播封面仍能加载（明文白名单是否够用）、公告与赞助列表仍能取到（https 接口）、更新检测正常。

### 第三十一轮（空态不再摆报错插画）· 26.09.13

**现象**：下载列表等页面为空时，屏幕上会显示 `loading_2233_error` 报错插画 + "啥都木有~"，
看起来像加载失败；但这些页面为空是**正常状态**（没有下载任务、没有收藏/历史/关注等）。

**根因**：`emptyTip` 在 XML 里写死了 `android:drawableTop="@mipmap/loading_2233_error"`
（第十轮视觉优化时加的，见本文档第十轮）。而 `showEmptyView()` 全工程只在
"加载成功但列表为空"时调用——真正的加载失败走 `loadFail()` / `report()` 弹提示，
从不走空态。也就是说空态**从来就不是**错误态，却一直挂着报错插画。

**改动**：4 个空态布局去掉 `drawableTop` / `drawablePadding`，空态只保留中性文案；
并在布局里加注释说明原因，避免以后又被当成"缺图标"加回来。

| 布局 | 使用页面 |
|---|---|
| `activity_simple_refresh.xml` | `RefreshListActivity` 全部子类（含下载列表）、`MessageSettingsActivity`、`ReplyInfoActivity`、流水页、`TimelineActivity` |
| `activity_simple_main_refresh.xml` | `RefreshMainActivity` 全部子类（推荐/热门/排行/必刷/动态/直播/热搜）、`LocalListActivity` |
| `fragment_simple_refresh.xml` | `RefreshListFragment`、`SearchFragment` |
| `fragment_simple_list.xml` | `OpusInfoFragment`、`EmoteActivity` |

> `loading_2233_error` 本身的用途不变：详情页（`OpusInfoActivity` / `DynamicInfoActivity` /
> `VideoInfoActivity`）**真实加载失败**时仍然用它，那才是它该出现的地方。

**验证**：`:app:assembleDebug` ✅ BUILD SUCCESSFUL；单测无相关改动（UP-TO-DATE）。
**待真机验证**：空列表页面只显示"啥都木有~"文案，不再出现报错插画。

---

### 第三十二轮（四方向深度审查 · 只审计未改动）· 26.09.24

**说明**：本轮**不修改任何业务代码**，只做审查与文档化。完整报告见
**`docs/review/audit-2026-09-24.md`**（新增，含证据代码块、受影响行号表、修复方向）。

**审查范围**：网络/API 层、测试与构建配置、播放器与下载服务、ProGuard/清单/版本管理，
4 个方向独立审计后**逐条 `sed -n 'Np'` 回读核实**。

**产出**：严重 10 / 中等 13 组 / 轻微 11。

**本轮同时澄清/证伪的事项**（详见 `audit-2026-09-24.md` 第〇节）：

- ✅ **确认已修的 10 项**（不再列为问题）：`saveCookiesFromResponse` 锁、重定向 `response.close()`、
  `Inflater.end()`、`allowBackup=false`、明文流量白名单、`CaptchaWebViewActivity` 加固、
  备份规则排除凭据、`TerminalContext` 单例写法、`ConfInfoApi` WbiCache 原子替换、`SplashActivity` 线程。
- ❌ **证伪 4 项**（不再重复排查）：`PlayerSurfaceBinder.removeCallbacksAndMessages` 影响其他 Handler、
  `downFileSpeedSeg` 的 `segments` 越界、minSdk 24 下线程池并发创建、`LiveInfoActivity.kt:148` host 拼接缺 scheme。
- 🔒 **密钥未泄漏**：`key.jks` / `local.properties` / `config.json` 均**从未被 git 跟踪、从未进入历史**。

**五项最值得优先处理**（详见报告第五节）：

| 优先级 | 项 | 理由 | 改动量 |
|---|---|---|---|
| 1 | S1 WBI 换序 + S2 `split("=", 2)` | 消除**全天级 403**，影响 17 处核心接口 | 约 3 行 |
| 2 | S4 删文件夹加保护 | **唯一会丢失用户已下载数据**的缺陷 | 数行 |
| 3 | M13 CI 加 `testDebugUnitTest` | 一行，**永久防回归**，让 112 个现存用例真正生效 | 1 行 |
| 4 | S9 停传完整 Cookie | **账号凭证跨进程外流** | 数行 |
| 5 | S5 + S6 下载竞态与幂等守卫 | 重复写同一文件 | 中等 |

**未覆盖**：`PlayerActivity.kt` 第 500-765、960-1240、1500-2860 行未通读；
`mips` 空壳 so 是否进 APK 未解包验证；`NetWorkUtil.decompress` 实现未读。

> **本轮无构建验证**：未改动任何代码，故未执行 `assembleDebug` / `testDebugUnitTest`。

---

## 三、审查前已修复（本次核查确认，无需改动）

| 文件 | 问题 | 防御措施 |
|---|---|---|
| `GetIntentActivity.kt` | 外部 Intent 直接 `!!`/`toLong()` 崩溃 | `toLongOrNull()` + `getLongExtra` 默认值 |
| `ImageViewerActivity.kt:31` | 空 Intent 崩溃 | `isNullOrEmpty()` 检查 |
| `CookieRefreshApi.java:99` | parseLong 空值崩溃 | 空值回退旧 mid |
| `FavoriteApi.java:285` | 未登录收藏 substring 越界 | 长度检查 |
| `LikeCoinFavApi.java:48` | 同上（重复实现） | 已修复 |
| `Reply.java:75` | location substring(5) 越界 | `length() > 5` 判断 |
| `OpusParagraph.java:98-100` | 空 blockquote setSpan 越界 | 长度检查 |
| `PlayerActivity.kt:1496` | onDestroy 提前 return 跳过清理 | 无条件清理 |
| `UpdateManager.kt:141-158` | 断点续传 200 响应追加损坏 | 丢弃旧残片从头写 |
| `LocalPageChooseActivity.kt:78-97` | 后台线程改列表 + 主线程 notify | 分离数据操作与 UI notify |
| `ToolsUtil.java:71` | 弹幕颜色错误（字符串拼接而非 RGB888） | `color & 0xFFFFFF` |
| `DanmakuApi.java:31,43` | 弹幕内容未 URL 编码 | `URLEncoder.encode(msg, "UTF-8")` |
| `PrivateMsgApi.java:219` | 私信内容未 URL 编码 | `URLEncoder.encode(content, "UTF-8")` |
| `ReplyApi.java:153,231,232` | 评论内容未 URL 编码 | `URLEncoder.encode(text/pictures, "UTF-8")` |

---

## 四、构建验证状态

| 时间 | 任务 | 结果 |
|---|---|---|
| 2026-09-07 15:45 | `:app:assembleDebug` | ✅ 成功（产出 4 个 APK） |
| 2026-09-07 16:01 | `:app:assembleDebug`（第二轮修复后） | ✅ BUILD SUCCESSFUL in 36s |
| 2026-09-07 16:20 | `:app:assembleDebug`（第三轮修复后） | ✅ BUILD SUCCESSFUL in 37s |
| 2026-09-07 16:45 | `:app:assembleRelease`（R8 压缩 + 签名） | ✅ BUILD SUCCESSFUL |
| 2026-09-10 | `:app:assembleDebug` + `:app:testDebugUnitTest`（视觉批次 0 前半） | ✅ 通过（需 `--no-build-cache`） |
| 2026-09-10 | `:app:assembleDebug` + `:app:testDebugUnitTest`（第十六轮：两个播放器性能与逻辑优化） | ✅ BUILD SUCCESSFUL in 1m 4s；11 个测试类 / 59 个用例 / 0 失败；4 个 debug APK |
| 2026-09-10 | `:app:assembleDebug` + `:app:testDebugUnitTest`（第十七轮：播放内核合并 S1/S2/S3 + 短视频 S5 部分） | ✅ BUILD SUCCESSFUL；59 个用例 / 0 失败；APK 29.85 MB |
| 2026-09-10 | `:app:assembleDebug` + `:app:testDebugUnitTest`（第二十轮步骤 1：惰性日志 + MultiDex 清理） | ✅ BUILD SUCCESSFUL in 1m 26s；59 用例 / 0 失败 |
| 2026-09-10 | `:app:assembleDebug` + `:app:testDebugUnitTest`（第二十轮步骤 2：35 处 `BiliTerminal.context!!`） | ✅ BUILD SUCCESSFUL in 47s；11 个测试类 / 59 用例 / 0 失败 |
| 2026-09-10 | `:app:assembleDebug` + `:app:testDebugUnitTest`（第二十一轮：Application 入口转 Kotlin） | ✅ BUILD SUCCESSFUL in 55s；11 个测试类 / 59 用例 / 0 失败（`--no-build-cache` 实跑） |
| 2026-09-10 | `:app:assembleRelease`（R8 + ABI 分包，验证入口类 keep 规则） | ✅ BUILD SUCCESSFUL in 2m 29s；universal 23.04 MB / arm64 9.97 / armeabi-v7a 8.54 / x86 10.96；`seeds.txt` 含 `BiliTerminal`，`mapping.txt` 中该类未被重命名 |
| 2026-09-11 | `:app:testDebugUnitTest`（第二十二轮步骤 0：主题单测网） | ✅ 12 个测试类 / 72 用例 / 0 失败；`ThemeManagerTest` 13 用例新通过 |
| 2026-09-11 | `:app:testDebugUnitTest` + `:app:assembleDebug`（第二十二轮步骤 1：色表缓存） | ✅ BUILD SUCCESSFUL；12 个测试类 / 73 用例 / 0 失败（`ThemeManagerTest` 增至 14 用例，含「501 次 getter 只读 1 次 SharedPreferences」的性能断言）；行为与视觉无变化 |
| 2026-09-11 | `:app:testDebugUnitTest` + `:app:assembleDebug`（第二十三轮：外观三模块门面骨架） | ✅ BUILD SUCCESSFUL in 11s / 6s；15 个测试类 / 103 用例 / 0 失败（新增 `CornerStyleTest` 7、`FontStyleTest` 11、`AppearanceManagerTest` 12）；无 `res/` 改动，圆角与字体尚无消费方 → UI 零变化 |
| 2026-09-11 | `:app:testDebugUnitTest` + `:app:assembleDebug`（第二十四轮：配色模块迁入 `ui/appearance/`） | ✅ BUILD SUCCESSFUL in 1m 16s（编译）/ 13s（测试+打包）；15 个测试类 / 103 用例 / 0 失败；27 个调用点迁移，`ui/theme/` 目录删除 |
| 2026-09-11 | `:app:assembleDebug` + `:app:testDebugUnitTest`（第二十五轮：圆角模块落地） | ✅ BUILD SUCCESSFUL in 1m 56s；15 个测试类 / 106 用例 / 0 失败（`CornerStyleTest` 7→10）；`R.txt` 已生成 `attr appCornerRadius` 与两个覆盖样式；16 处圆角定义全部改为 `?attr/` 引用，0 残留硬编码；**待真机验证两档切换** |
| 2026-09-11 | `:app:assembleDebug` + `:app:testDebugUnitTest`（第二十六轮：独立「外观设置」页面） | ✅ BUILD SUCCESSFUL in 12s；15 个测试类 / 106 用例 / 0 失败；纯设置页重组，无 `res/` 改动、无新 Activity/manifest 变更 |
| 2026-09-11 | `:app:assembleDebug` + `:app:testDebugUnitTest`（第二十七轮：自定义字体） | ✅ BUILD SUCCESSFUL in 51s；15 个测试类 / 107 用例 / 0 失败（`FontStyleTest` 13、`AppearanceManagerTest` 11，均按新语义重写）；字号/字族旧代码 0 残留；**待真机验证应用/恢复/拒绝非法文件** |
| 2026-09-11 | `:app:assembleDebug` + `:app:testDebugUnitTest`（第二十八轮：圆屏适配顶栏崩溃） | ✅ BUILD SUCCESSFUL；单测通过；`setRound()` 改为按真实父容器产出 LayoutParams；**待真机验证重进首页不崩** |
| 2026-09-13 | `:app:testDebugUnitTest` + `:app:assembleDebug`（第二十九轮：顶栏监听器探测降级） | ✅ 16 个测试类 / 112 用例 / 0 失败（新增 `ViewCapabilityProbeTest` 4 例）；`hasOn*ClickListeners()` 改为反射探测 + 降级；**待真机验证应用能起来** |
| 2026-09-13 | `:app:clean` → `:app:assembleDebug` + `:app:testDebugUnitTest`（第三十轮：安全加固） | ✅ BUILD SUCCESSFUL；16 个测试类 / 112 用例 / 0 失败；debug 与 release 合并清单均实测 `allowBackup=false` + `networkSecurityConfig`，release 清单 `TestActivity` 0 次、仅 2 个组件导出；**待真机验证图片加载与 https 接口** |
| 2026-09-13 | `:app:assembleDebug`（第三十一轮：空态去报错插画） | ✅ BUILD SUCCESSFUL；4 个空态布局去掉 `loading_2233_error`，空列表不再被误认成加载失败；**待真机验证下载列表等空页面** |

> 第二轮修复的 4 个文件（QRLoginFragment/CaptchaWebViewActivity/LocalListActivity/VideoInfoFragment）已重新编译验证通过。APK 时间戳更新至 16:01:21，universal 包 31.04 MB。
> 第三轮修复的 2 个文件（PrivateMsgActivity/NetWorkUtil）已重新编译验证通过。构建仅有 1 个 Hilt 处理选项无关警告，不影响产物。
> Release 构建成功：universal 22.94 MB / arm64-v8a 9.87 MB / armeabi-v7a 8.44 MB / x86 10.86 MB。

### 设备安装

| 操作 | 结果 |
|---|---|
| 卸载旧版（签名不一致） | ✅ Success |
| 安装 debug universal 版 | ✅ Success（设备 10AC9S2M4L000QL） |

---

## 五、待处理问题（按优先级）

### P0 剩余 Critical（待排查）

- [x] 私信会话列表逻辑写反：`PrivateMsgApi.java:157`（26.09.08 已修，第四轮）
- [x] 下载并发竞态：`DownloadService.start()` 无同步（26.10.02 修复轮次：改 `@JvmStatic @Volatile` + `@Synchronized`，检查与置位同锁）
- [x] 分片 join 超时并发写：`DownloadService`（26.10.02 修复轮次：超时不再逐片累加；分片失败回退不再双重计字节）

### P0 功能正确性（High）

- [x] 播放器长按后手势全失效：`PlayerControlDelegate.kt:215`（26.09.10 已修，第十六轮）
- [x] 设置页二级列表崩溃：`SettingsAdapter` 负值 viewType + `ConcatAdapter` 重映射（26.09.10 已修，第十九轮）
- [x] Menu 键同时打开菜单并关闭当前页（26.09.08 已修，第四轮）
- [x] 圆屏适配开启后顶栏 ClassCastException 崩溃：`BaseActivity.setRound()` 硬编码 RelativeLayout.LayoutParams（26.09.11 已修，第二十八轮）
- [x] `NoSuchMethodError: View.hasOnLongClickListeners()`：部分手表框架裁掉了该 API，`onStart` 必崩（26.09.13 已修，第二十九轮，改反射探测 + 降级）
- [ ] WebView 输入校验缺失：`SetupUIActivity.kt:79,86,92`

### P0 界面卡死 / 未接线（26.10.04 由旧报告迁移，逐条已重核源码）

> 来源：原 `docs/review/cleanup-scan.md` §7「顺带发现的真实缺陷」。该报告已删除，此节为其唯一存续处。
> 行号按 **26.10.03（c98aa7f）** 工作区重新核实，与旧报告行号有漂移。

- [x] `activity/settings/AnnouncementsActivity.kt:27-30`：catch 只有 `report(e)` + `MsgUtil.showMsg(...)`，**无 `setRefreshing(false)`** → 下拉刷新永久卡死（违反 `AGENTS.md` 硬约定）。**注**：同批的 `activity/message/NoticeActivity.kt` 已在 `:80/:88/:104/:132/:145/:155` 全面复位，故本项只剩 AnnouncementsActivity 一处。**26.10.04 批次 1 已修**：顺带核实该页**从未调用 `setOnRefreshListener`**（`activity/base/RefreshListActivity.kt:53` 默认 `isEnabled=false`），所以它此前根本不能下拉刷新；已重写成 `loadAnnouncements()` + `setOnRefreshListener` + `setOnEmptyRetry`，失败时复位转圈并给出可点重试的空态。
- [x] `activity/video/collection/CollectionInfoActivity.kt:42-71`：只有 `result.onSuccess{...}`，**无 `onFailure` 分支**。**26.10.04 批次 1 已修**：补 `onFailure`（复位转圈 + `MsgUtil.err` + 空态），另加 `videoInfo.collection == null` 防御（原来 `collection!!` 在「视频不属于任何合集」时直接崩）。
- [x] `activity/video/PopularActivity.kt:103-105`：catch 只 `runOnUiThread { MsgUtil.err(e) }`，不复位 `refreshing`。**26.10.04 批次 1 已修**：同时复位 `refreshing` 与 `swipeRefreshLayout`；顺带清掉 3 处 `Log.e("debug", …)` 与 `import android.util.Log`。
- [x] `activity/video/series/SeriesInfoActivity.kt:35-37`：`seriesCover`/`seriesIntro`/`seriesTotal` **声明后从不赋值**（`onCreate` L43-46 只取 type/mid/sid/name）→ 简介恒「这里没有简介哦」、浏览量恒「共」、封面恒占位图、封面点击恒不触发。原 `activity2-report.md` M-24 已指出，仍未修。**26.10.04 批次 1 已修**：`adapter/video/SeriesCardAdapter.kt` 补 `cover`/`intro`/`total` 三个 `putExtra`，详情页读回，并补 `setOnEmptyRetry { loadData(1) }`。
- [x] `activity/video/collection/CollectionInfoActivity.kt:38-39`：同类「复制粘贴忘接线」。**26.10.04 批次 1 已修**：`season_id`/`mid` 两个 extra **全库无任何 `putExtra` 方**（唯一入口 `activity/video/info/VideoInfoFragment.kt:571-572` 只传 `fromVideo`），确认死变量后删除。
- [x] `activity/article/OpusInfoActivity.kt:89-95`：空函数体的幽灵 `@Subscribe(threadMode = ThreadMode.ASYNC, sticky = true, priority = 1) fun onEvent(event: ReplyEvent) {}`。**26.10.04 批次 1 已修**：改为 `replyFragment?.notifyReplyInserted(event)`，与 `activity/dynamic/DynamicInfoActivity.kt:77-80` 对齐（发评论后评论列表即时插入）。**勘误**：原条目后半句「`onDestroy()` 漏了 `TerminalContext.leaveDetailPage()`」**不成立** —— 全库不存在 `leaveDetailPage()` 方法，`TerminalContext` 只有 `enterVideoDetailPage`/`enterArticleDetailPage`/`enterOpusDetailPage`/`enterDynamicDetailPage`/`enterLiveDetailPage`，该半句作废。
- [ ] `activity/settings/TestActivity.kt:105`：硬编码具体专栏 id `781871626480254985L`。同文件另有 `:177-179` POST `api.deepseek.com`（Debug-only 页面，优先级低）。
- [ ] `activity/settings/TutorialManagerActivity.kt:120,128,298,311,328,343`：仍在读写新教程系统共用的 `tutorial_ver_$tag` 键，**会污染新系统已读状态**（改教程前先读 `docs/tutorial-system-redesign.md`）。
- [x] `DownloadService.start()` 竞态 —— 26.10.02 修复轮次已改 `@JvmStatic @Volatile` + `@Synchronized`（见上方 P0 剩余 Critical）。

### P1 架构清理

- [x] 移除 Hilt 等死依赖与 ksp/serialization 插件（26.09.11 已完成）
- [x] 处置 `BiliTerminalApp.kt`：Hilt 注解已摘，但该类仍被 `SplashActivity` 的 UETool 逻辑引用（5 处静态方法），既非完全死代码也非 Application 入口 —— **26.10.02 修复轮次已解决**：类整体删除，UETool 的静态方法与常量移入 `BiliTerminal.kt` 伴生对象，`SplashActivity` 的 7 处调用改指向 `BiliTerminal.`，`proguard-rules.pro` 里该类的 keep 规则一并删除
- [x] 清空 23 个空目录（di/data/network/ui 等）（26.09.11 已完成，实测空目录 = 0）
- [x] 删除幻觉方法（`SharedPreferencesUtil.beginBatchEdit` 等；实测仅剩定义无调用）—— 26.10.02 修复轮次已删 `TerminalContext.leaveDetailPage()` / `getTerminalKey()`、`CenterThreadPool.getThreadPoolInstance()` 与死类 `SSLSocketFactoryCompat`；**`SharedPreferencesUtil.beginBatchEdit` 与同类的 `applyBatch` 已于 26.10.04 批次 4 删除**（E5，见 §十四）
- [x] 统一 Cookie 写入锁（26.09.08 已修，第四轮）

### P1 安全

- [x] AppInfoApi 升级 https（4 处明文 HTTP）（26.09.13 第三十轮，实测该域 https 返回 200）
- [x] 关闭 `allowBackup`，并为备份/迁移加凭据排除规则（26.09.13 第三十轮）
- [x] 明文流量收窄为域名白名单（`network_security_config`，26.09.13 第三十轮）
- [x] 组件导出面收敛：22 个多余 `exported="true"` 改 false（26.09.13 第三十轮）
- [x] `TestActivity` 改为仅 Debug 包（清单下移 `src/debug` + `BuildConfig.DEBUG` 门控，26.09.13 第三十轮）
- [x] 敏感日志清理（PrivateMsgApi/NetWorkUtil.post）—— 26.10.02 修复轮次：`PrivateMsgApi` 的逐条私信正文 `Log.e` 已删除（改为只记条数）；`QRLoginFragment` 的 4 行 token/完整 Cookie 日志在 26.09.08 第四轮已删
- [ ] 更新 APK 签名/哈希校验 —— **26.10.04 批次 4 已实现客户端侧（E6）**：新增 `util/ApkVerifier.kt` 校验包名 + 签名一致，接进 `UpdateManager.downloadApk` 与 `DownloadActivity.installApk` 两条安装链路；**发布侧（哈希/证书指纹）未做**，见 §十四
- [x] 危险权限收敛 —— 26.10.02 修复轮次：主清单已删 `ACCESS_WIFI_STATE`（无任何 `WifiManager` 引用）与 `READ_PHONE_STATE`（无 `TelephonyManager` 引用），`SYSTEM_ALERT_WINDOW` 下移到 `src/debug/AndroidManifest.xml` 只给 UETool 用；`MANAGE_EXTERNAL_STORAGE` 已不在主清单。**有意保留** `REQUEST_INSTALL_PACKAGES`（应用内更新装 APK 必需）与 `READ_EXTERNAL_STORAGE`（minSdk 24 读取外部存储）

### P2 工程化

- [ ] 拆分 PlayerActivity（实测 **3494 行**；26.10.04 拍板 → **E1：暂缓**）
- [ ] 拆分 DownloadService（65KB；26.10.04 拍板 → **E2：想要实现**）
- [ ] 补单元测试（当前 **26 个测试类 / 204 用例**；26.10.04 拍板 → **E3：想要实现**。历史：26.09.11 新增 `ColorSchemeTest` 14、`CornerStyleTest` 10、`FontStyleTest` 13、`AppearanceManagerTest` 11；26.09.13 新增 `ViewCapabilityProbeTest` 4；26.10.03 新增 `ViewPointSkipTest` 9；26.10.04 新增 `PlayerApiPbpTest` 9，批次 2 新增 `ReplyApiTest` 9 / `ReplyParseActionTest` 4 / `NetWorkUtilTest` +6，批次 3 新增 `PerformanceManagerTest` 10，批次 4 新增 `SettingsKeysTest` 2 / `ApkVerifierTest` 8）
- [ ] `AsyncLayoutInflaterX` 生命周期：`cancel()` 已实现但无人调用，`BaseActivity.asyncInflate` 回调可能落到已销毁 Activity（26.10.04 拍板 → **A12：想要实现**）
- [x] `SettingsKeys` 收敛收尾：`PLAYER` / `PLAY_QN` 与字面量、`SharedPreferencesUtil` 第三处定义并存 —— **26.10.04 批次 4 已完成（E4）**：13 处字面量（台账写 14 处）全部改调常量，`SharedPreferencesUtil.player` 死字段已删，`SettingsKeysTest` 2 例钉死键名；`SettingMainActivity.kt:118` 的分组 id `"player"` 是假阳性、保持不动，见 §十四

---

## 六、下一步建议

1. 立即重新构建验证第二轮 4 处修改
2. 继续排查剩余 Critical（私信/下载/网络）
3. 转向 High 功能正确性修复（弹幕颜色/URL 编码收益高、改动小）
4. 架构清理与安全加固（P1）可安排到后续迭代

---

## 七、GitHub issue 修复轮次（2026-10-03）

### issue #1 视频播放器左右滑动调进度与右滑返回冲突（已修）

- **根因**：手表系统自带的右滑返回由 `android:windowSwipeToDismiss` 控制，它在**窗口层直接 `finish()`，不经过 `onBackPressed`**；而 `onCreate` 里的 `setTheme(ColorScheme.themeResId(theme))` 无条件使用带 `windowSwipeToDismiss=true` 的主主题，把清单上声明的 `Theme.NoSwipe*` 覆盖掉了（7 套 `Theme.*.NoSwipe.AppCompat` 因此长期零引用）。后果有两个：
  - 「禁用返回键」只拦了 `onBackPressed`，对手表右滑返回无效 —— 即 issue 里说的「全局屏蔽右滑返回失效」；
  - 开启「左右滑动控制进度」后，横向滑动被系统右滑返回抢走，表现为「一滑就退出播放器」。
- **修复**：
  - `ui/appearance/ColorScheme.kt`：`themeResId(theme, noSwipe)` 新增 `noSwipe` 参数，映射到 7 套 `Theme.*.NoSwipe.AppCompat`（只多关 `windowSwipeToDismiss`，配色/圆角/字体不变）；`ColorSchemeTest` 新增 3 个用例钉住映射与「变体必须不同于主主题」。
  - `activity/player/PlayerActivity.kt`：`onCreate` 中当「左右滑动控制进度」或「禁用返回键」开启时改用 noSwipe 主题；`onBackPressed` 在开启滑动进度时直接屏蔽返回键。
  - `activity/base/BaseActivity.kt`：「禁用返回键」开启时全局改用 noSwipe 主题，覆盖全部继承页（含弹窗类页），修复全局屏蔽右滑返回失效。
- **行为边界**：未开启「左右滑动控制进度」时播放页保持原逻辑，仅受「禁用返回键」控制；开启后播放页不再接受返回键与右滑返回（与 issue 作者所述原版终端一致，可用顶栏/菜单退出）。

### issue #2 稍后再看 / 历史记录长按无法删除（核实：已在 main 修复）

- 维护者已把修复合入 `main`：`adapter/video/VideoCardAdapter.kt`、`HistoryVideoCardAdapter.kt` 的长按回调改为**「页面显式注册的长按优先，无自定义长按才回落到快速缓存」**。
- 本轮逐页复核（确认无需再改）：
  - 有自定义长按 → 走删除：`WatchLaterActivity`、`FavoriteVideoListActivity`、`FavoriteFolderListActivity`、`HistoryActivity`；
  - 无自定义长按 → 保持快速缓存：`UserVideoAdapter`（用户主页视频列表）、推荐 / 排行 / 搜索等浏览类列表。
- 待版本发布后由 issue 作者真机确认删除交互（第一次长按提示「再次长按删除」，4 秒内第二次长按执行删除）。

> 验证：本轮改动为 Kotlin / 资源引用级修改，且**未增删 `res/` 文件集合**（只引用既有的 `Theme.*.NoSwipe.AppCompat`），可避开 AGENTS.md 记录的资源 build-cache 坑。本机无 JDK / Android SDK，改由 GitHub Actions（`.github/workflows/ci.yml`：`:app:assembleDebug` + `:app:testDebugUnitTest`）验证，结果以 CI 为准。

### 短视频页点顶栏直接退出（已修）

- **现象**：短视频页点击顶栏（标题栏 / 返回箭头）会直接把页面 `finish()` 掉；短视频作为启动页时等于直接退出应用。
- **根因**：`ShortVideoPlayerActivity : InstanceActivity()`，属于「菜单入口页」。基类已为这类页面统一提供 `menuClick`（跳 `MenuActivity` 主菜单），其余兄弟页（推荐 / 热门 / 入榜必刷 / 排行榜 / 热搜 / 直播 / 时间线 / 动态）都在 `onCreate` 里 `setMenuClick()`。短视频页没走这条链，反而在 `PageHolder` 里把 `top.setOnClickListener` 覆盖成了 `pause(); activity.finish()`（`MenuActivity` 的 import 随之变成死代码，说明历史上曾是回菜单）。
- **修复**：`PageHolder` 的顶栏点击改为「先 `pause()` 暂停当前短视频与弹幕，再 `activity.menuClick.run()` 展开主菜单」，与其它菜单入口页一致。
- **未动正常视频**：`PlayerActivity`（普通播放器，非 `InstanceActivity`）的 `layout_top.setOnClickListener { finish() }` 保持原样。
- **说明**：`MenuActivity` 是 `launchMode="singleTask"`，若任务栈里已有菜单实例，`menuClick` 会回到那个实例并清掉其上的短视频页（与其它入口页行为一致）；短视频作为启动页（栈里无菜单）时，菜单叠在其上、短视频暂停，返回后按 `wasPlayingWhenPaused` 恢复播放。

### 弹幕"一跳一跳"（滚动弹幕每 0.25 秒一顿）（已修）

- **现象**（用户实测）：普通视频与短视频**两个页面都有**，从开始播放就一直如此，只有**滚动弹幕**看得出来（顶部/底部固定弹幕不横向移动，所以看不出来），有节奏地每隔约 0.25 秒顿/跳一下。
- **根因**：`DanmakuManager.updateTimer` 原来写的是「位置一变就 `timer.update(pos)`」，而 `timer.update` 是**把 DFM 的时钟强行拨到该值**。位置来自主线程定时器 `progressChange` 每 **250ms** 采样一次的 `video_now`（`PlayerActivity`）/ `videoNow`（短视频），**天然滞后 0~250ms**；DFM 本来在 `syncTimer` 里每帧 `timer.add(d)`（`DrawHandler.java:466`）平滑自走。于是每 250ms 就把已走到的时钟往回拽约 125ms，一秒 4 次 → 滚动弹幕"一跳一跳"。这是一次回归：更早的 `updateTimer` 是每帧直接读 `ijkPlayer.currentPosition`（新鲜值），后来为了不让渲染线程取 native 锁，改成了读 250ms 的 `video_now` 内存值，采样滞后随之被带进了时钟。
- **修复**：改为「DFM 自走 + 跑偏才拉回」——偏差在容差内一律不动，只有真正的 seek / 缓冲卡停 / 解码漂移才一次性拉回。判定抽成无 Android 依赖的纯函数 `DanmakuSync.shouldResync(pos, timerMs)`，容差 `TOLERANCE_MS = 400ms`（必须 > 250ms 采样间隔，另留 ~150ms 给主线程卡顿）；`DanmakuManager.updateTimer` 调用它，原 `lastTimerPos` 去重字段随之删除。新增 JVM 单测 `DanmakuSyncTest`（5 例）钉住"稳态滞后 0~250ms 不得触发校正"这条回归。
- **未动**：`onCurrentPositionMs` 的调用契约（负数=不可信）不变；两个播放器喂给弹幕的位置来源不变。
- **遗留观察（本次未改，待确认是否也要修）**：短视频 `cycleSpeed()` 只调了 `playerBridge.setSpeed`，没有同步 DFM 的倍速（`DanmakuManager` 也没有 `setSpeed` 入口），所以短视频在 1.25x/1.5x/2x 下弹幕会持续落后于视频并被容差逻辑周期性拉回，表现为更大、更频繁的跳。普通视频那边是同步调了 `mDanmakuView.setSpeed` 的。

### 新功能：「播放默认值」分组（26.10.03）

- **需求**：在「内置播放器设置」页原「默认横屏」那一带加一个分组，把"开播时想自动生效"的设置集中起来，免得每次进播放器再手动调一遍。
- **分组内容**（标题「播放默认值」）：
  - 弹幕 / 听视频模式 / 循环播放 / 自动连播 —— 三态：开、关、**沿用上次**；
  - 倍速 —— `0.5x~3.0x` 固定值，外带一档「沿用上次」；
  - 字幕 —— 中文 / 自行选择（两档，不是三态）；
  - 屏幕方向 —— 默认横屏 / 竖屏 / 按视频分辨率选择（替换原「默认横屏」开关）。
- **实现**：
  - `SettingsKeys` 新增 `PLAYER_DEFAULT_*`（模式：`on`/`off`/`last`）与 `PLAYER_LAST_*`（「沿用上次」的记录位）。
  - 纯逻辑抽到 `player/PlayerDefaults.kt`（三态解析、倍速解析、屏幕方向解析、中文字幕挑选），配 `PlayerDefaultsTest` JVM 单测。
  - `SettingTerminalPlayerActivity` 改用 `list_choose`：现有 `choose` 是**两选一的 RadioButton、存 Boolean**，装不下三态/九档，也存不了字符串值。同时补上 `onActivityResult(1001)` —— 该回调此前**只有 `SettingGroupActivity` 处理**，不补就是"点了没反应"。
  - `PlayerActivity`：`onCreate`/`onPrepared` 里解析并应用；播放中用户切换时写 `PLAYER_LAST_*`（听视频切换失败会回滚记录，避免污染「沿用上次」）。字幕新增 `maybeAutoSubtitle()`，`downSubtitle()` 增加 `autoChinese` 参数（中文时自动选、不弹框）。屏幕方向的「按视频分辨率选择」在 `changeVideoSize()` 拿到真实宽高后**只判一次**（`autoOrientationApplied`，否则 `onConfigurationChanged` 会来回抖）。
- **兼容与迁移（默认行为不变）**：旧 `player_loop` / `player_audio_only` / `player_autolandscape` 只作为新设置的**默认值来源**（true → 开/横屏），键保留并标注 legacy；弹幕沿用既有 `pref_switch_danmaku` 作为"上次值"（默认开）；自动连播/倍速原本不持久化，新默认分别是「关」「1.0x」。
- **行为变化（已与用户确认）**：全局「界面横屏」(`ui_landscape`) **不再强制覆盖播放器方向**，播放器方向改由「播放默认值 → 屏幕方向」决定。
- **顺带发现（本次未处理）**：`SettingsAdapter.listChooseLauncher` 是死代码（从未被 set/read）；真正生效的是 `startActivityForResult(..., 1001)` + 宿主 `onActivityResult`。


## 八、发版链路改造：Gitee 发行版同步 + 更新检查改读发行版（2026-10-03）

### 背景

此前客户端更新检查读的是**单独部署在 123pan 上的 `config.json`**：版本号/说明/下载直链/是否强制更新全靠人工同步三处（`build.gradle`、`strings.xml`、`config.json`），历史上已实际漂移过；发行包也只在 GitHub 上，国内下载体验差。

### 改动

1. **发版工作流同步发行版到 Gitee**（`.github/workflows/build-release.yml` + 新增 `.github/scripts/sync_gitee_release.py`）：
   - 构建后把**发行版**（只同步发行版，**不推代码**）同步到 `zisekongling/bili-terminal-re`：建 Release + 上传 APK / `md5sums.txt`；
   - 生成 `release-links.txt`（Gitee 与 GitHub 两侧直链）作为附件挂到 Release 上；
   - 只保留最近 **3** 个 Gitee 发行版，更早的自动删除（Gitee 有配额）；
   - 令牌走仓库 Secrets 的 `GITEE_TOKEN`（已用 Gitee OpenAPI 实测通过）；该步 `continue-on-error`，失败**不阻断** GitHub Release。
2. **版本元数据下放到 Release 说明**：CI 从 `app/build.gradle` 读出 `versionCode`/`versionName`（外加手工触发的 `force_update` 输入），写进 Release 说明末尾的
   `<!-- update: versionCode=… versionName=… forceUpdate=… -->`（HTML 注释，不参与渲染）。
   版本号从此与**被发布的那个包**强一致，不再需要人工往远端文件抄一遍。
3. **客户端更新检查改源**（`util/UpdateManager.kt`）：`Gitee releases/latest` → 失败回落 `GitHub releases/latest`，
   **彻底不再读 config.json**；解析逻辑抽到 `util/UpdateRelease.kt`（纯函数 + 18 例 JVM 单测）：
   - 元数据缺失时按 tag（`YY.MM.DD` → `YYMMDD0`）推算版本号；推算失败**报错**而不是把 0 当版本号（否则会"永远收不到更新"且无提示）；
   - 下载直链按设备 ABI 从附件里选 `app-<abi>-release.apk`，回落 universal；
   - **必须按精确文件名匹配**：Gitee 会自动往发行版里塞 `{tag}.zip` / `{tag}.tar.gz` 两个源码归档，任何"取第一个附件"的写法都会下到源码包（单测已钉住）。
4. **连带清理**：`app/build.gradle` 的 `verifyVersionConsistency` 去掉 config.json 校验（只留 build.gradle 与 strings.xml 更新日志锚点）；
   `docs/architecture-map.md`、`docs/FEATURES.md`、`.github/workflows/ci.yml` 注释、`.dsh/skills/rebili-version-release/SKILL.md` 同步更新。

### 验证与备注

- **Gitee API 全链路已实测**：用临时 tag + 假附件跑通「建 Release → 上传附件 → 生成直链 → 删旧发行版」，匿名下载正常（302 → `attach_files`），随后已删除测试发行版，Gitee 仓库当前无残留 release。
- Gitee 直链格式：`https://gitee.com/zisekongling/bili-terminal-re/releases/download/<tag>/<文件名>`。
- 已知副作用：Gitee 会**自动附带** `{tag}.zip` / `{tag}.tar.gz` 两个**源码归档**（tag 落在 `master` 上产生），这不是我们上传的内容；客户端已按精确文件名规避。
- 两处需要人工确认后才能盖章：① GitHub Release / Gitee 发行版的端到端一次真实发版（会建 tag 与 Release，未擅自执行）；② Release 说明里的元数据是否被客户端正确读到（需真机跑一次检查更新）。

### 渠道迁移：给老客户端的一次性 config.json（26.10.03 发版）

- **问题**：26.10.02 及更早的客户端只认单独部署在 123pan 上的 `config.json`（`UpdateManager.CONFIG_URL`）。新链路把客户端改成读发行版，但**已经装机的老客户端改不了**，不处理就会「再也收不到更新提示」。
- **做法**：发版工作流新增 `emit_config_json` 输入（默认关，仅迁移那次打开）。打开时在 Gitee 同步之后多跑一步
  `.github/scripts/make_config_json.py`，按**老格式**产出一份 `config.json`：
  - `downloadUrl` = **Gitee 直链**（Gitee 同步失败则回落 GitHub 直链），老客户端从此从 Gitee 下载；
  - `forceUpdate` = 本次发版的强制标记（迁移那次为 `true`，把老客户端强制带上新链路）；
  - 文件随 Release 一起发布，**需人工把它传到 123pan**（CI 没有该上传通道）。
- **兼容性**：老客户端的解析器（历史 `parseConfig`）对 `versionCode`/`forceUpdate` 同时兼容字符串与原生类型，故这里写规范 JSON 类型（number / boolean）即可。
- **一次性**：迁移之后新客户端不再读 config.json，该文件不再产出。

### 发版链路改为「GitHub 发 Release + 通知中转服务同步 Gitee」（2026-10-03，取代上面第 1 条的做法）

- **为什么改**：GitHub runner **访问不通 Gitee**，直传实测约 13KB/s（10MB 的包 759s），4 个包共 53MB 根本传不完；
  试过挂 VLESS 代理，节点从 runner 侧 TCP 能连、TLS/REALITY 握手无响应（同一节点从国内线路完全正常）——
  即节点拒绝云厂商出口 IP，此路不通。
- **现在怎么做**：Release（含全部附件）在 GitHub 发完之后，追加一步 `Notify relay` 通知中转服务
  （`POST $RELAY_URL`，请求体 `{"repository":{"full_name":"zisekongling/BiliTerminal-RE"},"release":{"tag_name":"<tag>"}}`，
  头 `X-GitHub-Event: repository_dispatch` + `X-Hub-Signature-256: sha256=<HMAC-SHA256 小写 hex>`）。
  中转服务自己去 GitHub 拉附件、校验 `md5sums.txt`、同步到 Gitee，且**幂等**（重复通知无害）。
- **实现要点**：请求体用 `printf '%s'` 落盘再 `--data-binary @file`（`echo` 会多一个换行 → 签名不一致 → 401）；
  签名 `openssl dgst -sha256 -hmac ... -binary | xxd -p -c256`；curl `-sS --fail-with-body --max-time 30`，
  失败重试 3 次、每次 5 秒，三次全失败就让这一步失败（不静默漏同步，也不影响已发布的 GitHub Release）；
  地址与密钥取 Secrets `RELAY_URL` / `RELAY_SECRET`，并 `::add-mask::` 两个值。
- **清掉的旧东西**：`build-release.yml` 里的代理/直连 Gitee 三步、`.github/scripts/sync_gitee_release.py`、
  `start_xray_proxy.sh`、`make_xray_config.py`、`workflows/sync-gitee.yml`、`workflows/proxy-check.yml`。
  新增 `workflows/relay-notify.yml`（只重发通知，不重新构建），用于漏同步时补救与验收复测。
- **受影响的 Secrets**：`GITEE_TOKEN`、`VLESS_PROXY_LINK` 已不再被任何工作流引用，可自行删除；
  新增 `RELAY_URL`（已写入）与 `RELAY_SECRET`（待人工填写）。

### 发版实操踩坑记录（2026-10-03）

- **tag 复用被永久封禁**：重发同一版本时原样用旧 tag 会失败：
  `tag_name was used by an immutable release` + `pre_receive ... Cannot create ref due to creations being restricted`
  （仓库没有 ruleset，是 release immutability 特性本身）。删掉 release 和 tag 也没用，**必须换 tag**。
  本次因此把重发的第一版发成 `26.10.03.1`、第二版发成 `26.10.03.2`。
- **不可变 release 的附件不能增删改**：发完就冻结（`Cannot upload assets to an immutable release`），
  所以 `config.json` 这类要事后替换的东西没法补；漏同步只能靠 `relay-notify.yml` 重发通知。
- **中转服务验证通过**：通知返回 `202 {"ok": true, "queued": true, "tag": "..."}`，
  约 1 分钟内 Gitee 就出现 4 个 APK + `md5sums.txt`；从 Gitee 匿名下载的包 MD5 与 `md5sums.txt` 一致
  （实测 ~1.5 MB/s，对比本机直连 GitHub ~35KB/s）。
- Gitee 上两个 tag（`26.10.03.1` / `26.10.03.2`）目前都完整保留；若中转以后开启"只留最近 N 个"的清理，
  注意第一版 `config.json` 指向的是 `26.10.03.1` 的直链，会被连带清掉。

---

## 九、高能进度条接口变更适配（2026-10-04）

### 现象

设置里开着「显示高能进度条」时，进度条下方的弹幕密度曲线变空。

### 根因

`https://bvc.bilivideo.com/pbp/data`（pbp）改了响应结构，而旧实现不会报错：

- 旧实现只认「数据摊在根上」的 `{"step_sec":…,"events":{"default":[…]}}`；
- 新结构把数据包进了 `{"modules":[{"params":{"data":{…}}}]}`。

`events` 取不到就落成空数组，`hasValidData()` 为 false，前端既不画线也不报错——**静默变成一条直线**，
是最难定位的那类回归。另外请求侧也缺了两样：`r=loader` 参数与「落在具体视频页上」的 Referer（站点根会被风控挡掉）。

### 依据

以 PiliPlus（`bggRGjQaUbCoE/PiliPlus` @ `2515ecf`，`lib/pages/video/controller.dart` 的 `_getDmTrend()`）的现行实现为准：
它带 `aid`/`bvid`/`cid`/`r=loader`，`referer` 用 `https://www.bilibili.com/video/$bvid`，
并且**先试 `res.data['modules'][0]['params']['data']`、失败才回退 `res.data`**。

`bilibili-API-collect` 的镜像文档（`pskdje/bilibili-API-collect` `docs/video/pbp.md`）**仍是旧格式**——
它的 last-modified 正好是该仓库被关停的 2026-01-28，所以照抄它就会落后，这也是本问题的来源。

### 修复

`api/PlayerApi.java`：

- 新增 `parseHighEnergyData(JSONObject)`（static 纯解析，零网络、不写日志）。
  按「根上的 modules → `data` 里的 modules → `data` 本身 → 根自己」四个候选依次找，
  **优先返回真正带 `events.default` 的那个**；都不带才退回第一个候选，至少留下 `step_sec`/`debug` 便于排查。
  这样「外壳在、数据仍留在根上」的中间态也不会画空线。
- 新增 `buildPbpReferer(bvid, aid)`（纯函数，优先级 bvid > av 号 > 站点根）。
- `getHighEnergyData(long cid, long aid, String bvid)`：补 `bvid` 与 `r=loader` 参数；
  请求头用 `new ArrayList<>(NetWorkUtil.webHeaders)` 复制后只替换 `Referer`，**不原地改全局快照**。
  保留二参重载委托给三参版本，不破坏既有调用点。
- `activity/player/PlayerActivity.kt` 的 `loadHighEnergyData()` 从 Intent 读可选的 `bvid`
  （`PlayerData` 没有该字段，多数入口会落到 av 号 Referer）。

### 验证

- 新增 `app/src/test/.../api/PlayerApiPbpTest.kt` **9 例**：旧扁平结构 / 新 modules 外壳 / `data` 里套 modules /
  `modules` 为空但数据仍在根上 / 无 events / `step_sec` 缺失兜底 / 错误码返回 null / null 响应 / Referer 三级回退。
- `:app:testDebugUnitTest`：**21 个 XML，165 用例，0 失败**（原 156 + 新增 9）。
- `:app:assembleDebug`：BUILD SUCCESSFUL。
- 待真机确认：拿一个真实 cid 看曲线是否恢复（本机 pwsh 无外网，无法直接打接口）。

### 交叉引用

架构记录见 `docs/architecture-map.md` §7.8。

---

## 十、26.10.04 逐条拍板后的待做队列

> 依据：`docs/watch-optimization-research.md` **§12 决策台账**。该轮把本报告 §五/§六 与调研报告 §5/§7/§8/§10.4 的全部未落地项整理成清单，逐条向项目所有者确认。
> 本节只解决 §五/§六 的处置去向，不重复台账全文（完整清单见台账 §12.2~§12.5）。

### 结论映射

| 台账编号 | 事项 | 结论 | 本报告对应位置 |
|---|---|---|---|
| A1 | csrf 实时化铺开到 11 个 api 类 | **已实现（26.10.04 批次 2，实为 13 个 api 类 41 处）** | §十二 |
| A2 | `AnnouncementsActivity` 下拉刷新卡死 | **已实现（26.10.04 批次 1）** | §五 P0 界面卡死 第 1 条 |
| A3 | `PopularActivity` 不复位 refreshing | **已实现（26.10.04 批次 1）** | §五 P0 界面卡死 第 3 条 |
| A4 | `CollectionInfoActivity` 缺 onFailure + 死变量 | **已实现（26.10.04 批次 1）** | §五 P0 界面卡死 第 2、5 条 |
| A5 | `SeriesInfoActivity` 空字段 | **已实现（26.10.04 批次 1）** | §五 P0 界面卡死 第 4 条 |
| A6 | `OpusInfoActivity` 幽灵订阅 | **已实现（26.10.04 批次 1）** | §五 P0 界面卡死 第 6 条（原「漏 `leaveDetailPage()`」半句已勘误作废） |
| A7 | `SetupUIActivity` WebView 输入校验 | 暂缓 | §五 P0 功能正确性 末条 |
| A8 | `TutorialManagerActivity` 教程键污染 | 暂缓 | §五 P0 界面卡死 第 8 条 |
| A9 | `TestActivity` 硬编码专栏 id | 无计划 | §五 P0 界面卡死 第 7 条 |
| A10 | 评论点踩死视图（`adapter/ReplyAdapter.kt:495`） | **已实现（26.10.04 批次 2）** | §十二 |
| A11 | 禁右滑主题被 `setTheme` 覆盖失效 | **核实为已实现，原条作废** | 已由 issue #1 修复（见 §七），提交 `25e6886`、`20e69c1` |
| A12 | `AsyncLayoutInflaterX` 生命周期 | **核实为已实现，原条作废** | 提交 `d676a77`；`BaseActivity.kt:353-358` 已调 `cancel()` |
| C6b | 评论发图无进度 / 无反馈 | 想要实现 | 新增（发图本体已实现） |
| E1 | 拆分 `PlayerActivity`（实测 3494 行） | 暂缓 | §五 P2 第 1 条 |
| E2 | 拆分 `DownloadService` | 想要实现 | §五 P2 第 2 条 |
| E3 | 补单元测试 | 想要实现 | §五 P2 第 3 条 |
| E4 | `SettingsKeys` 收敛 | **已实现（26.10.04 批次 4）** | §十四 |
| E5 | 删 `SharedPreferencesUtil.beginBatchEdit` | **已实现（26.10.04 批次 4，`applyBatch` 一并删）** | §十四 |
| E6 | APK 签名 / 哈希校验 | **已实现客户端侧（26.10.04 批次 4，包名 + 签名；哈希不采用）** | §十四 |

### 已核实为「已经实现」，相应旧条目作废

- **D2 跑马灯标题**：`ui/widget/MarqueeTextView.kt:21-31` 按 `marquee_enable` 设 `ellipsize=MARQUEE`，开关在 `activity/settings/SettingGroupActivity.kt:281`，约 20 个布局已改用。例外：`BaseActivity.kt:191-193` 把页面标题栏强制 `TruncateAt.END`，标题栏不跑马灯。
- **D3 圆屏适配**：`util/SettingsKeys.kt:20` `UI_ROUND = "player_ui_round"`，开关 `SettingGroupActivity.kt:234`，`BaseActivity.kt:188-204` + `PlayerActivity.kt:839/859/1129/2071-2072`。设置页与代码读同一 key，无冲突（本报告 §五 P0 里曾记的"圆屏适配顶栏崩溃"已在第二十八/二十九轮修复）。
- **D5 候选词点击即搜**：`activity/search/SearchActivity.kt:232-239` 建议项点击即 `setText` + `searchKeyword(...)`；输入框 `:197-202` 主动弹键盘。仅剩「搜索历史点击只填入、不触发搜索」（`:213`）→ 该小项裁为**暂缓**。

### 数字勘误

- `activity/player/PlayerActivity.kt` 实测 **3494 行**（原述 3090 行）。
- 单元测试实测 **29 个测试类 / 233 个用例**（原述 16 类 / 112 例；批次 1 后 21/165，批次 2 后 23/184，批次 3 后 24/194，批次 4 后 26/204，批次 5 后 29/233）。
- JVM 单测可用 `org.json`（`app/build.gradle` 已有 `testImplementation 'org.json:json:20231013'`），但**纯解析函数禁止调用 `android.util.Log`**（未开 `returnDefaultValues`，会抛 not-mocked）。

### 建议落地顺序（26.10.04 已由项目所有者确认为 8 批）

1. **A 组快修**：A2 A3 A4 A5 A6（+ 核实 A11 A12 已实现）→ **✅ 已完成，见 §十一**
2. **A1** csrf 实时化铺开到 13 个 api 类 + **A10** 评论点踩 → **✅ 已完成，见 §十二**
3. **B1 B2 B3** + **B5**（限推荐/热门/搜索三页）+ **B8** → **✅ 已完成，见 §十三**
4. **E4 E5 E6** → **✅ 已完成，见 §十四**
5. **C12 C13 C14 C16**（私信与通知链）→ **✅ 已完成，见 §十五（C13）/ §十六（C12）/ §十七（C14）/ §十八（C16）**
6. **C3 C4 C6b C7 C8 C9 C10 C27**
7. **C18 C19 C20 C21**（收藏与关注整理）
8. **E2** 拆分 `DownloadService` + **F4** 漫画（追漫列表 / 漫画详情 / 长条阅读器）

**依赖约束**：C3 / C7 / C8 / C9 / C10 / C13 / C19 / C20 等写操作必须排在 **A1 csrf 实时化**之后，否则风控会 412。**E3 补单测贯穿每一批**，不单列。

---

## 十一、26.10.04 批次 1：A 组快修落地记录

> 依据 §十「建议落地顺序」第 1 批。范围经源码核实后由 7 项缩为 **5 项**：A11、A12 经核实**早已实现**，原条目作废（依据见 §十「已核实为『已经实现』」与调研报告 §12.2）。

### 修复明细

| 台账编号 | 文件 | 改动 |
|---|---|---|
| A2 | `activity/settings/AnnouncementsActivity.kt` | 全量重写（原 33 行）：新增 `loadAnnouncements()` 统一装载；`setOnRefreshListener` + `setOnEmptyRetry`；`try/catch` 双路径都 `setRefreshing(false)`，失败时 `showEmptyView()` 并提示。**顺带发现**：该页此前**从未调用 `setOnRefreshListener`**，而 `activity/base/RefreshListActivity.kt:53` 默认把 `swipeRefreshLayout.isEnabled` 置为 `false`，所以它此前根本不能下拉刷新。 |
| A3 | `activity/video/PopularActivity.kt` | catch 分支同时复位 `refreshing` 与 `swipeRefreshLayout.setRefreshing(false)`；清理 3 处遗留 `Log.e("debug", …)` 与 `import android.util.Log`。 |
| A4 | `activity/video/collection/CollectionInfoActivity.kt` | 补 `onFailure`（复位转圈 + `MsgUtil.err` + 空态）；`collection` 由字段改为局部变量并做 null 防御（原来 `collection!!` 在「视频不属于任何合集」时直接崩）；删除 `season_id`/`mid` 两个死 extra（全库无 `putExtra` 方）；onCreate 补 `setOnEmptyRetry { recreate() }`。 |
| A5 | `adapter/video/SeriesCardAdapter.kt` + `activity/video/series/SeriesInfoActivity.kt` | 跳转补 `cover`/`intro`/`total` 三个 `putExtra`，详情页读回；补 `setOnEmptyRetry { loadData(1) }`；顺带修掉 `getItemCount()` 的 `Condition is always 'true'` 编译警告。 |
| A6 | `activity/article/OpusInfoActivity.kt` | 空函数体的幽灵 `@Subscribe` 改为 `replyFragment?.notifyReplyInserted(event)`，与 `activity/dynamic/DynamicInfoActivity.kt:77-80` 对齐。**勘误**：原条目「漏 `leaveDetailPage()`」不成立——全库不存在该方法。 |

### 验证

- `:app:testDebugUnitTest`：**21 个 XML，165 用例，0 失败**（未新增测试，本批为纯接线修复）。
- `:app:assembleDebug`：BUILD SUCCESSFUL。
- 本批未增删 `res/` 文件，故单次 gradle 调用即可，无需 clean 分两次。

### 交叉引用

- 调研报告 §12.2（A 组台账，已按本批结果更新）。
- 本报告 §五「P0 界面卡死 / 未接线」6 条已全部勾除。

## 十二、26.10.04 批次 2：A1 csrf 实时化 + A10 评论点踩

> 依据已拍板的 8 批顺序第 2 批（§十）。四个实现细节经 ask 逐条确认：**① csrf 取用收敛为唯一入口；② 落 Cookie 时回写快照；③ 点踩做完整实现（能踩/能取消/显示已踩/与点赞互斥）；④ 验证方式 = JVM 纯函数单测 + 本文末尾真机清单**。

### A1 csrf 实时化（风控类静默失败的根因）

**问题**：`bili_jct` 随 Cookie 刷新轮换，而全库 14 个 `api/` 类里散布 41 处直接读 `SharedPreferencesUtil.csrf` **快照**（登录那一刻写入），POST 会静默拿 `-111`/`-412`，表现成「点赞/收藏/评论偶尔点了没反应」，且与登录时机相关、极难复现。

| 落点 | 改动 |
|---|---|
| `util/NetWorkUtil.java` | 新增 `currentCsrf()`（`:432`）与纯逻辑 `pickCsrf(cookieString, storedCsrf)`（`:443`）：优先取实时 Cookie 里的 `bili_jct`，取不到才退回快照。**这是全库唯一入口。** |
| `util/NetWorkUtil.java` | `saveCookiesLocked()`（`:510` 附近）落 Cookie 时顺手回写快照（只在值真的变了时写）——治根，不再依赖"登录那一刻"。 |
| **13 个 api 类共 41 处** | `ArticleApi` 4、`CookiesApi` 1、`DanmakuApi` 4、`DynamicApi` 5、`EmoteApi` 4、`FavoriteApi` 5、`LikeCoinFavApi` 4、`LoginApi` 1、`OpusApi` 1、`PrivateMsgApi` 2、`ReplyApi` 4、`VoteApi` 3、`WatchLaterApi` 2 —— 全部改为 `NetWorkUtil.currentCsrf()`。 |
| `api/HistoryApi.java` | 删除私有 `currentCsrf()`（唯一实现的 KDoc 并入 `NetWorkUtil`），3 处调用点改调公共入口。 |
| `util/AccountManager.java:135` | 改调 `NetWorkUtil.currentCsrf()`（账号切换会写回 Cookie，存快照会导致切回账号后 POST 全 -111）。 |
| `activity/settings/SettingGroupActivity.kt:208` | 设置页显示的 csrf 改为「当前有效值」，并补 `import ...util.NetWorkUtil`。 |

**勘误**：台账原写「11 个 api 类」，实际为 **14 个类 / 41 处 + 2 处非 api 调用点**。

### A10 评论点踩（`dislikeBtn` 是死按钮）

**问题**：`app/src/main/res/layout/cell_reply_list.xml:70-82` 的 `dislikeBtn` 在布局里可见且有约束，但全库只有 `adapter/ReplyAdapter.kt` 里一行 `findViewById`，从不绑定监听也不设状态 → 纯装饰。且 `model/Reply.java` 把服务端 `action` 字段写成 `liked = action == 1`，把「已踩(2)」与「无操作(0)」混为一谈。

| 落点 | 改动 |
|---|---|
| `api/ReplyApi.java` | 新增 `dislikeReply(oid, rpid, type, action)`（`POST x/v2/reply/hate`，参数 `type/oid/rpid/action/csrf`）+ 纯逻辑 `isLikedAction(int)`/`isDislikedAction(int)`/`actionErrorMsg(int)`（0/-101/-102/-111/-400/-404/-509/12002/12004/12006/12009/12011/65004~65007 → 中文提示，未知码保留原始数字）。 |
| `model/Reply.java` | 新增字段 `public boolean disliked`；解析改为 `parseAction(replyJson.optInt("action", 0))`，落到两个互斥状态上。 |
| `adapter/ReplyAdapter.kt` | `dislikeBtn` 绑定点击（复用点赞那把「处理中」互斥锁，因为赞/踩在服务端互斥）；新增 `applyDislikeResult()` 把结果落到状态与视图（已踩高亮 `ColorScheme.LIKE_COLOR`，点踩成功时同步撤掉本地点赞状态与计数）；点赞成功分支同时清掉本地已踩状态；失败提示改用 `actionErrorMsg(code)`（原来统一显示"失败"）。 |

### 验证

- 新增单测：`api/ReplyApiTest`（9 例：action 判定互斥 + 错误码翻译不落兜底）、`model/ReplyParseActionTest`（4 例）、`util/NetWorkUtilTest` 补 6 例 `pickCsrf`（实时值优先 / 缺失退回 / null / 空值 / 双空 / 非首段命中）。
- `:app:testDebugUnitTest`：**23 个 XML，184 用例，0 失败 0 错误**（批次 1 后为 21/165）。
- `:app:assembleDebug`：BUILD SUCCESSFUL。未增删 `res/` 文件，单次 gradle 调用即可。
- 编码事故记录：批量替换 `api/` 目录 csrf 时曾用 PowerShell `Get-Content -Raw` + `WriteAllText` 把中文写成乱码（按 ANSI 解码），已 `git checkout` 还原后用 UTF-8 显式读写重做；`git diff` 已确认无 `鐐|璇|鍒` 等乱码签名。**教训：本仓库批量改文本必须走 edit 工具或显式 UTF-8 读写。**

### 真机验证清单（JVM 单测覆盖不到的部分，发布前逐条走一遍）

**A1**：
1. 登录后进入任意视频，点赞/收藏/投币/发评论各一次，均应成功。
2. **关键**：在网页端或另一设备触发一次 Cookie 刷新（或等 `bili_ticket` 续期）后，回到本机立刻点赞 → 仍应成功（这是本次修复的核心场景；修复前此步会静默失败）。
3. 退出登录后点赞 → 提示「还没有登录喵~」，而不是点了没反应。
4. 设置页「账号」组的 csrf 与当前 Cookie 里的 `bili_jct` 一致。
5. 频繁操作触发风控后，重试一次应能成功（`-352/-412` 重试链 + 实时 csrf 配合）。

**A10**：
1. 评论列表点「踩」→ 图标高亮 + 提示「已点踩」；再点一次 → 取消。
2. 先点赞再点踩 → 点赞图标与计数**自动回落**（互斥生效），反之亦然。
3. **杀进程重进**同一条评论 → 已踩状态仍然显示（`parseAction` 生效；修复前显示成未操作）。
4. 连点两次「踩」→ 第二次提示「正在处理中」，不发出第二条请求。
5. 断网/被限流时提示具体原因（如「操作过于频繁，请稍后再试」），不是笼统的"失败"。
6. 在**动态/专栏**评论区（`type != 1`）点踩也应成功（原先点赞的 `type` 硬编码为 1 会被服务端拒绝，点踩这里传的是真实 `replyType`）。

### 交叉引用

- 调研报告 §12.2（A1/A10 台账已更新为「已实现（26.10.04 批次 2）」）、§12.6（单测数字更新为 23 类 / 184 例）、§12.7（8 批顺序）。
- `docs/architecture-map.md` §6.1（新增「csrf 唯一入口」说明、行数 605→709）、§7.3（测试清单与数量）、§9 第 8 条（key 混用已收敛 csrf 部分）。
- 下一批（批次 3）：B1 B2 B3 B5（限推荐/热门/搜索）B8。

---

## 十三、26.10.04 批次 3：性能参数接线 + 列表增量刷新越界 + 崩溃页独立进程

> 依据已拍板的 8 批顺序第 3 批（§十二 末尾）。五条实现口径经 ask 逐条确认：**① B1 分类处理（能接的接、接不了的删）；② B2 检测挪后台、先落默认中档；③ B3 图片档位改成对手表列表图有意义的粒度，不盲目照抄台账；④ B5 同类 bug 一次清干净；⑤ B8 独立进程 + 剪掉重初始化**。

### B1 `PerformanceManager` 死参数裁决

**问题**：`util/PerformanceManager.kt` 按硬件分档声明了一批运行时参数，但大量 getter 从未被调用；三个 `applyXxxPerfSettings()` 函数体各只有一行 `Logu.i`。

**勘误**：台账 §12.3 B1 写「7 个死 getter」，实测 **8 个**（多一个 `isHighPerformanceMode()`）。

| 分类 | 成员 | 处置 |
|---|---|---|
| 接线 | `getImageQuality` / `getImageMaxWidth` | 接进 `util/GlideUtil.java`（即 B3） |
| 接线 | `getPageSize` | 接进 3 处「纯追加列表」分页（见下） |
| 删除 | `isHighPerformanceMode` / `getOkHttpConnectionPoolSize` / `getOkHttpKeepAliveMinutes` / `isImageTransitionEnabled` / `isVideoPreloadEnabled` | 全库无引用，直接删（避免留 `@Deprecated` 噪音） |
| 保留 | `isHardwareBitmapEnabled`（`helper/CustomGlideModule.kt:38` 在用）、`getGlideDiskCacheSizeMB` / `getGlideMemoryCacheSizeMB` / `getRecyclerViewCacheSize` / `getRecyclerViewPrefetchCount` / `isLowPerfDevice` / `setHighPerformanceMode` / `getCurrentPerfLevel` | 本来就在用 |

**分页只接了 3 处**：`api/RecommendApi.java:84`（popular `ps`）、`:106`（precious `page_size`）、`api/SeriesApi.java:28`（用户系列 `page_size`）。**其余硬编码 ps 故意不接**，因为它们各自编码了接口语义：`FavoriteApi.java:106 ps=100`（收藏夹一次拉全）、`MessageApi.java:380 page_size=35`（cursor 分页，改大小会让游标错位）、`EmoteApi.java:94/120 ps=12`（表情面板整屏）等。这条边界已写进 `getPageSize()` 的 KDoc。

### B2 首次硬件检测挪到后台

**问题**：`PerformanceManager.init()` 在 `BiliTerminal.onCreate` 同步调用，首次冷启动（无 `KEY_DEVICE_PERFORMANCE_LEVEL` 缓存）要在主线程跑 `getHardwareScore()` → 读 `/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq`，失败还要逐行解析 `/proc/cpuinfo`。

**改法**：`init()` 改双重检查；**有缓存** → 直接读缓存档位 + `applyPerformanceSettings()`；**无缓存** → 先 `currentPerfLevel = PERF_LEVEL_MEDIUM` + `applyPerformanceSettings()` 立即返回，把检测交给新增的 `detectAndApplyPerformanceLevel()` 在 `CenterThreadPool` 上跑（算完写缓存、必要时自动打开高性能、重跑 `applyPerformanceSettings()`、异常只记日志并保持中档）。顺带删掉了原先「`if (Logu.LOGI_ENABLED)` 才打日志」的短路绕法——那是为了省一次 `getHardwareScore()`，现在检测已在后台，不再需要。

### B3 图片按档位下采样

**问题**：`GlideUtil.url()/url_hq()` 对所有设备用同一组写死的质量/宽度。

**口径修正（重要）**：台账原方案（低 256 / 中 320 / 高 512）与实际情况不符——服务端已按档位下发，把中/高端列表图从 512 提到 1024 等于像素 ×4，手表上纯属浪费。最终粒度：

| 场景 | 低端设备 | 中/高端设备 |
|---|---|---|
| 列表图 `GlideUtil.url()` | **320w / 50q** | **512w / 60q**（与修复前完全一致） |
| 大图 `GlideUtil.url_hq()` | **512w / 60q** | **1024w / 80q**（与修复前完全一致） |

档位计算全部沉到 `PerformanceManager` 的 `@JvmStatic` 纯函数（`listImageQuality` / `listImageMaxWidth` / `hqImageQuality` / `hqImageMaxWidth`，接收 `level, highPerformanceMode`），`GlideUtil` 里原有的 `QUALITY_HIGH` / `QUALITY_LOW` / `MAX_W_HIGH` / `MAX_W_LOW` 四个常量**已删除**（避免两处真相）。另外把 `isLowPerfDevice()` 的判断抽成纯函数 `isLowPerfLevel(level, highPerformanceMode)` 以便单测。

### B5 列表增量刷新越界

**勘误**：台账 B5 说「推荐 / 热门页用全量刷新」——实际 `activity/video/RecommendActivity.kt:85` 与 `activity/video/PopularActivity.kt:96` **本来就是** `notifyItemRangeInserted`，那两条已达标。本次真正修的是 **4 处真 bug**：

| 位置 | 问题 | 修法 |
|---|---|---|
| `activity/search/SearchVideoFragment.kt:55` | `notifyItemRangeInserted(lastSize + 1, videoCardList.size - lastSize)` —— 起点多算 1 | 改 `notifyItemRangeInserted(lastSize, list.size)` |
| `activity/search/SearchArticleFragment.kt:53` | 同上 | 同上 |
| `activity/search/SearchLiveFragment.kt:64` | 同上 | 同上 |
| `activity/video/series/UserSeriesActivity.kt:50-56` | **根本没把新数据加进 adapter 的列表**，只报 `notifyItemRangeInserted(oldSize, seasonList.size)` → 报出的新增数与 `getItemCount()` 对不上，必撞 `Inconsistency detected` | 记住第 1 页的 adapter（`seasonAdapter`），第 2 页起 `seasonList.addAll(...)` 后再以 `oldSize = adapter.seasonList.size` 通知；`adapter/video/SeriesCardAdapter.kt` 的 `seasonList` 由 `List` 放宽为 `MutableList` |

**判定坑（走了弯路，务必记住）**：`notifyItemRangeInserted(x + 1, …)` 在本项目**大多数是对的**——这些 adapter 的 `getItemCount()` 带一个头部占位（`data.size + 1`，位置 0 是标题/头部）。已逐一核实并**保持不动**：`activity/video/series/SeriesInfoActivity.kt:86`（`oldSize` 在 `addData` 之前取，内部 adapter 是 `data.size + 1`）、`activity/reply/ReplyFragment.kt:250`（`ReplyAdapter.getItemCount() = replyList.size + 1`）、`activity/user/info/UserDynamicFragment.kt:89`（`UserDynamicAdapter.getItemCount() = dynamicList.size + 1`）、`adapter/user/FollowGroupAdapter.kt:108`（分组结构）。**结论：判越界前必须先读对应 adapter 的 `getItemCount()`，看那 `+1` 是不是头部。**

顺带清理：各搜索页的 `Log.e("debug","加载下一页")`（`SearchVideoFragment` / `SearchArticleFragment` / `SearchBangumiFragment` / `SearchLiveFragment` / `SearchUserFragment`）、`SearchActivity.kt:89` 的 `Log.e("debug","进入搜索页")`、`RecommendActivity` 的 3 处 `Log.e("debug", …)`，以及 `RecommendActivity` / `PopularActivity` 上已失效的 `@SuppressLint("NotifyDataSetChanged")`。**遗留（不在本批范围）**：`activity/reply/ReplyFragment.kt:241` 仍有 `Log.e("debug", …)`。

### B8 崩溃页独立进程

**问题**：`ErrorCatch` 在主进程崩溃后同进程启动 `CatchActivity`，极易二次崩溃把崩溃页一起带走；为此代码里塞了 `Thread.sleep(300)` 硬等。

| 落点 | 改动 |
|---|---|
| `AndroidManifest.xml` | `.activity.CatchActivity` 加 `android:process=":error_activity"`（附注释说明理由） |
| `BiliTerminal.kt` | 新增 `currentProcessName()`（读 `/proc/self/cmdline`，minSdk 24 用不了 API 28 的 `Application.getProcessName()`）；`onCreate()` 开头判进程名，错误进程只做最小初始化（SharedPreferences + 适配 Context + `applyLogSwitches()`）后 `return`，**不碰**性能检测、强制更新、`registerActivityLifecycleCallbacks`、`ErrorCatch.init`、未读轮询、更新检查 |
| `BiliTerminal.kt` | 日志开关集中为 `applyLogSwitches()`（主进程分支改调它，避免两处重复） |
| `ErrorCatch.java` | 删除 `Thread.sleep(300)` 及其 try/catch：`CatchActivity` 现在在独立进程，`startActivity` 已同步交给 AMS，本进程立刻死掉也会被拉起 |

已核实 `activity/CatchActivity.kt` 用到的 `SharedPreferencesUtil` / `MsgUtil` / `CenterThreadPool` / `AppInfoApi.uploadStack` / `StringUtil.setCopy` / 跨进程 `stopService(DownloadService)` 都不需要主进程那套初始化；`BaseActivity.onCreate` 只读 SharedPreferences + 主题，最小初始化足够。

### 验证

- 新增单测 `app/src/test/java/com/RobinNotBad/BiliClient/util/PerformanceManagerTest.kt`（10 例）：档位分界（0/34/35/64/65/100）、`isLowPerfLevel` 三态、列表图与大图的低端降档 + **中档不被抬成 80q/1024w**（防以后改回）、未 init 时的默认中档参数。
- 未给 `GlideUtil.url()/url_hq()` 写单测：其静态初始化会构造 Glide 的 `DrawableCrossFadeFactory`（依赖 android 类），档位逻辑已全沉到 `PerformanceManager` 纯函数并被覆盖。
- `:app:testDebugUnitTest` + `:app:assembleDebug`：**BUILD SUCCESSFUL in 57s**；**24 个 XML / 194 用例 / 0 失败 0 错误**（批次 2 后为 23/184，+1 类 +10 例）。
- 未增删 `res/` 文件，单次 gradle 调用即可（无需 clean 两段式）。

### 真机验证清单（JVM 单测覆盖不到的部分，发布前逐条走一遍）

**B1 / B3**：
1. 低端手表（或设置页手动关掉高性能）刷推荐/热门/搜索结果 → 列表缩略图应更糊更小，滚动更跟手。
2. 中/高端设备刷同样的列表 → **列表图观感应与升级前完全一致**（512w/60q 没变）；点进视频详情，封面等大图仍清晰（1024w/80q）。
3. 低端设备进视频详情 → 大图降到 512w/60q，不再有"大图解码吃满内存"的卡顿。
4. 设置页切「高性能模式」开/关 → 列表图档位应立即跟着变（切换后重新进列表页观察）。
5. 推荐/热门/用户系列翻页 → 第 2 页起应正常追加、不闪退；`adb logcat` 无 `Inconsistency detected`。
6. 低端设备首屏分页应变成 10 条/页，中高端 20 条/页。

**B2**：
7. 全新安装（或清除应用数据）后冷启动 → 首屏应立即出来（不再等硬件检测），随后在 logcat 里能看到一次「PerformanceManager 初始化完成 / 性能检测」后台日志。
8. 冷启动后立刻连续快速滑动首页列表 → 不应有可感知的一次性卡顿。

**B5**：
9. 搜索「视频 / 专栏 / 直播」三类，各下滑加载第 2、3 页 → 无闪退、无 `Inconsistency detected`、列表项与数量正确（修复前第 2 页起会越界）。
10. 用户投稿里的「系列」页下滑第 2 页 → 新条目正常出现在列表末尾且**数量对得上**（修复前根本不追加）。

**B8**：
11. 用 `adb shell am broadcast` 或调试开关人为触发一次崩溃 → 崩溃页应正常弹出并显示堆栈（即使主进程已被 kill）。
12. 崩溃页点「重启」→ 能正常回到闪屏/主界面，且**不叠加**一个新的未处理异常。
13. 崩溃页点「退出」→ 应用完全退出，`adb shell ps` 里主进程与 `:error_activity` 进程都不应残留。

### 交叉引用

- 调研报告 §0 优先清单（第 2~8 项标注落地状态）、§4.2（补「已按 §12.3 B1/B2/B3 处理完毕」）、§7.1（P0/P1 表更新）、§9（加「已被 §12.7 取代」声明）、§12.3（B1/B2/B3/B5/B8 台账更新为「已实现（26.10.04 批次 3）」）、§12.6（单测数字 → 24 类 / 194 例）、§12.7（批次 3 完成）。
- 下一批（批次 4）：E4 E5 E6。

## 十四、26.10.04 批次 4：SettingsKeys 收敛收尾 + 删假批量 API + 更新包校验

> 台账来源：调研报告 §12.5 D/E/F 组的 E4 / E5 / E6。三项均已落地。
> 决策：E4 选「13 处字面量改调常量 + 删死字段」；E5 选「`beginBatchEdit` 与 `applyBatch` 一并删」；E6 选「客户端校验包名与签名一致」，**发布侧零改动**。

### E4 `SettingsKeys` 收敛收尾：`player` / `play_qn`

**问题**：同一个 key 有三处定义 —— `util/SettingsKeys.kt:72` 的 `PLAYER`、`:74` 的 `PLAY_QN`，以及 `util/SharedPreferencesUtil.java:59` 的 `public static String player = "player"`（非 final、零引用）；而真正读写时用的**全是字面量**，常量形同虚设。

| 处理 | 内容 |
|---|---|
| 删除 | `util/SharedPreferencesUtil.java:59` 的 `public static String player` 死字段（原地留注释说明该 key 现在只有唯一来源） |
| 改调常量 | 13 处字面量全部改为 `SettingsKeys.PLAYER` / `SettingsKeys.PLAY_QN`：`api/PlayerApi.java:251`（`getString("player", "")`，默认值是空串不是 `"null"`，**保持原样**）、`:436`（switch 的取值）、`model/VideoInfo.java:126`、`activity/player/PlayerActivity.kt:401/2690/3338`、`activity/live/LiveInfoActivity.kt:172/226`、`activity/settings/SettingPlayerChooseActivity.kt:20/83/135`、`activity/settings/SettingQualityActivity.kt:49`、`activity/video/JumpToPlayerActivity.kt:109` |
| 补 import | `PlayerApi.java`、`VideoInfo.java`、`LiveInfoActivity.kt`、`SettingPlayerChooseActivity.kt`、`SettingQualityActivity.kt`、`JumpToPlayerActivity.kt`（`PlayerActivity.kt` 原本已 import） |
| **不动** | `activity/settings/SettingMainActivity.kt:118` 的 `"player"` —— 它是设置页分组的 **id/跳转标记**，与 SharedPreferences 键只是字面量碰巧相同。已在该处加注释，防止后人"顺手替换" |

顺带清理：`SettingPlayerChooseActivity.kt:103` 的 `Log.e("debug", "点击了$finalI")` 与其后失效的 `import android.util.Log`（同类残留见 §十三 B5）。

**勘误**：台账（调研报告 §7.1 / §12.5）记「14 处字面量」；逐处核对后是 **13 处**（`SettingMainActivity.kt:118` 是分组 id，不是 SP 键；`SharedPreferencesUtil.java:59` 是定义不是用法）。

**新增单测** `app/src/test/java/com/RobinNotBad/BiliClient/util/SettingsKeysTest.kt`（2 例）：把 `PLAYER == "player"`、`PLAY_QN == "play_qn"` 钉死。理由：key 字符串就是**磁盘协议**，改了不报错、只是用户设置静默回默认值——收敛到常量之后，这层保护才真正生效。

### E5 删除假批量 API

`util/SharedPreferencesUtil.java` 里两个零调用且**误导**的方法已删（真正的批量入口 `edit(Consumer<Editor>)` 保留，`util/AccountManager.java:202` 在用）：

| 已删 | 为什么是坑 |
|---|---|
| `beginBatchEdit()` | 方法体只有 `sharedPreferences.edit();` —— editor 拿到就丢，什么都没做；注释「该方法内部已延迟初始化editor」是错的 |
| `applyBatch(Runnable)` | 同样拿到 editor 就丢，注释说「将editor传入操作（通过ThreadLocal或回调）」但代码里根本没有传 |
| 保留 `edit(java.util.function.Consumer<SharedPreferences.Editor>)` | 真的把 editor 交给调用方并在最后 `apply()`，是唯一的批量写入入口 |

### E6 更新包校验（包名 + 签名）

**问题**：`util/UpdateManager.kt` 下载完 APK 直接交给系统安装器，中间零校验；被替换/截断/串包时用户只看到系统那句笼统的「解析软件包时出现问题」。

**新增 `app/src/main/java/com/RobinNotBad/BiliClient/util/ApkVerifier.kt`**：

| 成员 | 作用 |
|---|---|
| `isSamePackage(expected, actual)` | 纯函数：包名一致；`actual` 为 null/空判不一致 |
| `isSameSignature(expected, actual)` | 纯函数：证书十六进制串集合一致，**与顺序无关、忽略大小写**；任一侧为空 → 失败关闭（false） |
| `signatureHex(bytes)` | 纯函数：证书字节 → 小写十六进制，每字节固定两位（含前导零） |
| `verify(context, apkFile): Result` | Android 侧：`getPackageArchiveInfo(..., GET_SIGNATURES)` 取包名与签名，与 `getPackageInfo(packageName, GET_SIGNATURES)` 比对；包名不符 / 签名不符 / 读不到自身签名 / 包无法解析，都返回 `Result(false, 中文原因)` |

**接线（两条安装链路都覆盖）**：

- `util/UpdateManager.kt` `downloadApk()`：`writeResponseToFile` 之后、`onComplete` 之前校验；不通过则**删掉文件**（留残片会被下次 `Range` 续传当成"已下载一部分"，永远修不好）并 `onError("安装包校验失败：…")`。
- `activity/DownloadActivity.kt` `installApk()`：最老的「下载成 `.bak` 再改名安装」自更新链路，开头加同一校验，不通过返回 `false` 走既有的「安装失败，已保存到下载文件夹」分支（该方法不在主线程，不在里面弹提示）。

**为什么没做哈希校验**：发布侧现有的 MD5（说明里的「APK 校验值（MD5）」+ `md5sums.txt` 附件）与安装包来自**同一个响应**，能改包的人也能改元数据，对真正的中间人几乎没有增量价值；而签名是攻击者没有私钥就伪造不出来的。代价与边界：系统安装器本身也会拒绝「同包名不同签名」，本校验的价值是**早失败 + 说清原因**；它防不住「同一签名者发布的坏包」（那属于发布侧被攻破）。

**新增单测** `app/src/test/java/com/RobinNotBad/BiliClient/util/ApkVerifierTest.kt`（8 例）：包名一致/不同/缺失、签名顺序无关、大小写无关、多一个签名也算不一致、**任一侧为空都失败关闭**、十六进制补齐两位（`0x0A → "0a"`、`0x00 → "00"`、`0xFF → "ff"`）、输出恒为小写。

### 验证

- `:app:testDebugUnitTest` + `:app:assembleDebug`（`--offline --no-configuration-cache`）：**BUILD SUCCESSFUL in 53s**；**26 个 XML / 204 用例 / 0 失败 0 错误**（批次 3 后为 24/194，+2 类 +10 例）。
- 未增删 `res/` 文件，单次 gradle 调用即可（无需 clean 两段式）。
- 唯一警告仍是既有代码的弃用项（`PlayerActivity.kt` 的 MediaSession 常量、`JumpToPlayerActivity.kt:96` 的 `getParcelableExtra`、`UpdateManager.kt:45` 的 `versionCode`）。

### 真机验证清单（JVM 单测覆盖不到的部分，发布前逐条走一遍）

**E4**：
1. 设置页切播放器（内置 / 小电视 / 凉腕）→ 重启应用后选择**仍保留**（收敛只改调用方式，不能改键名）。
2. 设置页切清晰度（360P/720P/1080P）→ 重启应用后清晰度**仍保留**；进播放器默认按该清晰度起播。
3. 直播页长按播放按钮 → 提示文案仍按「当前是否内置播放器」正确出现（该判断也读了同一个 key）。
4. 升级安装（覆盖安装旧版本）→ 上面两项的旧设置**不能丢**（键名一致性的真机证据）。

**E5**：无行为变化（删的是零调用方法），只需确认应用能正常启动、设置项读写正常。

**E6**：
5. 正常更新一次（或手动触发检查更新后下载）→ 能正常进系统安装器、能装上，说明**没有误杀**自家包。
6. 把下载目录里的更新包 `util/UpdateManager` 产物（`.../cache/update/bili_terminal_update.apk`）换成另一个应用的 APK，再触发一次下载完成 → 应提示「安装包校验失败：安装包包名不符（…）」，且该文件被删除、没有进安装器。
7. 同一路径放一个**同包名但用 debug 密钥重签**的 APK（本地 `assembleDebug` 产物即可，前提是 release 与 debug 签名不同）→ 应提示「安装包签名与当前应用不一致，已拒绝安装」。
8. 把更新包删一半（或写 0 字节）→ 应提示「安装包无法解析，文件可能已损坏」或「安装包不存在或为空」。
9. `adb logcat` 确认失败后重试能重新完整下载（残片已删，不会 206 续传到坏包上）。

### 交叉引用

- 调研报告 §12.5（E4/E5/E6 三行改「已实现（26.10.04 批次 4）」）、§12.6（单测数字 → 26 类 / 204 例）、§12.7（批次 4 完成，8 批顺序第 4 批标 ✅）。
- `docs/architecture-map.md` 新增 §7.11「更新包校验」+ §7.12「设置 key 的唯一来源」。
- 下一批（批次 5，按调研报告 §12.7 的 8 批顺序）：C12 C13 C14 C16。

---

## 十五、26.10.04 批次 5（1/4）：私信会话管理（C13）

对应调研报告 §12.4 的 C13「私信删除 / 置顶 / 折叠」。**范围按用户拍板收窄：只做「移除会话」+「置顶/取消置顶」，不做折叠消息（`batch_rm_dustbin`）**。批次 5 按「C13 → C12 → C14 → C16」各自独立提交，本条只记 C13。

### 新增接口

| 位置 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/api/PrivateMsgApi.java` | 新增 `public static int removeSession(long talkerId, int sessionType)` → `POST https://api.vc.bilibili.com/session_svr/v1/session_svr/remove_session`；新增 `public static int setSessionTop(long talkerId, int sessionType, boolean top)` → `POST …/session_svr/set_top`；两者共用私有 `postSessionAction(url, talkerId, sessionType, extra)`（`talker_id`/`session_type`/`csrf_token`/`csrf`/`build=0`/`mobi_app=web`，csrf 走 `NetWorkUtil.currentCsrf()`） |
| 同上 | 新增**纯函数** `public static int opTypeForTop(boolean top)`：**`op_type` 0 = 置顶、1 = 取消置顶**（接口文档如此，与直觉相反）。抽成纯函数并配单测，因为写反的后果是"点置顶实际取消置顶"，不报错、极难发现 |
| 同上 | 新增 `public static String sessionErrorMsg(int code)`：0→空串、-101→"还没有登录喵~"、-400→"请求错误，会话可能已不存在"、其余保留错误码 |
| 同上 | 新增会话类型常量 `SESSION_TYPE_USER = 1`、`SESSION_TYPE_FAN_GROUP = 2` |
| 同上 | `parseSessionsList` 与 `getNewSessionsList` 都补解析 `top_ts`（`optLong("top_ts", 0)`，缺字段按未置顶） |
| `app/src/main/java/com/RobinNotBad/BiliClient/model/PrivateMsgSession.java` | 新增 `public long topTs = 0;` 与 `public boolean isTop() { return topTs > 0; }`——服务端**没有布尔型的"是否置顶"字段**，只能判非零 |

### UI

| 位置 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/message/PrivateMsgSessionsAdapter.kt` | 构造参数新增 `onSessionsChanged: (() -> Unit)? = null`。**长按菜单**（原来长按＝直接跳用户主页，现改为弹 `AlertDialog` 菜单）：置顶会话 / 取消置顶、删除会话、查看用户主页（跳主页能力保留为菜单项，没丢）；删除走二次确认（文案明确"不会删除聊天记录"）。两个操作都 `CenterThreadPool.run` + `CenterThreadPool.runOnUiThread` 回 UI 提示，成功后调 `onSessionsChanged` 重新拉列表；删除时额外本地 `removeAt` + `notifyItemRemoved` 做即时反馈 |
| 同上 | 会话名渲染加置顶标记：`displayName = if (isTop()) "[置顶] ${user.name}" else user.name`；未读徽章的 `SpannableStringBuilder`/span 起点同步改用 `displayName.length`（原来用 `user.name.length`，加前缀后必须跟着改，否则徽章会插在名字中间） |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/message/MessageActivity.kt` | 把 `onCreate` 里的会话加载逻辑抽成 `private fun loadSessions()`（含 `MessageApi.getUnread()` + `getSessionsList(20)` + 未读排序 + `getUsersInfo` + 建 adapter + 回 UI 更新未读数/`MESSAGE_UPDATE_NUM`），并把 adapter 的回调接成 `{ loadSessions() }`；`swipeRefreshLayout` 由局部变量提升为字段；`scrollView` 请求焦点的三行留在 `onCreate` 里一次性执行 |

### 单测

`app/src/test/java/com/RobinNotBad/BiliClient/api/PrivateMsgApiTest.kt` +3 例（该文件原有 6 例，`sessionJson` 助手新增 `topTs` 参数）：
1. `parseSessionsList_解析置顶时间`——`top_ts` 非零 → `isTop` 为真；为 0 → 假；缺字段 → 按未置顶。
2. `opTypeForTop_0是置顶_1是取消置顶`——锁死反直觉的 0/1 映射（防以后被"修正"成 `top ? 1 : 0`）。
3. `sessionErrorMsg_成功为空串_其余给出可读提示`。

**验证**：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → BUILD SUCCESSFUL in 40s；`app/build/test-results/testDebugUnitTest` **26 个 XML / 207 个用例 / 0 失败 / 0 错误**（批次 4 后 26 类 / 204 例，本批 +3 例）。

### 真机验证清单（JVM 单测覆盖不到的部分，发布前逐条走一遍）

1. 私信列表长按会话 → 弹「会话操作」，三个菜单项都在。
2. 选「置顶会话」→ 提示「已置顶」，列表刷新后该会话名开头出现 `[置顶]`；进 B 站 App / 网页确认服务端也真的置顶了（**这条专门验 `op_type` 没写反**）。
3. 再长按同一会话，菜单第一项应变成「取消置顶」→ 执行后 `[置顶]` 标记消失、服务端也不再置顶。
4. 选「删除会话」→ 二次确认弹窗出现；确认后该会话从列表消失，但**点进网页版仍有聊天记录**（接口语义如此，不是 bug）。
5. 删除后让对方发一条新消息 → 会话应重新出现在列表中。
6. 未登录状态下执行任意一项 → 应提示「还没有登录喵~」，不崩溃。
7. 断网执行 → 应走 `MsgUtil.err` 的异常提示，不崩溃。
8. 「查看用户主页」菜单项仍能正常跳转（原有能力不能退化）。

### 交叉引用

- 调研报告 §12.4 的 C13 行改为「删除 + 置顶/取消置顶已实现（26.10.04 批次 5）」并注明折叠消息不做。
- 接口依据：仓库自带快照 `bilibili-API/docs/message/private_msg.md`（移除会话 :1075-1107、置顶 :1136-1165、会话列表字段 `top_ts` :12）。
- `docs/architecture-map.md` §7.13「session_svr 会话管理接口」。
- 本批剩余：C12（私信发图）→ C14（新私信通知）→ C16（追番更新提醒），各自独立提交。

---

## 十六、26.10.04 批次 5（2/4）：私信发图（C12）

对应调研报告 §12.4 的 C12「私信发图」。**范围按用户拍板收窄：只做「从相册选图发送」，不做拍照**。上一条是 C13，本条只记 C12。

### 接口依据

`bilibili-API/docs/message/private_msg_content.md:27-57`：`msg_type=2` 的 content 根对象字段为

| 字段 | 要求 |
|---|---|
| `url` | **必须是 B 站图床地址**，否则接口返回 21037「图片格式不合法，不要调戏接口啦」 |
| `width` / `height` | 建议必带，缺了消息在客户端显示异常 |
| `imageType` | 不带 `image/` 前缀的格式名，如 `"jpeg"` |
| `original` | 传 1 时 APP 显示「下载原图」 |
| `size` | 单位是 **KB**（示例值 `55.443`，带三位小数） |

### 新增纯函数

| 位置 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/api/PrivateMsgApi.java` | 新增 `public static JSONObject buildImageContent(String url, int width, int height, long byteSize, String imageType)`：按上表组装 content，`size` 走 `sizeToKb` |
| 同上 | 新增 `public static double sizeToKb(long byteSize)`：`Math.round(byteSize / 1024d * 1000d) / 1000d`；`byteSize <= 0` 返回 0（不能发出 NaN / 负数） |
| 同上 | 新增 `public static String imageTypeOf(String mimeType)`：转小写、去掉 `image/` 前缀、`jpg` 归一成 `jpeg`、空值兜底 `jpeg`（用 `Locale.ROOT`，避免土耳其语 i 问题） |

抽出这三个纯函数的理由：都是"错了不报错、只在服务端默默拒绝或显示异常"的格式约定（21037 只会在真发图时才出现），放纯函数才能被 JVM 单测锁死。

### UI

| 位置 | 改动 |
|---|---|
| `app/src/main/res/layout/activity_private_msg.xml` | 输入行新增 `TextView id=image_btn`（38dp 圆角、`background_privatemsg_send`、文字「图」），并把 `msg_input_et` 的 `layout_toStartOf` 从 `send_btn` 改到 `image_btn`。**用文字按钮而不是新图标**：`res/drawable/` 里没有任何 image/pic/photo/album 类图标，新增资源文件会触发 build cache 陈旧资源坑（要跑两段式 clean），收益不抵成本；复用 `WriteReplyActivity` 的"文字入口"风格 |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/message/PrivateMsgActivity.kt` | 新增 `imageBtn` 字段 + `imageLauncher`（`registerForActivityResult` + `ActivityResultContracts.StartActivityForResult`）；点击先判登录（`SharedPreferencesUtil.getLong(mid, 0) == 0L` → 提示「还没有登录喵~」），再 `pickImage()`；`pickImage()` 用 **`ACTION_GET_CONTENT`** + `type=image/*` + `CATEGORY_OPENABLE`（手表相册不一定支持 `ACTION_PICK`，与 `WriteReplyActivity`、`SettingGroupActivity` 的判断一致） |
| 同上 | 新增 `private fun sendImage(uri: Uri)`：`CenterThreadPool.run` 内 `ImageApi.prepareImage`（按最长边 2048 压缩，避免手表 OOM）→ `ImageApi.uploadImage(..., ImageApi.BIZ_REPLY)` → `buildImageContent` → `PrivateMsgApi.sendMsg(..., PrivateMessage.TYPE_PIC, ...)`；成功提示「发送成功」并 `refresh()`，失败弹 `message`，异常走 `MsgUtil.err` |
| 同上 | **`ImageApi.BIZ_REPLY` 是复用而非专有值**：私信图片没有独立的 `biz`（`biz=` 在接口快照全库只命中 album/live/space 等无关文档），而 21037 只校验"url 是不是 B 站图床"，图床本身就是共用的 `upload_bfs`，所以复用 `BIZ_REPLY`（`new_reply`）安全。以后若找到私信专用 biz，改这一处即可 |

代码依据：`app/src/main/java/com/RobinNotBad/BiliClient/api/ImageApi.java` 已有 `prepareImage(Context, Uri)`（GIF ≤20MB / PNG ≤8MB 原样透传，其余压 JPEG90，`MAX_IMAGE_SIZE` 25MB）与 `uploadImage(byte[], String, String, String)`（内部 `ReplyApi.uploadReplyImage` → `POST https://api.bilibili.com/x/dynamic/feed/draw/upload_bfs`），C12 没有新增任何上传代码，只是接线。**已知取舍：不做 EXIF 旋转**（无 `androidx.exifinterface` 依赖且约定不轻易加库，与 `WriteReplyActivity` 表现一致），竖拍照片可能方向不对。

### 单测

`app/src/test/java/com/RobinNotBad/BiliClient/api/PrivateMsgApiTest.kt` +4 例：
1. `sizeToKb_按千字节保留三位小数`——0/负数 → 0、1024 → 1.0、56774 → 55.443、512 → 0.5（小于 1KB 不能被截成 0）。
2. `imageTypeOf_去掉mime前缀并把jpg归一成jpeg`——含大小写不敏感。
3. `imageTypeOf_空值兜底为jpeg`。
4. `buildImageContent_字段齐全且是图片消息规格`——逐字段断言，`size` 断言 55.443。

**验证**：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → BUILD SUCCESSFUL；`app/build/test-results/testDebugUnitTest` **26 个 XML / 211 个用例 / 0 失败 / 0 错误**（C13 后 207，本批 +4）。

### 真机验证清单（JVM 单测覆盖不到的部分，发布前逐条走一遍）

1. 私信页输入行能看到「图」按钮，且输入框/图/发送三个控件不重叠（圆形表盘上尤其看一眼）。
2. 点击「图」→ 拉起系统选图；**手表上确认 `ACTION_GET_CONTENT` 真能选到图**（这是选它而非 `ACTION_PICK` 的原因）。
3. 选一张普通照片 → 提示上传中 → 「发送成功」，消息气泡里出现图片。
4. 在 **B 站官方 App / 网页**里看同一条消息：图片能正常显示、尺寸比例正确（验 `width`/`height` 带对了）。
5. 选一张 **GIF** 发送 → 不应被压成静态图（`prepareImage` 对 GIF 原样透传）。
6. 选一张 **竖拍照片** → 记录实际显示方向（已知不做 EXIF 旋转，此条是确认现状而非要求修复）。
7. 选一张超过 25MB 的图 → 应走异常提示不崩溃。
8. 未登录点「图」→ 提示「还没有登录喵~」，不弹选图。
9. 选图后直接返回（不选任何图）→ 不应发送、不崩溃。
10. 断网发送 → 走 `MsgUtil.err`，不崩溃。

### 交叉引用

- 调研报告 §12.4 的 C12 行改为「选图发送已实现（26.10.04 批次 5），拍照不做」。
- 接口依据：`bilibili-API/docs/message/private_msg_content.md:27-57`（图片消息 content 结构、21037 的触发条件）。
- 复用基建：`app/src/main/java/com/RobinNotBad/BiliClient/api/ImageApi.java`（`prepareImage` / `uploadImage` / `BIZ_REPLY`）。
- 本批剩余：C14（新私信通知栏通知，不做 RemoteInput 速回）→ C16（打开应用时检查追番更新，不做后台定时），各自独立提交。

---

## 十七、26.10.04 批次 5（3/4）：新消息通知栏通知（C14）

对应调研报告 §12.4 的 C14「新消息通知」。**范围按用户拍板收窄：只做通知栏通知，不做 RemoteInput 速回；只在打开应用检查未读时触发，不做后台定时**。上一条是 C12，本条只记 C14。

### 为什么这么做（取舍写在代码里，也记在这里）

- **不做速回**：`RemoteInput` 要额外申请权限、处理跨进程回复广播，手表上打字成本本来就高，收益不抵复杂度。点通知＝打开消息页。
- **不做后台定时**：项目里没有 WorkManager / AlarmManager 依赖，也不为此新增（见 `AGENTS.md`「不轻易引入新第三方库」）。触发点直接用 `BiliTerminal.onCreate` 里**既有的未读检查**，零新增定时器。
- **只在"未读变多"时提醒**：单纯"有未读"会在每次冷启动都弹，变成骚扰。

### 新增文件与改动

| 位置 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/util/MsgNotifier.kt`（**新建**，`object`） | `CHANNEL_ID = "private_msg_channel"`、`NOTIFICATION_ID = 1029`（**1027 被 `DownloadService` 占用、1028 被 `PlaybackService` 占用，必须避开**）；`notifyNewMessages(context, privateMsgUnread, otherUnread)`（先查 `areNotificationsEnabled()`，未授权直接放弃；O+ 建渠道；`PendingIntent` 指向 `MessageActivity`，`NEW_TASK or CLEAR_TOP` + `FLAG_IMMUTABLE`；异常只记日志，绝不影响未读检查本身）；`cancel(context)`（撤通知） |
| 同上 | **纯函数** `shouldNotify(previousUnread, currentUnread, enabled) = enabled && currentUnread > 0 && currentUnread > previousUnread`；**纯函数** `summaryText(privateMsgUnread, otherUnread)`（"3 条新私信、2 条新消息"式，全 0 兜底"有新消息"） |
| `app/src/main/java/com/RobinNotBad/BiliClient/BiliTerminal.kt` | `MESSAGE_UPDATE_CHECK_ENABLE` 分支里：进 try 先取 `previousUnread`（上次存的 `MESSAGE_UPDATE_NUM`）→ 原两次 unread 检查与写回不变 → 读开关 → `shouldNotify` 为真才 `notifyNewMessages(context, privateMsgUnread, messageUnread)`（注意实参顺序：`MessageApi.checkPrivateMsgUnread()` 给 privateMsg、`checkMessageUnread()`（at+reply）给 other） |
| 同上 | **两个 catch 分支的 `putInt(MESSAGE_UPDATE_NUM, 0)` 改为只记日志**。理由：清零会让下一次成功检查把"老未读"当成新增未读，网络抖一次就重复弹通知；保留上次已知值既不误报也不丢提示。这是 C14 引入的**行为变更**，专门列在真机清单里 |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/message/MessageActivity.kt` | `onCreate` 调 `requestNotificationPermissionIfNeeded()`：Android 13+ 且未授予 `POST_NOTIFICATIONS` 时用 `registerForActivityResult(RequestPermission())` 申请（放消息页请求最自然）；`loadSessions()` 把未读清零后追加 `MsgNotifier.cancel(this)`，避免"看过了通知还挂着" |
| `app/src/main/java/com/RobinNotBad/BiliClient/util/SettingsKeys.kt` | 新增 `const val PRIVATE_MSG_NOTIFY_ENABLE = "private_msg_notify_enable"`（新增"通知"分组注释段） |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/settings/SettingPrefActivity.kt` | 「更新提醒」分组在「消息数量检查」之后新增开关「新消息通知」（默认 `"true"`） |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/settings/SettingsIndex.kt` | 通用偏好的可搜索条目列表补「新消息通知」（新增设置项的第三处） |
| `app/src/main/res/values/strings.xml` | 新增 `desc_private_msg_notify_enable`（设置项说明走 `strings.xml` 的 `desc_*` 惯例） |

**无需改动**：`app/src/main/AndroidManifest.xml:18` 早已声明 `POST_NOTIFICATIONS`，本次只是第一次真正用上它。

### 单测

`app/src/test/java/com/RobinNotBad/BiliClient/util/MsgNotifierTest.kt`（**新建**）+5 例：
1. `shouldNotify_仅在未读变多时才提醒`——0→3 真、2→3 真、3→3 假（不重复骚扰）、5→2 假（用户读过了）。
2. `shouldNotify_当前没有未读时不提醒`——-1→0 假、0→0 假。
3. `shouldNotify_开关关闭一律不提醒`。
4. `summaryText_私信与其它未读分开报`——3/0、0/2、3/2 三种组合逐字断言。
5. `summaryText_都没有未读时给出兜底文案`。

**验证**：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → BUILD SUCCESSFUL in 1m 2s；`app/build/test-results/testDebugUnitTest` **27 个 XML / 216 个用例 / 0 失败 / 0 错误**（C12 后 211，本批 +5）。

### 真机验证清单（JVM 单测覆盖不到的部分，发布前逐条走一遍）

1. 首次进消息页（Android 13+）应弹「允许发送通知」权限请求；拒绝后再进不反复弹。
2. 系统设置里关掉本应用通知 → 有新消息时应不弹、且不崩溃（`areNotificationsEnabled()` 兜底）。
3. 通知栏点通知 → 直接进消息页，且**不会叠出多个消息页**（`NEW_TASK | CLEAR_TOP`）。
4. 进消息页后通知应自动消失（撤通知 + `setAutoCancel`）。
5. 冷启动应用、有未读 → 弹一次通知；**不操作、再次冷启动 → 不应重复弹**（这就是 `shouldNotify` 的判据）。
6. 在消息页把未读读掉 → 再冷启动 → 不应弹。
7. 让对方新发一条私信 → 冷启动应弹，且正文是「N 条新私信」；只有回复/@ 类未读时正文是「N 条新消息」。
8. 设置里把「新消息通知」关掉 → 冷启动不弹；打开后恢复。
9. **断网启动**（验收 C14 引入的行为变更）：未读数应**保持上次已知值**、不弹通知、不崩溃；恢复网络后冷启动，若期间真有新增未读则弹一次，且不会把断网前的老未读当成新增而重复弹。
10. 与下载通知、播放通知同时存在时，三条通知互不覆盖（ID 1027/1028/1029 不冲突）。

### 交叉引用

- 调研报告 §12.4 的 C14 行改为「通知栏通知已实现（26.10.04 批次 5），不做 RemoteInput 速回、不做后台定时」。
- `docs/architecture-map.md` §7.15「新消息通知」。
- 通知 ID 占用：`app/src/main/java/com/RobinNotBad/BiliClient/service/DownloadService.kt:493`、`app/src/main/java/com/RobinNotBad/BiliClient/service/PlaybackService.kt:42-43`。
- 本批剩余：C16（打开应用时检查追番更新提醒，不做后台定时）。

---

## 十八、26.10.04 批次 5（4/4）：追番更新提醒（C16）

对应调研报告 §12.4 的 C16「追番更新提醒」。**范围按用户拍板收窄：只在打开应用时检查追番列表，不做后台定时**（项目没有也不引入 WorkManager / AlarmManager）。复用 C14 刚建好的通知基建（`MsgNotifier`）。

### 为什么这么做（取舍写在代码里，也记在这里）

- **判据是「同一部番的 `new_ep.id`（最新一集 id）变了」**，不是"总集数变了"（电影/特别篇对总集数不敏感），也不是服务端的 `is_new`（那是个"有新内容"标记，用户在别的客户端看过之后会被清掉，与本地的"我看到哪了"无关）。
- **首次检查只写快照、不提醒**：否则一装上就会把全部追番报成"更新"。
- **这次新追的番不算更新**：只记入快照。否则"追了一部已经更完的番"会立刻弹通知。
- **快照只在成功拉到列表后写回**：拉取失败保持旧快照，否则下次会把老集当新集重复提醒（与 C14 未读检查失败不清零是同一个道理）。
- **拉取上限 10 页 × 30 条 = 300 部**：冷启动不该为了一个提醒把流量打满；`ps` 的定义域就是 1-30（见接口快照 `bilibili-API/docs/user/space.md:4626-4795`）。

### 新增文件与改动

| 位置 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/model/FollowedBangumi.java`（**新建**） | 只带判断更新需要的四个字段：`mediaId` / `title` / `newEpId`（`new_ep.id`）/ `newEpIndexShow`；类注释写明为什么不用 `total_count` 与 `is_new` |
| `app/src/main/java/com/RobinNotBad/BiliClient/api/BangumiApi.java` | 新增常量 `FOLLOW_PAGE_SIZE = 30`、`FOLLOW_MAX_PAGES = 10`；新增 `getFollowedBangumi()`（未登录 `mid==0` 直接返回空表；逐页拉到"不满一页"为止，最多 10 页）；新增**纯解析** `parseFollowingList(JSONObject)`（`code!=0` 抛 `JSONException(message)`、没有 message 时用 `错误码：N` 兜底；`data`/`data.list` 缺失返回空表；`media_id==0` 的项跳过，避免脏数据污染快照）。原有给列表页用的 `getFollowingList(int, List<VideoCard>)` **未动**（两个用途：一个给 RecyclerView 翻页展示，一个给更新检查，字段取舍不同） |
| 同上 | 顺手删除 `import android.util.Log;` 与 `getMdidFromEpid` 里的 `Log.e("debug-epid", …)`（调试残留；删 import 后该行编译不过，正好一起清掉） |
| `app/src/main/java/com/RobinNotBad/BiliClient/util/BangumiUpdateChecker.kt`（**新建**，`object`） | **纯函数** `snapshotJson(items)`（`{"media_id": new_ep_id, …}`，整份替换语义：取消追番后旧条目要跟着消失）、`parseSnapshot(json)`（空/坏 JSON 一律当"没有快照"、非数字键跳过，绝不因坏数据崩在启动路径）、`findUpdated(stored, current)`（**只有"快照里存在 + `newEpId` 变了 + 新值 > 0"才算更新**）；入口 `checkAndNotify(context)`（未登录/空列表直接返回且**不覆盖快照**；无快照只写快照、不通知；有更新则 `MsgNotifier.notifyBangumiUpdates`；最后写回当前快照） |
| `app/src/main/java/com/RobinNotBad/BiliClient/util/MsgNotifier.kt` | 新增 `BANGUMI_CHANNEL_ID = "bangumi_update_channel"`、`BANGUMI_NOTIFICATION_ID = 1030`（避开 1027/1028/1029）、`notifyBangumiUpdates(context, titles)`（`PendingIntent` 指向 `FollowingBangumisActivity`）、**纯函数** `bangumiSummaryText(titles)`（1 部→`《x》更新了`、N 部→`《第一部》等 N 部追番更新了`、空→`有追番更新了`）；把两处建渠道的重复代码抽成私有 `ensureChannel(context, id, name, description)` |
| `app/src/main/java/com/RobinNotBad/BiliClient/util/SettingsKeys.kt` | 新增 `const val BANGUMI_UPDATE_NOTIFY_ENABLE = "bangumi_update_notify_enable"`（通知分组内，紧跟 C14 的开关） |
| `app/src/main/java/com/RobinNotBad/BiliClient/util/SharedPreferencesUtil.java` | 新增 `BANGUMI_UPDATE_SNAPSHOT = "bangumi_update_snapshot"`（快照 JSON），注释写明"失败绝不清空" |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/settings/SettingPrefActivity.kt` | 「更新提醒」分组在「新消息通知」之后新增开关「追番更新提醒」（默认 `"true"`） |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/settings/SettingsIndex.kt` | 通用偏好的可搜索条目补「追番更新提醒」（新增设置项的第三处） |
| `app/src/main/res/values/strings.xml` | 新增 `desc_bangumi_update_notify_enable` |
| `app/src/main/java/com/RobinNotBad/BiliClient/BiliTerminal.kt` | 在未读检查块与 `checkAppUpdate()` 之间加第三块：开关默认 true **且** 已登录 → `CenterThreadPool.run { BangumiUpdateChecker.checkAndNotify(context) }`，两个 catch（`IOException`/`JSONException`）只记日志、不动快照 |

### 单测

- `app/src/test/java/com/RobinNotBad/BiliClient/api/BangumiApiTest.kt`（**新建**）+6 例：取 `mediaId`/`newEpId`/`indexShow`；没有 `new_ep` 时 `newEpId` 为 0；跳过 `media_id=0` 的脏数据；`data`/`list` 缺失或为 null 返回空表；错误码抛可读异常（53013 隐私未公开）；错误码无 message 时用 `错误码：-400` 兜底。
- `app/src/test/java/com/RobinNotBad/BiliClient/util/BangumiUpdateCheckerTest.kt`（**新建**）+8 例：快照序列化往返；空值/坏 JSON/数组都当没有快照；非数字键跳过；最新集变了才算更新；**这次新追的番不算更新**；**新集变成 0 不算更新**；多部更新保持列表顺序；快照为空时一律不报更新。
- `app/src/test/java/com/RobinNotBad/BiliClient/util/MsgNotifierTest.kt` +2 例：`bangumiSummaryText` 的一部/多部文案；没有更新时的兜底文案。
- `app/src/test/java/com/RobinNotBad/BiliClient/util/SettingsKeysTest.kt` +1 例：钉死 `bangumi_update_notify_enable` 的键名（改了会静默失效用户的开关）。

**验证**：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → BUILD SUCCESSFUL in 16s（首次失败过一次：删了 `import android.util.Log` 但漏删 `getMdidFromEpid` 里的 `Log.e("debug-epid", …)`，编译报"找不到符号 Log"，两处一起清掉后通过）；`app/build/test-results/testDebugUnitTest` **29 个 XML / 233 个用例 / 0 失败 / 0 错误**（C14 后 27 个 / 216 例，本批 +2 类 +17 例）。

### 真机验证清单（JVM 单测覆盖不到的部分，发布前逐条走一遍）

1. 首次安装（无快照）冷启动 → **不弹**追番更新通知，安静地写下快照。
2. 追一部"已经更完"的番 → 冷启动 → 不弹（新追的番不算更新）。
3. 让追番里某部更新一集（或手工改快照里的 `new_ep_id` 模拟）→ 冷启动 → 弹一次，正文是「《番名》更新了」。
4. 连点两次冷启动、期间没有新集 → **只弹一次**（快照已写回，不重复提醒）。
5. 多部同时有更新 → 正文是「《第一部》等 N 部追番更新了」，N 与快照 diff 的数量一致。
6. 通知栏点追番通知 → 进「追番列表」页（`FollowingBangumisActivity`），不会叠出多个页面。
7. 取消追番后再冷启动 → 不弹（旧条目从快照里消失，不会被当成更新）。
8. 未登录/退出登录状态冷启动 → 不弹，且**快照不被清空**（重新登录后仍能正确比对）。
9. 断网冷启动 → 不弹、不崩溃；恢复网络后冷启动，期间真有新集才弹，且不会把老集当新集重复弹。
10. 设置里关掉「追番更新提醒」→ 冷启动完全不检查（应有对应日志缺失）、不弹；打开后恢复。
11. 与下载通知（1027）、播放通知（1028）、新消息通知（1029）同时存在时，四条通知互不覆盖。

### 交叉引用

- 调研报告 §12.4 的 C16 行改为「已实现（26.10.04 批次 5）：打开应用时对比追番最新集快照，有变化才提醒；不做后台定时」。
- `docs/architecture-map.md` §7.16「追番更新提醒」。
- 接口依据：仓库自带快照 `bilibili-API/docs/user/space.md:4626-4795`（`type=1` 追番 / `type=2` 追剧、`ps` 定义域 1-30、53013 隐私未公开）。
- 批次 5 至此四条全部完成：C13（§十五）→ C12（§十六）→ C14（§十七）→ C16（本条）。下一批（批次 6）：C3 C4 C6b C7 C8 C9 C10 C27。

---

## 十九、26.10.04 批次 6（1/8）：评论置顶 / 取消置顶（C3）

对应调研报告 §12.4 的 C3「评论删除 / 置顶自己的评论」。删除本来就有，本次补上置顶/取消置顶，并把删除入口一起收进长按菜单。

接口依据（仓库自带快照）：`bilibili-API/docs/comment/action.md:399-455` —— `POST https://api.bilibili.com/x/v2/reply/top`，参数 `type`/`oid`/`rpid` + `action`（**:417 写明 `0=取消置顶`、`1=设为置顶`**）+ `csrf`（:418）；响应只有 `code`/`message`/`ttl`，**没有 data**；错误码 :426 里与本功能相关的是 **12029「已经有置顶评论」**、**12030「不能置顶非一级评论」**、`-403`（权限不足）、`-404`（没有这条评论）。:407 原文：「只能置顶自己管理的评论区中的一级评论」。

### 为什么这么做（取舍写在代码里，也记在这里）

- **置顶的 `action` 语义是反的**（1=置顶、0=取消），很容易写反又很难在真机上发现，所以抽成纯函数 `ReplyApi.topActionFor(boolean)` 并单测钉死。
- **服务端一个评论区只有一个置顶位**（否则回 12029），而 `model/Reply.java` 的 `isTop` 是逐条布尔。置顶成功后若不清旧标记，列表里会**同时出现两条「[置顶]」**。清理逻辑抽成纯函数 `Reply.clearTopFlags(List<Reply>)` 并单测。
- **`[置顶]` 前缀是构造 `Reply` 时拼进显示文本的**（`Reply.java` 构造里 `TOP_TIP + htmlToString(...)` + `StringUtil.setTopSpan`），适配器绑定的是 `textView.text = reply.message`。所以运行时只翻 `isTop` 布尔值，界面**一点变化都没有**；新增 `Reply.setTopFlag(boolean)` 同步增删前缀（前缀永远在第 0 位，删除后表情/@/投票/超链接的 span 由 SpannableStringBuilder 自动平移），`clearTopFlags` 也改走它。
- **入口收进长按菜单**（用户拍板）：原来删除是「点一下提示『长按删除』→ 长按两次且间隔 <6 秒」，交互藏在 cell 里一个小按钮上。现在长按弹 `AlertDialog` 菜单，**替换**原来的两次长按删除交互。
- **置顶项只对 `isManager` 显示**（视频 UP 主 / 合作稿 staff，沿用已有的删除权限判据）。服务端允许「评论区管理员」置顶，但客户端目前没有这个判据；不显示必然失败的入口，权限最终仍以服务端 `-403` 为准。**评论区管理员的支持留待以后**。
- **删除改为现取现用列表下标**：原实现捕获绑定时的 `realPosition`，弹窗期间列表若被刷新（翻页/删除）就会删错行；现在用 `replyList.indexOf(reply)`。

### 改动

| 位置 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/api/ReplyApi.java` | 新增**纯函数** `topActionFor(boolean top)`（`top ? 1 : 0`）；新增 `topReply(long oid, long rpid, int type, boolean top)` → POST `x/v2/reply/top`，`FormData` 带 `type`/`oid`/`rpid`/`action`/`csrf=NetWorkUtil.currentCsrf()`，返回 `code`；`actionErrorMsg` 补 `12029`→「已经有置顶评论了，请先取消原置顶」、`12030`→「只能置顶一级评论」 |
| `app/src/main/java/com/RobinNotBad/BiliClient/model/Reply.java` | 新增 `setTopFlag(boolean)`（同步 `[置顶]` 前缀与主色 span）；`clearTopFlags(List<Reply>)` 改为调用它；`import java.util.List` |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/ReplyAdapter.kt` | 长按 `item_reply_delete` → `showManageMenu(reply)`（`AlertDialog.setItems`：UP 侧多一项「置顶评论/取消置顶」）；新增 `setReplyTop(reply, top)`（后台请求 → 成功则 `clearTopFlags` + `setTopFlag` + `notifyItemRangeChanged`）、`confirmDeleteReply(reply)`（二次确认弹窗）、`deleteReply(reply)`（原删除逻辑搬过来，下标改用 `indexOf`）；删除原来的「两次长按删除」`OnLongClickListener`；补 `import androidx.appcompat.app.AlertDialog` |

### 单测

- `app/src/test/java/com/RobinNotBad/BiliClient/api/ReplyApiTest.kt` +2 例：`topActionFor_oneMeansTopAndZeroMeansCancel`（钉死反直觉语义）；`actionErrorMsg_explainsExistingTopAndNonRootReply`（12029/12030 文案含「置顶/取消」「一级评论」），并把已知错误码清单扩到含 12029/12030。
- `app/src/test/java/com/RobinNotBad/BiliClient/model/ReplyParseActionTest.kt` +3 例：`clearTopFlags_removesEveryTopMark`、`clearTopFlags_withoutAnyTopMarkReturnsZero`、`clearTopFlags_toleratesNullListAndNullItems`。
- **`setTopFlag` 本身没写 JVM 单测**：它操作 `SpannableStringBuilder` 与 `StringUtil.setTopSpan`（依赖 `ColorScheme`，JVM 下是 not-mocked）。单测里 `message` 为 null，只翻状态，所以 `clearTopFlags` 的断言仍然成立；前缀增删留真机验证。

**验证**：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → BUILD SUCCESSFUL in 54s；`app/build/test-results/testDebugUnitTest` **29 个 XML / 238 个用例 / 0 失败 / 0 错误**（批次 5 后 233 例，本次 +5 例）。中途失败过一次：断言写了 `actionErrorMsg(12029).contains("已有")`，而文案是「已经**有**置顶评论了…」——`"已有"` 不是 `"已经有"` 的子串，改为断言 `contains("置顶")` + `contains("取消")` 后通过。

### 真机验证清单（JVM 单测覆盖不到的部分，发布前逐条走一遍）

1. **最重要的一条**：置顶成功后退出重进评论页，「[置顶]」前缀**是否还在**。服务端字段 `reply_control.is_up_top`（`model/Reply.java:88-92` 在读）**不在接口快照的字段表里**，是逆向出来的未文档化字段；若重进后标记丢了，说明翻页/懒加载接口不返回它，需要改从顶层 `data.top` / `data.upper`（`bilibili-API/docs/comment/list.md:92-98`、`:1001-1007`）判定。
2. 自己的稿件下长按操作按钮 → 菜单出现「置顶评论」「删除评论」；别人的评论区（非 UP/staff）→ 只有「删除评论」。
3. 置顶后本条正文出现「[置顶]」前缀且是主色，列表里**有且只有一条**带前缀。
4. 已有一条置顶时置顶另一条 → 旧条目前缀消失、新条目前缀出现（换置顶，不该出现两条）。
5. 长按「取消置顶」→ 前缀消失。
6. 置顶一条二级评论（楼中楼）→ 提示「只能置顶一级评论」（12030）。
7. 点「删除评论」→ 弹二次确认；确认后该行消失、剩余行不错位；在评论详情页删掉根评论应返回上一页。
8. 删除弹窗点「取消」→ 什么都不发生（不请求、不刷新）。
9. 断网状态下置顶/删除 → 只报错提示，不崩、不改变界面状态。
10. 手表端操作用表冠滚动 + 触摸长按，确认菜单在小屏上可点、不会被裁掉。

### 交叉引用

- 调研报告 §12.4 的 C3 行改为「删除 + 置顶/取消置顶已实现（26.10.04 批次 6）」。
- `docs/architecture-map.md` 的 `ReplyApi` 方法表补齐 `dislikeReply`/`deleteReply`/`topReply`/`getRepliesLazy`/`getReplyCount`/`sendDynamicReply`。
- 接口依据：`bilibili-API/docs/comment/action.md:399-455`（置顶）、`:285-328`（点踩，批次 2）。
- 批次 6 进度：C3（本条）→ C4 → C6b → C7 → C8 → C9 → C10 → C27。

---

## 二十、26.10.04 批次 6（2/8）：评论楼中楼排序（C4）

对应调研报告 §12.4 的 C4「评论楼中楼排序 / 定位」。

### 为什么原来那个排序开关是假的

评论详情页（`activity/reply/ReplyInfoActivity.kt`，楼中楼）自带一个「排序」按钮，但**三层叠加把它彻底废掉了**：

1. 接口层：`/x/v2/reply/reply` 的参数**只有 `type`/`oid`/`root`/`ps`/`pn`**，**没有 sort / mode**（`bilibili-API/docs/comment/list.md:1559-1665`，`:1567` 明确写「按照回复顺序排序」；`ps` 定义域 1-49 但每页 `data.replies` 最多返回 20 条）。`ReplyApi.getReplies` 虽然把 `&sort=` 拼进了 URL，服务端不认，切 0↔1 拿回来的顺序完全一样。
2. 适配器层：`ReplyAdapter` 在 `isDetail` 时把这个按钮设成了 `View.GONE`——详情页根本看不见它。
3. 文案层：按钮文字取 `sorts[sort]`，而主列表的 `sorts` 表是 `{"未知排序","未知排序","时间排序","热度排序"}`；详情页用的是 0/1 两档，正好落在两个「未知排序」上。

### 改动

| 位置 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/model/Reply.java` | 新增常量 `SORT_TIME = 0`、`SORT_LIKE = 1`；新增**纯函数** `sortReplies(List<Reply> replies, int sort, int fromIndex)`——`SORT_LIKE` 时对 `subList(fromIndex, size)` 按 `likeCount` 降序（`Collections.sort` 稳定，同热度保持时间序），其余取值直接返回；`import java.util.Collections` |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/ReplyAdapter.kt` | 详情页把排序按钮显示出来（`VISIBLE`）；文案抽成 `sortLabel()`（详情页 0/1 → 「时间排序/热度排序」，主列表 2/3 → 同一张 `sortNames` 表）；`listener` 调用统一为 `listener?.onItemClick(0)` |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/reply/ReplyInfoActivity.kt` | `setOnSortSwitch` 改为**不重新请求**：切档后同步 `replyAdapter.sort` 并调 `applySort()`；新增 `applySort()`（`Reply.sortReplies(list, sort, 1)` + `notifyDataSetChanged`）；`refresh()` 末尾改调 `applySort()`；`continueLoading()` 在 `SORT_LIKE` 下整体重排、其余情况维持 `notifyItemRangeInserted`；顺手清掉本文件 4 处 `Log.e("debug", …)` 与 `import android.util.Log`（批次 3 的 B5 清理遗漏） |

### 取舍

- **只排「已经加载到本地」的评论**：服务端不给排序参数，就只能排当前这一页（含翻页累积）。翻到下一页时把新数据并进来重排，所以「热度排序」在翻页后顺序会整体变化，这是这个方案固有的。
- **「按时间」不做任何本地重排**：接口返回顺序本身就是回复顺序，动它反而可能把顺序弄乱。**没有用 `floor` 排**——该字段在部分评论区不存在（`bilibili-API/docs/comment/readme.md:56` 注明「若不支持楼层则无此项」），用它排会出现「不支持楼层时整片乱序」。
- **切档不再重新请求**（改掉原 `refresh()`）：重排是本地纯计算，重新请求只会拿回同样的服务端顺序，白等一次网络。
- **`fromIndex` 固定传 1**：详情页第 0 位是根评论（所有楼中楼回复的父评论），不能参与排序，否则根评论会被排进子评论里。
- **翻页路径分开处理**：`SORT_LIKE` 时新页要并入全局排序、插入位置不再是「接在末尾」，只能整体重排重绑；`SORT_TIME` 时保留原来的 `notifyItemRangeInserted`（局部插入更省）。
- **「定位到某条评论」不做**：C4 的另一半要走 `seek_rpid` + `min_floor`（`ReplyInfoActivity` 已有的 `rpid` 就是走 `seek_rpid`），单独页面重构，当前没必要。

### 单测

`app/src/test/java/com/RobinNotBad/BiliClient/model/ReplySortTest.kt`（新建，6 例）：降序、同热度稳定（保持时间序）、`SORT_TIME` 不动列表、`fromIndex` 之前的元素不动（根评论留住且 `assertSame`）、null/空/单元素/越界与负数 fromIndex 都不抛、未知档位（2/3，主列表用的服务端档位）不动列表。

**中途失败一次**：`sortReplies_likeMode_ordersByLikeCountDesc` 我按「全局降序」断言，但用例传的是 `fromIndex = 1`，第 0 位本来就该不动——改成 `fromIndex = 0` 后通过。这个用例反而证明了 `fromIndex` 生效。

**验证**：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → BUILD SUCCESSFUL；`app/build/test-results/testDebugUnitTest` **30 个 XML / 244 个用例 / 0 失败 / 0 错误**（C3 后 238 例，本次 +1 类 +6 例）。

### 真机验证清单

1. 进任意评论的楼中楼详情页 → 第 2 项上方能看到「时间排序」按钮（以前是隐藏的）。
2. 点一下 → 按钮变「热度排序」，列表**立即**重排（不出现转圈刷新），点赞多的排前面。
3. 再点一下 → 回到「时间排序」，顺序变回原来的回复顺序（**注意**：不是动画回滚，是重排结果）。
4. 在「热度排序」下滚到底加载下一页 → 新页内容并入整体排序，不会出现「新页自己排一段」。
5. 根评论（第 1 项）在任何排序下都留在最上面，不会被排到子评论中间。
6. 在楼中楼里点赞一条评论再切排序 → 该条的点赞数/图标状态不丢（排序只是重绑，不改数据）。

### 交叉引用

- 调研报告 §12.4 的 C4 行已改写。
- `docs/architecture-map.md` 新增 §7.17（主列表走服务端排序、楼中楼只能客户端排，两种档位别共用文案表）。
- 接口依据：`bilibili-API/docs/comment/list.md:1559-1665`（`/x/v2/reply/reply` 参数表与 20 条上限）、`:876`（`/x/v2/reply/wbi/main` 的 `mode`）、`:18`（`/x/v2/reply` 的 `sort`）、`bilibili-API/docs/comment/readme.md:56`（`floor` 字段）。
- 批次 6 进度：C3（§十九）→ C4（本条）→ C6b → C7 → C8 → C9 → C10 → C27。

---

## 二十一、26.10.04 批次 6（3/8）：带图评论的三个静默出错（C6b）

对应调研报告 §12.4 的 C6 行（「上传/发送无进度无反馈」）。

### 问题：不是「没有反馈」这么简单

勘察后纠正了原台账的描述——代码**本来就有**失败提示（「图片上传失败」「图片处理失败」）和发送结果提示（「发送成功>w<」「评论发送失败：…」）。真正的问题是**三个静默出错**：

1. `activity/reply/WriteReplyActivity.kt` 的 `addImage()` 在 `imageList.add()` 之后立刻调 `updateImageText()`，按钮马上显示「图片(1)」，但压缩 + 上传还在后台线程跑；界面没有任何「进行中」的迹象，用户以为已经可以发了。
2. **图还没传完就点发送**：`buildPictures()` 只遍历 `uploadDataList`（上传成功的那些），评论会**少图发出且没有任何提示**；更糟的是原发送成功分支会把后到的 `uploadDataList` 里的 url 补进本地 `resultReply.pictureList`，于是**本地显示的图比实际发出去的还多**。
3. 原来的 `sent` 是**请求返回之后**才置 `true`（失败置回 `false`），而 `else MsgUtil.showMsg("正在发送中")` 只在 `sent == true` 时可达——也就是**发送等待期根本没有闸门**，连点会把同一条评论发出两遍。

### 改动（第 1 步，零布局改动）

| 位置 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/reply/WriteReplyActivity.kt` | ① 字段：`sent` → `@Volatile private var sending`（点下即置位、后台 `finally` 里松开），新增 `pendingUploads`（仍在上传的图片张数；读写在主线程故不加锁）。② 发送入口：先判 `sending`（→「正在发送中」）→ 再判 `ReplyApi.canSendReply(pendingUploads)`（→「还有 k 张图片正在上传，请稍候」并 return）→ 进后台先弹「正在发送…」→ 整段包 `try/finally`，成功、失败、提前 return 都会松开闸门。③ `addImage()`：`pendingUploads++` 后再 `updateImageText()`；`finally { runOnUiThread { pendingUploads--; updateImageText() } }`（不管成功失败都要归零，否则发送会被永久拦住），两个失败分支里重复的 `updateImageText()` 删除。④ `updateImageText()`：有图在上传时显示「图片(n)・上传中(k)」 |
| `app/src/main/java/com/RobinNotBad/BiliClient/api/ReplyApi.java` | 新增两个纯函数：`canSendReply(int pendingUploads)`（<=0 才允许发送）、`uploadPendingTip(int pendingUploads)`（「还有 k 张图片正在上传，请稍候」，无待传时返回空串）。放在 api 层是因为同文件已有 `actionErrorMsg(int)` 这种「用户可读文案」的先例，且纯函数才好单测 |
| `app/src/test/java/com/RobinNotBad/BiliClient/api/ReplyApiTest.kt` | +2 例（见下） |

### 取舍

- **只做第 1 步**（用户拍板）：不加进度条、不改布局。`res/layout/activity_write_reply.xml` 里唯一能承载状态的控件就是 image 按钮上的 `imageText`，所以「进行中」用按钮文字 + toast 表达。
- **「正在发送」用 toast 而不是 `MsgUtil.createSnack(LENGTH_INDEFINITE)`**：后者需要一个可 dismiss 的 View 句柄，而发送成功会 `finish()`，多一个句柄就多一处悬挂/泄漏风险；发送本身只有一次网络往返，toast 足够。
- **没做按字节的真进度条**：那需要新写 `ProgressRequestBody`（OkHttp 4.12 的 `RequestBody` 子类 + 8KB 分块 + 200ms 节流，范式见 `util/UpdateManager.kt:238-290`）并给 `uploadReplyImage` 加重载签名，属第 2 步。
- **闸门是「点下即置位」而不是「期间禁用按钮」**：不改布局；用户再点只会看到「正在发送中」。
- **没顺手做**（发现但保持独立）：`api/ReplyApi.java` 的 `deleteReply` 里还留着 `Log.e("debug-点赞评论", …)`；`activity/reply/ReplyFragment.kt:241` 还有 `Log.e("debug", …)`。都不属于 C6b，留待专门的清理。

### 单测

`api/ReplyApiTest.kt` +2 例：`canSendReply_blocksWhileImagesAreStillUploading`（0 与 -1 放行，1 与 3 拦住）、`uploadPendingTip_countsAndIsEmptyWhenClear`（0/-1 返回空串，带张数，并钉死完整文案「还有 3 张图片正在上传，请稍候」）。

### 验证

`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → BUILD SUCCESSFUL；`app/build/test-results/testDebugUnitTest` **30 个 XML / 246 个用例 / 0 失败 / 0 错误**（C4 后 244 例，本次 +2 例）。

### 真机验证清单

1. 选一张大图 → 按钮立刻变「图片(1)・上传中(1)」，上传完成后回到「图片(1)」。
2. 图还在上传时点发送 → 提示「还有 1 张图片正在上传，请稍候」，**评论不会发出去**。
3. 图传完后点发送 → 先弹「正在发送…」，再「发送成功>w<」并返回上一页。
4. 发送过程中连点发送 → 只弹「正在发送中」，服务端只多一条评论。
5. 选一张超过 25MB 的图触发「图片上传失败」→ 张数与「上传中」计数都回退，之后仍能正常发送。
6. 选图后不输入文字直接发送 → 图能正常带出（只有文字与图片都为空才拦「还没输入内容呢~」）。
7. 一条评论发送失败（如频繁）后再点发送 → 能再发（闸门已松开，不会被永久挡住）。

### 交叉引用

- 调研报告 §12.4 的 C6 行已改写；`docs/architecture-map.md` 新增 §7.18。
- 第 2 步的落点（未做）：`api/ReplyApi.uploadReplyImage`（`app/src/main/java/com/RobinNotBad/BiliClient/api/ReplyApi.java:218/231`）与小图压缩 `api/ImageApi.java:107-137`（`WriteReplyActivity` 的私有 `prepareImage` 是它的重复实现）。
- 批次 6 进度：C3（§十九）→ C4（§二十）→ C6b（本条）→ C7 → C8 → C9 → C10 → C27。

---

## 二十二、26.10.04 批次 6（4/8）：动态编辑与管理菜单（C7）

### 为什么做

调研台账的 C7 原本只写「想要实现」，理由是本地没有编辑接口的记录；后来的结论是**查 PiliPlus 源码**：它走 `POST https://api.bilibili.com/x/dynamic/feed/edit/dyn`（WBI 签名），快照里没有这个接口不代表服务端没有。用户为此拍板「你看看piliplus是怎么实现的，对齐他」，所以本条按 PiliPlus 的 `DynamicsHttp.editDyn` 对齐实现。

顺带解决另一个问题：列表里「管理」这条动态原来只有**两次长按（间隔 <10 秒）删除**一种入口——删除是危险操作却用双击防误触，而编辑、置顶这类操作根本没有位置放。C7 把它换成**长按弹 `AlertDialog` 菜单**，菜单项按服务端下发的 `three_point_items` 动态生成，C8 的置顶后面可以直接往这个菜单里加。

### 改动表

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/api/DynamicApi.java` | 新增 `editDynamic(long dynId, JSONArray contents, JSONArray pics, JSONObject option, JSONObject topic, int scene)`：`/x/dynamic/feed/edit/dyn` + `signWBI`；新增纯函数 `buildUploadId(mid, seconds, random)`、`editErrorMsg(code)`；新增常量 `EDIT_DYN_META`/`EDIT_DYN_DEVICE_JSON`；`analyzeDynamic` 里新增 `dynamic.canEdit = supportItemTypes.contains("THREE_POINT_EDIT")` |
| `app/src/main/java/com/RobinNotBad/BiliClient/model/Dynamic.java` | 新增 `public boolean canEdit`（编辑与删除是两个独立开关，不能互相顶替） |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/dynamic/DynamicHolder.kt` | 三个 `getDeleteListener` 全部替换为 `getManageListener`（列表 4/5 参重载 + 详情 2/3 参重载）；新增 `showManageMenu`（`AlertDialog` 菜单，按 `canEdit`/`canDelete` 拼项）、`launchEdit`、`confirmDelete`；`showDynamic` 里「管理」入口点一下等于长按（弹同一个菜单，不另维护回调） |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/dynamic/DynamicAdapter.kt` | 改调 `getManageListener`，可见条件 `canDelete \|\| canEdit` |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/dynamic/UserDynamicAdapter.kt` | 同上 |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/dynamic/DynamicInfoFragment.kt` | 同上；编辑成功后原地重画这张卡片 |
| `app/src/main/res/layout/cell_dynamic.xml` | `item_dynamic_delete` 文案「删除」→「管理」 |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/base/BaseActivity.kt` | 新增 `editDynamicLauncher` 与 `pendingDynamicEdit: ((String) -> Unit)?` 回调槽 |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/dynamic/DynamicActivity.kt` | 新增 `getEditDynamicLauncher(activity)`：结果回来时先取走并清空回调槽，非 `RESULT_OK`/无 `edit_dyn_id`/无 `text` 直接忽略 |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/dynamic/send/SendDynamicActivity.kt` | 新增编辑模式：读 `edit_dyn_id`，预填正文、**跳过 `TerminalContext` 的转发卡片**、隐藏投票与带图入口；`submitEdit(text)` 在页内直接调 `editDynamic`，成功回传 `edit_dyn_id` + 新正文 |

### 取舍

- **复用发布页而不是新开编辑页**：正文编辑、@ 识别（`Pattern.compile("@(\\S+)\\s")` + `DynamicApi.mentionAtFindUser`）、表情拆分（`EmoteApi.getEmoteTexts` + `buildContents`）在 `SendDynamicActivity` 里已经齐全，新页只会造出第二份真相。
- **编辑模式必须绕开 `TerminalContext.getForwardContent()`**：该字段只在 `SendDynamicActivity.onDestroy` 里清，若上一次转发没走完就被清掉，编辑页会把旧转发卡片画出来，还会把 `addPic` 误判成不可用。所以编辑分支里 `forwardContent = null`。
- **编辑不带投票、不带图**：编辑接口接受 `pics`/`option`，但本项目投票走 `attach_card`、图片要重新上传，语义与发布不完全一致，不在本条范围内，因此直接隐藏这两个入口（宁可少功能，不要让用户以为能改）。
- **回调走 `Activity` 上的一个槽位**：`ActivityResultLauncher` 只能在 Activity 上注册一次，没有 per-holder 的注册点，而「编辑完怎么刷新这一条」只有发起方（适配器/详情页）知道。槽位在结果回来时**先取走再清空**，避免下一次编辑调到上一次的回调。
- **编辑是有损的**：`Dynamic` 没有原始正文，只能用 `content.toString()` 预填；WEB 节点只存 `orig_text`（URL 丢了）、@ 与表情退化成文本形式，保存后按纯文本重发。这是已知取舍，进真机清单。
- **没顺手做**（保持独立）：`DynamicApi.deleteDynamic` 仍是老的 `rm_dynamic`（新版是 `/x/dynamic/feed/operate/remove`，本条不动）；`DynamicActivity.kt:187/:204` 的 `Log.e("debug", …)` 仍在。

### 单测

新建 `app/src/test/java/com/RobinNotBad/BiliClient/api/DynamicApiTest.kt` 6 例：`buildUploadId_joinsMidSecondsAndRandomWithUnderline`、`buildUploadId_isDeterministicForSameInput`、`editErrorMsg_successIsEmpty`、`editErrorMsg_mapsEveryAuthFailureToRelogin`（-101/-102/-111）、`editErrorMsg_explainsKnownBusinessErrors`（-400/-403/-404/-509）、`editErrorMsg_unknownCodeStillShowsTheCode`。

### 验证

`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → BUILD SUCCESSFUL；`app/build/test-results/testDebugUnitTest` **31 个 XML / 252 个用例 / 0 失败 / 0 错误**（C6b 后 246 例，本次 +6 例）。

### 真机验证清单

1. 自己的纯文本动态 → 长按列表项（或点「管理」）弹菜单，有「编辑动态」与「删除动态」两项。
2. 别人的动态（或已超时可编辑期的）→ 菜单里**不出现**「编辑动态」；一条都没有时提示「没有可操作的项」。
3. 编辑一条带 @ 的动态 → 编辑页能打开、正文预填正确；保存后列表里的正文**立刻变成新内容**（本地先替换，不回拉服务端）。
4. 编辑一条带表情/网页链接的动态 → 预填文本里表情显示成 `[xxx]`、链接只剩标题文字；保存后服务端正文也变成这个纯文本形式（**已知有损**）。
5. 编辑时把正文清空 → 提示「正文不能为空」，不发请求。
6. 编辑页里确认没有图片入口与投票入口。
7. 编辑成功后返回列表，下拉刷新 → 服务端返回的正文与刚才提交的一致（说明 `signWBI` 的 query 签名与 body 都被接受）。
8. 编辑失败（如凭证过期）→ 弹「登录凭证已失效，请重新登录」，页面**不退出**、可以改完再发。
9. 删除那条走菜单 → 仍然二次确认「删除后无法恢复，确定删除这条动态吗？」，成功后从列表移除。
10. 详情页（动态详情）里做编辑 → 保存后详情页原地重画；删除 → 带着结果退出，返回列表时那一条也已消失。

### 交叉引用

- 调研报告 §12.4 的 C7 行已改写为「已实现（对齐 PiliPlus）」，C8 行同步说明删除入口已改为管理菜单；`docs/architecture-map.md` 新增 §7.19。
- 接口依据：PiliPlus `DynamicsHttp.editDyn`（`/x/dynamic/feed/edit/dyn`）；本地快照 `bilibili-API/docs/opus/features.md:25` 与 `data.module_more.three_point_items[]` 证明服务端会下发 `THREE_POINT_EDIT`。
- 批次 6 进度：C3（§十九）→ C4（§二十）→ C6b（§二十一）→ C7（本条）→ C8 → C9 → C10 → C27。

---

## 二十三、26.10.04 批次 6（5/8）：动态置顶与取消置顶（C8）

### 为什么做

`model/Dynamic.java` 的 `isTop` 从动态解析里就有（读 `module_tag.text == "置顶"`），但**全库没有一处 UI 读它**，用户既看不到「这条是置顶」也改不了置顶状态。C7 刚把列表项入口换成了「管理」菜单，正好把置顶放进去——这也是 C7 那条改动预留的位置。

### 改动表

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/api/DynamicApi.java` | 新增 `setDynamicTop(long dynId, boolean top)`（`POST /x/dynamic/feed/space/set_top` 或 `/rm_top`，正文 `{"dyn_str":"<id>"}`、csrf 走 query）；新增纯函数 `topPath(boolean)`、`topSuccessMsg(boolean)`、`topErrorMsg(int)` |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/dynamic/DynamicHolder.kt` | `showManageMenu` 增加 `onChanged` 参数；新增置顶菜单项（按 `isTop` 切「置顶动态/取消置顶」，门槛 `canDelete`）与 `toggleTop(activity, dynamic, onChanged)`；列表版监听器把 `onChanged` 接成 `notifyItemChanged` |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/dynamic/DynamicInfoFragment.kt` | 详情版监听器加 `onChanged`，置顶成功后原地重画这张卡片 |

### 取舍

- **置顶入口的门槛用 `canDelete`**：客户端拿不到「这条是不是我的」之外更可靠的信号（别人的动态不下发 `THREE_POINT_DELETE`，且置顶只对自己的空间有意义），所以用同一个开关当门槛，并写在注释里。
- **成功后本地翻 `isTop` 并刷新那一条**：服务端只回 `code`，不回新动态；不刷新的话用户再次长按看到的还是旧文案。首页/空间里的「置顶」标记来自服务端 `module_tag`，下拉刷新会回到真实状态。
- **不做「置顶到空间顶部」那种二次确认**：置顶可逆、且再置顶会顶掉原来那条是服务端行为，菜单里已经是显式点选，不再多一层弹窗。
- **不换删除接口**：新版是 `/x/dynamic/feed/operate/remove`，与本条无关，`deleteDynamic` 仍是老的 `rm_dynamic`。

### 单测

`api/DynamicApiTest.kt` +5 例：`topPath_switchesBetweenSetAndRemoveTop`、`topSuccessMsg_matchesTheDirection`、`topErrorMsg_successIsEmpty`、`topErrorMsg_explainsKnownCodes`（-101 / -102 / -111 / 4100001 / -404）、`topErrorMsg_unknownCodeStillShowsTheCode`。

### 验证

`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → BUILD SUCCESSFUL；`app/build/test-results/testDebugUnitTest` **31 个 XML / 257 个用例 / 0 失败 / 0 错误**（C7 后 252 例，本次 +5 例）。

### 真机验证清单

1. 自己的动态长按（或点「管理」）→ 菜单里有「置顶动态」；别人的动态**没有**这一项。
2. 点「置顶动态」→ 弹「置顶成功~」，该条刷新后带「置顶」标记。
3. 再长按同一条 → 菜单文案变成「取消置顶」。
4. 点「取消置顶」→ 弹「已取消置顶~」，标记消失。
5. 置顶 A 再置顶 B → A 的置顶被顶掉（服务端只保留一条），下拉刷新后与页面显示一致。
6. 在**别人的空间**（用户动态页）长按自己的动态 → 菜单与上面一致，不崩。
7. 断网 / 凭证过期时点置顶 → 弹「操作过于频繁」「登录凭证已失效，请重新登录」之类的文案，**本地 `isTop` 不变**。
8. 详情页里做置顶 → 原地重画且标记正确。

### 交叉引用

- 调研报告 §12.4 的 C8 行改为「已全部实现」；`docs/architecture-map.md` §7.19 补了置顶这一段。
- 接口依据：`bilibili-API/docs/dynamic/action.md:233-292`（set_top）、`:294-317`（rm_top），两处正文参数都只有 `dyn_str`。
- 批次 6 进度：C3（§十九）→ C4（§二十）→ C6b（§二十一）→ C7（§二十二）→ C8（本条）→ C9 → C10 → C27。

---

## 二十四、26.10.04 批次 6（6/8）：动态定时发布（C9）

### 为什么做

`api/DynamicApi.java` 的 `buildPublishOption(boolean, Integer, Integer, String)` 早就把 `timer_pub_time` 拼进去了，但**全库零调用点**——是个死函数；页面里也没有任何定时入口。这次按用户拍板接线（A 方案：接真接口，真机验证；兜底才是草稿箱）。

### 改动表

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/api/DynamicApi.java` | `buildPublishOption` 的 `timerPubTime` 由 `String` 改成 `Integer`（秒级时间戳）并订正 javadoc；新增纯函数 `timerSecondsAt(long nowSeconds, int addMinutes)` |
| `app/src/main/res/layout/activity_send_dynamic.xml` | 投票卡片之前新增 `add_timer` 卡片 + `add_timer_text` |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/dynamic/send/SendDynamicActivity.kt` | 新增 `timerPubTime` 字段、`showTimerPicker()`/`tomorrowNoonSeconds()`/`applyTimer()`/`updateTimerText()`；`add_timer` 按 `normalPublish` 显隐；结果 intent 在（无图 / 带图）两条分支都带 `timerPubTime` |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/dynamic/DynamicActivity.kt` | `writeDynamicLauncher` 读 `timerPubTime`，> 0 时用 `buildPublishOption(false, null, null, seconds)` 组 `option` 并传进四条发布链路；定时成功改提示「已设置定时发布~」且**跳过本地插入** |

### 取舍

- **只给固定档，不做自由输入**：10/30/60/120 分钟后、明天 12:00、不定时。手表上打字选日期时间不现实，项目里也没有 DatePicker/TimePicker 先例，六项 AlertDialog 点一下就定。
- **`timer_pub_time` 必须是 int 时间戳**：老注释写的 `yyyy-MM-dd HH:mm` 是错的（上游 web 端与 PiliPlus 传的都是 int），这次连类型带注释一起改掉，并在单测里把「是 Int」钉死。
- **只在普通发布时露出**：转发走 `relayDynamic`（没有 option），编辑接口也没有定时字段，所以 `add_timer` 与 `add_pic` 一样按 `normalPublish` 显隐。
- **定时成功后不插本地列表**：动态此刻还没真正发出去，`getDynamic` 拿回的状态不对，插进去只会显示一条「将来才发」的动态；提示后由下拉刷新兜底。
- **失败没有精细文案**：`publishComplex` 失败只回 -1，页面显示「发送失败」，所以「离现在太近被拒」这种情况只能靠真机确认（见清单第 1 条）。

### 单测

`api/DynamicApiTest.kt` +5 例：`buildPublishOption_writesTimerAsIntegerSeconds`、`buildPublishOption_withoutTimerHasNoTimerKey`、`buildPublishOption_keepsOtherFlags`、`timerSecondsAt_addsMinutes`、`timerSecondsAt_ignoresNonPositiveMinutes`。

### 验证

`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → BUILD SUCCESSFUL；`app/build/test-results/testDebugUnitTest` **31 个 XML / 262 个用例 / 0 失败 / 0 错误**（C8 后 257 例，本次 +5 例）。布局改了 id（未增删资源文件），无需 clean。

### 真机验证清单

1. **先试最近的一档（10 分钟后）**：若能发出去（返回动态 id、提示「已设置定时发布~」），说明服务端接受近时间；若提示「发送失败」，说明服务端有最小提前量限制，需要把档位改大或改走草稿箱。
2. 选「30 分钟后」→ 按钮文案变成「定时：MM-dd HH:mm」，且时间与当前时间相差约 30 分钟。
3. 选「明天 12:00」→ 文案是明天的 12:00（跨天正确）。
4. 选「不定时」→ 文案回到「定时发布」，再发就是立即发布。
5. 定时 + 带图：先选图再定时，发送后图片正常上传、结果同样提示定时。
6. 定时 + 投票：选投票再定时，发送后投票能创建（`VoteApi.createVote` 先走），动态为定时状态。
7. 编辑模式下**看不到**「定时发布」入口；转发模式下也看不到。
8. 定时发送后下拉刷新：动态**不会**立刻出现在列表里（服务端未发布）；到点后下拉刷新才出现。
9. 未登录 / 凭证过期时走一遍：仍然先被 `cookie_refresh` 闸门或发布失败拦住，不会静默成功。
10. 到点后到 B 站客户端/网页确认动态确实发出且正文、图片、投票与预期一致。

### 交叉引用

- 调研报告 §12.4 的 C9 行改为「已实现」；`docs/architecture-map.md` 新增 §7.20「定时发布」。
- 接口依据：`bilibili-API/docs/dynamic/publish.md`（`option` 字段）；旧注释的 `yyyy-MM-dd HH:mm` 来自本仓库自己写错，非快照内容。
- 批次 6 进度：C3（§十九）→ C4（§二十）→ C6b（§二十一）→ C7（§二十二）→ C8（§二十三）→ C9（本条）→ C10 → C27。

---

## 二十五、26.10.04 批次 6（7/8）：话题广场与话题动态列表（C10）

### 为什么做

调研把「动态话题页」列为想要实现，但一直没做，原因是**话题 id 拿不到**：动态正文里的话题节点（`RICH_TEXT_NODE_TYPE_TOPIC`）只带一个搜索页跳转链接，反推不出 `topic_id`。所以 C10 不能只做「点正文里的话题进话题页」，必须**自建话题广场作为 id 来源**——这一点已由用户拍板（A 方案：话题下动态列表 + 自建话题广场入口，发布器不加话题选择）。

### 改动表

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/model/Topic.java` | **新增**：`id`/`name`/`discuss`/`dynamics`/`view` |
| `app/src/main/java/com/RobinNotBad/BiliClient/api/TopicApi.java` | **新增**：纯解析 `parseTopic(JSONObject)` / `parseTopics(JSONArray)`，接口 `getRecommendedTopics()`（`x/topic/web/dynamic/rcmd`）与 `getTopicDynamicList(list, topicId, offset)`（`x/polymer/web-dynamic/v1/feed/topic`，query 过 WBI，解套壳后交给 `DynamicApi.analyzeDynamic`） |
| `app/src/main/res/layout/cell_topic.xml` | **新增**：广场一项的卡片（`topic_name` + `topic_stats`） |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/dynamic/TopicAdapter.kt` | **新增**：广场列表 adapter，点击回调交回 `Topic` |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/dynamic/TopicDynamicAdapter.kt` | **新增**：话题动态列表 adapter（第 0 位头部 `#话题名`，复用 `DynamicHolder` + 管理菜单） |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/dynamic/DynamicTopicActivity.kt` | **新增**：一个 Activity 两种形态（有无 `topic_id`），继承 `RefreshListActivity` |
| `app/src/main/AndroidManifest.xml` | 注册 `.activity.dynamic.DynamicTopicActivity`（`exported="false"` + `screenOrientation="locked"`） |
| `app/src/main/res/layout/cell_dynamic_action.xml` | 动态页动作卡片新增整行按钮 `topic`（「话题广场」） |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/dynamic/DynamicAdapter.kt` | `WriteDynamic` 持 `topic` 按钮并绑点击 → 打开话题广场 |

### 取舍

- **广场入口放在动态页动作卡片，不进 `MenuConfig`**：`util/MenuConfig.kt` 的 `loadEnabled` 对老用户已存的 `menu_enabled` 直接返回，新增菜单 key **不会自动出现**，只有新用户/清过设置的人能看到；塞进动态页动作卡片则人人可见。
- **一个 Activity 两种形态**：广场与话题列表共用一个 `DynamicTopicActivity`，广场点一项就用同一个类再 `startActivity` 一次（带 `topic_id`/`topic_name`），返回即回广场。省掉一个 Activity、一套布局和一次注册。
- **解析必须“解套壳”**：话题列表接口返回的 `items[]` 是 `{dynamic_card_item, topic_type}`，动态本体在内层；解析内层时复用已有的 `public static Dynamic analyzeDynamic(JSONObject)`，不重复那 200 行。
- **保留第 0 位头部占位**：`TopicDynamicAdapter` 的 `getItemCount() = dynamicList.size + 1`。不只是为了显示 `#话题名`——`DynamicHolder.getManageListener` 列表版在置顶/编辑成功后按 `realPosition + 1` 反推要刷新的行，去掉头部会刷错行；翻页通知起点因此是 `lastSize + 1`。
- **没有复用 `DynamicAdapter`/`UserDynamicAdapter`**：前者硬绑 `context as DynamicActivity`，后者要 `UserInfo` 且第 0 位是用户信息头；话题页两者都不满足，只能新写，但 item 布局与 `DynamicHolder` 直接复用。
- **不做发布器的话题选择**（用户拍板）：带话题发布要先有 id，而正文里拿不到 id；做话题搜索/选择器属另一件事。

### 单测

`api/TopicApiTest.kt` **4 例**：`parseTopic_readsEveryField`、`parseTopic_toleratesMissingFieldsAndNull`、`parseTopics_skipsNullEntriesAndItemsWithoutId`、`parseTopics_toleratesNullOrEmptyArray`。网络请求部分不测（与既有 api 测试一致）。

### 验证

`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → **BUILD SUCCESSFUL in 1m 4s**；`app/build/test-results/testDebugUnitTest` **32 个 XML / 266 个用例 / 0 失败 / 0 错误**（C9 后 262 例，本次 +4 例）。新增了 `cell_topic.xml`（资源文件集合变化），本次未触发 build cache 回放陈旧资源（若报 `Unresolved reference 'R.layout.cell_topic'` 就走 AGENTS.md 的 clean + `--no-build-cache` 两步）。乱码自检 `git diff | Select-String '鐐|璇|鍒|锛|銆|鎴|鏂|锟'` 计数 0。

### 真机验证清单

1. 动态页顶部动作卡片能看到「话题广场」按钮，点进去是推荐话题列表（9 条），每项显示 `N 动态 · M 浏览`。
2. 广场下拉刷新不崩、不显示空视图（除非服务端返回空）。
3. 点一个话题 → 进入该话题的动态列表，顶栏标题是话题名，列表第 0 项显示 `#话题名`。
4. 话题列表滚动到底能继续翻页；`has_more=false` 后不再请求（不再触发 loading）。
5. 话题里点一条动态能进详情；返回后列表位置正常。
6. 话题里长按自己动态的「管理」按钮，菜单里「编辑/删除」可用；启用后该条正确刷新（验证头部占位 +1 的行号没算错）。
7. 话题为空 / 接口失败时显示空视图，点重试能恢复。
8. 未登录状态下广场与话题列表仍可浏览（这两个接口不要求登录）；若 401 类错误，走 `loadFail` 提示而不是白屏。
9. 手表上（小屏）广场项与动态卡片排版不重叠，`topic_stats` 不折行。
10. 从话题页返回后回到广场（不是回到动态页），再返回才回动态页。

### 交叉引用

- 调研报告 §12.4 的 C10 行改为「已实现」；`docs/architecture-map.md` 新增 §7.21「话题」。
- 接口依据：`bilibili-API/docs/dynamic/topic.md:3-54`（话题动态列表，`items[]` 套壳结构）与 `:5314-5356`（推荐话题 `topic_items[]`）。
- 批次 6 进度：C3（§十九）→ C4（§二十）→ C6b（§二十一）→ C7（§二十二）→ C8（§二十三）→ C9（§二十四）→ C10（本条）→ C27（仅剩这一项）。

---

## 二十六、26.10.04 批次 6（8/8）：视频笔记查看（C27）

### 为什么做

调研把「笔记」列为想要实现，用户拍板**范围限定仅「查看」**（不做创建/编辑，也不做我的笔记列表页）。改之前全库没有任何笔记代码（`grep 笔记|NoteApi|note_id` 于 `app/src/main/java` 零命中）。难点是**正文不是 HTML 而是 Quill delta 的 JSON 字符串**，且接口文档自己的示例就暴露了 `note_id` 的精度丢失——这两个坑决定了实现方式。

### 改动表

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/model/Note.java` | **新增**：`noteId`（**字符串**，只认 `note_id_str`）/`title`/`summary`/`videoTitle`/`videoDesc`/`aid`/`bvid`/`blocks` |
| `app/src/main/java/com/RobinNotBad/BiliClient/model/NoteBlock.java` | **新增**：`TYPE_TEXT`/`TYPE_IMAGE`/`TYPE_TAG` + 样式字段（bold/underline/strike/color/background/list）与图片、tag 字段 |
| `app/src/main/java/com/RobinNotBad/BiliClient/api/NoteApi.java` | **新增**：纯解析 `parseNoteIds`/`pickNoteId`/`parseBlocks`/`parseNoteDetail`/`formatTagSeconds`/`noteErrorMsg`；网络 `getNoteIdsOfVideo(long aid)`（`x/note/list/archive` + csrf）与 `getNoteInfo(long aid, String noteId)`（`x/note/info`，不带 csrf） |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/note/NoteActivity.kt` | **新增**：`BaseActivity` 子类，登录闸门 → 取 id 列表 → 取正文 → 把 blocks 拼成一段 `SpannableStringBuilder` 渲染 |
| `app/src/main/res/layout/activity_note.xml` | **新增**：标准活动页骨架（`pageName`/`TextClock` + `RotaryScrollView`），含 `note_title`/`note_info`/`note_content`/`note_empty` |
| `app/src/main/AndroidManifest.xml` | 注册 `.activity.note.NoteActivity`（`exported="false"` + `screenOrientation="locked"` + `label="笔记"`） |
| `app/src/main/res/layout/fragment_video_info.xml` | 视频详情页新增整行 `MaterialButton id=note`（文案「笔记」） |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/video/info/VideoInfoFragment.kt` | 绑定 `note` 按钮 → `NoteActivity`（带 `aid`）；未登录时与「稍后再看」「转发」「视频摘要」一起隐藏 |
| `app/src/test/java/com/RobinNotBad/BiliClient/api/NoteApiTest.kt` | **新增**：12 例纯函数单测 |

### 取舍

- **`note_id` 一律取 `note_id_str` 并当字符串传**：快照 `bilibili-API/docs/note/list.md` 的示例里 `note_id` 是 `24508729145690110`、`note_id_str` 是 `"24508729145690112"`——17 位超过 2^53，走 JSON number 已经丢精度。`NoteApi.pickNoteId` 固定优先字符串字段、回退数字字段，`model/Note.noteId` 直接声明成 `String`。
- **不复用 opus 的解析与 adapter**：`model/OpusParagraph.java` + `adapter/article/OpusContentAdapter.kt` 处理的是**嵌套**段落结构，而笔记正文是**扁平** delta 数组（`[{attributes, insert}, …]`，`insert` 可能是字符串、`imageUpload` 对象或 `tag` 对象），结构不兼容。另写 `NoteBlock` + `NoteApi.parseBlocks`，样式是**逐片段**的（同一句话可能被拆成多个元素，逐个套 span 而非合并后处理）。
- **渲染用一段 `SpannableStringBuilder` 而不是 RecyclerView**：笔记正文是一整篇连续富文本，一个 `TextView` + span 比列表更好排版、也省掉 adapter；样式用 `StyleSpan`/`UnderlineSpan`/`StrikethroughSpan`/`ForegroundColorSpan`/`BackgroundColorSpan`。脏色值 `Color.parseColor` 包 try/catch 返回 null，不崩。
- **图片只占位、tag 只显示时间**：图片要走图床加载与宽高还原（`imageUpload.width` 还是"宽度 - 2"），视频进度 tag 要跳播放器进度——都超出「仅查看」的范畴，本次只渲染 `[图片]` 与 `[视频进度 mm:ss]`（有分P索引则 `[分P n mm:ss]`）。解析阶段**不校验** `status` 等字段，坏数据一律安全跳过。
- **入口放视频详情页，不进 `MenuConfig`**：笔记是"某个视频的笔记"，脱离视频没有意义；顺带规避了新增菜单 key 对老用户不生效的问题（见 §二十五同一条取舍）。
- **未登录双保险**：视频页按钮直接隐藏（与「稍后再看」「转发」「视频摘要」同批），`NoteActivity` 里再判一次 `mid == 0` 并提示「登录后才能看笔记喵~」（接口只返回私有笔记，未登录必失败）。
- **不做的**：我的笔记列表页（`x/note/list`）、公开笔记（`cvid` + `x/note/publish/info`）、创建/编辑笔记，全部不做。

### 单测

`api/NoteApiTest.kt` **12 例**：`parseNoteIds_readsStringIdsAndSkipsBlanks`、`parseNoteIds_toleratesMissingFieldAndNullData`、`pickNoteId_prefersTheStringForm`（用 24508729145690110 与 `"24508729145690112"` 这组真实示例钉死精度行为）、`pickNoteId_fallsBackToTheNumberField`、`parseBlocks_readsTextAndAttributes`、`parseBlocks_readsTagAndImageInserts`（tag 分P/秒数 + 图片 url/宽度，未知 insert 对象被跳过）、`parseBlocks_keepsEmptyTextOnlyWhenItIsAListItem`、`parseBlocks_toleratesBrokenInput`（null / 空串 / 非 JSON / 根是对象）、`parseNoteDetail_readsArcAndContent`、`parseNoteDetail_toleratesNull`、`formatTagSeconds_padsMinutesAndHours`（0/65/3599/3600/3725/负数）、`noteErrorMsg_mapsKnownCodes`。网络请求部分不测（与既有 api 测试一致）。

### 验证

因新增了 `res/layout/activity_note.xml`（资源文件集合变化），按 AGENTS.md 走两步：`.\gradlew.bat :app:clean --offline --no-configuration-cache` → **BUILD SUCCESSFUL in 19s**；再 `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-build-cache --no-configuration-cache` → **BUILD SUCCESSFUL in 1m 12s**。`app/build/test-results/testDebugUnitTest` **33 个 XML / 278 个用例 / 0 失败 / 0 错误**（C10 后 32/266，本次 +12 例）。乱码自检 `git diff | Select-String '鐐|璇|鍒|锛|銆|鎴|鏂|锟'` 计数 0。

### 真机验证清单

1. 视频详情页能看到整行「笔记」按钮；未登录时该按钮与「稍后再看」「转发」「视频摘要」一起消失。
2. 未登录时若强行进入 `NoteActivity`（如从别处带 `aid` 拉起），提示「登录后才能看笔记喵~」而不是空页或崩溃。
3. 登录后点「笔记」：对**有私有笔记**的视频能显示出标题与正文；对**没有笔记**的视频提示「这个视频还没有笔记」。
4. 正文里的粗体、下划线、删除线、彩色文字、高亮底色能看出来（重点验证分段样式：同一句话被拆成多个 delta 元素时样式要逐段正确）。
5. 有序/无序列表项每行带 `n. `/`• ` 前缀，且**有序编号在非列表段落之后重新从 1 开始**。
6. 正文里的图片位置显示 `[图片]` 占位（不崩、不请求不存在的图片）；视频进度位置显示 `[视频进度 mm:ss]` 或 `[分P n mm:ss]`。
7. 接口失败（如 `79503` 正文缺失、`79502` 详情缺失）时提示对应中文文案；断网时提示「获取笔记失败」。
8. 笔记很长时上下滚动顺畅（`RotaryScrollView` 正常），标题/信息区不遮挡正文；手表小屏上正文可读、不横向溢出。
9. 从视频页进入笔记再返回，视频页状态与播放位置不受影响。
10. 从笔记页右滑返回可用（未额外禁用滑动删除）。

### 交叉引用

- 调研报告 §12.4 的 C27 行改为「已实现（范围限定：仅『查看』）」；`docs/architecture-map.md` 新增 §7.22「笔记，仅查看」。
- 接口依据：`bilibili-API/docs/note/list.md:3-71`（`x/note/list/archive`）、`bilibili-API/docs/note/info.md:57-172`（`x/note/info`，错误码 79502/79503）、`bilibili-API/docs/note/readme.md:19-158`（delta 正文结构与真实示例）。
- 批次 6 进度：C3（§十九）→ C4（§二十）→ C6b（§二十一）→ C7（§二十二）→ C8（§二十三）→ C9（§二十四）→ C10（§二十五）→ C27（本条）。**批次 6 八项至此全部完成**；下一批为批次 7（C18 C19 C20 C21），其后批次 8（E2 DownloadService + F4 漫画）。

---

## 二十七、26.10.04 批次 7（1/4）：稍后再看「未看完」分类（C18）

### 为什么做

稍后再看列表混着「没看过的」「看了一半的」「已经看完的」。原先只能从头往下翻，想找「上次没看完的那几个」要靠记忆。调研报告 §12.4 的 C18 建议加分类，本次按用户拍板的范围落地：**只加一个「全部 / 未看完」筛选**，不做排序、不做自动清理。

### 改动

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/api/WatchLaterApi.java` | `getWatchLaterList` 解析时补 `card.progress = optInt("progress", 0)`、`card.duration = optLong("duration", 0)`；新增纯函数 `isUnfinished(long, long)` 与 `filterUnfinished(List<VideoCard>, boolean)` |
| `app/src/main/java/com/RobinNotBad/BiliClient/model/VideoCard.java` | 新增 `public long duration = 0;`（只本接口会填）；`Parcel` 构造器与 `writeToParcel` **成对**追加在末尾 |
| `app/src/main/res/layout/activity_simple_refresh.xml` | 新增**默认 `android:visibility="gone"`** 的 `filterBar`（两个等宽 `TextView`：`filterAll`「全部」/ `filterUnfinished`「未看完」），放在 `SwipeRefreshLayout` 外面 |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/user/WatchLaterActivity.kt` | 重写：`allList`（接口全量）+ `shownList`（交给 adapter 的引用）；`onCreate` 里点亮 `filterBar`、接管两个 chip 的点击、抓默认字色；`loadWatchLater()` 拉一次数据；`applyFilter()` 只重填 `shownList` + `notifyDataSetChanged()`；删除时两张表同改 |
| `app/src/test/java/com/RobinNotBad/BiliClient/api/WatchLaterApiTest.kt` | **新增**：7 例纯函数单测 |

### 取舍

- **判据用接口自带的 `progress`/`duration`，不查观看记录**：稍后再看接口本来就返回这两个字段（`bilibili-API/docs/historytoview/toview.md:175/183`），再打一次历史接口纯属浪费。`progress <= 0` 判为「没播过」而不是「未看完」——否则「全部未看」的视频会全挤进「未看完」，筛选就没用了。
- **总时长未知（0）时不判「已看完」**：老数据或特殊稿件可能没 `duration`，此时只按 `progress > 0` 判定。宁可放进「未看完」，也不要让用户找不到自己看了一半的稿件。
- **筛选条放列表外，不做 adapter 头部项**：`activity_simple_refresh.xml` 是**所有** `RefreshListActivity` 共用的布局，新增分组默认 `gone`，只有本页点亮，其它页面零感知。做成 adapter 头部项会改变业务 adapter 的 `viewType`/`adapterPosition` 语义——本项目已有「头部占位导致通知起点 `+1`」的坑（`docs/architecture-map.md` §7.9），不值得为一个页面再引入一次。
- **切档不重新请求**：数据一次拿全，切档只在本地重填。手表上网络慢，来回请求不如直接用内存里的那份；代价是「稍后再看」改动（在视频页添加/删除）后要重新进页面才会刷新，与原有行为一致。
- **删除后两张表同步**：`shownList` 里存的是 `allList` 的**同一个对象引用**，按对象 `allList.remove(card)`、按位 `shownList.removeAt(position)`。只删一张表的话，切档时已删条目会"复活"。
- **不改删除手势**：仍是原来的「4 秒内连点两次长按才删」。C18 只加分类，删除体验另行处理。
- **选中色不写死**：选中档位用 `ColorScheme.PRIMARY`（跟随外观设置的主题色），另一档用 `onCreate` 时从 `filterAll.currentTextColor` 抓到的默认次要色。

### 单测

`api/WatchLaterApiTest.kt` **7 例**：`isUnfinished_requiresPositiveProgress`（0 / 负数）、`isUnfinished_treatsUnknownDurationAsUnfinished`（duration 0 / 负数）、`isUnfinished_comparesProgressWithDuration`（小于 / 等于 / 大于）、`filterUnfinished_toleratesNullAndEmpty`（null / 空表 × 两档）、`filterUnfinished_skipsNullElements`（含 null 元素）、`filterUnfinished_returnsOnlyUnfinishedInOriginalOrder`（顺序保持 + 同引用）、`filterUnfinished_falseReturnsCopyOfWholeList`（false 档是拷贝，改副本不动原表）。网络部分不测（与既有 api 测试一致）。

### 验证

本次只改既有布局文件、**没有新增 `res/` 文件**，按 AGENTS.md 不需要 clean 两连：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → **BUILD SUCCESSFUL in 1m 10s**（99 tasks；pwsh 因 javac 的过时 API 注记报 `[exit code: 1]`，属已知假阳性）。`app/build/test-results/testDebugUnitTest` **34 个 XML / 285 个用例 / 0 失败 / 0 错误**（C27 后 33/278，本次 +7 例）。乱码自检 `git diff | Select-String '鐐|璇|鍒|锛|銆|鎴|鏂|锟'` 计数 0；diff 只涉及 4 个已跟踪文件 + 1 个新测试文件。

### 真机验证清单

1. 进入「稍后再看」，顶部出现「全部 / 未看完」两个筛选项；**其它列表页（关注动态、历史记录等）看不到这一行**。
2. 默认选中「全部」，列表与升级前完全一致（条数、顺序、缩略图）。
3. 点「未看完」：只剩 `progress > 0` 且未看完的稿件；「从未播放」和「已看完」的都不出现。
4. 两个筛选项的字色跟随主题：选中项是主题色，另一项是次要色；切档立即生效、无网络请求（可断网验证：断网后切档仍能筛）。
5. 某一档结果为空时显示空态提示，不是白屏；切回另一档能恢复列表。
6. 「未看完」档下连点两次长按删除一条，列表立即少一行且**不报错、不跳位**。
7. 删除后切到「全部」再切回「未看完」，**刚删的那条不会复活**。
8. 删到「未看完」档为空时显示空态；此时「全部」档仍能看到其它稿件。
9. 手表小屏上两个筛选项可点（`minHeight=touch_min`），不误触列表项。
10. 从稍后再看返回视频页/首页再进来，列表正常刷新，筛选条仍在。

### 交叉引用

- 调研报告 §12.4 的 C18 行改为「已实现（26.10.04 批次 7）」；§12.6 测试数改 34 类 / 285 例；§12.7 的「想要实现 36（…剩 14）」改剩 13 并新增「26.10.04 批次 7 落地」行；批次顺序 ⑦ 标为进行中（1/4）。
- `docs/architecture-map.md` 新增 §7.23「稍后再看『未看完』分类」，并把测试数改为 34 个测试类 / 285 个用例（api 层 9 个类被覆盖）。
- 接口依据：`bilibili-API/docs/historytoview/toview.md:124-334`（列表接口，字段表 `:148`/`:173`/`:175` `duration`/`:183` `progress`/`:184` `add_at`）。
- 批次 7 进度：C18（本条）→ C19 收藏夹排序/复制/移动 → C20 收藏夹多选删除 → C21 关注分组增删改。

---

## 二十八、26.10.04 批次 7（2/4）：收藏夹排序与复制 / 移动（C19）

### 为什么做

收藏夹内容列表此前**只有一个顺序**（老接口写死 `order=fav_time`），想找播放量最高的、或最近投稿的只能自己翻；收藏内容也没法在收藏夹之间整理——想「把这条挪到另一个夹子」只能取消收藏再去目标夹重新收藏，两步操作且会丢收藏时间。调研报告 §12.4 的 C19 建议做排序 + 复制/移动，本次按用户拍板的范围落地：**排序 + 单条复制/移动 + 单条取消收藏**，多选（C20）留到下一项。

### 改动

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/api/FavoriteApi.java` | 新增排序常量 `ORDER_FAV_TIME`/`ORDER_VIEW`/`ORDER_PUBTIME` 与 `legacyOrder(String)`；`getFolderVideosNew` 与 `getFolderVideos` 各加一个带 `order` 的重载（旧签名保留并委托）；新增纯函数 `buildResources(List<VideoCard>)`；新增 `copyResources`/`moveResources`（共用私有 `dealResources`）与 `resourceErrorMsg(int)` |
| `app/src/main/res/layout/activity_simple_refresh.xml` | 在 C18 的 `filterBar` 之后新增**默认 `android:visibility="gone"`** 的 `sortBar`（三个等宽 `TextView`：`sortFavTime`「收藏时间」/`sortView`「播放量」/`sortPubtime`「投稿时间」） |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/user/favorite/FavoriteVideoListActivity.kt` | 重写：`readOnly`/`writable` 分开判定、`sortOrder` + `switchSort()`（重置 `page`/`bottom` 后重拉第一页）、`fetch()` 统一两条加载路径、`writable` 时长按弹「复制到…/移动到…/取消收藏」菜单（目标夹从 `getFavoriteFolders(mid)` 里选并排除自己）、`media_id` 为 0 时保留原「连点两次长按删除」 |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/favorite/FavoriteFolderAdapter.kt` | 自己的收藏夹点击时补传 `mediaId` 与 `readOnly=false` |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/favorite/UserFavoriteFolderAdapter.kt` | 补传 `readOnly=true` |
| `app/src/test/java/com/RobinNotBad/BiliClient/api/FavoriteApiTest.kt` | 追加 5 例：`buildResources` ×3、`legacyOrder` ×1、`resourceErrorMsg` ×1 |

### 取舍

- **「收藏夹排序」落地成「收藏内容列表排序」**：快照里**没有任何给收藏夹本身排序的接口**（`bilibili-API/docs/fav/list.md` 只有资源列表的 `order`）。能做的就是给 `x/v3/fav/resource/list` 传 `mtime`/`view`/`pubtime`，这也是用户实际想要的效果（"让我按播放量看这个夹子"）。
- **排序必须重发请求，与 C18 的筛选相反**：筛选是内存过滤，切档零请求；排序是服务端参数，切档必须 `page = 1` + 清列表 + 重拉第一页。两套东西共用同一个布局文件里的两个默认隐藏分组，注释里写明了区别，避免以后互相抄错。
- **老接口的 `order` 取值不同**：`x/space/fav/arc` 的收藏时间叫 `fav_time` 而不是 `mtime`，另两个取值两边一致。映射收敛到纯函数 `legacyOrder()`，调用点只认新常量。
- **自己的收藏夹也要带 `media_id`**：原先只有"别人的收藏夹"才传 `mediaId`，页面用 `readOnly = mediaId > 0` 把两件事混在一起。现在 `FavoriteFolderAdapter` 也传自己的 `media_id`，页面改用显式 `readOnly` extra（缺省仍按 `mediaId > 0` 兜底，兼容旧调用方）。**`media_id` 是从 `getFavoriteFolders` 的 `fid → media_id` 映射里拿的，映射失败就是 0**——此时退回老接口、不给复制/移动，长按保留原删除手势，不会出现"点了没反应"。
- **长按弹菜单而不是长按即删**：与 C3/C7 的菜单化一致，也避免"想复制却误删"。菜单里仍保留「取消收藏」；`media_id` 为 0 的老链路保留原「连点两次长按删除」，不静默丢掉既有能力。
- **`resources` 参数只拼 `aid:2`**：`{内容id}:{内容类型}` 里视频稿件是 `2`、内容 id 是 **avid**；拼装进纯函数 `buildResources`（跳过 `aid <= 0` 的脏数据），C20 的多选删除（`x/v3/fav/resource/batch-del`）直接复用同一个函数与同一套错误码文案。
- **客户端预校验不发请求**：`src_media_id`/`tar_media_id` 缺失或 `resources` 为空时直接返回 `-400`，不浪费一次网络往返。
- **目标收藏夹排除自己**：源夹不出现在选择列表里（`mediaId` 相同或 `<= 0` 的跳过），避免"复制到自己"这种服务端必拒的操作。

### 单测

`api/FavoriteApiTest.kt` 由 3 例增至 **8 例**（+5）：`buildResources_joinsAidAndType`（`1:2,2:2`）、`buildResources_skipsNullAndBadAid`（`aid<=0` 与 null 元素）、`buildResources_nullOrEmpty_returnsEmpty`、`legacyOrder_mapsFavTimeOnly`（`mtime → fav_time`，`view`/`pubtime` 原样）、`resourceErrorMsg_mapsKnownCodes`（0 / -101 / -111 / -400 / 11010 / 未知码带原码）。已有的 3 例 `parseFavoriteState` 未改。网络请求部分不测（与既有 api 测试一致）。

### 验证

本次只改既有布局文件、**没有新增 `res/` 文件**，按 AGENTS.md 不需要 clean 两连：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → **BUILD SUCCESSFUL in 1m 4s**（99 tasks；pwsh 因 javac 的过时 API 注记报 `[exit code: 1]`，属已知假阳性）。`app/build/test-results/testDebugUnitTest` **34 个 XML / 290 个用例 / 0 失败 / 0 错误**（C18 后 34/285，本次 +5 例）。乱码自检 `git diff | Select-String '鐐|璇|鍒|锛|銆|鎴|鏂|锟'` 计数 0；diff 涉及 6 个已跟踪文件（无新增文件）。

### 真机验证清单

1. 进入**自己的**收藏夹：顶部出现「收藏时间 / 播放量 / 投稿时间」三个排序项；**其它列表页（稍后再看、历史记录等）看不到这一行**，稍后再看的「全部 / 未看完」也不受影响。
2. 默认选中「收藏时间」，列表与升级前一致（顺序、条数、封面）；点「播放量」「投稿时间」后列表内容/顺序确实变化（可与网页端同一收藏夹对照）。
3. 排序项字色跟随主题：选中项是主题色，另两项是次要色；点已选中档位不重新请求、不闪屏。
4. 长按自己收藏夹里的一条：弹出菜单，标题是该视频标题，三项「复制到…」「移动到…」「取消收藏」。
5. 「复制到…」：选择目标收藏夹后提示「已复制」；**当前列表不变**，去目标收藏夹能看到这条，源收藏夹里也还在。
6. 「移动到…」：提示「已移动」，**当前列表立即少一行且不跳位**；源收藏夹少一条、目标收藏夹多一条（网页端核对）。
7. 「取消收藏」：二次确认后提示「已取消收藏」，列表少一行；网页端该收藏夹也少了。
8. 目标收藏夹选择列表里**不出现当前这个收藏夹**；只有 0 个可选目标时提示「没有别的收藏夹可以放喵~」而不是弹空列表。
9. 进入**别人的**收藏夹：排序项在、长按**不弹菜单**、点击仍按虚拟合集逻辑播放；未登录/接口失败时提示文案正常（`-101`「还没有登录喵~」等）。
10. 点「播放量」排序后点一条视频，虚拟合集的播放顺序与屏幕上看到的顺序一致。

### 交叉引用

- 调研报告 §12.4 的 C19 行改为「已实现（26.10.04 批次 7）」；§12.6 测试数改 34 类 / 290 例；§12.7 的「想要实现 36（…剩 13）」改剩 12，「26.10.04 批次 7 落地」行改 2/4；批次顺序 ⑦ 标为进行中（2/4）。
- `docs/architecture-map.md` 新增 §7.24「收藏夹：排序 + 复制 / 移动」，并把测试数改为 34 个测试类 / 290 个用例。
- 接口依据：`bilibili-API/docs/fav/list.md:5-32`（资源列表的 `order` 取值）、`bilibili-API/docs/fav/action.md:246-304`（`x/v3/fav/resource/copy`）、`:306-364`（`/move`，参数与 `resources` 格式、错误码 0/-101/-111/-400/11010）、`:366-418`（`batch-del`，C20 用）。
- 批次 7 进度：C18（§二十七）→ C19（§二十八）→ C20 收藏夹多选删除（本条）→ C21 关注分组增删改。

---

## 二十九、26.10.04 批次 7（3/4）：收藏夹多选删除（C20）

### 为什么做

用户裁决「收藏夹批量整理」为**想要实现**（调研报告 §12.4 的 C19/C20）。C19 解决的是「一条一条挪」，C20 解决的是「一次清掉一批」——手表上没有键盘，但多选只需要点，正好适合触屏。范围限定为**收藏夹内的多选删除**，不做跨夹批量移动（那需要另一套目标夹选择交互，收益低）。

### 改动

| 文件 | 改动 |
| --- | --- |
| `app/src/main/res/layout/activity_simple_refresh.xml` | 新增默认 `gone` 的 `manageBar`（`manageToggle`「多选」/`manageDelete`「删除」两个等宽 chip），第三个复用该基类布局的可选分组 |
| `app/src/main/java/com/RobinNotBad/BiliClient/api/FavoriteApi.java` | 新增 `batchDeleteResources(mediaId, cards)`：走 `POST x/v3/fav/resource/batch-del`，复用 `buildResources()`；`mediaId<=0` 或资源为空直接返回 `-400` 不发请求；错误文案复用 `resourceErrorMsg()` |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/video/VideoCardAdapter.kt` | 新增 `selectionMode` 与 `selectedAids`（页面注入同一个集合引用）；`onBindViewHolder` 末尾调 `holder.applySelection(...)` |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/video/VideoCardHolder.kt` | 新增 `applySelection(selectionMode, selected)`：未选中压暗 `alpha=0.45f`、标题前加 `"✓ "`/`"　 "` 前缀；companion 新增两个 2 字符前缀常量 |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/user/favorite/FavoriteVideoListActivity.kt` | `setupManageBar()`/`enterSelectionMode()`/`exitSelectionMode()`/`toggleSelected()`/`updateManageBar()`/`confirmBatchDelete()`/`applyRemoved()`；多选下点击与长按都改为勾选；`switchSort` 先退出多选；虚拟合集关闭时补回「进视频详情页」默认行为 |

### 取舍

- **接口用 `x/v3/fav/resource/batch-del` 而不是循环调单条删除**：一次请求删完，且与快照 :366-418 的参数（`resources`/`media_id`/`platform`/`csrf`）完全对上；`resources` 与 C19 的复制/移动同格式，直接复用 `buildResources()`，两个功能不会各写一套拼接。
- **不加 FAB / 不在条目上加 checkbox**：`cell_video_list.xml` 的 id 是跨包事实协议（`PrivateMsgAdapter`、`DynamicHolder`、`NoticeHolder`、`OpusContentAdapter` 都在用），为多选加控件风险大于收益。选中态改用「条目压暗 + 标题前缀」，零新 id。
- **勾选状态放页面、adapter 只画样式**：删除要按「当前列表里选中的那些」算，页面本来就持有 `videoList`；adapter 持有同一个 `MutableSet` 引用即可，避免两边状态不同步。`notifyItemChanged(position)` 只重画改动的那一条。
- **`manageBar` 只在 `writable` 时出现**：别人的收藏夹（`readOnly`）与拿不到 `media_id` 的老链路不做多选——服务端也一定会拒（`media_id` 必填）。与 C19 的「长按菜单」共用同一个 `writable` 判据。
- **切排序先退出多选**：排序会清空并重拉 `videoList`，`selectedAids` 若留着就会指向已经不存在的条目；先 `exitSelectionMode()` 再重拉，避免"删了一批看不见的东西"。
- **空选时「删除」压暗但仍可点**：点了只提示「先选几条吧~」，比禁用后毫无反馈更适合手表（小屏上用户不一定看得出手感差异）。
- **删除成功后本地移除、不重拉整页**：与 C19 的移动一致（`videoList.removeAll` + `notifyDataSetChanged`），少一次网络往返；空列表时才 `showEmptyView()`。

### 单测

C20 没有新增纯函数（`buildResources`/`resourceErrorMsg` 已在 C19 测过），故测试数不变：**34 个测试类 / 290 个用例**。网络请求部分与既有 api 测试一致，不测。

### 验证

本次只改既有布局文件、**没有新增 `res/` 文件**，按 AGENTS.md 不需要 clean 两连：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → **BUILD SUCCESSFUL in 1m 7s**（99 tasks）。`app/build/test-results/testDebugUnitTest` **34 个 XML / 290 个用例 / 0 失败 / 0 错误 / 0 跳过**。乱码自检 `git diff | Select-String '鐐|璇|鍒|锛|銆|鎴|鏂|锟'` 计数 0；`git status --porcelain` 无未跟踪文件；diff 涉及 5 个已跟踪文件（+207/-3）。

### 真机验证清单

1. 进入**自己的**收藏夹：排序行下面出现「多选 / 删除」两个项；**别人的收藏夹没有这一行**，其它列表页（稍后再看、历史记录）也看不到。
2. 点「多选」：提示「点条目勾选，再点「删除」」，按钮文案变「退出多选」，进入勾选状态。
3. 点几条视频：条目标题前出现 `✓`，未选中的条目明显压暗；再点一次取消勾选、标题前缀变回全角空格（文字不抖动、不错位）。
4. 勾选后「删除」文案变「删除(3)」且是高亮色；没勾选时是「删除」且压暗，点它只提示「先选几条吧~」、不弹确认框。
5. 点「删除」勾选 3 条：确认框写「确定把选中的 3 条从收藏夹里移除吗？」；确认后提示「已删除 3 条」、列表少 3 条、自动退出多选；网页端核对这 3 条确实没了。
6. 取消确认框：列表不变、仍停留在多选模式、勾选状态保留。
7. 多选模式下点击条目**不会**触发虚拟合集播放、也不会进视频详情页；长按也不会弹 C19 的管理菜单。
8. 多选模式下切「播放量」排序：自动退出多选（按钮回到「多选」、勾选清空），列表按新排序重拉。
9. 把整个收藏夹都选上删除：提示条数正确，删完显示空视图（不是白屏），再进这个收藏夹网页端也是空的。
10. 未登录/风控场景：接口返回 `-101`/`-111` 时提示「还没有登录喵~」/「登录凭证已失效，请重新登录」；选中别人的收藏夹（若可达）时不会出现多选入口。

### 交叉引用

- 调研报告 §12.4 的 C20 行改为「已实现（26.10.04 批次 7）」；§12.7 的「想要实现 36（…剩 12）」改剩 11、「26.10.04 批次 7 落地」行改 3/4；批次顺序 ⑦ 标为进行中（3/4）。§12.6 测试数保持 34 类 / 290 例（无新增用例）。
- `docs/architecture-map.md` 新增 §7.25「收藏夹多选删除」，并说明 `manageBar` 是 `activity_simple_refresh.xml` 里第三个默认隐藏分组。
- 接口依据：`bilibili-API/docs/fav/action.md:366-418`（`x/v3/fav/resource/batch-del` 参数与错误码）；`resources` 格式与错误码文案见 C19（§二十八 / §7.24）。
- 批次 7 进度：C18（§二十七）→ C19（§二十八）→ C20（本条）→ C21 关注分组增删改。

---

## 三十、26.10.04 批次 7（4/4）：关注分组增删改（C21）

### 为什么做

调研报告 §12.4 的 C21 列为**想要实现**（:408 功能对照表里「关注分组增删改 ❌」）。本项目早就有了「关注分组模式」（设置里开启后按分组折叠展示关注），但只能看——分组一旦在手机/网页端建好就不能改。手表上没有键盘，但**创建/重命名只需要一次短文本输入**，而 `InputDialogActivity` 已经是现成的输入页（本地列表、回复等处都在用），删除只需一次确认。故范围限定为**分组本身的创建 / 重命名 / 删除**，不做分组内成员的增删（那是另一套「从关注列表勾选后加入分组」的交互，收益低）。

### 改动

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/RobinNotBad/BiliClient/api/FollowApi.java` | 新增 `TAG_NAME_MAX_LENGTH = 16`；纯函数 `checkTagName(name)`（空/全空白、trim 后超 16 字）与 `tagErrorMsg(code)`（22101/22102/22103/22104/22106）；写接口 `createFollowTag(name)` → `POST x/relation/tag/create`、`renameFollowTag(tagid, name)` → `.../tag/update`、`deleteFollowTag(tagid)` → `.../tag/del`，三者统一经私有 `postTag(url, formData)` 取 `code` |
| `app/src/main/res/layout/activity_simple_refresh.xml` | 新增默认 `gone` 的 `groupBar`（一行「+ 新建分组」），第四个复用该基类布局的可选分组 |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/user/FollowGroupAdapter.kt` | 新增 `setOnGroupLongClickListener(listener)`；`GroupHolder` 在点击展开之外加长按回调（传 `group.tag`） |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/user/FollowUsersActivity.kt` | `setupGroupBar()`（仅分组模式点亮）；`pendingInputCallback` + `inputLauncher`（`registerForActivityResult`）；`showCreateGroupDialog()`/`showRenameGroupDialog(tag)`/`showDeleteGroupDialog(tag)`/`showGroupMenu(tag)`/`isEditableTag(tag)`；`loadGroupMode()` 挂长按回调并**去掉 `count > 0` 过滤** |
| `app/src/test/java/com/RobinNotBad/BiliClient/api/FollowApiTest.kt` | 新增 5 例：`checkTagName` 空/正常/16 字边界（含外包空格仍超长）、`tagErrorMsg` 已知码与未知码 |

### 取舍

- **复用 `InputDialogActivity` 而不是新建输入页**：它已支持 `title`/`initial_text`/`hint` 三个 extra 且回传 `input_text`，重命名时直接预填旧名字；照抄 `LocalListActivity` 的 `pendingInputCallback` + `registerForActivityResult` 范式即可，零新 Activity、零新布局。
- **入口是长按分组标题，不是每组加按钮**：分组行本来就窄（手表上更窄），加按钮会把分组名挤没；长按弹「重命名 / 删除」两项菜单，与 C3/C7/C19 的菜单化风格一致。新建按钮放在列表上方的 `groupBar`（整行，好点）。
- **本地先做名称预校验**：`checkTagName` 挡掉空名字与超 16 字，避免为一个必然失败的名字发请求；服务端的 22101/22103/22106 仍全部映射成中文文案兜底。
- **系统分组不可编辑**：`isEditableTag(tag) = tag.tagid > 0`。默认分组（`tagid = 0`）与特别关注（`-10`）是服务端固定分组，长按只提示「默认分组和特别关注不能改名或删除」——不发请求也不给菜单。
- **去掉 `loadGroupMode()` 里 `tag.count > 0` 的过滤**：这是本次的隐式 bug——刚建好的分组 `count` 为 0，若不列出，用户建完看不到它、更没法改名/删除，「创建」功能等于半残。空分组现在照常列出，展开为空。
- **成功后整页重拉而不是局部改**：分组增删改会改变行数与展开状态，重拉最省心；`RefreshListActivity.setAdapter()` 只是给 `recyclerView` 赋值（没有「只能设一次」的守卫），重建 adapter 安全。
- **改名时名字没变不发请求**：`name.trim() == tag.name` 时直接返回，避免无意义的写请求触发风控。

### 单测

新增 `app/src/test/java/com/RobinNotBad/BiliClient/api/FollowApiTest.kt` **5 例**（只测纯函数，不碰 android）：`checkTagName_rejectsNullAndBlank`、`checkTagName_acceptsNormalNames`、`checkTagName_lengthBoundaryIs16`、`tagErrorMsg_mapsKnownCodes`、`tagErrorMsg_unknownCodeKeepsTheCode`。测试总数 **35 个测试类 / 295 个用例**。

### 验证

本次只改既有布局文件、**没有新增 `res/` 文件**，按 AGENTS.md 不需要 clean 两连：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → **BUILD SUCCESSFUL in 1m 6s**（99 tasks）。`app/build/test-results/testDebugUnitTest` **35 个 XML / 295 个用例 / 0 失败 / 0 错误 / 0 跳过**。乱码自检 `git diff | Select-String '鐐|璇|鍒|锛|銆|鎴|鏂|锟'` 计数 0；`git status --porcelain` 只有 4 个已改文件 + 新增的 `FollowApiTest.kt`。

### 真机验证清单

1. 设置里打开「我的关注列表分组」后进入关注列表：列表上方出现「+ 新建分组」整行；粉丝列表、普通关注列表（未开分组模式）**没有这一行**。
2. 点「+ 新建分组」：弹出输入页标题「新建分组」、提示「请输入分组名（最多 16 个字）」；输入「测试分组」确认后 toast「分组已创建」，列表里立刻出现该分组（**关注数为 0 也要显示**）。
3. 什么都不输入直接确认：提示「分组名不能为空」，不弹 toast 说创建失败。
4. 输入 17 个汉字：提示「分组名最多 16 个字」，不发请求。
5. 建一个重名分组：提示「已经有同名的分组了」（22106）。
6. 长按刚建的分组标题：弹出菜单「重命名分组 / 删除分组 / 取消」，标题是该分组名。
7. 选「重命名分组」：输入页预填旧名字；改成「改过的名字」确认后 toast「已重命名为「改过的名字」」；**原名不改直接确认**时不发请求、无提示。
8. 选「删除分组」：确认框文案「确定删除「改过的名字」吗？\n分组里的关注不会取关，只是回到默认分组。」；确认后 toast「分组已删除」，列表里该分组消失；网页端核对分组确实没了。
9. 长按**默认分组**或**特别关注**：只提示「默认分组和特别关注不能改名或删除」，不弹菜单。
10. 已存在的分组展开仍然能正常加载成员、翻页正常；未登录时三个写接口都提示「还没有登录喵~」（-101），登录过期提示「登录凭证已失效，请重新登录」（-111）。

### 交叉引用

- 调研报告 §12.4 的 C21 行改为「已实现（26.10.04 批次 7）」；§12.6 测试数改 35 类 / 295 例；§12.7 的「想要实现 36（…剩 11）」改剩 10，「26.10.04 批次 7 落地」行改 4/4；批次顺序 ⑦ 标为**批次 7 全部完成**。
- `docs/architecture-map.md` 新增 §7.26「关注分组增删改」，并把测试数改为 35 个测试类 / 295 个用例（api 层 10 个类被覆盖）。
- 接口依据：`bilibili-API/docs/user/relation.md:2398-2458`（`x/relation/tag/create`，`tag` 最长 16 字符、错误码 22101/22102/22103/22106）、`:2460-2508`（`.../tag/update`，错误码含 22104）、`:2512-2560`（`.../tag/del`）；输入页复用见 `app/src/main/java/com/RobinNotBad/BiliClient/activity/InputDialogActivity.kt` 与 `app/src/main/java/com/RobinNotBad/BiliClient/activity/video/local/LocalListActivity.kt:46`、`:62-69`、`:302-336`。
- 批次 7 进度：C18（§二十七）→ C19（§二十八）→ C20（§二十九）→ **C21（本条）**，批次 7 全部完成；下一批 ⑧ E2 拆分 DownloadService + F4 漫画。

---

## 三十一、26.10.04 批次 8（1/2）：拆分 DownloadService（E2）

### 为什么做

调研报告 §12.4 的 E2 列：**拆分 `DownloadService`**（依据是「65KB」）。实测 `app/src/main/java/com/RobinNotBad/BiliClient/service/DownloadService.kt` **1593 行**，一个类里同时装着五类关注点：纯函数（分片路径、进度数学、批次统计）、进程级状态（进度映射 / 暂停标志 / 速度采样）、数据库访问（`DownloadSqlHelper` 的增删查改）、前台通知（通道 / 两个 Builder / 进度定时器）、下载主循环。任何一个下载 bug 都要在上千行里定位，且**这个文件原来一条单测都没有**——因为纯函数和 IO/通知纠缠在同一个类里，拿不出来测。

本次是**行为零变化的结构拆分**：对外 API（Java 侧仍写 `DownloadService.startDownload(...)` / `DownloadService.getDownloadProgress(...)`）一个字都不改，只把实现按关注点搬到 `service/download/` 下，并给纯函数补 JVM 单测。

### 改动

| 提交 | 文件 | 改动 |
| --- | --- | --- |
| `51c0306`（1/4） | `service/download/DownloadPathSpec.kt`（72 行）、`service/download/DownloadProgressMath.kt`（60 行）+ 3 个测试文件 | 抽走纯函数：分片路径 / 分段数 / 分段区间、`progressForBytes` / `pseudoProgress`、批次统计；`DownloadService` 内改为调用并保留同名 `@JvmStatic` 门面 |
| `ea83063`（2/4） | `service/download/DownloadProgressStore.kt`（143 行） | `internal object` 持有 `downloadProgressMap` / `pausedMap` / 累计字节 / `speedSampler` + `speedLock` / `speedStr` / `isSpeedMode`，`Companion` 只留 `@JvmStatic` 转发 |
| `d48c0fd`（3/4） | `service/download/DownloadRepository.kt`（157 行） | DB 访问（`getFirst` / `getAll` / `deleteSection` / `clear` / `setState` / `firstDown` / 视频元信息写入）整体搬入 |
| `3cdf02c`（4/4） | `service/download/DownloadNotifier.kt`（122 行） | 通知通道 + 两个 Builder + 每秒进度刷新 + `notifyExit` / `notifyCompletion` 搬入，**普通 class 持 Service 引用**（不可做 object） |
| 收尾 | `service/DownloadService.kt` | 1593 → **1284 行**；`onCreate` 里 `DownloadNotifier(this).init()`，实例字段只留持有者 |

合计 9 files changed, +1159/−422（含 3 个测试文件 367 行）。

### 取舍

- **只搬实现，不动公开契约**：10 个 `@JvmStatic` 字段 + 24 个 `@JvmStatic` 函数全部保留原签名、原位转发。Java 调用点（`api/PlayerApi.java`）与 Kotlin 调用点（`DownloadListActivity` / `DownloadAdapter`）零改动。用 `javap` 逐项核对过。
- **`start(Long)` 一行未搬**：它是 Companion 的 `@JvmStatic @Synchronized`，`started` 的 check-then-act 全靠这把锁。行为等价优先于"看起来更干净"，把锁一起搬走风险远大于收益。
- **两把锁不合并**：`speedLock` 是速度采样自己的锁，与 Companion 监视器从来就是两把；`synchronized(speedLock)` 原样保留、`speedSampler` 与锁对象成对搬进 `DownloadProgressStore`。**换锁即改语义**，注释里已写明"不得互换、合并"。
- **刻意不加 `volatile`**：`speedStr` / `isSpeedMode` / `activeDownloadsCount` 本来就不是 volatile（UI 线程读、下载线程写，是既有数据竞争）。顺手加 volatile 属于行为变更，不归结构拆分管。
- **`pausedMap` 必须还是同一个实例**：`DownloadListActivity` 直接对 `DownloadService.pausedMap` 做 add/remove，所以 `Companion` 里用带 getter 的 `val` 转发生成的新实例风险极高——实现上直接返回 `DownloadProgressStore.pausedMap`（同一个 `ConcurrentHashMap`）。
- **`DownloadNotifier` 是普通 class 而不是 object**：object 是进程级单例，会跨批次抓住第一个 Service 的 Context 与 Timer——服务销毁后定时器还在跑（线程泄漏）且继续往通知栏写已经死掉的服务。实例字段随 Service 回收。
- **通知可变状态仍留在 Service 实例上**（`statusBuilder` / `completionBuilder` / `notifyManager` / `notifyTimer`）：notifier 只读写、不另存一份，避免"两处真相"。代价是 `notifyTimer` 由 `private` 放宽为 `internal`（M11-d 幂等守卫在 notifier 里）。
- **`DownloadProgressInfo` 的 FQN 变了**：`DownloadService$Companion$DownloadProgressInfo` → `com.RobinNotBad.BiliClient.service.download.DownloadProgressInfo`。它是 public 门面的返回类型，不能是 internal；全仓无显式引用（调用点靠类型推断），但按旧 FQN 反射的外部代码会失配——记录在此。
- **死代码原样保留**：`clear()` 与从未赋值的 `toastTimer` 照旧留在原位，删除属于另一件事（本次不做功能/行为变更）。
- **通知常量与数据库 schema 一个字不动**：channel `biliterminal_download`、前台 id `1027`、`notifyExit` 的 `2`、`notifyCompletion` 的 `id % 100 + 100`、download 表 11 列 / version 4、SP 键（`aria2_enabled` / `aria2_split` / `parallel_download_videos`）。

### 单测

新增 3 个纯 JVM 测试文件 **32 例**（不碰 android，不碰 `SharedPreferences`）：`app/src/test/java/com/RobinNotBad/BiliClient/service/download/DownloadPathSpecTest.kt`（72 行）、`.../service/download/DownloadProgressMathTest.kt`（159 行）、`.../service/DownloadBatchStatsTest.kt`（136 行）。覆盖 0 字节、除零、最后一节、越界 id、负数回滚等边界。测试总数 **38 个测试类 / 327 个用例**（批次 7 后 35 类 / 295 例）。这些用例正是拆分前拿不到的：纯函数原来和 `DownloadSqlHelper`、`NotificationCompat` 混在一个类里。

### 验证

四步每步各自 `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-configuration-cache` → BUILD SUCCESSFUL，测试从 35/295 一路到 38/327/0/0；收尾额外跑了一次 `:app:clean :app:testDebugUnitTest :app:assembleDebug`（`res/` 未动，但本次改动量大，按 AGENTS.md 走 clean 两连），4 个 APK 全部重出、测试仍 38/327/0/0。Lead 复核：`git diff --stat 95b38aa..HEAD` 与声称一致（9 files, +1159/−422，`DownloadService.kt` 1593 → 1284）；删掉 `test-results` 后 `:app:testDebugUnitTest --no-build-cache` **真实重跑**（不是 FROM-CACHE 回放）→ **38 个 XML / 327 个用例 / 0 失败 / 0 错误 / 0 跳过**；`javap` 核对 `DownloadService$Companion.start(long)` 仍是 `public final synchronized`、静态桥 `public static synchronized`，静态字段与函数数量与拆分前一致。

### 真机验证清单

1. 下载一个视频：通知栏前台通知正常出现（标题「下载视频中」、进度随下载更新），完成后「下载结束」通知出现，前台通知消失。
2. 下载中退出列表页再进来：进度条、速度、状态与退出前一致（说明进度映射仍是同一份）。
3. 暂停一个下载任务：状态变「已暂停」，其他任务继续；恢复后能继续下载（`pausedMap` 仍是同一实例、`resumeDownload` 能拉起服务）。
4. 并行下载多个视频：总进度 = 各任务平均，速度是聚合速度（`DownloadProgressStore` 的采样没有被拆坏）。
5. 下载中断网再恢复：失败分片回滚后继续，累计字节数正确（`addDownloadedBytes(-x)` 仍允许减小）。
6. 重复拉起点下载 / 服务被 START_STICKY 重建：不会出现两个批次并发写同一文件（`start()` 的 `@Synchronized` 未动，`batchRunning` 守卫仍在）。
7. 下载完成后杀掉 App 再打开：已下载条目、封面、质量列表正常（DB 访问搬到 `DownloadRepository` 后写入一致）。
8. 删除一条下载记录、清空全部记录：列表与数据库同步变化。
9. 通知每秒刷新不造成卡顿或崩溃；后台放置 10 分钟后回来，进度通知仍在刷新（定时器挂在 Service 实例上、随 Service 销毁而 cancel）。
10. 长时间下载后查看日志：无 `DownloadService` 相关异常刷屏；不出现"通知停止更新但服务还在下载"（TimerTask 异常已被吞掉并记日志）。

### 交叉引用

- 调研报告 §12.4 的 E2 行改为「已实现（26.10.04 批次 8）」；§12.6 测试数改 38 类 / 327 例；§12.7 的「想要实现」行与批次顺序 ⑧ 同步；F4 漫画由用户裁为**不做**（见下条）。
- **F4 漫画本轮不做**：勘察确认快照 `bilibili-API/docs/manga/` 只有 `comic.v1.Comic/ComicDetail`（详情）、`GetImageIndex`（章节图片）、`ImageToken`（取图 token）、`BuyEpisode`，**没有任何「追漫 / 收藏漫画列表」接口**（全库 grep `追漫`/`收藏`/`Favorite` 只命中 `bilibili-API/docs/user/space.md:866` 的追漫**计数**），也没有漫画搜索接口；项目内漫画实现为零（只有 `activity/user/VipActivity.kt:79-80` 的会员权益文案）。原范围「追漫列表 + 详情 + 长条阅读器」缺了首环就无法闭环，用户 26.10.04 明确「漫画算了」→ 从「想要实现」移出，记为**不做**（不是暂缓：暂缓意味着还打算做）。
- `docs/architecture-map.md` 新增 §7.27「下载服务分层」，并把测试数改为 38 个测试类 / 327 个用例。
- 接口/结构依据：拆分前 `app/src/main/java/com/RobinNotBad/BiliClient/service/DownloadService.kt`（1593 行，`Companion` 52–491、实例成员 493–1592）；外部调用点 `app/src/main/java/com/RobinNotBad/BiliClient/api/PlayerApi.java:74/79/95/100`、`app/src/main/java/com/RobinNotBad/BiliClient/activity/video/local/DownloadListActivity.kt:83/84/86/118/131/164/170/175/176/178` 等、`app/src/main/java/com/RobinNotBad/BiliClient/adapter/video/DownloadAdapter.kt:63/121/128`。
- 批次 8 进度：**E2（本条）✅** → F4 漫画 ❌（不做）。批次 8 结束，8 批落地顺序全部走完。




## 三十二、26.10.05：弹窗（选择框）主题接入 + 终端列表收口

> 用户反馈：表盘上弹出的选择框「灰底白字 + 粉色按钮」，且**没有圆角，像安卓原生的一样**。
> 设计稿：`docs/design/dialog-redesign-v2.html`（方案 B 终端列表）。专项记录：`docs/review/dialog-redesign-progress.md`。

### 根因（不是「没接入主题系统」，而是只接入了一半）

7 族主题都声明了 `colorSurface`，弹窗底色**是**跟随主题的（前序 `themes.xml:681-682` 注释提过的
`colorSurface`/`colorBackground` 那半已补）。真正缺的是三个属性，全工程 `app/src/main/res` **0 命中**：

| 缺失属性 | 谁在用 | 回退到什么 | 现象 |
|---|---|---|---|
| `alertDialogTheme` | AlertDialog 选哪个颜色 overlay | `Theme.MaterialComponents` 自带的 alert overlay | 弹窗不读本主题定制 |
| `colorOnSurface` | Material 正文/标题文字色 | Material 默认近白 | **「白字」**（`android:colorForeground` 救不了，Material 正文不读它） |
| `colorError` | 破坏性按钮色 | Material 默认红 | 删除类操作无危险语义 |

**「粉按钮」来源**：按钮文字色走 `colorAccent`，这是 7 族主题里**唯一都设了**的属性，
B站粉与经典终端恰好都是 `#FF6699`。**「方角」来源**：AppCompat AlertDialog 背景方角无描边 +
dialog window 自带一层背景底衬。

> 教训：断言「某属性全工程缺失」必须**同时 grep 相邻属性做对照组**。最初结论「弹窗层从未接入
> 主题系统」被「7 族主题都声明了 `colorSurface`」与「XML 侧 71 处 `?attr/` 引用」直接推翻。

### 修复明细

**主题层**（让既有裸 Builder 立刻正常，调用点零改动）
- `res/values/colors.xml`：新增 14 个弹窗语义色（`on_surface_*` / `error_*` × 7 族）。
- `res/values/dimens.xml`：新增 7 个弹窗 token（条目内距 15/11dp、最小高 44dp、标题内距、引导符宽 11dp）。
- 新建 `res/drawable/dialog_background.xml`：`solid=?attr/colorSurface` + `stroke 1dp ?attr/colorPrimary`
  + `corners @dimen/card_round`。
- 新建 `res/layout/item_dialog_terminal.xml` 与 `res/layout/layout_dialog_terminal.xml`。
- `res/values/themes.xml`：新增 7 份 `ThemeOverlay.<X>.Dialog`（各含 `colorSurface`/`colorOnSurface`/
  `colorError`/`android:background`），并给 7 族主题各加一行 `alertDialogTheme`。

**统一入口**：新建 `util/TerminalDialog.kt`（约 369 行），四个静态方法 `menu` / `singleChoice` /
`confirm` / `alert`。颜色只读 `?attr/`，文件内无色值常量；`attrColor` 带兜底默认值。
`Sheet.init` 必须清 window 背景（`setBackgroundDrawable(TRANSPARENT)`），否则圆角被方角底衬盖掉。

**17 处调用点全部迁移**（`adapter/ReplyAdapter.kt` 2、`adapter/dynamic/DynamicHolder.kt` 2、
`adapter/message/PrivateMsgSessionsAdapter.kt` 2、`activity/vote/VoteInfoActivity.kt` 1、
`activity/dynamic/send/SendDynamicActivity.kt` 1、`activity/settings/login/AccountSwitchActivity.kt` 1、
`activity/user/FollowUsersActivity.kt` 2、`activity/user/favorite/FavoriteVideoListActivity.kt` 4、
`activity/video/info/BangumiInfoFragment.kt` 2+1）。

### 验证

**未执行编译与单测**：本次环境下 `pwsh` 工具完全不可用（任何命令返回空输出 + `[exit code: 3221225794]`，
即 `0xC0000142 STATUS_DLL_INIT_FAILED`，进程起不来），`assembleDebug` / `testDebugUnitTest` 均未跑。
已做的只有静态核查：逐文件回读 + 全仓 grep 确认
`app/src/main/java` 下除 `TerminalDialog.kt` 自身外 **0 处 `AlertDialog`**；
7 处 `alertDialogTheme` 与 7 份 overlay 名字一一对应；14 个新色值与 7 个新 dimen 全部存在且被正确引用。
**恢复环境后第一件事**：`:app:clean` 与 `assembleDebug --no-build-cache --no-configuration-cache`
**分两次调用**（本次新增 3 个 `res/` 文件，按已知坑极易触发缓存回放陈旧资源）。

### 交叉引用

- `AGENTS.md` 硬约定新增弹窗禁令（禁止裸写 `AlertDialog.Builder`，含正确写法与给新主题补 overlay 的清单）。
- `docs/architecture-map.md` 新增 §8.7.0「弹窗的主题属性接入」，§8.4 补 `TerminalDialog` 四方法表，
  §8.4b 公共落点表补一行。
- 专项进度：`docs/review/dialog-redesign-progress.md`。
- 与既有报告的关系：`docs/visual-experience-report.md` §2.25 的对比度问题（`#EBE0E2` 叠 `#FF6699`
  约 2.1:1）属同一视觉面，本次新增的 `colorError` 语义色**未重新做对比度测量**；
  §2.12「86 处布局引用静态调色板」与 `styles.xml` 组件样式硬编码同源，本次未动。

---

## 二十二、26.10.04 批次 7（5/5）：置顶会话排序 + 关注分组移动成员（C22）

本轮处理三件事：**A 置顶会话排序修复**、**B 收藏夹排序行的排查结论**、**C 关注分组移动成员**。

### A. 置顶会话排序：真因是「未读优先」把置顶项挤下去

**清单第 40 条**点名验 `op_type` 有没有写反。**核对结果是没写反**：

- `api/PrivateMsgApi.java:274` `opTypeForTop(boolean top) { return top ? 0 : 1; }` —— 0 = 置顶、1 = 取消置顶。
- 接口快照 `bilibili-API/docs/message/private_msg.md:1150` 确认 `op_type` 语义；`:1173-1179` 的示例就是 `op_type=0`（置顶）。
- `setSessionTop`（`:284-290`）经 `postSessionAction`（`:310-326`）发公共参数 `talker_id`/`session_type`/`csrf_token`/`csrf`/`build=0`/`mobi_app=web`，链路正确。

**真因在客户端本地排序**：`activity/message/MessageActivity.kt` 的 `loadSessions()` 里对服务端返回的会话列表又做了一次「未读优先」的 `Collections.sort`。而服务端 `get_sessions` 返回的顺序本身就是**置顶在前、其余按时间倒序**。这段排序把「有未读的会话」整体提前，于是**置顶但没有未读的会话被挤到未读会话之后、甚至列表最底部**——用户看到的就是「提示已置顶、`[置顶]` 前缀也出来了，但会话反而排到下面去了」。

**修法**：抽出纯函数 `util/SessionSorter.kt`，判据从高到低：

1. 置顶优先（`topTs > 0`，服务端置顶时间戳，微秒级）；
2. 都是置顶时，`topTs` 大的靠前（服务端置顶也有先后）；
3. 都未置顶时，有未读的靠前（保留「先看未读」的诉求）；
4. 其余保持服务端原序 —— 靠的是 `Collections.sort` 的**稳定性**，所以不再按时间重排，避免打乱服务端顺序。

`MessageActivity.kt` 里原地的 `Collections.sort` 块替换为 `SessionSorter.sort(sessionsList)`，并移除不再需要的 `import java.util.Collections`。

| 位置 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/util/SessionSorter.kt` | 新增。`@JvmStatic fun sort(MutableList<PrivateMsgSession>)` + `@JvmStatic fun compare(o1,o2): Int`（比较逻辑抽成纯函数便于单测） |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/message/MessageActivity.kt` | `loadSessions()` 里的「未读优先」`Collections.sort` 换成 `SessionSorter.sort(sessionsList)`；去掉 `import java.util.Collections`；补中文注释说明为什么不能只按未读排 |
| `app/src/test/java/com/RobinNotBad/BiliClient/util/SessionSorterTest.kt` | 新增，7 例（见下） |

**单测覆盖**（`SessionSorterTest`）：

1. **置顶会话排在最前，即使它没有未读** —— 这条就是用户 bug 的回归用例（修复前会把未读的普通会话排前面）；
2. 两个置顶会话按置顶时间倒序；
3. 都未置顶时有未读的排前面；
4. 未读会话不会越过置顶会话；
5. 排序是稳定的，同优先级保持服务端原序；
6. 空列表与单元素列表不崩；
7. `topTs == 0` 视为未置顶。

### B. 收藏夹排序行「没看到」：链路 100% 通，问题不在代码

**清单第 159 条**要求「进入自己的收藏夹：顶部出现 收藏时间/播放量/投稿时间」。用户补充原话是「两个地方有，第一个是我的，第二个是用户信息里，**请注意了**」——本轮把这条从「代码没写」一路查到「运行时状态」，**结论是静态链路逐环正确，没有可修的缺陷**。

**两处入口汇合到同一个页面**（这是排查的重点，一度怀疑「两处入口不同源、只通了一处」，已排除）：

- 入口①「我的」：`MySpaceActivity`/`MySpaceMoreActivity` → `MySpaceMenu.open(activity, "favorite", mid)`（`activity/user/MySpaceMenu.kt:49`）→ **`FavoriteFolderListActivity`**（收藏夹*列表*页）→ 点某个收藏夹 → `adapter/favorite/FavoriteFolderAdapter.kt:115-126` `putExtra("fid"/"mid"/"name"/"mediaId")` + `putExtra("readOnly", false)` → `FavoriteVideoListActivity`。
- 入口②「用户信息」：`UserInfoActivity:43-44` `UserFavoriteFragment.newInstance(mid)`（第 3 个 tab，`:74` 标题「收藏夹」）→ `activity/user/info/UserFavoriteFragment.kt:47-51` → `adapter/favorite/UserFavoriteFolderAdapter.kt:64-72` `putExtra("mediaId"/"mid"/"name")` + `putExtra("readOnly", true)` → **同一个 `FavoriteVideoListActivity`**。
- 全仓 grep `FavoriteVideoList|getFolderVideos|fav/resource/list` 确认：**全项目只有 `FavoriteVideoListActivity` 一个页面渲染收藏夹内容**，没有第二个变体。

**排序行的点亮是无条件的**（所有「可能被跳过」的路径都排除了）：

- `FavoriteVideoListActivity.kt:77-80` `onCreate` 是三条无条件直线：`setupSortBar()` → `setupManageBar()` → `setOnLoadMoreListener{...}` → `loadFirstPage()`。
- `setupSortBar()` 第一行就是 `findViewById<View>(R.id.sortBar).visibility = View.VISIBLE`；**没有** `mediaId` 前置判断、**没有** early return，且**先于** `loadFirstPage()`。
- grep `R.id.sortBar` 全 `app/src/main/java` **只有 1 处命中**（`FavoriteVideoListActivity.kt:84`），没有别处把它设回 `GONE`。
- `mediaId == 0` 只影响 `fetch()` 走老接口 `getFolderVideos`，**不影响可见性**。
- 布局 `res/layout/activity_simple_refresh.xml:117` 默认 `gone`，但 `:110-159` 三个 TextView（`sortFavTime`「收藏时间」/`sortView`「播放量」/`sortPubtime`「投稿时间」）齐全；构建产物 `app/build/intermediates/packaged_res/debug/packageDebugResources/layout/activity_simple_refresh.xml` 里也都在，**资源确实进包**。
- 排除项（均读全文确认）：`BaseActivity.asyncInflate` 会二次 `setContentView` 重置 visibility，但本类**不用**它；`BaseActivity.onContentChanged` → `ui/appearance/AppearanceApplier.applyToContentView` 只处理 typeface/radius，**不碰 visibility/textColor**；`RefreshListActivity.onCreate`（`:47`）只 `setContentView(R.layout.activity_simple_refresh)` 并取 emptyTip/loadMoreTip/swipeRefreshLayout/recyclerView，**不碰 sortBar**。

**结论与处置**：

- **代码上无可修缺陷**，链路是通的。用户的「没看到」最可能是**没点进具体收藏夹**：清单第 159 条要的是「进入自己的收藏夹」后顶部出现，而入口①的第一屏是 `FavoriteFolderListActivity`（**文件夹列表，有意不放排序行**——排序是视频内容的属性，文件夹列表排不了）。用户「两个地方有…请注意了」是指路语气（叮嘱别只修一处），不是陈述已经看到。
- **本轮的处置**：① 在此记录事实（排序行的确切位置 = 点进具体收藏夹后的**内容页顶部**；两处入口都会到达同一个 `FavoriteVideoListActivity`；收藏夹列表页有意不放）；② 在 `setupSortBar()` 末尾加**一行临时诊断埋点**，供真机确认运行时状态。

| 位置 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/user/favorite/FavoriteVideoListActivity.kt` | `setupSortBar()` 末尾加一行 `Log.e`，打印 `sortBar.visibility` + `mediaId` + `fid` + `readOnly`。**只加日志，不加逻辑、不改 visibility、不加网络请求** |

**真机排查指令**：`adb logcat -s "debug-收藏夹排序"` —— 正常应看到 `sortBar.visibility=0 mediaId=<非0> fid=<非0> readOnly=false`（`0` 即 `View.VISIBLE`）。若看到 `visibility=8`（`GONE`）则说明有别的代码路径在点亮后又把它设回去了，那是新线索。埋点沿用了 `api/FavoriteApi.java:92-97` 已有的 `"debug-收藏夹…"` tag 风格，**倾向保留**（本项目已有同类埋点先例，且对 Debug 包无害）。

**未做**：没有改 `docs/review/real-device-regression-checklist.md` 第 159 条（不属本轮范围）；没有为了「更醒目」给排序行加视觉标识（无依据，且会动 `res/layout/activity_simple_refresh.xml`，那是稍后再看那条线的范围）。

### C. 关注分组移动成员：从「完全没有入口」到「长按成员即可移动」

**用户原话**：「我不能移动被我关注的人到其他分组」。排查确认这不是「入口坏了」，而是**入口根本不存在**：

- `activity/user/FollowUsersActivity.kt` 原本只有分组的**增/改/删**（C21：`showGroupMenu` 重命名/删除、`showCreateGroupDialog` 新建）；`groupBar` 上只有「新建分组」一个按钮。
- `adapter/user/FollowGroupAdapter.kt` 的成员项**只有短按** `BiliTerminal.jumpToUser`，**没有长按、没有任何成员操作入口**。
- `api/FollowApi.java` 有 `createFollowTag`/`renameFollowTag`/`deleteFollowTag`，**没有移动成员**。
- 台账 `docs/review/fix-progress.md` §三十（C21）当初明确写了「**不做分组内成员的增删**（那是另一套「从关注列表勾选后加入分组」的交互，收益低）」——这正是现在用户抱怨的缺口。
- 注：`api/RelationApi.java` **不存在**（曾以为接口在这里）。接口依据是 `bilibili-API/docs/user/relation.md:2674`。

**接口快照**（`bilibili-API/docs/user/relation.md:2674-2722`）：

```
POST https://api.bilibili.com/x/relation/tags/moveUsers
参数：beforeTagids（原分组 id，逗号分隔）/ afterTagids（新分组 id，逗号分隔）
      / fids（待移动用户 mid 列表，逗号分隔）/ csrf
错误码：0 成功 / -111 csrf 失败 / -101 未登录 / -400 请求错误 / 22104 分组不存在 / 22105 未关注
```

注意这是**移动**（不是取关，也不是「复制到多个分组」——后者是 `tags/addUsers`）。把人移出分组而不取关的办法是把 `afterTagids` 传 0（默认分组）。

| 位置 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/api/FollowApi.java` | ① 新增 `moveFollowTagUsers(int beforeTagid, int afterTagid, List<Long> fids)`：空列表直接返回 `-400` 不发请求；走 `FormData` 装 `beforeTagids`/`afterTagids`/`fids`/`csrf`，POST 到 `moveUsers`。② 新增纯函数 `joinMids(List<Long>)`：把 mid 列表拼成逗号分隔串（`null`/空返回空串）。③ `tagErrorMsg` 补 `case 22105: "你还没有关注这个人"`（`22104` 原本已有） |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/user/FollowGroupAdapter.kt` | ① 新增成员长按回调 `setOnUserLongClickListener(listener: ((FollowTag, UserInfo) -> Unit)?)`（带上成员**所在分组**，因为移动接口必须知道原分组 id）。② `onBindViewHolder` 的 `UserHolder` 分支加 `setOnLongClickListener`。③ 新增私有 `getUserOwnerGroup(position)`：只在位置确实落在某个展开组的成员区间时才返回该组（区别于 `getGroupForPosition`，后者对成员位置也返回该组） |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/user/FollowUsersActivity.kt` | ① `loadGroupMode()` 里挂 `setOnUserLongClickListener`。② 新增 `showMoveUserDialog(fromTag, user)`：用 `TerminalDialog.singleChoice` 列目标分组，**排除当前分组**（移到自己等于没做）但**保留默认分组**（移出分组而不取关的唯一办法）；没有别的分组时提示「还没有别的分组可以移过去」。③ 新增 `confirmMoveUser(fromTag, toTag, user)`：`TerminalDialog.confirm`（`confirmIsDanger = false`，移动不是破坏性操作）确认后走 `CenterThreadPool.run` → `FollowApi.moveFollowTagUsers` → 成功提示「已移动到「X」」+ `loadGroupMode()` 整页重拉（分组归属和人数都变了，服务端才是唯一真相）；失败显示 `tagErrorMsg(code)` |
| `app/src/test/java/com/RobinNotBad/BiliClient/api/FollowApiMoveUsersTest.kt` | 新增，10 例（见下） |

**单测覆盖**（`FollowApiMoveUsersTest`）—— 按「请求与错误码→文案分离」的原则，只测不联网的纯逻辑：

- `joinMids`：单个 mid 不加分隔符 / 多个 mid 逗号连接 / 空列表返回空串 / `null` 返回空串而不是崩（4 例）；
- `moveUsersFields`（**字段名断言**）：字段名恰好是 `beforeTagids`/`afterTagids`/`fids`/`csrf` 四个 / 原分组与新分组用复数形式且是单值 / `fids` 是逗号分隔串 / 移动到默认分组时 `afterTagids` 为 `0` / `csrf` 为 `null` 时退化成空串而不是 `"null"` 字面量（5 例）。**字段名写错是本接口最容易犯又最难发现的错**——服务端对错字段名一律回 `-400`，真机上只显示「请求出错了，请稍后再试」，猜不到是拼写问题；为此把字段组装抽成纯函数 `moveUsersFields(...) : Map<String, String>`（返回 `Map` 而非 `FormData`，以免把依赖 Android 的 `NetWorkUtil` 拖进 JVM 单测）；
- `tagErrorMsg`：成功码返回空串 / -101 与 -111 有专门文案 / **22104 分组不存在** / **22105 未关注** / -400 兜底 / 未知码兜底带上错误码本身（6 例）。

**边界与取舍**：

- 移动是写操作但**不是破坏性操作**（不取关、不丢数据），所以 `confirmIsDanger = false`，确认按钮走主色而非危险色。
- 目标分组列表**保留默认分组（tagid 0）**：这是「把成员移出分组」的正规做法，比让人去取关再关注友好得多。
- `singleChoice` 按实现**不会自动关闭**，所以回调里显式 `sheet.dialog.dismiss()`。

**真机验证要点 —— 弹窗里显示的是分组名，不是 id**：

目标分组列表的文案直接取 `groupList` 里每个 `FollowTag.name`，也就是 `x/relation/tags`（`getFollowTags`）返回的 `name` 字段。接口快照 `bilibili-API/docs/user/relation.md:2076-2079` 的响应示例确认**默认分组服务端返回的 name 就是 `"默认分组"`**（`{"tagid": 0, "name": "默认分组", "count": 340, "tip": ""}`），`:2046` 的字段表也写明 `-10：特别关注 / 0：默认分组`。

所以真机上：

- **看不到裸的 `0` / `-10`**，弹窗里显示的是「默认分组」「特别关注」这样服务端给的可读名字。若真机上真的出现 `0`，说明该账号的 `tags` 响应异常，是**新线索**，需要单独查。
- 想**把成员移出分组但不取关**：长按该成员 → 在弹窗里选「**默认分组**」。这就是 `afterTagids=0` 那条路径（接口文档 `relation.md:2574` 对 `addUsers` 的同款说明：「如需删除分组中的成员，请将 `tagids` 设为 0，即移动至默认分组，而不是取关」）。
- 「特别关注」（tagid -10）也在列表里，所以也能把成员移进/移出特别关注；`FollowUsersActivity.isEditableTag(tag) = tag.tagid > 0` 只限制**改名/删除**，不影响移动的目标选择。
- 弹窗**不会**列出成员当前所在分组（`filter { it.tagid != fromTag.tagid }`），避免「移到自己」这种空操作。
- 注意 `count` 是服务端快照：移动成功后走 `loadGroupMode()` 整页重拉，人数才会刷新；**UI 不做本地乐观更新**（有意为之，分组归属以服务端为准）。


### 验证

`.\gradlew.bat :app:assembleDebug --offline --no-build-cache --no-configuration-cache` → **BUILD SUCCESSFUL in 56s**。

`.\gradlew.bat :app:testDebugUnitTest --offline --no-build-cache --no-configuration-cache` → **398 例中 3 例失败，均不在本轮改动范围内**：

| 测试类 | 结果 | 归属 |
|---|---|---|
| `util.SessionSorterTest` | **7/7 通过** | 本轮 A |
| `api.FollowApiMoveUsersTest` | **15/15 通过** | 本轮 C |
| `api.DynamicApiTest` | 20 例中 2 例失败 | 非本轮（话题广场那条线） |
| `api.NoteApiTest` | 16 例中 1 例失败 | 非本轮（笔记那条线） |

失败详情（供归属方参考，本轮**未修**）：

- `DynamicApiTest.parseTopicNodes_picksEveryTopicNodeInOrder` at `DynamicApiTest.kt:151` —— `java.lang.AssertionError: expected:<#A#> but was:<null>`；
- `DynamicApiTest.parseTopicNodes_topicWithoutJumpUrlIsStillCollected` at `DynamicApiTest.kt:203` —— 同样是 `AssertionError`；
- `NoteApiTest.failureText_hidesGenericFallback` at `NoteApiTest.kt:198` —— `java.lang.NullPointerException: null cannot be cast to non-null type kotlin.String`。

**一次真实的编译错误（已修，记录在此以免重犯）**：首次 `:app:assembleDebug` 报

```
e: adapter/user/FollowGroupAdapter.kt:300:55 Argument type mismatch: actual type is
   '...FollowGroupAdapter.GroupItem', but '...model.FollowTag' was expected.
```

原因是 `getUserOwnerGroup(position)` 返回 `GroupItem?`（它要遍历展开后的扁平位置，自然产物就是 `GroupItem`），而 `setOnUserLongClickListener` 的形参是 `FollowTag`，我漏了取 `.tag`。修法是 `userLongClickListener?.invoke(owner.tag, user)`——**不是强转**（`GroupItem` 本就持有 `val tag: FollowTag`，见 `FollowGroupAdapter.kt:352-355`）。

**教训**：这个错误纯函数单测**测不到**（泛型/类型层面的错，只有编译器能发现）。本轮 15 + 7 例单测全绿的同时 `compileDebugKotlin` 失败，正说明「单测绿 ≠ 编得过」，**`assembleDebug` 必须跑**。

### 交叉引用

- 接口依据：`bilibili-API/docs/user/relation.md:2674-2722`（`tags/moveUsers`）、`:2568-2616`（`tags/addUsers`，对照「复制/添加」与「移动」的区别）；`bilibili-API/docs/message/private_msg.md:1150`/`:1173-1179`（`op_type` 语义，确认没写反）、`:510`（`session_list` 排序）、`:12`（`top_ts` 字段）。
- 台账前序：§二十八（C19 收藏夹排序，`fix-progress.md:2178-2232`）、§三十（C21 关注分组增删改，`:2292-2342`，其中「不做分组内成员的增删」正是本轮 C22 补上的缺口）。
- 清单条目：`docs/review/real-device-regression-checklist.md:102`（第 40 条 op_type）、`:266`（第 159 条收藏夹排序）、`:292-301`（第 179-188 条关注分组）。
- 相关架构：`docs/architecture-map.md` 中 `PrivateMsgApi`/`FavoriteApi`/`FollowApi` 的条目。





## 三十三、26.10.05：片头片尾跳过默认开启 + 新增「通知设置」大类

> 本轮对应两条用户反馈：
> ①「片头片尾自动跳过 —— 没看到实现了，且默认不是开启吗，这边默认关闭了」
> ②「另外各种新加的通知应该在设置里新增一个大类-通知设置来关闭他，默认开启」

### 一、片头片尾自动跳过（任务 B）

#### 结论先说：功能不是没实现，是默认关 + 引导判据有问题

用户说「没看到实现了」，容易误判成「压根没接线」。逐行读源码后的结论是：**接线是完整的，跳过逻辑本身没有任何缺失**，
用户看不到效果的唯一原因是默认值写死为 `false`。证据链如下（改前状态）：

- `app/src/main/java/com/RobinNotBad/BiliClient/player/ViewPointSkip.kt`（91 行，纯 object、零 Android 依赖）：
  `TYPE_OP = 1` / `TYPE_ED = 2`、`MAX_SEGMENT_SECONDS = 600.0`、`data class Segment(type, fromSec, toSec)`、
  `isSkippable(type)`、`keyOf(segment)`、`buildSegments(raw)`（只保留 1/2 类型、`toSec > fromSec`、时长 ≤ 600s，按起点排序）、
  `segmentAt(pos, segments)`（**左闭右开** `[from, to)`——因为跳过后的落点正好等于 `toSec`，右闭会自我重复命中）、
  `shouldSkip(pos, segments, handled)`（命中 `handled` 返回 null，保证每段只跳一次）、`keyForManualSeek(pos, segments, handled)`。
- `app/src/main/java/com/RobinNotBad/BiliClient/activity/player/PlayerActivity.kt`：`skipSegments` 字段（:321）；
  `maybeAutoSkipOpEd(positionSec: Double)`（:2303-2313）挂在**既有的 250ms 主线程定时器** `progressChange()` 里（调用点 :1197）；
  命中后 `seekToPosition((segment.toSec * 1000.0).toLong())` + `lastSkippedSegment = segment` + `showSkipUndoSnack(segment)`；
  `showSkipUndoSnack`（:2316-2328）用 `MsgUtil.createSnack(anchor, "已跳过片头/片尾", LENGTH_LONG, MsgUtil.Action("撤回"){...})`；
  `onUserSeekTo(positionMs)`（:2336-2341）在 `seekToPosition()`（:2282）里调用，避免用户手动拖进片头又被弹走；
  `loadViewPoints()`（:2905-2941）调 `PlayerApi.getViewPoints(aid, cid)` → `ViewPointSkip.buildSegments(...)`，
  在 `runOnUiThread` 里重置 `skipSegments` / `skipHandled` / `lastSkippedSegment` 并调 `maybeShowSkipGuide()`（:2931）。

**「没看到」的直接原因 = 四处调用点全部把默认值写死成 `false`**（改前）：

- `PlayerActivity.kt:2295`（`needViewPoints()` 里的 `getBoolean(SettingsKeys.PLAYER_SKIP_OP_ED, false)`）
- `PlayerActivity.kt:2305`（`maybeAutoSkipOpEd` 里的开关判断）
- `PlayerActivity.kt:2351`（`maybeShowSkipGuide` 里的开关判断）
- `app/src/main/java/com/RobinNotBad/BiliClient/activity/settings/SettingTerminalPlayerActivity.kt:131-132`
  `SettingSection("switch", "自动跳过片头片尾", SettingsKeys.PLAYER_SKIP_OP_ED, getString(R.string.desc_player_skip_op_ed), "false")`

**用户「默认不是开启吗」是有根据的**：设计参照对象 PiliPlus 的同类开关默认就是跳过。
`D:\tmp\piliplus\lib\utils\storage_pref.dart:977-979`：

```dart
static SkipType get pgcSkipType =>
    SkipType.values[_setting.get(SettingBoxKey.pgcSkipType) ?? SkipType.skipOnce.index];
```

默认档位是 `SkipType.skipOnce`（`D:\tmp\piliplus\lib\models\common\sponsor_block\skip_type.dart` 五档：
`alwaysSkip('总是跳过') / skipOnce('跳过一次') / skipManually('手动跳过') / showOnly('仅显示') / disable('禁用')`）。
（注：PiliPlus 里 `SKIPOPED` 只出现在生成的 protobuf（`lib/grpc/bilibili/playershared.pbenum.dart`），
其真实实现走 sponsor_block 的 `pgcSkipType`，不是那个枚举。）B 站官方播放器同样默认跳过。

#### 改动 1：默认值改为开启，且收敛到唯一来源

新建 `app/src/main/java/com/RobinNotBad/BiliClient/player/SkipOpEdPrefs.kt`（纯 object，零 Android 依赖）：

```kotlin
const val DEFAULT_ENABLED = true
fun shouldShowGuide(hasSegments: Boolean, alreadyGuided: Boolean): Boolean = hasSegments && !alreadyGuided
fun guideText(enabled: Boolean): String        // 开="已自动跳过片头/片尾，可在下方撤回"；关="这个视频有片头片尾，可以自动跳过"
fun guideActionText(enabled: Boolean): String  // 开="知道了"；关="开启"
```

**为什么必须收敛成一个常量**：改前四个调用点各写一遍字面量 `false`，改默认值时只要漏掉一处，就会出现
「设置页显示开着、播放器却不跳」这种极难排查的半生效状态。现在四个点全部引用 `SkipOpEdPrefs.DEFAULT_ENABLED`：

- `PlayerActivity.kt` 新增 `private fun skipOpEdEnabled(): Boolean = SharedPreferencesUtil.getBoolean(SettingsKeys.PLAYER_SKIP_OP_ED, SkipOpEdPrefs.DEFAULT_ENABLED)`
- `PlayerActivity.needViewPoints()` 的默认值参数 → `SkipOpEdPrefs.DEFAULT_ENABLED`
- `PlayerActivity.maybeAutoSkipOpEd` → `if (!skipOpEdEnabled()) return`
- `SettingTerminalPlayerActivity.kt:132` 的 `SettingSection(..., "false")` → `SkipOpEdPrefs.DEFAULT_ENABLED.toString()`

#### 改动 2：老用户显式关过的必须保持关（不需要任何迁移代码）

`SharedPreferences.getBoolean(key, def)` 的语义就是「**键不存在时才用 def**」。因此只改默认值就天然满足
「新装默认开 + 升级后尊重用户显式选择」，**不需要写任何迁移/重置逻辑**（写了反而危险）。
仓库里已有同样的判定范式可参照：`app/src/main/java/com/RobinNotBad/BiliClient/util/SharedPreferencesUtil.java:132-144`
的 `loadMenuEnabled()` 就是用 `sharedPreferences.contains(key)` 区分「已存值 / 缺失」。

新建 `app/src/test/java/com/RobinNotBad/BiliClient/player/SkipOpEdPrefsTest.kt`（JUnit4 + 共享 `FakeSharedPreferences`，
`@Before` 注入 / `@After` 置 null），共 9 例，其中**「老用户显式关过之后仍然是关闭」是必须保留的一条**——
它防的正是用户骂「我明明关过怎么又给我开了」：

- `默认值是开启`
- `从未存过时读取默认值得到开启`
- **`老用户显式关过之后仍然是关闭`**
- `老用户显式开过之后仍然是开启`
- `没引导过且有片段时要引导`
- `引导过之后不再引导`
- `视频没有片头片尾时不引导`
- `开关已开时文案是提示而不是劝开`
- `开关未开时文案是劝开`

#### 改动 3：引导判据与开关解耦（否则默认一改，引导立刻变死代码）

改前的 `maybeShowSkipGuide()` 是三重 return：`skipSegments` 空就 return → **开关开着就 return** → 引导过就 return。
一旦默认值改成 `true`，第二个 return 永远命中，**这段引导就成了永远跑不到的死代码**，
真机清单第 200 条也就永远测不到。改为只看「引导过没有」：

- 判据：`SkipOpEdPrefs.shouldShowGuide(skipSegments.isNotEmpty(), getBoolean(PLAYER_SKIP_OP_ED_GUIDED, false))`
- 弹过即写 `putBoolean(PLAYER_SKIP_OP_ED_GUIDED, true)`，之后进任何视频都不再弹
- 文案随开关状态分两套：开关已开 →「已自动跳过片头/片尾，可在下方撤回」+「知道了」（这是**提示**，不是劝开）；
  开关未开（用户自己先关掉的）→「这个视频有片头片尾，可以自动跳过」+「开启」，点开启才写 `PLAYER_SKIP_OP_ED = true`
- `PLAYER_SKIP_OP_ED_GUIDED`（`player_skip_op_ed_guided`）**只是「引导已弹过」的记账位，不出现在设置页**，别当成用户可见开关

### 二、新增顶层「通知设置」大类（任务 A）

#### 关键核实：两个开关早已存在且早已接线，问题只是「找不到」

调查用户诉求时先做了全库 grep，结论推翻了「需要新增开关」的初始假设：

- `app/src/main/java/com/RobinNotBad/BiliClient/util/SettingsKeys.kt:59`
  `const val PRIVATE_MSG_NOTIFY_ENABLE = "private_msg_notify_enable"`
- `app/src/main/java/com/RobinNotBad/BiliClient/util/SettingsKeys.kt:64`
  `const val BANGUMI_UPDATE_NOTIFY_ENABLE = "bangumi_update_notify_enable"`
- 唯一读取方 `app/src/main/java/com/RobinNotBad/BiliClient/BiliTerminal.kt:331`
  `val notifyEnabled = SharedPreferencesUtil.getBoolean(SettingsKeys.PRIVATE_MSG_NOTIFY_ENABLE, true)`
  → `MsgNotifier.shouldNotify(previousUnread, totalUnread, notifyEnabled)` → `MsgNotifier.notifyNewMessages(...)`
- 唯一读取方 `app/src/main/java/com/RobinNotBad/BiliClient/BiliTerminal.kt:346`
  `if (SharedPreferencesUtil.getBoolean(SettingsKeys.BANGUMI_UPDATE_NOTIFY_ENABLE, true) && ... mid != 0L)`
  → `BangumiUpdateChecker.checkAndNotify(it)`

**两处默认值本来就都是 `true`**。所以本任务**不新增任何 SP 键、`SettingsKeys.kt` 一行未动**——
这也意味着老用户升级后天然保持开启，完全不需要迁移。
问题只在位置：两项原先被埋在「内容与浏览 → 通用偏好 → 更新提醒」段（`SettingPrefActivity.kt:38-41`），入口太深，用户找不到。

`grep` 全库 19 处引用确认：除 `SettingsKeys.kt` 的定义、`BiliTerminal.kt` 的两个读取点、`SettingPrefActivity.kt` 的旧 UI、
`strings.xml` 的文案、`SettingsKeysTest.kt:29`（只钉键名字符串）以及文档提及外，**没有任何单测或代码依赖旧 UI 位置**，
可以安全移除。

#### 形态选择：复用「本页另一个 group_type 分组」，不新建 Activity

设置页有两种子页面形态：①独立 Activity（要改三处含 manifest）；②同一个 `SettingGroupActivity` 里的另一个 `group_type` 分组。
选②，需要动的只有四处：`buildContent` 加分支 + 写 `buildXxxGroup()` + 上级分组加 `nav` + `SettingsIndex` 加条目，
**不需要新 Activity、manifest、布局文件**。范式完全照抄既有的 `GROUP_APPEARANCE`。

#### 落地改动（5 个文件）

1. `app/src/main/java/com/RobinNotBad/BiliClient/activity/settings/SettingGroupActivity.kt`
   - 新增顶层常量 `const val GROUP_NOTIFY = "notify"`（紧跟 `const val GROUP_APPEARANCE = "appearance"`）
   - `buildContent(groupType)` 的 `when` 加分支 `GROUP_NOTIFY -> buildNotifyGroup()`
   - 新增 `buildNotifyGroup()`，复用既有的 `title()` / `switch()` 私有 helper（未新增 helper）：
     ```kotlin
     private fun buildNotifyGroup() {
         title("通知开关")
         switch("新消息通知", getString(R.string.desc_private_msg_notify_enable),
             SettingsKeys.PRIVATE_MSG_NOTIFY_ENABLE, true)
         switch("追番更新提醒", getString(R.string.desc_bangumi_update_notify_enable),
             SettingsKeys.BANGUMI_UPDATE_NOTIFY_ENABLE, true)
     }
     ```
   - 只放「**会响通知的开关**」。「新动态数量检查」「消息数量检查」等只影响应用内红点、不产生系统通知的项**仍留在通用偏好**——
     搬进来会让用户误以为关掉它们就没通知了。（中途曾一度多加一个「后台自动检查更新」开关，判定为 scope creep 已删除还原。）
2. `app/src/main/java/com/RobinNotBad/BiliClient/activity/settings/SettingMainActivity.kt`
   - `buildGroups()` 在 `buildContentGroup()` 之后插入 `buildNotifyGroup()`（第一层级第 5 个分组）
   - 新增：
     ```kotlin
     private fun buildNotifyGroup() {
         addGroup(
             R.drawable.icon_announcement,
             "通知设置",
             "新消息、追番更新等通知栏提醒的开关",
             GROUP_NOTIFY
         )
     }
     ```
   - 图标用 `R.drawable.icon_announcement`（`res/drawable/` 下无 bell/notification 类图标，`icon_announcement` 语义最贴近）
3. `app/src/main/java/com/RobinNotBad/BiliClient/activity/settings/SettingPrefActivity.kt`
   - 从「更新提醒」段**移除**「新消息通知」「追番更新提醒」两项，替换为三行中文注释（写明已迁到 `buildNotifyGroup()`，
     别再加回来——**同一开关留两个入口会出现「一处改了另一处不刷新」的假故障**，用户会以为设置没生效）
   - 该段其余项不动：新动态数量检查 / 消息数量检查 / 最近更新的UP主 / 私信自动已读 / 夜深了 / 后台自动检查更新
4. `app/src/main/java/com/RobinNotBad/BiliClient/activity/settings/SettingsIndex.kt`（设置页全局搜索的索引）
   - 一级分组加条目 `Entry("通知设置", "新消息、追番更新等通知栏提醒的开关") { a -> openGroup(a, GROUP_NOTIFY, "通知设置") }`
   - `addLeafItems(list, "通用偏好", SettingPrefActivity::class.java, ...)` 列表里**删掉** `"新消息通知", "追番更新提醒"`
   - 新增两条可搜索叶子项：
     ```kotlin
     list += Entry("新消息通知", "发现新消息时在通知栏提醒") { a -> openGroup(a, GROUP_NOTIFY, "通知设置", "新消息通知") }
     list += Entry("追番更新提醒", "追的番剧有新一集时在通知栏提醒") { a -> openGroup(a, GROUP_NOTIFY, "通知设置", "追番更新提醒") }
     ```
     **`Entry.name` 必须与 `buildNotifyGroup()` 里 `SettingSection.name` 严格一致**——分组页靠 `highlight` 参数按项名滚动定位。
5. `app/src/main/res/values/strings.xml`：**未新增任何条目**，直接复用既有的 `:165 desc_private_msg_notify_enable`
   与 `:167 desc_bangumi_update_notify_enable`（文案本身已经写得很清楚：只在打开应用时对比、不做后台定时检查、点通知进哪里）。

`GROUP_NOTIFY` 与 `SettingsIndex` 同属 `package com.RobinNotBad.BiliClient.activity.settings` 顶层常量，**无需 import**。

### 三、明确没有碰的东西

- **`SettingsKeys.kt` 一行未动**（共享热点文件，本轮只做「复用既有键」，不重排、不重命名、不动 `mid` 等其它键）
- **`app/src/main/java/com/RobinNotBad/BiliClient/util/MsgNotifier.kt` 未动**：通知 ID 无冲突风险。
  现状（供核对）：`CHANNEL_ID = "private_msg_channel"`（:28）、`NOTIFICATION_ID = 1029`（:31，注释写明 1027/1028 已被
  `DownloadService`/`PlaybackService` 占用）、`BANGUMI_CHANNEL_ID = "bangumi_update_channel"`（:101）、`BANGUMI_NOTIFICATION_ID = 1030`（:104）
- 未触碰话题、稍后再看、评论、动态、收藏夹、关注分组等其它模块

### 四、真机验收步骤

**片头片尾（第 199/200 条）**
1. 覆盖安装（或新装）后**不碰任何设置**，进一个有片头/片尾的番剧 → 应自动跳到正片、片尾处自动跳过，下方出现「撤回」，点撤回回到被跳过的地方。
2. 设置 → 播放器 → 关掉「自动跳过片头片尾」→ 重进视频 → **必须完全不跳**。
3. 首次遇到带片头片尾的视频 → 弹一次引导，之后再进别的视频**不再弹**。
4. 老用户场景：在旧版本里手动关过该开关 → 覆盖安装 → 该开关**仍然是关的**、视频仍然不跳。

**通知设置（第 202-206 条）**
1. 主页 → 设置 → 第一层级看到「通知设置」→ 进去有「新消息通知」「追番更新提醒」，**默认都是开**。
2. 留一条未读 → 回主页触发未读变多 → 通知栏出现「N 条新私信」（点通知进消息页）；关掉「新消息通知」→ 再制造一次未读变多 → **不再出现**；重开 → 恢复。
3. 追一部在更新的番 → 打开应用触发追番检查（同一部番最新集变了才提醒）→ 通知栏出现「《XXX》更新了」（点通知进追番列表）；关掉开关 → 再次触发 → **不再出现**。
4. 设置 → 内容与浏览 → 通用偏好 → 「更新提醒」段里**不应再有**这两项；在设置页搜索「新消息通知」→ 应跳进「通知设置」并滚动定位到该开关。
5. 升级不丢设置：旧版本里关掉「新消息通知」→ 覆盖安装 → 进「通知设置」→ 该开关**仍然是关的**。

### 五、构建与测试状态

- `./gradlew.bat :app:assembleDebug` 与 `./gradlew.bat :app:testDebugUnitTest` 的**最终结果见本章末尾的补充**；
  本轮改动涉及 `res/` 之外的资源引用与新增 Kotlin 文件，验证按团队规则执行
  （`--offline --no-build-cache --no-configuration-cache`，需要时 `:app:clean` 与 `:app:assembleDebug` 分两次调用，
  避免 `mergeDebugResources` 回放陈旧结果验出**假绿**）。
- 新增单测：`app/src/test/java/com/RobinNotBad/BiliClient/player/SkipOpEdPrefsTest.kt`（9 例，覆盖默认值 / 旧值尊重 / 引导判据 / 文案）。
- 既有单测 `app/src/test/java/com/RobinNotBad/BiliClient/util/SettingsKeysTest.kt`、`util/MsgNotifierTest.kt`、
  `player/ViewPointSkipTest.kt` 不受影响。

### 六、交叉引用

- `docs/architecture-map.md` §7.7（片头片尾跳过，整段重写）、§7.15（新消息通知）、§7.16（追番更新提醒）已同步。
- `docs/review/real-device-regression-checklist.md`：第 199/200 条重写，新增第九章含第 202-206 条，总数 **201 → 206**。

## 三十四、26.10.05：话题广场加载不出来 + #话题# 不解析（C23）

**用户报告（原文）**：「话题广场加载不出来，且一般动态里的话题比如#A#没有正常解析，解析应该是蓝色的且可以跳转」。

这是**两个独立缺陷**：一个是数据源已废弃，一个是富文本分支缺失。分开说。

### A. 话题广场「加载不出来」：真因是接口已废弃 + 静默空列表

**任务书里写的根因（「可能抛异常」）是错的**，实测后纠正如下。

原实现（`api/TopicApi.java`）打的是
`https://app.bilibili.com/x/topic/web/dynamic/rcmd?page_size=9&source=Web&web_location=333.1365`，
代码形如：

```java
if (all.getInt("code") != 0) throw new JSONException(all.optString("message"));
JSONObject data = all.optJSONObject("data");
if (data == null) return new ArrayList<>();   // ← 实际走到这里
```

**实测结论**：该端点**恒返回 `{"code":0,"message":"OK","ttl":1,"data":null}`**。
带参数/不带参数、带游客 Cookie（`buvid3`）/不带、伪装浏览器 headers、`page_size` 取 9/25/26 —— 全部一样。

关键在于 **`code` 是 0**，所以那句 `if (code != 0) throw` **永远不会触发**，
代码静默走到 `data == null → 返回空列表`，页面于是 `showEmptyView()`。
用户看到的「加载不出来」= **静默空列表**，既不是抛异常、也不是 `loadFail`——
所以按「网络异常/抛异常」方向排查全是死路。**接口已废弃，不再下发数据。**

**替代数据源（实测可用）**：`https://api.bilibili.com/x/topic/pub/search`

| 实测项 | 结果 |
|---|---|
| `keywords` 留空 | 按热度返回**整页话题**（稳定 20 条，连拉 3 次一致） |
| 首条 | `小剧场过大年` `id=1269670` `view=2247738435` `discuss=2204644` |
| 字段 | 只有 `id/name/view/discuss/stat_desc/description/show_interact_data`，**没有 `dynamics`** |
| 翻页 | `data.page_info{offset:20,has_more:true}` |
| 登录要求 | 匿名可用 |

所以 `keywords` 留空这一个用法**本身就是广场**：零硬编码词表、内容随服务端热度自然更新、无需登录
（这正是「不要用硬编码热门词表」要求的落地方式——不需要退化词表，因为空关键词就够用）。
PiliPlus 亦已改用同名端点（`lib/http/api.dart:866 topicPubSearch`）。

| 位置 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/api/TopicApi.java` | ① `getRecommendedTopics()` 改为 `return searchTopics("", 20);`，**保留「空列表 = 没数据、不抛异常」语义**（`DynamicTopicActivity.loadPlaza()` 依赖它走 `showEmptyView()`）；② 新增 `searchTopics(String keywords, int pageSize)`；③ 新增纯函数 `parseSearchResponse(JSONObject all)`（`code != 0` 或 `data == null` 返回空列表）；④ 加 `import java.net.URLEncoder;` |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/dynamic/TopicAdapter.kt` | stats 文案改用 `discuss`：`"${toWan(discuss)} 讨论 · ${toWan(view)} 浏览"`；`discuss` 也为 0 时退化成只显示浏览数。原因：新端点**不返回 `dynamics`**，原写法会永远显示「**0 动态**」，属于误导 |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/dynamic/DynamicTopicActivity.kt` | `loadPlaza()` 空态显式覆盖文案为「话题广场没有取到数据\n可能是服务端暂时没有下发\n点我重试」（`setOnEmptyRetry` 会写入通用文案「啥都木有~」，这里在其后覆盖），让用户看懂是**服务端没数据**而不是自己操作错 |

### B. `#话题#` 不解析：富文本 switch 缺 TOPIC 分支

动态正文与 Opus 段落各有一处解析富文本节点的 `switch`，**都没有 `RICH_TEXT_NODE_TYPE_TOPIC` 分支**，
于是话题节点被当普通文本落进 `stringBuilder`——**既不上色也点不动**。共 3 个渲染点：

| 位置 | 原状 | 改动 |
|---|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/api/DynamicApi.java` `analyzeTextContent`（`:1018`，调用点 `:822`/`:914` 覆盖动态 desc 与 summary） | **完全没有 TOPIC 分支** | 在 `RICH_TEXT_NODE_TYPE_AT` 后新增 `case "RICH_TEXT_NODE_TYPE_TOPIC"` |
| `app/src/main/java/com/RobinNotBad/BiliClient/model/OpusParagraph.java:264`（`analyzeOpus`） | 有分支，但只挂 `LinkClickableSpan`、**没设颜色**（保持黑字） | 改走 `StringUtil.setSingleTopic(...)` |
| `app/src/main/java/com/RobinNotBad/BiliClient/model/OpusParagraph.java:215`（`analyzeText` 的 `TEXT_NODE_TYPE_RICH` 内层 switch） | 只处理 `EMOJI`/`RICH`，TOPIC 退化成黑字 | 新增 `case "RICH_TEXT_NODE_TYPE_TOPIC"`，用 `stringBuilder.length() - text.length()` 算起点 |

三处统一收敛到一个入口，避免「两处真相」：

`app/src/main/java/com/RobinNotBad/BiliClient/util/StringUtil.java` 新增
`public static void setSingleTopic(SpannableStringBuilder stringBuilder, int start, int end, String jumpUrl)`。

**这里踩到并修掉了一个真实的坑（值得记下来）**：最初打算复用现有的
`StringUtil.LinkClickableSpan(text, TYPE_WEB_URL, jumpUrl)`，但读实现后发现**两个问题**：

1. `LinkClickableSpan.onClick` 的 `case TYPE_WEB_URL:` 调的是
   `LinkUrlUtil.handleWebURL(widget.getContext(), text)`——取的是 **`text`（span 的显示文本）**，
   **`val`（第三个构造参数）在这个分支根本不会被读**。
   `StringUtil.java:205` 处之所以能正常工作，是因为那里的 `text` 就是 URL 本身（巧合）。
   我们若把 `#A#` 当 `text`、`jump_url` 当 `val` 传进去，**点击时会拿 `#A#` 去当网址解析**，必然跳不过去。
2. `LinkClickableSpan.updateDrawState` **硬编码 `ds.setColor(Color.rgb(0x66,0xcc,0xff))`（青色）**，
   且 `updateDrawState` 在 span 生效时执行、会**覆盖** `ForegroundColorSpan`，
   所以「先上主色再挂 LinkClickableSpan」的做法**颜色一定会被吃掉**。

于是不复用它，改为新增专用 `StringUtil.TopicClickableSpan`：`updateDrawState` 取
`ColorScheme.INSTANCE.getPRIMARY()`（**不硬编码色值**，符合 AGENTS.md 与「7 套主题」的约定），
`onClick` 直接 `startActivity` 进站内 `DynamicTopicActivity`（带 `topic_id`），不走 Web。

配套在 `app/src/main/java/com/RobinNotBad/BiliClient/util/LinkUrlUtil.java` 新增：

```java
public static final int TYPE_TOPIC = 5;
public static final Pattern TOPIC_ID_PATTERN = Pattern.compile("[?&]topic_id=(\\d+)");
public static long parseTopicId(String jumpUrl);   // 解析不出返回 0，不抛异常
```

话题的 `jump_url` 形如 `https://m.bilibili.com/topic-detail?topic_id=1305890&topic_name=%E5%A4%A9...`
（见 `bilibili-API/docs/dynamic/space.md:2252`、`bilibili-API/docs/opus/features.md:1117`），
只需要其中的 `topic_id` 就能进站内话题页。**解析不出 `topic_id` 时退化为「只上色、不可点」**——
宁可有色不可点，也不要拿 `#话题#` 去当网址请求。

**可点击性依赖**：`app/src/main/java/com/RobinNotBad/BiliClient/adapter/dynamic/DynamicHolder.kt:685` 已对正文
`content` 装了 `StringUtil.ClickableSpanTouchListener`，Opus 路径经
`app/src/main/java/com/RobinNotBad/BiliClient/adapter/article/OpusContentAdapter.kt:397` 的
`StringUtil.setLink(textView)` 也会装上（`StringUtil.java:243`），所以两条路径点击都生效，无需额外改动。

**新增纯函数便于单测**：`api/DynamicApi.java` 新增
`public static java.util.List<android.util.Pair<String,String>> parseTopicNodes(JSONArray richTextNodes)`，
挑出全部 TOPIC 节点的 `text`/`jump_url`。

### 单测覆盖

- `app/src/test/java/com/RobinNotBad/BiliClient/api/TopicApiTest.kt` 新增 4 例：
  `parseSearchResponse` 对 **`data: null`（即废弃端点 rcmd 的返回）返回空列表且不抛** /
  真实 `pub/search` 结构（含「**没有 `dynamics` 字段**」的断言）/ `code != 0`（-101 未登录）与 `null` 入参 /
  跳过无 `id` 项。
- `app/src/test/java/com/RobinNotBad/BiliClient/api/DynamicApiTest.kt` 新增 4 例（针对 `parseTopicNodes`）：
  按顺序挑出全部 TOPIC / **不把 AT、EMOJI、WEB 误当话题** / 空 `text`、`null` 项跳过 /
  **缺 `jump_url` 的话题仍要被收集**（否则会退回黑字）。
- `app/src/test/java/com/RobinNotBad/BiliClient/util/LinkUrlUtilTest.kt` 新增（新文件，5 例）：
  `parseTopicId` 读真实 `jump_url` / 忽略其后的 `topic_name` / `topic_id` 不在首个参数位也能读 /
  缺失或不可解析时返回 `0` 而非抛异常 / 锚定 `[?&]` 防串味。

### 边界与取舍

- **`getRecommendedTopics()` 保持「返回空列表 = 没数据」，不改成抛异常**：调用方 `loadPlaza()` 依赖它走空态；改成抛异常会让「服务端没数据」变成「加载失败」，反而更糟。
- **不引入硬编码热门词表**：`pub/search` 的 `keywords` 留空本身就返回热门话题页，无需词表；也就没有「词表过期」和「每次打开都是那几条」的问题。
- 广场仍是**单页**（`bottom = true`，不翻页），与改造前一致；`page_info.has_more` 已在数据里，将来要翻页可直接接。
- 空态文案**必须诚实**：明确写成「可能是服务端暂时没有下发」，而不是「啥都木有~」——后者会让用户以为自己网络或操作有问题。

### 验证

`.\gradlew.bat :app:assembleDebug` 与 `.\gradlew.bat :app:testDebugUnitTest`（全队串行窗口内跑）。

### 交叉引用

- 接口依据：`bilibili-API/docs/dynamic/topic.md:74`（`feed/topic`，本页话题动态列表用，实测匿名可用、**无需改动**）、`:5376-5448`/`:5539-5559`/`:5718-5723`（TOPIC 节点的 `jump_url` 真实形态）；`bilibili-API/docs/dynamic/space.md:2252`、`bilibili-API/docs/opus/features.md:1117`（带 `topic_name` 的形态）。
- 台账前序：`fix-progress.md:2020-2028`（话题广场/话题页首建，即本轮 C23 修的数据源）。
- 相关架构：`docs/architecture-map.md:527`（`DynamicTopicActivity` 一个 Activity 两种形态）、`docs/watch-optimization-research.md:649`（C10 条目，其中记载的广场走 `rcmd` 已被本轮推翻）。
- PiliPlus 对照：`lib/http/api.dart:866-867`（`topicPubSearch`，已在用）、`:887`（`dynTopicRcmd`，仍在但同样拿不到数据）、`lib/http/search.dart:242-265`（传参形态）；`lib/pages/dynamics/widgets/rich_node_panel.dart:115-132`（TOPIC 渲染参考）。

---

## 二十九、26.10.04 批次 6 复查（C27 视频笔记 + C6b 带图评论）

本节处理用户报的两条：**「笔记无法正常加载」**与**「评论带图报错 12066」**。

> **先说结论，避免误读：**
> - **「笔记无法正常加载」的根因至今未确证**，本次交付的是**可观测性**（分级错误提示 + 真机埋点），
>   **不是「修好了笔记」**。详见 §29.1 / §29.2。
> - **「12066」的确切触发条件也未复现**，无大会员账号、无真机；本次修的是同一条链路上
>   **与官方语义不符的确定性缺陷**（`img_size` 小数被截断），**未与 12066 做因果绑定**。详见 §29.3。

### 29.1 笔记：已排除的与仍存疑的

#### 调用链（现状）

`activity/video/info/VideoInfoFragment.kt:563-569`（点「笔记」）→ 只 `putExtra("aid", videoInfo!!.aid)`，
**不传 `note_id`** → `activity/note/NoteActivity.kt:52-53` 读 `aid`、`note_id` 缺省空串
→ `NoteActivity.kt:66-73` 因 `noteId.isEmpty()` 先走 `NoteApi.getNoteIdsOfVideo(aid)` 取 `ids[0]`
→ `NoteActivity.kt:74` `NoteApi.getNoteInfo(aid, noteId)`。

#### 已排除（有依据，不是这三处）

1. **不是 csrf 问题**：`NoteApi.getNoteInfo`（`api/NoteApi.java:259-276`）按
   `bilibili-API/docs/note/info.md:65-71` 的参数表**不带 csrf**，与文档一致；
   `getNoteIdsOfVideo` 带 csrf 是因为 `bilibili-API/docs/note/list.md:19` 将其标为**非必要**（非禁止），保留无收益损失。
2. **不是 `note_id_str` 精度设计问题**：`pickNoteId` 优先 `note_id_str` 是**正确**的
   ——`list.md:42` 明确 `noteIds` 项类型为 `str`、`:64-66` 示例 `"3809605586518023"`；
   且 `info.md:131-139` 官方示例自己就用 17 位 `note_id=24508729145690112`。**该设计保持原样，一行未动。**
3. **不是 UI 层**：`activity_note.xml` 的 `RotaryScrollView` + 单 `TextView` 渲染链路结构正确，
   `renderBlocks`/`applyStyle` 的样式逐段套用逻辑无缺陷；`parseColor` 已对脏色值做 try/catch。

#### 仍存在的假设（**未验证，禁止当作结论**）

**假设 H1**：`info.md:71` 把请求参数 `note_id` 的类型标为 **`num`**，而 `list/archive` 返回的是**字符串**
（`list.md:42`）。若服务端真按 `num` 解析这个 17 位 id，则**服务端自己**会丢精度
（17 位 > 2^53），查不到 → 返回 **79502**。这可以解释用户报的「笔记无法正常加载」。
**但这只是假设**：`info.md:131-139` 的示例本身用 17 位串，说明服务端**可能**按字符串收；
无有效 Cookie 打真接口，**本地无法证实或证伪**。

**假设 H2**：`getNoteIdsOfVideo` 在未登录/凭据过期时拼出 `&csrf=`（空值），
服务端是否把空 csrf 当参数错误**无证据**。

#### 验证方法（留给有 Cookie 的人）

1. 用有效 SESSDATA 抓 `GET https://api.bilibili.com/x/note/list/archive?oid=<真有笔记的稿件>&oid_type=0`
   ，看 `data.noteIds[0]` 的**原始字符串**。
2. 用它抓 `GET https://api.bilibili.com/x/note/info?oid=<同稿件>&oid_type=0&note_id=<上一步的值>`，
   记录原始响应 `code` / `message`。
3. **对比两种传参**：把同一个 id 分别按字符串（原样）与按 `num`（即先 `Number()` 再转回字符串，
   观察是否已丢精度）各发一次，看服务端返回是否不同。**这是判定 H1 的唯一办法。**
4. 若返回 79502/79503，**服务端的 `message` 原文是唯一权威线索**，请回传。

### 29.2 本次实际交付：可观测性（这才是这次改的东西）

用户看到的一直是笼统的「获取笔记失败」，因为原 `catch (e: Exception)` 把
**断网 / 未登录 / 79502 / 79503 / 解析异常**五种完全不同的情况压成了同一句话。
本次把它拆开：

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/api/NoteApi.java` | 新增纯函数 `failureText(Throwable)`；两个接口补真机诊断埋点 `logFailure(...)` 与成功分支日志；新增 `maskCsrf(String)` 脱敏 csrf；新增 tag 常量 `TAG = "BiliNote"` |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/note/NoteActivity.kt` | `:77-83` 的 `catch` 改为 `failureText(e)` 分级显示（走既有 `showEmpty` 链路，未新造 UI） |
| `app/src/test/java/com/RobinNotBad/BiliClient/api/NoteApiTest.kt` | 新增 4 例纯函数单测（见 §29.4） |

**分级规则**（`NoteApi.failureText`）：

| 异常 | 页面显示 |
|---|---|
| `IOException`（断网 / DNS / 超时） | 「网络异常，请检查网络后重试」 |
| `JSONException` 且 message 是服务端原话 | **服务端原文**（如「没有找到这篇笔记」「这篇笔记还没有正文」「还没有登录喵~」）|
| `JSONException` 且 message 是兜底文案 | 「获取笔记失败」 |
| 其它 | 「获取笔记失败」 |

两个关键取舍：

- **不原样吐异常描述**：断网的描述是 `java.net.UnknownHostException: api.bilibili.com`，
  手表小屏上既长又无意义，也**不该把内部类名暴露给用户**；故按类型给固定文案。
- **判断顺序**：`UnknownHostException` 继承链上是 `IOException`，**先判 `IOException` 再判 `JSONException`**，
  否则网络异常会被 `JSONException` 分支抢走。

**真机埋点**：两个接口的失败分支都打 **code + 服务端 message + 完整 URL**（`Log.e`），
成功分支打「笔记数」与「标题长度 + 正文块数」。后者是为了把
**「接口没给 content」**和**「content 没解析出块」**分开——两者现象都是「还没有正文」，
只看错误码分不清。过滤命令：

```
adb logcat -s BiliNote
```

**csrf 已脱敏**：`maskCsrf` 把 `csrf=` 后的值换成 `***`，日志不留凭据明文；
`note_id` **保留原值**（精度问题正是要查的东西）。

### 29.3 带图评论 12066：改了什么、没改什么

#### 已确证的确定性缺陷（与 12066 **不做因果绑定**）

**`img_size` 小数被截断。** 官方与上游都按浮点处理，只有我们按整数读：

| 依据 | 内容 |
|---|---|
| `bilibili-API/docs/dynamic/publish.md:40` | `img_size` 类型 `number`，内容「已上传图片大小」，单位 `k`（KB，小数）|
| `bilibili-API/docs/dynamic/publish.md:66` | 官方响应示例 `"img_size": 6.261` |
| `bilibili-API/docs/dynamic/card_info.md:133` | `1425.97998046875` |
| 上游 PiliPlus `lib/models_new/upload_bfs/data.dart:5,19` | `double? imgSize;` / `(json['img_size'] as num?)?.toDouble()` |

原先 `api/ReplyApi.java` 用 `public long img_size` + `data.optLong("img_size", 0)`，
会把 `6.261` 读成 `6`、`662.6005859375` 读成 `662`。

改动：
- `api/ReplyApi.java`：`UploadImageData.img_size` 改为 `double`；`optLong` → `optDouble`；
  新增纯函数 `buildPictures(List<UploadImageData>)`（组装 `pictures` JSON 数组）。
- `api/ImageApi.java`：`UploadedImage.size` 改为 `double`。
  **`toDynamicPicJson()` 逻辑未动**（它本来就是对的）。
- `activity/reply/WriteReplyActivity.kt`：`buildPictures()` 改为委托 `ReplyApi.buildPictures(...)`，
  **原有的 `synchronized(uploadDataList)` 保留**（写方 `:241-243` 与读方 `:169-173`、`:361-362`
  都在 `CenterThreadPool.run` 的后台线程上，`ArrayList` 非线程安全，取值快照必须在锁内完成）。

#### 顺带修掉的既有缺陷：私信图片 `size` 小 1024 倍（**HEAD 就存在，非本次引入**）

调用点传的是图床返回的 **KB** 值，而 `api/PrivateMsgApi.java` 的 `buildImageContent(..., long byteSize, ...)`
第 4 参数按**字节数**语义做 `sizeToKb`（`:353-356` 除以 1024），导致 `size` 只有真实值的 1/1024。
官方 `bilibili-API/docs/message/private_msg_content.md:42` 明确单位是**千字节**、`:55` 示例 `55.443`。

改动：新增 `buildImageContentOfKb(url, width, height, sizeKb, imageType)` 显式接收 KB；
调用点（`activity/message/PrivateMsgActivity.kt` 的 `sendImage` 段）改用它。
**保留** `buildImageContent(long byteSize)` 作为委托入口（将来只有字节数的调用方路径仍然正确，
且删除会波及既有单测）。

> **归因澄清（重要）**：`uploaded.size.toLong()` 是**临时编译修复**，唯一目的是让 `Double` 能传给
> `long` 参数。**1/1024 的来源是 `buildImageContent` 的字节数语义与调用点传入的 KB 值不匹配**
> （HEAD 既有缺陷），`.toLong()` 只是让它得以编译通过、从而暴露出来。

#### 已排除（不是这两处）

- **不是字段名写错**：`bilibili-API/docs/comment/readme.md:289-296` 的 `pictures[]` 字段为
  `img_src` / `img_width` / `img_height` / `img_size`（单位 KB），与上游
  `lib/pages/common/publish/common_rich_text_pub_page.dart:519-524` **完全一致**。
- **不是编码问题**：`util/NetWorkUtil.java:367-385` 已用 `charset=utf-8`；
  `sendReply` 对 `message` 与 `pictures` 各自 `URLEncoder.encode(..., "UTF-8")`。
- **`plat` 非必要**：`docs/comment/action.md:21`、`docs/comment/readme.md:225`、`docs/comment/list.md:879` 均写明非必要、默认 1。

#### 12066 本身：**未复现**，且是**未文档化错误码**

- 本地 `bilibili-API/` 全库 grep `12066` **零命中**；上游 collector `docs/comment/action.md`
  错误码表末尾明写「**（其他错误码有待补充）**」，表中只到 12001-12052。
- 这解释了用户看到**裸数字**：`WriteReplyActivity.kt` 的 `msgMap` 无此项。
  已补 `12066 to "图片信息异常，请重新选图后再试"`（只保证不再显示裸码，**不是**声称已修好）。

#### 真机验证步骤（改动效果的直接判据）

1. **大会员账号**（带图评论仅大会员，见「图」按钮的 `VipApi.getVipInfo` 判断）→ 视频评论 →「图」→ 选一张 **>1MB** 的图 → 发送。
2. 抓 `POST https://api.bilibili.com/x/v2/reply/add` 的 `pictures` 参数，确认 `img_size` 是
   **小数**（如 `662.6005859375`）而非整数（`662`）。**这是本次改动最直接的判据。**
3. **若仍回 12066**，则说明 `img_size` 不是成因。此时需要拿到 `message` 原文：
   在 `ReplyApi.sendReply` 的失败分支打印完整响应体（`code` / `message` / `data`），
   把 `message` 回传。**12066 无文档，服务端 `message` 是唯一权威线索。**
4. 私信：发一张 >1MB 的图，抓 `msg[content]` 的 `size`，确认是 **KB** 且与图床 `img_size` 一致
   （不再是被多除过 1024 的值）。

### 29.4 新增单测

`app/src/test/java/com/RobinNotBad/BiliClient/api/NoteApiTest.kt`（+4 例）：

- `failureText_givesNetworkAdviceForIoExceptions`（`UnknownHostException` 与 `SocketTimeoutException` 都走网络文案）
- `failureText_passesThroughServerMessage`（含剥掉 `org.json.JSONException:` 前缀）
- `failureText_hidesGenericFallback`（兜底文案不再重复显示；`null` 异常也给文案）
- `failureText_hidesInternalDetailForUnknownTypes`（不外泄内部类名）

`app/src/test/java/com/RobinNotBad/BiliClient/api/ReplyApiTest.kt`（+5 例）：
`buildPictures_keepsFractionalKb`（`6.261` 不被截成 `6`）、`buildPictures_hasAllFourDocumentedFields`、
`buildPictures_emptyMeansNoParameter`（空表/null → **空串**而非 `"[]"`）、
`buildPictures_skipsNullEntriesButKeepsTheRest`、`buildPictures_keepsOrderAndCount`。

`app/src/test/java/com/RobinNotBad/BiliClient/api/PrivateMsgApiTest.kt`（+3 例）：
`buildImageContentOfKb_不再次除以1024`、`buildImageContentOfKb_与字节版对同一个值给出一致结果`
（`56774` 字节 == `55.443` KB，保证两条入口不漂移）、`buildImageContentOfKb_非正数按0处理`。

> `buildPictures` 空表返回**空串**这个细节很关键：`"[]"` 可能被服务端当成「有 0 张图」而报错。

### 29.5 明确没有碰的东西

- **`note_id` 的传参方式一行未动**（在 H1 未确证前改动属赌博，会掩盖真因）。
- **`activity/video/` 下无任何写入**（该目录有其它队友与用户的未提交改动）。
- **`model/Reply.java` 未动**（归其它任务）。
- **`ImageApi.toDynamicPicJson()` 逻辑未动**。
- **`WriteReplyActivity.kt` 的 `pendingUploads++/--`（`:224`/`:253`）未加同步保护**：
  已发现该计数在主线程入口与后台 `finally` 各改一次、无同步，但**与本次用户报的 bug 无关**，
  改动会引入无法验证的风险，故**发现但不动**，仅此记录。
- `activity/message/` 下除 `PrivateMsgActivity.kt` 的 `sendImage` 段外未动。

### 29.6 构建与测试状态

`./gradlew.bat :app:assembleDebug --offline --no-build-cache --no-configuration-cache` 与
`./gradlew.bat :app:testDebugUnitTest --offline --no-build-cache --no-configuration-cache` 的
最终结果见本节末尾补充。**编写本文档时尚未取得构建窗口**（团队规则：同一时刻只允许一人跑 Gradle，
且需在 Lead 协调的窗口内执行，避免多人并发写同一个 `app/build/` 产生假的编译失败）。

**未复现清单（如实记录）**：

- 79502 / 79503 的**确切触发条件未复现**（无有效 Cookie、无真机）。
- **12066 未复现**，其与 `img_size` 截断的因果关系**未建立**。
- 用户是否在真机上因私信 `size` 的 1/1024 看到异常表现**未复现**（该缺陷有代码依据）。

---

## 30. 长按冲突与楼中楼排序二次切换失效（task-4）

用户报告原文：

> 「长按操作按钮/长按动态」与「长按复制」冲突。
> 楼中楼评论详情页排序「切换一次后可以再次切换，但评论顺序不会再次变化」。

两件事各自独立，分列如下。

### 30.1 楼中楼排序二次切换失效（**确证并修复**）

#### 根因

`app/src/main/java/com/RobinNotBad/BiliClient/model/Reply.java` 的
`public static void sortReplies(List<Reply> replies, int sort, int fromIndex)` 首行是：

```java
if (replies == null || sort != SORT_LIKE) return;
```

`SORT_TIME`（`= 0`）**直接 return，从不重排**。于是：

1. 初始 `sort = SORT_TIME`（列表是服务端回复序，恰好是时间序）；
2. 用户切到 `SORT_LIKE` → 真正排序 → 列表变热度序；
3. 用户切回 `SORT_TIME` → **什么都不做** → 顺序永久停在热度序。

⇒ 这是**单向排序**缺陷，**与 `notifyDataSetChanged()` 无关**。用户描述的「可以再次切换」是对的
（`sort` 变量确实翻转、按钮文案确实变化），「顺序不会再次变化」也是对的（因为时间分支是空操作）。
两句描述完全吻合这个根因。

#### ⚠️ 本次是**推翻一个旧的设计取舍**，不是修一处疏忽

**这一点必须写明，否则未来 reviewer 只看新代码会以为旧实现是粗心，进而把本次改动误判为
「修疏忽」——而实际是「改设计决定」。改设计决定需要更充分的理由，理由在下面。**

「时间序 = 不做任何本地重排」**是上一轮有意做出的取舍，不是遗漏**。原文见
`docs/review/fix-progress.md:1758`（§上一轮 C4 改动「取舍」段）：

> **「按时间」不做任何本地重排**：接口返回顺序本身就是回复顺序，动它反而可能把顺序弄乱。
> **没有用 `floor` 排**——该字段在部分评论区不存在
> （`bilibili-API/docs/comment/readme.md:56` 注明「若不支持楼层则无此项」），
> 用它排会出现「不支持楼层时整片乱序」。

当时这个取舍**在它自己的前提内是成立的**：楼中楼接口
（`/x/v2/reply/reply`，参数只有 `type`/`oid`/`root`/`ps`/`pn`，**无 sort/mode**）
返回的确实是回复顺序，直接沿用不会出错；而且选 `floor` 做排序键**确实有真实缺陷**。

**它失效的条件是「本地重排不可逆」**：一旦用户切到热度序，`Collections.sort` 就地打乱了列表，
服务端原始顺序**在客户端已经不存在了**；此时再切回时间序若只 `return`，那份被丢弃的顺序
**再也拿不回来**。所以这个取舍有一个**延迟触发的失效模式** —— 静态看没问题，只有「切换过一次」
之后才暴露，这正是用户报的现象。

**推翻它的理由（三条，缺一不可）：**

1. **失效模式的代价高于取舍的收益**：正确性问题（顺序不可恢复）> 省一次本地排序。
2. **`ctime` 恰好避开了当初否决 `floor` 的那个理由**：`ctime` 是服务端**恒返回**字段
   （不像 `floor` 在部分评论区不存在），且解析用 `optLong("ctime", 0L)` 缺省 `0` +
   比较器稳定排序兜底 —— 即使个别项缺 `ctime` 也只是「保持其原有相对位置」，不会整片乱序。
   **所以「不能用服务端字段排序」这个当初的前提，对 `ctime` 不成立。**
3. **替代方案（备份原始顺序）被否决**：见下节，`Reply` 是 `Serializable` 且跨页传递，
   藏 mutable 备份字段容易变脏数据 —— 那就只能选「用 `ctime` 真排序」这条路。

> **给未来 reviewer 的结论**：本次改动是**在已知旧取舍的前提下、针对其延迟失效模式**做的设计变更，
> 不是没看见旧代码就重写。若要再次推翻（例如改回「不重排」），需要先解决「切换后原始顺序
> 不可恢复」这个问题。

#### 修法（Lead 明令：不用「备份原始顺序」这种带状态写法）

**否决了「排序前备份原列表、切回时还原」的方案**：`Reply` 实现 `Serializable` 且会在页面间传递，
在模型里藏一个 mutable 备份字段，经过翻页 / 点赞 / 删除之后极易变成脏数据。

改为**让 `SORT_TIME` 成为真正执行的排序分支**，按 `ctime` 显式升序重排：

```java
public static void sortReplies(List<Reply> replies, int sort, int fromIndex) {
    if (replies == null) return;
    if (fromIndex < 0) fromIndex = 0;
    if (replies.size() - fromIndex < 2) return;
    Collections.sort(replies.subList(fromIndex, replies.size()),
            sort == SORT_LIKE ? LIKE_ORDER : TIME_ORDER);
}
```

两个比较器为 `private static final` 常量（避免每次调用重新分配 lambda），并各自带 `null` 兜底
（`null` 项排到最后）：

- `LIKE_ORDER`：`Integer.compare(b.likeCount, a.likeCount)`，点赞数降序。同赞数时由
  `Collections.sort` 的**稳定性**保住原有（时间）顺序。
- `TIME_ORDER`：`Long.compare(a.ctime, b.ctime)`，时间升序。

两个分支都是**幂等**的，所以「时间→热度→时间」往返必然回到原序。未知 `sort` 值
（如主评论列表用的 2/3）现在**一律回落到时间序**，不再被当成「不打乱」而留下脏顺序。

#### 新增字段 `ctime`（排序键，非展示字段）

`Reply` 新增 `public long ctime;`，取服务端 `ctime` **原值（秒）**：

```java
this.ctime = replyJson.optLong("ctime", 0L);
```

- 用 `optLong` 带默认值，接口缺 `ctime` 时不抛异常；默认 `0` 表示无时间信息，
  此时靠稳定排序保持原有相对位置。
- **为什么不复用已有的 `pubTime`**：`pubTime` 是**展示文案**（「3小时前」「2026-10-04 12:00 | IP:上海」），
  **不单调**，不能做排序键。
- 单位刻意与局部变量 `ctime`（`replyJson.getLong("ctime") * 1000`，毫秒）区分开，避免单位歧义。

#### 为什么不做成「每次切换都重新请求服务端」

参考上游 PiliPlus（`D:\tmp\piliplus`）：

- `lib/pages/common/reply_controller.dart:91-107` 的 `queryBySort()` 每次切换都
  `feedBack(); onReload();`，因为它的**服务端接口支持 `mode`**（`Mode.MAIN_LIST_HOT` / `Mode.MAIN_LIST_TIME`）。
- `lib/models/common/reply/reply_sort_type.dart` 是 `time` / `hot` / `select` 三态。

而我们的**楼中楼接口没有 `sort`/`mode` 参数**，服务端不提供热度序，只能本地重排。
`lib/utils/storage_pref.dart:744-753` 显示 PiliPlus 的 `reply2SortType`（楼中楼）默认 `time`。

#### 顺带澄清：`ctime` 不需要同步任何 Parcelable（**理由修正、结论不变**）

Lead 一度担心「新增字段没同步 `Parcelable` 会导致跨页变 0、排序静默失效」。
**结论正确（不需要补任何东西），但理由需要修正**：

- `model/Reply.java:31` 是 `public class Reply implements Serializable {` —— **只实现
  `Serializable`，全文无 `Parcelable` / `writeToParcel` / `CREATOR` / `describeContents`**
  （四个关键字全库 grep，除第 31 行外零命中）。新增非 transient 字段由 Java 序列化自动带上，不会丢。
- 也没有「整对象 `putExtra`」的传递路径：评论相关 `putExtra` **只传标量**
  （`ReplyAdapter.kt:493-498` 传 `rpid`/`parent`/`replyType`/`parentSender`/`pos`；`:344` 传 `imageList`；
  `:639` 传 `type`；`activity/message/MessageActivity.kt:66` 传 `type`；
  `activity/article/OpusInfoActivity.kt:52` 与 `adapter/video/VideoCardHolder.kt:86` 传 `seekReply`）。

> 记录方式说明：这是**理由修正、结论不变**，不是「防住了一个风险」——
> 原本担心的失效路径**不存在**，写清楚是为了避免后人照着一个错误前提去做多余的 Parcelable 同步。

### 30.2 长按与「长按复制」的冲突（动态侧**确证并修复** / 评论侧**未复现**）

先厘清「长按复制」到底是什么：它**不是** `LinkMovementMethod` / `textIsSelectable`，
而是业务代码自己注册的长按监听 ——
`util/StringUtil.java:170` 的 `setCopy(TextView textView, String customText)`，在
`SharedPreferencesUtil.getBoolean("copy_enable", true)` 为真时执行
`textView.setOnLongClickListener(v -> { startActivity(CopyTextActivity, extra "content" ...); return true; })`。
（开关项 `SettingsKeys.COPY_ENABLE`，文案「长按复制」。）

所以**同一个 TextView 上只能有一个长按结果**，修复方向是「让长按按需让位」，而不是去改复制本身。

#### 动态侧：**确证并修复**（真实存在过的双弹窗）

`app/src/main/java/com/RobinNotBad/BiliClient/adapter/dynamic/DynamicHolder.kt` 原代码是：

```kotlin
item_dynamic_delete!!.setOnClickListener { item_dynamic_delete!!.performLongClick() }
```

即「点一下」通过 `performLongClick()` 转发给长按。**长按手势结束后系统还会补发一次 click**，
于是转发的 `performLongClick()` 再触发一次 → **弹出两个菜单**。这是真实缺陷，已修复。

#### 评论侧：**未复现（布局层面不可能，附 `cell_reply_list.xml` 兄弟节点依据）**

逐行核对 `app/src/main/res/layout/cell_reply_list.xml`：

- 根节点 `androidx.constraintlayout.widget.ConstraintLayout` 只带
  `android:foreground="?attr/selectableItemBackground"`，**没有 `android:clickable` / `android:focusable`**，
  不参与触摸拦截；
- `replyText`（`:46-56`，正文）、`item_reply_delete`（`:87-99`，操作按钮）、`likes`（`:58`）、
  `dislikeBtn`（`:71`）、`replyBtn`（`:179`）**全部是同级兄弟节点**，互不包含、位置不重叠。

Android 的命中测试只会把一次触摸派发给**其中一个** View，不存在「两个 View 互相补发手势」的路径。
⇒ **评论侧结构上不可能出现双弹窗**，故记为**未复现**，并给出上述结构性依据（而非含糊的「没测出来」）。

#### 那么用户说的「冲突」到底是什么：**入口不可发现 + 长按语义二义**

原始代码（`git show HEAD:` 核对确认）：

```kotlin
replyHolder.item_reply_delete.setOnClickListener { MsgUtil.showMsg("长按操作") }
replyHolder.item_reply_delete.setOnLongClickListener { showManageMenu(reply); true }
```

即**点一下只弹 toast「长按操作」，必须长按才出菜单** → 入口几乎不可发现。叠加第二条：
**同一屏内「长按」在正文上等于「复制」、在操作按钮上等于「管理菜单」** → 用户感知到的
「长按操作按钮和长按复制冲突」，本质是**长按这一手势承载了两种互斥语义**，
加上操作入口本身藏得深（要点必须长按）。

#### 修法

**评论侧** `adapter/ReplyAdapter.kt`：

1. `item_reply_delete` 改为**点一下与长按都弹同一个菜单**（点击成为主路径 → 入口可发现），
   替换掉原来只弹 toast 的写法；
2. 正文 `message` 上，`setCopy(...)` 先注册长按复制，**仅当 `canManage(reply)` 为真时**
   才覆盖成长按弹菜单 —— 为假时**保留复制**（是「让位」，不是把复制砍掉）；
3. 新增 `private fun canManage(reply: Reply)`，让「操作按钮显不显示」与「正文长按弹不弹菜单」
   共用同一判据（否则会出现「按钮看得见但长按没反应」）；
4. 顺带把 `reply.sender!!.mid == SharedPreferencesUtil.getLong("mid", 0)` 的字面量
   `"mid"` 换成已有常量 `SharedPreferencesUtil.mid`，`!!` 换成 `?.`。

**动态侧** `adapter/dynamic/DynamicHolder.kt`：把「管理入口」收敛成**唯一真相源**
`showManage()`，点击与长按都调它，并加 400ms 去重：

```kotlin
private const val MANAGE_DEDUP_MS = 400L
private var manageAction: (() -> Unit)? = null
private var lastManageAt = 0L

fun setManageAction(action: (() -> Unit)?) {
    manageAction = action
    lastManageAt = 0L   // 复用时清旧时间戳，否则新动态的首次点击会被上一条的时间戳吞掉
}

fun showManage(): Boolean {
    val action = manageAction ?: return false
    val now = SystemClock.uptimeMillis()
    if (now - lastManageAt < MANAGE_DEDUP_MS) return true
    lastManageAt = now
    action()
    return true
}
```

- 存**普通 lambda** 而不是 `View.OnLongClickListener`：若存后者并用 `performLongClick()` 转发，
  长按结束补发的 click 会再弹一次（正是动态侧原缺陷的成因）。
- `showManage()` 在 `manageAction == null` 时返回 `false` → **不消费长按事件** →
  自动回落到 `setCopy` 注册的长按复制。这就是动态正文侧「让位」的实现机制。
- 新增 `getManageAction(...)` 系列（`@JvmStatic`，纯 lambda 版）供三个调用点注入；
  **旧 `getManageListener(...)` 的 4 个重载全部保留为兼容入口**（内部委托到 `getManageAction`），
  因为 `adapter/dynamic/TopicDynamicAdapter.kt:69` 仍在调用它（该文件属其它任务，未动）。

最终调用链（真机验收用）：

| 触发 | 路径 |
|---|---|
| 管理按钮**点击** | `setOnClickListener` → `showManage()` → 守卫 → `manageAction()` → `showManageMenu(...)` → `TerminalDialog.menu(...)` |
| 管理按钮**长按** | `setOnLongClickListener` → **同一个 `showManage()`** |
| 动态**正文长按** | `content.setOnLongClickListener` → **同一个 `showManage()`** |

`manageAction` 的注入者只有三个：`adapter/dynamic/DynamicAdapter.kt`、
`adapter/dynamic/UserDynamicAdapter.kt`、`activity/dynamic/DynamicInfoFragment.kt`。
⇒ **只有一个真相源**，点击与长按绝不分叉。

#### 全仓 `AlertDialog` 约束

本次改动全部走 `util/TerminalDialog.kt` 的 `menu` / `confirm`，**未新增任何裸
`AlertDialog.Builder`**；删除类项均经 `danger` 集合高亮（`dangerIndex = actions.indexOfFirst { it.first.startsWith("删除") }`）。

### 30.3 新增与改写的单测

`app/src/test/java/com/RobinNotBad/BiliClient/model/ReplySortTest.kt`（整文件重写，11 例）：

| 用例 | 覆盖点 |
|---|---|
| `sortReplies_likeMode_ordersByLikeCountDesc` | 热度降序 |
| `sortReplies_likeMode_isStableForEqualLikeCounts` | 同赞数保持原序（稳定性） |
| `sortReplies_timeMode_ordersByCtimeAscending` | 时间升序（数据构造使两档结果可区分） |
| `sortReplies_timeMode_restoresOrderAfterLikeMode` | **用户报的 bug**：时间→热度→时间可还原 |
| `sortReplies_repeatedSwitching_isIdempotent` | `repeat(3)` 三轮往返，每轮断言 |
| `sortReplies_timeMode_isStableForMissingCtime` | `ctime` 全 `0` 时保持原序 |
| `sortReplies_likeMode_doesNotTouchItemsBeforeFromIndex` | 根评论 `assertSame` 在下标 0 |
| `sortReplies_timeMode_doesNotTouchItemsBeforeFromIndex` | 根评论 `ctime` 设最大仍不被排进去 |
| `sortReplies_toleratesNullEmptyAndShortLists` | `null` / 空 / 单元素 / `fromIndex = 5` / `-1` 均不抛 |
| `sortReplies_toleratesNullItems` | 列表混入 `null` 不崩（比较器 null 兜底） |
| `sortReplies_unknownMode_fallsBackToTimeOrder` | `sort = 2/3` 回落时间序 |

**删除的两个旧用例**（与新语义冲突，不能保留）：

- `sortReplies_timeMode_keepsServerOrder` —— 断言「时间序 = 保持服务端顺序」，正是缺陷本身的断言；
- `sortReplies_unknownMode_keepsOrder` —— 断言「未知排序不重排列表」，与「未知值回落时间序」冲突。

（该文件既有的 `sortReplies_likeMode_doesNotTouchItemsBeforeFromIndex`、
`sortReplies_toleratesNullEmptyAndShortLists` 语义保留，改为新 helper 写法。）

helper：`private fun reply(rpid: Long, likeCount: Int, ctime: Long = rpid): Reply`（`ctime` 默认等于
`rpid`，便于直接构造时间序）。**环境限制**：`app/build.gradle` 只有 `junit:junit:4.13.2` 与
`org.json:json:20231013`，**没有 Robolectric**，故 `SystemClock` 相关逻辑（`MANAGE_DEDUP_MS` 去重）
无法进 JVM 单测，只能真机验证。

### 30.4 构建与测试状态（如实记录）

`./gradlew.bat --stop` 后按 Lead 给的独占窗口，序列执行
`:app:clean` → `:app:assembleDebug` → `:app:testDebugUnitTest`（均带
`--offline --no-build-cache --no-configuration-cache`）。结果：

- `:app:clean` **FAILED**：`New files were found. This might happen because a process is still
  writing to the target directory`（daemon 关闭竞态）。**但实际效果已达成**：`app/build` 递归文件计数
  = **0**，只剩 `outputs/apk/release` 等空目录壳被某进程句柄占用
  （`cmd rmdir` 诊断：`The process cannot access the file because it is being used by another process.`，
  已确认无 `java.exe` 残留，仅剩两个与构建无关的 node 进程）。
- `:app:assembleDebug` **BUILD FAILED**，`:app:compileDebugKotlin FAILED`，
  **失败点不在本次改动范围内**：

```
e: .../app/src/main/java/com/RobinNotBad/BiliClient/adapter/user/FollowGroupAdapter.kt:300:55
   Argument type mismatch: actual type is
   'com.RobinNotBad.BiliClient.adapter.user.FollowGroupAdapter.GroupItem',
   but 'com.RobinNotBad.BiliClient.model.FollowTag' was expected.
```

  `adapter/user/FollowGroupAdapter.kt` 属**关注分组移动**（task-5，另一名队友在途），
  是**真实的源码错误**，非并发噪声。
- `:app:testDebugUnitTest` **未能跑完**：编译单元里存在上述在途错误，任务链到不了测试阶段。

> **本次改动曾取得过一次 `assembleDebug` 成功**（清理增量产物后重跑，`BUILD SUCCESSFUL`，
> `compileDebugKotlin` / `compileDebugJavaWithJavac` / `dexBuilderDebug` / `packageDebug` 全走完），
> 该次成功**早于** `FollowGroupAdapter.kt` 的在途改动落地。故本节的准确表述是：
> **本次改动自身在编译层面已验证过一次通过，但「clean 后完整 assembleDebug + testDebugUnitTest
> 全绿」这一最终验收未取得干净窗口。**

> ### ⚠️ 结论：本批代码的编译验证**被队友在途源码阻塞，未取得干净窗口**
>
> **不得读作「已验证通过」。**`build.gradle` 的验收项（`assembleDebug` 通过 +
> `testDebugUnitTest` 全绿）在本批代码上**均未取得最终结果**：
>
> - `:app:assembleDebug` 唯一的一次成功发生在 `FollowGroupAdapter.kt` 在途改动落地**之前**；
>   干净窗口下的重跑被 `FollowGroupAdapter.kt:300:55` 的类型不匹配错误拦下，
>   该错误**不在本批改动范围内**。
> - `:app:testDebugUnitTest` **一次都没有成功跑完**（编译单元被上述错误阻断，任务链到不了测试阶段）。
>
> 该错误的归属**已由 Lead 裁定为 w5（关注分组移动 / task-5）负责修复**；
> 修复后由 **Lead 在全队冻结后串行重跑全量验证**（届时本批代码已在树中）。
> 在此之前，本节的单测用例**只做过静态审查，未经 JVM 实际执行**。

**未复现清单（如实记录）**：

- **评论侧双弹窗未复现**（布局层面不可能，依据见 §30.2 的 `cell_reply_list.xml` 兄弟节点分析）。
- `MANAGE_DEDUP_MS = 400L` 的去重效果**未经真机验证**（无 Robolectric，`SystemClock` 无法单测）。
- 300×300 表盘上弹窗的实际可点性**未在真机确认**。
- `ReplyInfoActivity.kt` 分页插入起点的 `+2` **疑似越界但未确证、未修改**（详见 §30.5b，属未决项）。

### 30.5 明确没有碰的东西

- `util/StringUtil.java`（`setCopy` 本体）**一行未动** —— 只在其调用点决定「让位」与否。
- `activity/dynamic/DynamicTopicActivity.kt`、`adapter/dynamic/TopicAdapter.kt`、
  `adapter/dynamic/TopicDynamicAdapter.kt`、`api/TopicApi.java`、`api/DynamicApi.java`、
  `model/OpusParagraph.java` 均未动（属其它任务）。
- `Reply` 的 `Parcelable` / `Serializable` 实现未动；字段语义未变（仅新增 `ctime` 排序键）。
- `activity/dynamic/DynamicInfoActivity.kt` 未动（经核实只是 ViewPager 容器，无长按逻辑）。

### 30.5b 未决项：`ReplyInfoActivity.kt` 分页插入起点疑似越界（**发现但未修改**）

> **疑似越界，但未确证；未修改；建议后续单独立项，需真机复现。**

`app/src/main/java/com/RobinNotBad/BiliClient/activity/reply/ReplyInfoActivity.kt` 的
`continueLoading()` 在非 `SORT_LIKE` 分支写的是：

```kotlin
replyAdapter!!.notifyItemRangeInserted(replyList!!.size - list.size + 2, list.size)
```

起点用了 `+2`。可疑之处：`ReplyAdapter.getItemCount()` 是 `replyList.size + 1`，
且详情页列表**第 0 位是根评论本身**（不是头部占位），所以「头部占位应占 1 个」这个常见解释
在这里未必成立 —— 看起来是 `+1` 更合理，`+2` 会报出超出 `getItemCount()` 的范围。

**但为什么不当场改（三条理由）：**

1. **不在用户报告的问题范围内**。「排序切换第二次不生效」走的是 `applySort()` /
   `notifyDataSetChanged()` 路径；这条是**分页插入**路径（`sort != SORT_LIKE` 且翻页时）。
2. **静态推不出结论**。`+2` 是否真的越界，取决于运行时的 `replyList.size` 与 `list.size`
   的关系（即「本次请求的 `list` 中有多少条是真新增」）—— 若接口每页最多返回 20 条而
   `ps` 定义域是 1-49，`list.size` 与实际新增数是否恒等**无法从代码静态判定**。
   猜错会引入新的分页 bug（漏插 / 位置错位 / `Inconsistency detected`）。
3. **本轮已冻结**：任何改动都要重新开 Gradle 窗口，而这是**存量可疑点**、不是本次改动引入的回归，
   收益不值。

**参考既有约定（说明为什么不能只凭「看到 `+1` 就判越界」）**：
`docs/review/fix-progress.md:1305` 已记录过同类判断坑 ——
「`notifyItemRangeInserted(x + 1, …)` 在本项目**大多数是对的**」（这些 adapter 的
`getItemCount()` 带一个头部占位，`data.size + 1`，位置 0 是标题/头部）。
当时逐一核实并**保持不动**的清单包含 `activity/reply/ReplyFragment.kt:250`
（其 `ReplyAdapter.getItemCount() = replyList.size + 1`）。
⇒ **结论：判越界前必须先读对应 adapter 的 `getItemCount()`，并确认位置 0 到底是
「头部占位」还是「根评论」。** 详情页属于后者（位置 0 是根评论），所以这一处
**与那些「保持不动」的案例前提不同**，但也因此更需要真机确认而非静态直接改。

> **对 `AGENTS.md` 那条「已知坑」的补充（重要）**：
> AGENTS.md 的 `+1` 规则写的是「有头部占位的 adapter（`ReplyAdapter` / `UserDynamicAdapter` /
> 系列详情）`getItemCount() == data.size + 1`，起点要 `sizeBefore + 1`」——
> **但该规则不能只看 adapter 类名，必须看宿主 Activity。**
> **同一个 `ReplyAdapter`，在 `activity/reply/ReplyFragment.kt`（主评论列表）位置 0 是
> 「头部占位」，在 `activity/reply/ReplyInfoActivity.kt`（楼中楼详情页）位置 0 却是
> 「根评论」—— 前提正好相反。** 只按类名套用会得出错误结论。
> 判据应是：**先确认宿主页在第 0 位放了什么**，再决定 `+1` 是否成立。

**后续处置建议**：单独立项，真机复现步骤 —— 进入楼中楼详情页 → 切到**时间排序**
→ 持续下滑翻页 → 观察是否出现 `IndexOutOfBoundsException` /
`Inconsistency detected. Invalid item position` / 新加载页错位或缺失。

### 30.6 真机验证步骤（改动效果的直接判据）

1. **排序往返**（最直接的判据）：进入任一楼中楼详情页 → 点排序按钮切到「热度排序」
   → **再切回「时间排序」** → 列表应**真正回到时间序**（而非停在热度序）。反复切 3 次，
   每次都应变。
2. **根评论恒在最上**：上述任意排序状态下，页面第一条恒为被点开的根评论。
3. **评论操作入口**：自己发的评论（或管理员）→ 操作按钮**点一下**即应弹出菜单
   （不再只弹 toast「长按操作」）；菜单中「删除评论」应有 `danger` 高亮；长按同一按钮弹同一菜单。
4. **非本人评论**：正文**长按应仍是复制**（弹 `CopyTextActivity`），不应弹管理菜单。
5. **动态侧去重**：动态「管理」按钮**点一下**只弹**一个**菜单；长按也只弹**一个**
   （原缺陷会弹两个）。正文长按：有可操作项时弹管理菜单，无可操作项时回落到复制。

### 30.7 交叉引用

- `AGENTS.md` 弹窗硬约定：禁止裸写 `AlertDialog.Builder`，统一走 `util/TerminalDialog.kt`。
- `docs/review/dialog-redesign-progress.md`：弹窗改版专项进度（本次两处新接线点
  `adapter/ReplyAdapter.kt`、`adapter/dynamic/DynamicHolder.kt` 即该节 17 处调用点中的 2+2 处）。
- `docs/architecture-map.md` §8.4 的 `TerminalDialog` 四方法表。
- 真机回归清单：`docs/review/real-device-regression-checklist.md`（本次新增 §30.6 五条可直接并入）。

---

## 三十五、26.10.05：稍后再看「未看完」为空（C18 回归）+ 新增「清除所有已看完」（task-2）

**用户报告（原文）**：稍后再看切到「未看完」是空的，一条都不显示；同时希望有一个
「清除所有已看完」的入口。

### 35.1 「未看完为空」是**双层根因**，不是单点 bug

> C18（§二十七）当时把筛选判据定为 `progress > 0`，并明确写下「`progress <= 0` 判为『没播过』
> 而不是『未看完』」。**那个决定本身就是本条 bug 的第一层根因**：用户「从未播放」的稿件
> 正是他想在「未看完」里看到的东西，而旧实现把它们全部排除在外——若整表都从未播放，
> 「未看完」档就是**空的**，与用户现象逐字吻合。

**① 表象层（本地实现）**：`api/WatchLaterApi.java` 的 `isUnfinished` 旧实现首行为

```java
if (progress <= 0) return false;   // ← 「从未播放」被当成「不是未看完」
```

**② 真根因层（服务端能力从未被使用）**：本工程旧代码**从未给 `history/toview/web` 带过
`viewed` 参数**，等于永远只拿「全部」，筛选完全靠客户端本地做。

| 依据 | 内容 |
|---|---|
| 上游 PiliPlus `lib/models/common/later_view_type.dart` | 定义两个档位：`all(0)` / `unfinished(2)` |
| 上游 PiliPlus `lib/http/user.dart` 的 `seeYouLater` | 把 `viewed` 作为请求参数发给服务端 |
| 本工程 HAR 抓包 | 确认在线请求带 `viewed=2` |

⇒ 正确做法是**让服务端过滤**（`viewed` = `0` 全部 / `2` 未看完），本地过滤退化为兜底。

### 35.2 主判据 vs 兜底（**分工是刻意的**）

| 层 | 角色 | 说明 |
|---|---|---|
| **主判据** | 服务端 `viewed` 参数 | `0` = 全部、`2` = 未看完，由**服务端**过滤。这是「未看完为空」的真根因层修复 |
| **兜底** | 本地 `filterUnfinished` | 仅在服务端忽略该参数时**防脏**；同时作为**纯函数**供 JVM 单测直接断言 |

兜底判据（本次收口为一条明确规则）：

- **`duration <= 0` → 一律算「未看完」**（总时长未知时，宁可放进未看完，也不要让用户找不到
  自己看了一半的稿件——与 C18 的取舍一致并加强）；
- **只有 `progress >= duration && duration > 0` 才算「已看完」**；
- 其余（含 `progress = 0`、`progress` 为负数）**全部算「未看完」**。

> 这条规则的关键变化：**「从未播放」从「不算未看完」翻转为「算未看完」**，直接对应用户现象。

### 35.3 新增功能：清除所有已看完

| 项 | 内容 |
|---|---|
| 接口 | `POST https://api.bilibili.com/x/v2/history/toview/clear` |
| 请求体 | multipart，`clean_type=2` + `csrf` |
| 响应 | `{"code":0,"message":"OK","ttl":1}` |
| 提取方式 | 本工程 HAR 抓包 |
| 入口 | `res/layout/activity_simple_refresh.xml` 的 `filterBar` 内**第三个 `TextView`** `clearWatched`，**默认 `android:visibility="gone"`**，仅「稍后再看」页点亮 |
| 既有 id | **未改任何既有 id**（`filterBar` / `filterAll` / `filterUnfinished` 保持不变） |

### 35.4 新增回归单测（**用户 bug 的直接复现**）

`app/src/test/java/com/RobinNotBad/BiliClient/api/WatchLaterApiTest.kt` 增至 **9 例**
（C18 原 7 例 + 本次调整/新增）。四条**点名钉住**的用例：

| 用例 | 断言 | 为什么值钱 |
|---|---|---|
| `isUnfinished_treatsNeverPlayedAsUnfinished` | `isUnfinished(0, 100) == true`、`isUnfinished(0, 0) == true` | **直接复现用户 bug**：从未播放必须算未看完 |
| `filterUnfinished_keepsNeverPlayedAndDropsFinished` | `[进度30/100, 进度100/100(已看完), 进度0/100(从未播放), 进度5/时长未知]` → `[1, 3, 4]` | **从未播放的第 3 条必须留下**，只有已看完的第 2 条被剔除 |
| `filterUnfinished_allNeverPlayedIsNotEmpty` | 全表都是「从未播放」时，未看完档 = **全表** | **旧代码此处返回空 = 用户现象本身** |
| `isUnfinished_negativeProgressIsStillUnfinished` | 进度为负也算未看完 | 脏数据不应被静默丢弃 |

> **这四条是用户 bug 的护栏**：将来谁把判据改回 `progress > 0`，一跑就知道。

### 35.5 本次改动的文件（4 个）

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/api/WatchLaterApi.java` | 带 `viewed` 参数（`0`/`2`）；`isUnfinished` 判据收口（见 35.2）；`filterUnfinished` 保持纯函数 |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/user/WatchLaterActivity.kt` | 档位切换改为**按档发请求**（`viewed` 主判据）+ 本地兜底；接线「清除所有已看完」 |
| `app/src/main/res/layout/activity_simple_refresh.xml` | `filterBar` 内新增第三个 `TextView` `clearWatched`（默认 `gone`）；**未改既有 id** |
| `app/src/test/java/com/RobinNotBad/BiliClient/api/WatchLaterApiTest.kt` | 9 例纯函数单测（见 35.4） |

### 35.6 验证状态（**如实记录**）

- **单测**：`WatchLaterApiTest` **9 tests / 0 failures / 0 errors**，取得于一次**隔离运行**
  （当前版本），可信。
- **`:app:assembleDebug`**：本批 4 个文件在 `adapter/user/FollowGroupAdapter.kt` 的在途错误落地
  **之前**，`compileDebugKotlin` 曾完整通过一次；此后干净窗口下的重跑被该错误拦下，
  **该错误不在本批改动范围内**（见 §30.4 的同一条记录）。
- **`:app:testDebugUnitTest` 全量**：未取得干净窗口（同上，编译单元被阻断）。

> ### ⚠️ **不得读作「已验证通过」**
>
> 本节代码的最终验收（冻结后 `assembleDebug` + 全量 `testDebugUnitTest`）**未由本任务的
> 执行者取得**，改由 **Lead 在全队冻结后串行重跑**。在那之前，9 例单测的可信度仅来自
> 上述那一次隔离运行。

### 35.7 真机验收判据（**唯一存疑点，务必按此排查**）

1. 进入「稍后再看」，切到「未看完」档：应能看到**从未播放**的稿件（这正是用户原来的现象）。
2. **若「未看完」仍为空** → 说明服务端 `viewed=2` 的语义与预期不同，**立刻抓包**：
   - 抓 `history/toview/web` 请求的 **`viewed` 值**（确认客户端确实发了 `2`）；
   - 抓**响应体**，确认服务端是否真的按 `2` 过滤（还是忽略该参数、或 `2` 另有语义）。
3. 「全部」档应仍与升级前一致（条数、顺序、封面）。
4. 「清除所有已看完」入口只在「稍后再看」页可见（其它 `RefreshListActivity` 子类看不到）。
5. 点「清除所有已看完」→ 二次确认 → 提示成功 → 「全部」档里已看完的稿件消失、
   **未看完的必须还在**；网页端核对。
6. 切档时的网络行为：主判据是服务端参数，切档**会**重新请求（与 C18 的「切档零请求」不同，
   属本次有意改变）；断网时应给出失败提示而不是白屏。

### 35.8 明确没有碰的东西

- `res/layout/activity_simple_refresh.xml` 中 `filterBar` 的**既有 id 与既有两个 chip 未动**
  （`filterAll` / `filterUnfinished` 原样），仅**新增** `clearWatched`。
- 收藏夹（C19/C20）、关注分组（C21）相关文件均未动。
- `isUnfinished` / `filterUnfinished` 的**签名未变**（仍是纯函数），故 C18 的对调用方式不受影响。

### 35.9 交叉引用

- §二十七（C18 原实现）：本节是对其筛选判据的**方向性更正**——C18 的「`progress <= 0` 不算
  未看完」即用户 bug 的第一层根因。
- §30.4：同一时段因 `FollowGroupAdapter.kt` 在途错误**未能取得干净验证窗口**的记录。
- 接口依据：`bilibili-API/docs/historytoview/toview.md`；上游 PiliPlus
  `lib/models/common/later_view_type.dart`、`lib/http/user.dart`。

---

## 三十六、26.10.05 Lead 终验：全队冻结后的串行全量验证 + 本轮问题闭环对照

> 本节由 Lead 在全队写入冻结后撰写，**目的是把本轮 6 个并行任务合并成一个可交付的整体结论**，
> 并明确区分「**已用构建/单测证据关闭**」与「**只能真机验证、至今未复现**」两类状态。
> 前者已闭环；后者**没有**因为单测变绿而变成已验证——真机上仍可能失败。

### 36.1 用户报告的问题与本轮落点的逐条对照

用户原始报告见本仓库 `docs/review/real-device-regression-checklist.md` 的相关条目。
「状态」列的含义：**确证修复** = 找到确定性根因且改动已落地并进入本轮全量验证；
**未复现** = 无真机/无登录态，无法稳定复现，只交付了确定性缺陷修复或可观测性；
**代码无缺陷** = 逐条排查后未发现可修缺陷，交付结论与诊断埋点。

| # | 用户报告 | 落点章节 | 状态 |
|---|---|---|---|
| 1 | 置顶会话反而排到下面 | §二十二（第二处，`MessageActivity`） | **确证修复**：`loadSessions()` 的「未读优先」稳定排序把无未读的置顶会话挤走，已抽 `util/SessionSorter.kt` 并改为「置顶优先 → 未读优先 → 保持服务端原序」 |
| 2 | 新增通知应有一个「通知设置」大类，默认开启 | §三十三 | **已落地**：新增顶层分组 `GROUP_NOTIFY`，**复用既有两个 SP 键**（本来就默认 true），旧位置已移除，避免两处状态不同步 |
| 3 | 自己的稿件下长按操作按钮与长按复制冲突 | §30.2 | **确证修复**：操作按钮的点击与长按都弹同一个 `TerminalDialog` 菜单，正文长按让位 |
| 4a | 楼中楼「时间排序」切换一次后再切不生效 | §30.1 | **确证修复**：`sortReplies` 原来对 `SORT_TIME` 直接 `return`（单向排序），已改为按 `ctime` 的真双向排序 |
| 4b | 评论带图报错 12066 | §二十九（第二处） | **未复现**：修复了同一条链路上**确定性**的 `img_size` 小数被截断缺陷，**未与 12066 建立因果关系** |
| 5 | 自己的动态长按会触发长按复制 | §30.2 | **确证修复**：顺手修掉了「长按手势结束补发的 click 再触发一次 `performLongClick` → 弹两个菜单」的真实缺陷，三入口收敛到 `DynamicHolder.showManage()` + 400ms 去重 |
| 6 | 话题广场加载不出来 + `#A#` 不解析（应蓝色可跳转） | §三十四 | **确证修复**：广场数据源换成 `/x/topic/pub/search`（原 `/x/topic/web/dynamic/rcmd` 已废弃，恒返回 `code=0 + data=null` 造成静默空列表）；`#话题#` 补 `RICH_TEXT_NODE_TYPE_TOPIC` 分支 + 新 `TopicClickableSpan`（主色 + 站内跳转） |
| 7 | 稍后再看「未看完」为空 | §三十五 | **确证修复（第一层）+ 待真机（第二层）**：本地判据 `progress <= 0` 排除「从未播放」已修；同时改为按服务端 `viewed=2` 过滤（原实现从未带该参数） |
| 8 | 新功能：清除所有已看完（**仅稍后再看**） | §三十五 | **已落地**：`POST x/v2/history/toview/clear` + `clean_type=2`，入口只在稍后再看页，未扩散到历史记录 |
| 9 | 收藏夹排序行「没看到」（「我的」入口） | §二十二（第二处） | **代码无缺陷**：两处入口汇合到**同一个** `FavoriteVideoListActivity`，`setupSortBar()` 无条件点亮、资源已进包 |
| 10 | 收藏夹两处入口（我的 / 用户信息）都要有 | §二十二（第二处） | 同上；`FavoriteFolderListActivity` **有意不放**排序行（排序是视频内容属性），已加临时诊断埋点 |
| 11 | 不能把已关注的人移动到其他分组 | §二十二（第二处） | **已落地（新功能）**：新增 `FollowApi.moveFollowTagUsers`（`x/relation/tags/moveUsers`）+ 长按成员选目标分组 |
| 12 | 片头片尾自动跳过默认是关的 | §三十三 | **确证修复**：结论是「**逻辑本来就接好了，只是默认值写死 false**」，四处调用点统一改默认 true，引导判据改为看 `player_skip_op_ed_guided` |
| 13 | 笔记无法正常加载 | §二十九（第二处） | **根因未确证**：已排除 csrf、`note_id_str` 精度设计、UI 层；本次交付的是**可观测性**（分级错误提示 + `BiliNote` 埋点），**不是「修好了笔记」** |

**⚠️ 请务必按上表最后两列读**：第 4b、7（第二层）、13 三项**没有拿到真机复现**，
真机上是否真的好了**未知**；第 9/10 项**没有可修缺陷**，交付的是结论与埋点。

### 36.2 本轮新增章节索引（按文件顺序）

| 章节 | 任务 | 负责人 | 主题 |
|---|---|---|---|
| §二十二（第二处） | task-5 | w5-sortgroup | 置顶会话排序 + 收藏夹排序行 + 关注分组移动成员 |
| §三十三 | task-3 | w3-notify | 片头片尾跳过默认开启 + 新增「通知设置」大类 |
| §三十四 | task-1 | w1-topic | 话题广场加载不出来 + `#话题#` 不解析 |
| §二十九（第二处） | task-6 | w6-note-replyimg | C27 视频笔记 + C6b 带图评论（12066）复查 |
| §30. | task-4 | w4-longpress | 长按冲突 + 楼中楼排序二次切换失效 |
| §三十五 | task-2 | w2-watchlater | 稍后再看「未看完」为空 + 新增「清除所有已看完」 |
| §三十六 | — | Lead | 本节：终验与闭环对照 |

### 36.3 最终验证（全队写入冻结后，由 Lead 一人串行执行）

**执行环境**：`D:\Users\ASUS\Desktop\DEVELOP\ReBiliClient`，Gradle 8.11.1 / AGP 8.5.2 / Kotlin 2.0.0 / JDK 17。
**前提**：`.\gradlew.bat --status` 确认**无任何 Gradle daemon 在跑、无 java 进程**，且 6 名队友全部回报「已冻结、无未落地写入、无 gradle」。

| 步骤 | 命令 | 结果 |
|---|---|---|
| 清缓存 | `.\gradlew.bat :app:clean` | **FAILED（已知环境噪音，已绕过）**：`app\build\outputs\apk\release` 是**空目录**，却被某个非 java 进程持有句柄（`--stop` 后 java 进程全消失仍锁）。改为**外科式删除**：把 `app/build` 下 `generated` / `intermediates` / `kotlin` / `reports` / `test-results` / `tmp` 全部删除，仅剩 `outputs/apk/release` 空壳与一个 logs 文本文件。**实质目的（打掉 `mergeDebugResources` 的陈旧缓存回放）已达成** |
| 编译 | `.\gradlew.bat :app:assembleDebug --offline --no-build-cache --no-configuration-cache` | **BUILD SUCCESSFUL** in 1m 30s（92 actionable tasks: 37 executed, 55 up-to-date；`dexBuilderDebug` / `packageDebug` 等真实执行，非缓存回放） |
| 单测 | `.\gradlew.bat :app:testDebugUnitTest --offline --no-build-cache --no-configuration-cache` | **BUILD SUCCESSFUL** in 9s；`compileDebugKotlin` 为 `UP-TO-DATE`（**无并发噪声、无在途中间态**） |

**单测汇总（直接读 `app/build/test-results/testDebugUnitTest/*.xml`，共 43 个测试类）**：

```
tests = 400    failures = 0    errors = 0    skipped = 0
```

**产物**（`app/build/outputs/apk/debug/`，2026-10-05 14:04:48）：
`app-arm64-v8a-debug.apk` 15.6 MB · `app-armeabi-v7a-debug.apk` 14.17 MB ·
`app-universal-debug.apk` 28.59 MB · `app-x86-debug.apk` 16.59 MB。

**对照上一轮（修复中、队友仍在写文件时跑的那次）**：398 例 / **3 例失败** ——
`DynamicApiTest.kt:151` 与 `:203`（`expected:<#A#> but was:<null>`，`android.util.Pair` 在 JVM 单测里是 Android 桩，字段恒 null）、
`NoteApiTest.kt:198`（`NullPointerException: null cannot be cast to non-null type kotlin.String`）。
**两处均已修**，但**两处的修法完全不同，请勿混淆**：

- w1 的 `DynamicApiTest` 两例是**产品代码缺陷**：`android.util.Pair` 在 JVM 单测下是 Android 桩，
  `first`/`second` 恒 null。修法是新建 `model/TopicNode.java` 这个**真实类**取代 `Pair`
  （`DynamicApi.parseTopicNodes` 返回 `List<TopicNode>`），并加回归守卫 `parseTopicNodes_returnsRealObjectsNotAndroidStubs`。
- w6 的 `NoteApiTest` 一例是**测试代码缺陷，产品代码当时就是对的**：`:198` 原本写 `as String`（**非空**转型），
  Kotlin 在**构造器调用当场**就抛 `NullPointerException`，异常**根本没进入 `failureText`**；
  改成 `as String?` 后 null 才真正传进去，而 `NoteApi.failureText` 的 null 兜底（`NoteApi.java:237`）**在失败时就已存在、一行未改**。
  w6 加的 `@NonNull` 只是把「绝不返回 null」的契约显式化，**不改变任何行为、也不是该失败消失的原因**。
  ⇒ **判据**：看异常堆栈里**有没有被测方法的帧**——没有，说明失败发生在**构造参数转型**这一层，不是被测函数返回了坏值。
  ⚠️ **不要把这条读成「产品代码曾经有个 null 返回缺陷」**，那会凭空造出一个不存在的 bug 记录。
本轮 400 例全绿。

**AGENTS.md 硬约定自检**：`grep -r "AlertDialog" app/src/main/java` → **9 处命中全部在 `util/TerminalDialog.kt` 自身，业务代码 0 命中** ✅。
本轮**未新增任何 Activity**（设置页走「本页另一个 `group_type` 分组」形态），故无新增 `android:exported` 项。

### 36.4 本节关闭的历史缺口（措辞更正，结论不变）

以下三处在编写时如实记录了「**当时**没取得干净验证窗口」，**它们记录的是历史事实，不是当前状态**；
该缺口**已由 36.3 的串行全量关闭**。**保留原文不回退**，因为原文准确描述了当时的阻塞原因（`FollowGroupAdapter.kt` 在途编译错误 + 多 daemon 并发踩踏）： 
- §29.4 / §29.5（w6）：编写时尚未取得构建窗口 → **现已关闭**。
- §30.4（w4）：`testDebugUnitTest` 未跑完、编译验证被队友在途源码阻塞 → **现已关闭**。
- §35.6（w2）：未取得干净窗口 → **现已关闭**。

**注意**：上述「已关闭」**只针对构建与 JVM 单测这一层**。这些章节里同时记录的
「未复现」清单（如 §29.5 的 79502/79503/12066、§30.2 的评论侧双弹窗）**仍然未复现**，
不因单测变绿而升级为已验证。

### 36.5 至今仍未验证的部分（必须真机，且**不因本轮全绿而改变**）

1. **评论带图 12066**：`img_size` 小数截断是**确定性缺陷**（依据 `bilibili-API/docs/dynamic/publish.md:40` 与 PiliPlus
   `lib/models_new/upload_bfs/data.dart:5,19`），但它与 12066 的因果关系**未建立**。若真机仍报 12066，
   需要在 `sendReply` 失败分支打印**完整响应体**，把服务端 `message` 原文取回来——12066 在本地快照与上游 collector 里都无文档，
   `message` 是唯一权威线索。
2. **笔记加载**：根因**未确证**。已排除 csrf、`note_id_str` 精度设计、UI 层；
   仍存在的假设是「服务端按 `num` 解析 17 位 `note_id` 会丢精度 → 79502」。
   验证方法：**同一个 id 分别按字符串与按 num 各发一次对比**。`pickNoteId` 的 `note_id_str` 优先设计**保持现状未动**。
3. **稍后再看「未看完」**：主判据是服务端 `viewed=2`。若真机仍为空，需抓 `history/toview/web` 的请求 `viewed` 值与响应体。
4. **收藏夹排序行**：代码链路经逐条排查**未发现可修缺陷**（两处入口汇合到同一个 `FavoriteVideoListActivity`）。
   已加临时诊断埋点 `adb logcat -s "debug-收藏夹排序"`。
5. **关注分组移动成员**：新实现，**未真机验证**。`getUserOwnerGroup` 的位置算术是静态复核，未运行验证。
6. **片头片尾**：默认值与引导判据已改；跳过逻辑本身（`ViewPointSkip.kt`）**一行未动**，经核实本来就接好了。

### 36.6 明确没有碰的东西

- `util/SettingsKeys.kt`：**本轮一行未动**（w3 复用既有键，因此老用户升级后设置必然保留）。
- `docs/review/real-device-regression-checklist.md`：**只由 w3 更新了自己名下的条目**
  （片头片尾第 2/199/200 条、新增第九节「通知设置」第 202-206 条，总数 201→206）；
  其它条目一字未改。
- `activity/video/` 下：**无任何写操作**（笔记入口 `VideoInfoFragment.kt` 只读）。
- `model/Reply.java`：只改了排序函数与其直接依赖（新增 `ctime` 字段供排序键），`Serializable` 实现与其它字段未动。
- `ViewPointSkip.kt`（片头片尾跳过逻辑）、`MsgNotifier.kt` 的开关语义、`isUnfinished`/`filterUnfinished` 的函数签名：均未动。

### 36.7 本轮的方法论沉淀（写给后续维护者）

1. **JVM 单测里的 Android 桩陷阱**：`android.util.Pair` 这类**直接 `new` 出来的 Android 框架类**在单测下字段恒 null，
   而「只断言列表长度」完全测不出来。判据不是「类型名里有没有 `android`」，而是「**这个值是真实实现算出来的，还是桩给的默认值**」——
   自己实现 Android 接口的 fake（如 `FakeSharedPreferences`）不踩坑，`org.json` 因 `app/build.gradle` 单独补了真实实现也不踩。
   更进一步：**「测试文件里没有 `import android.*`」不等于「该测试不依赖 Android 桩」**，
   要看**被测函数的实际执行路径**。
2. **并发构建必须串行**：`app/build/` 是共享目录，Gradle 对 `tmp/kotlin-classes`、`intermediates/javac` 是「先删再写」，
   多人同跑必然互踩并产生**假失败**。判据：①复跑同一条命令 ②`git status --short` 前后对比确认工作树静止
   ③确认报错文件 `git diff` 与 HEAD 有实际差异；三者不全只能报「疑似在途中间态」。
   **类型不匹配 = 真错误；引用不存在的类 = 多为中间态。**
3. **队友自跑只用于早发现问题，最终证据必须来自一次干净的串行全量**——各人各跑一遍拼起来不构成证据（环境不同源）。
4. **正式文档只写正确结论**；唯一例外是「以错误理由做出的正确决定」，
   要写成「**理由修正、结论不变**」并保留原错误理由，否则后人会重新推演出同一个错误理由
   （见 §30.1 对「不做本地重排」这一历史设计取舍的推翻记录）。
5. **用例数一律以机器产出为准**：本次一名队友口述「23 例」，实际是 **25 例**——
   他是按「覆盖了哪些场景」清点的，把同一个 `@Test` 里的多个断言场景算成了多条用例，
   **清点口径（场景数）与正式口径（`@Test` / `<testcase>` 数）被混用而不自知**。
   ⇒ 规则：**汇报与文档里的用例数一律取自 `app/build/test-results/testDebugUnitTest/*.xml` 的 `<testcase>` 计数；
   口述数字不进入正式文档。** 本节 36.3 的总数 400 即由 43 个 XML 的 `tests` 求和得来。
   这条与第 1 条同源：**「看起来完成了」和「实际验证到位」是两件事**——前者可以是单测断言无效，
   也可以是计数口径混用。

---

## 三十七、26.10.05 批次 8：长按操作面板（复制收进弹窗）+ 顶部工具条滚动收回

> 本节的两项来源都是**用户口头新增的需求，不在 26.08.27 那份 286 条清单里**：
> ①「与复制冲突的几个，复制放入弹出里，并在个性化设置里给个选项启用长按打开管理评论/动态的面板」
> ②「收藏夹和稍后再看的上面的几个选项做一下滚动自动收回」。
> 用户口中的「个性化设置」在本工程**不存在同名页面**，落点见 §37.1。

### 37.1 改动 1：复制收进操作面板 +「长按打开操作面板」开关（默认开启）

#### 问题的真正形态

`util/StringUtil.setCopy(TextView, String)` 注册的长按监听，做的事情是**打开复制界面**（`CopyTextActivity`，
让用户自己选复制哪一段），不是直接写剪贴板。而「自己的稿件 / 自己的动态」出现「置顶 / 删除」之后，
业务代码又在同一批 View 上挂了管理菜单监听：

- `adapter/ReplyAdapter.kt`：原先「`canManage(reply)` 为真就挂管理菜单，否则交给 `setCopy`」；
- `adapter/dynamic/DynamicHolder.kt`：原先 `setCopy(content)` 与 `setOnLongClickListener { showManage() }` **同时注册**，
  后者覆盖前者。

于是**同一个长按手势在不同条目上有两种结果**（有时复制、有时弹菜单），用户无从预期——这就是
「和长按复制冲突」的实际含义。它不是一个能被复现的崩溃，而是**同一手势的语义二义**。

#### 方案

把复制**收进操作面板**，长按只负责弹一次面板：

| 开关 `long_press_panel_enable` | 长按评论/动态正文的结果 |
| --- | --- |
| **开启（默认）** | 弹操作面板；面板第一项是「复制文字」 |
| 关闭 | 直接打开复制界面（= 改版前的旧行为） |

三条边界（都在代码注释里写明了，此处再记一遍）：

1. **「复制文字」这一项只受 `copy_enable` 与「正文非空」约束，与长按开关无关。**
   面板是「这条评论/动态能做的操作」的完整列表，`long_press_panel_enable` 管的是「长按走哪条路」，
   不是「面板里有什么」。正文为空时不列（点了只会进到一个空界面，既不报错也没反馈）；
   用户把 `copy_enable` 关掉时也不列（否则与设置自相矛盾）。
2. **评论末列的操作按钮、动态的「管理」按钮不受本开关影响**，点击一律弹面板。
   本开关只管「长按正文」这一个入口。
3. **`adapter/ReplyAdapter.kt` 的「删除评论」从无条件改成显式 `canManage(reply)` 判定。**
   原来它只在 `canManage` 为真的调用点被调用，所以可以不判；现在**别人的评论长按也会进到面板**，
   漏判就会给出一个必然失败的删除项。

#### 开关落在哪一页

用户说的是「个性化设置」。全工程**没有这个页面名**：`activity/settings/SettingMainActivity.buildGroups()`
的一级分组是 播放与播放器 / 账号与登录 / 界面与外观 / 内容与浏览 / 通知设置 / 缓存与下载 / 高级与实验 /
关于与帮助（+ Debug 开发者工具）。语义上最贴近的是 `activity/settings/SettingPrefActivity.kt`
（`setPageName("偏好设置")`，经「内容与浏览 → 通用偏好」进入，见 `SettingsIndex.kt` 的
`addLeafItems(list, "通用偏好", SettingPrefActivity::class.java, …)`）。

因此开关落在**该页「功能」组、紧挨「长按复制」**——两者是兄弟项，放一起用户才找得到。
**这是一处需要用户确认的落点**：如果他心里的「个性化设置」是别的地方，搬走只需改这一行。

#### 默认值只有一个来源

新增 `util/LongPressPrefs.kt`，把默认值 `DEFAULT_ENABLED = true` 与判据收在一处，
`SettingPrefActivity` 里 `SettingSection` 的默认值**引用该常量而不是再写一遍字面量**——
两处各写一个 `"true"` 的典型症状是「设置页显示开着、实际却是关的」，这种错不报错、只能人肉比对。
这与 `player/SkipOpEdPrefs.kt`（§三十三）是同一个模式。

**老用户升级不会被改设置**：`SharedPreferencesUtil.getBoolean(key, def)` 的语义是**键不存在才用 def**，
所以只改默认值天然满足「新装默认开 + 升级尊重用户显式选择」，**不需要任何迁移/重置代码**。
单测 `LongPressPrefsTest.老用户显式关过之后仍然是关闭` 就是这条的护栏。

#### 改动文件（6 个）

| 文件 | 改动 |
| --- | --- |
| `util/SettingsKeys.kt` | **只新增** `LONG_PRESS_PANEL_ENABLE = "long_press_panel_enable"`，既有常量值一个未改 |
| `util/LongPressPrefs.kt`（新） | `DEFAULT_ENABLED` / `isEnabled()` / `shouldOfferCopy(copyEnabled, text)` |
| `util/StringUtil.java` | 抽出 `isCopyEnabled()` 与 `openCopyPage(Context, String)`；`setCopy(TextView, String)` 改为调用两者，**对既有 14 个 `setCopy` 调用点行为零变化** |
| `adapter/ReplyAdapter.kt` | 长按按开关二选一；`showManageMenu` 增加「复制文字」项；「删除评论」补 `canManage` 门槛 |
| `adapter/dynamic/DynamicHolder.kt` | `showDynamic` 长按按开关二选一；静态 `showManageMenu` 增加「复制文字」项 |
| `activity/settings/SettingPrefActivity.kt`、`activity/settings/SettingsIndex.kt`、`res/values/strings.xml` | 新增开关条目与 `desc_long_press_panel_enable`；全局搜索索引加同名叶子 |

> `util/StringUtil.java` 里 `isCopyEnabled()` 内部仍写的是字面量 `"copy_enable"`（未换成
> `SettingsKeys.COPY_ENABLE`）——与原实现保持一致，避免把一次纯抽取变成涉及常量迁移的改动。

#### 单测

`app/src/test/java/com/RobinNotBad/BiliClient/util/LongPressPrefsTest.kt`，**9 例**
（`LongPressPrefsTest` 的 `<testcase>` 计数；本次新增用例按 §36.7 第 5 条以机器产出为准）。
其中三条是「不会崩、只会静默错」的护栏：默认值必须是开启、老用户显式关过必须仍是关、
**键名必须稳定**（改了键名等于把老用户的设置读成「没设过」，默认值会把他关掉的开关重新打开）。

`shouldOfferCopy` 之所以抽成纯函数也是这个原因——「空正文还列出复制」既不崩也不报错，
只是点了没反应，编译器和异常都抓不到，只能靠断言钉住。

### 37.2 改动 2：收藏夹排序行 / 稍后再看筛选条的「滚动自动收回」

#### 为什么不能靠 RecyclerView 自己把条带走

`res/layout/activity_simple_refresh.xml` 里 `filterBar` / `sortBar` / `manageBar` / `groupBar`
都是列表的**兄弟节点**、位于 `SwipeRefreshLayout` 之上，不是列表项
（布局注释已写明做成列表项会重映射业务 adapter 的 viewType 并改变 `adapterPosition` 语义）。
它们不会随列表滚动，只能在滚动回调里手动把 `layoutParams.height` 收到 0。手表屏本来就小，
收起来能多露出一整行视频。

#### 实现位置

能力加在 `activity/base/RefreshListActivity.kt`，**默认不启用**：该布局被大量列表页共用
（设置页、历史、下载、关注……），默认给所有页面加滚动收起是不合适的。子类显式调用
`setupAutoHideBars(vararg bars)` 才生效。

- `activity/user/WatchLaterActivity.kt`：`filterBar`（全部 / 未看完 / 清除已看完）
- `activity/user/favorite/FavoriteVideoListActivity.kt`：`sortBar`（收藏时间 / 播放量 / 投稿时间）

**多选条（`manageBar`）故意没放进去**：用户正勾着视频选删除呢，条自己收走就没法点「删除」了。

#### 行为

| 手势 | 结果 |
| --- | --- |
| 向下滚超过阈值 | 收回（高度动画到 0，结束后 `GONE`） |
| 向上滚超过阈值 | 展开 |
| 已经收着，且列表停在顶部 | 强制展开 |

收回/展开用**一个** `ValueAnimator` 同时推多条（高度按同一条 0..1 进度算），
每条各起一个动画会因启动时刻微差而错位，看起来像抽搐。切换筛选/排序后列表回到第一页，
用户已经滚不回去，靠滚动事件把条带回来是不可靠的，所以 `reloadForFilter()` / `switchSort()`
里显式调 `expandAutoHideBars()`。

#### 判据抽成纯函数并单测

真正决定观感的是「什么时候收、什么时候展」，而 `View.animate()`、`layoutParams.height`
在 JVM 单测里全是 Android 桩，断言写了也是假的（比不写更危险，见 §36.7 第 1 条）。
所以新增 `util/view/ScrollRetractDecider.kt` 承载判据：**阈值 12px 防抖**（直接看 dy 正负会让
工具条随手指抖动来回抽搐），调用方负责累加同向滚动量。

其中一条返回值看起来反直觉，必须留在文档里：

> **已经收着的时候，继续向下滚仍然返回 `COLLAPSE`。**
> 它的真正作用是让调用方把累加值清零。若在这里返回 `NONE`，累加值会一直涨；
> 等用户往上滚时先要抵消掉这些历史正值，阈值早被吃掉，表现为
> **「条收起来以后怎么滚都不回来」**。

另一条是 `canScrollUp == false` 时强制展开：列表顶部没有更多内容可滚，
`RecyclerView` 不会再产生负的 `dy`，靠滚动事件永远等不到展开，只能在这里兜住。

`app/src/test/java/com/RobinNotBad/BiliClient/util/view/ScrollRetractDeciderTest.kt`，**8 例**
（覆盖阈值边界、防抖、正常收展、回到顶部强制展开、到顶但正在向下滚时不展开）。

#### 改动文件（4 个 + 1 个新测试目录）

| 文件 | 改动 |
| --- | --- |
| `util/view/ScrollRetractDecider.kt`（新） | 纯判据对象 |
| `activity/base/RefreshListActivity.kt` | 新增 `setupAutoHideBars` / `expandAutoHideBars` + 私有动画实现；在既有 `onScrolled` 里多调一次 `handleAutoHideScroll(dy)` |
| `activity/user/WatchLaterActivity.kt` | 点亮 `filterBar` 后 `setupAutoHideBars(...)`；`reloadForFilter()` 里 `expandAutoHideBars()` |
| `activity/user/favorite/FavoriteVideoListActivity.kt` | `setupSortBar()` 里 `setupAutoHideBars(...)`；`switchSort()` 里 `expandAutoHideBars()` |
| `app/src/test/java/com/RobinNotBad/BiliClient/util/view/ScrollRetractDeciderTest.kt`（新） | 8 例 |

> 「自然高度」必须缓存：条本来是 `wrap_content`，收回时被压成 0，而 0 高度量不出「原本多高」，
> 所以第一次量到就存进 `autoHideBarHeight`，否则会**收得回去、展不回来**。
> 另外「收回」只动当前真正可见的条——不可见的（只读收藏夹的多选条、非稍后再看页的清除按钮）
> 高度是 0，收它没有意义，还会被算进待展开集合里，让展开时把本该 `GONE` 的条点亮。

#### 真机验收步骤

1. 进「稍后再看」，向下滚一屏 → 顶部「全部 / 未看完 / 清除已看完」这一行收起；
2. 向上滚 → 该行展开；滚回最顶部（列表已经到顶）→ 该行必须已经展开（不需要再滚一下）；
3. 在收起状态下点「未看完」→ 列表重拉后该行应立即回到展开状态；
4. 进自己的收藏夹，同样验证「收藏时间 / 播放量 / 投稿时间」这一行的收放；
5. 收藏夹里点「多选」进多选模式 → **多选条（多选 / 删除）不应该被收起**，始终可点；
6. 手指轻点式小幅滑动（不到阈值）→ 条不应该闪烁收放。

### 37.3 本批次的验证状态

- `:app:assembleDebug --offline --no-build-cache --no-configuration-cache`：**BUILD SUCCESSFUL**。
- `:app:testDebugUnitTest`（同参数）：**BUILD SUCCESSFUL**；读
  `app/build/test-results/testDebugUnitTest/*.xml`，**45 个测试类 / 417 例 / 0 失败 / 0 错误 / 0 跳过**。
  对照批次 7 收尾时的 43 类 / 400 例：**+2 类 / +17 例**（`LongPressPrefsTest` 9 + `ScrollRetractDeciderTest` 8），
  既有用例一条未减少、一条未改判定。
- 过程中修掉一个真实编译错误：`adapter/ReplyAdapter.kt:536` `Argument type mismatch:
  actual type is 'kotlin.CharSequence!', but 'kotlin.String!' was expected` ——
  `Reply.message` 在 Java 侧是 `CharSequence`，而复制界面只收 `String`，统一转一次后通过。
- `grep -r "AlertDialog" app/src/main/java`：**9 处命中全部在 `util/TerminalDialog.kt` 自身，业务代码 0 命中**。
- 本节**未新增任何 Activity**（开关走 `SettingPrefActivity` 已有分组），`AndroidManifest.xml` 未动。
- **滚动收放本身、以及长按面板的真机手感都只有静态与单测级别的验证**：动画与手势走 View 层，
  JVM 单测覆盖不到（这正是把判据抽成 `ScrollRetractDecider` 的原因）。真机步骤见 §37.2 与清单第十章。

### 37.4 本批次明确没有碰的东西

- `util/StringUtil.java`：`setCopy` 的**既有调用点一处未改**，`copyText()` 与剪贴板路径未动。
- `player/ViewPointSkip.kt`（片头片尾跳过逻辑）、`util/MsgNotifier.kt`：一行未动。
- `util/SettingsKeys.kt`：**只新增一个常量，既有键名与值一字未改**。
- `activity/video/`、`model/Reply.java` 的排序函数、`api/` 下任何接口：未动（批次 7 已冻结）。
- `res/layout/activity_simple_refresh.xml`：**本批次未改**——滚动收回用的是既有的条，
  没动布局、没加 view、没改 id（`filterBar` 的 `clearWatched` 是批次 7 / w2 加的）。

---

## 三十八、26.10.05 线上 release 崩溃：动态详情页 NPE（用户提供混淆栈）

用户粘贴了一份 release 包的 `java.lang.NullPointerException` 栈（无文字说明，**没有告知触发页面和操作**）。
本节记录从这份栈反推出根因、修复与验证的完整过程。

### 38.1 怎么把混淆栈还原成人话

1. 栈里只有 `SourceFile:323` 这类合成行号，先确认 release 的 mapping 还在：
   `app/build/outputs/mapping/release/mapping.txt`（用户自己打的包，时间戳 2026-10-05 14:49）。
   `docs/review/` 以外的 `app/build/` 不进版本库，**换台机器或跑过 `clean` 就没有了**，所以这类栈要趁 mapping 还在时先反混淆。
2. 用 SDK 自带的 retrace：

```bash
java -jar "D:\Program Files\android-sdk\tools\proguard\lib\retrace.jar" -verbose \
     "app/build/outputs/mapping/release/mapping.txt" "D:\tmp\crash.txt"
```

   ProGuard 的 retrace 能读 R8 写进 mapping 的 `# {"id":"sourceFile"}` 与 `residualsignature` 注释，**不需要额外装 R8 retrace**。

3. 还原结果（关键三帧）：

```
java.lang.NullPointerException
 at com.RobinNotBad.BiliClient.activity.dynamic.DynamicInfoActivity$$ExternalSyntheticLambda1
    .void DynamicInfoActivity.onCreate$lambda$3$lambda$1(long, DynamicInfoActivity, Dynamic)(SourceFile:323)
 at com.RobinNotBad.BiliClient.util.Result.onSuccess(androidx.core.util.Consumer)
 at com.RobinNotBad.BiliClient.activity.dynamic.DynamicInfoActivity$$ExternalSyntheticLambda0
    .kotlin.Unit DynamicInfoActivity.onCreate$lambda$3(long, …, Result)(SourceFile:194)
 at com.RobinNotBad.BiliClient.activity.article.OpusInfoActivity$sam$androidx_lifecycle_Observer$0
    .void DynamicInfoActivity$sam$androidx_lifecycle_Observer$0.onChanged(java.lang.Object)
 at androidx.lifecycle.LiveData.considerNotify …
 at androidx.lifecycle.ReportFragment$LifecycleCallbacks.onActivityPostStarted
```

   ⇒ 崩在 **`DynamicInfoActivity.onCreate` 里 `getDynamicById(id).observe{}` → `onSuccess { dynamic -> … }` 这个 lambda 体内**，
   由 Activity 走到 `STARTED` 时 LiveData 派发触发。

> **⚠️ R8 合并类的陷阱（本次差点看走眼）**：mapping 里 `E0.j` / `C0.c` 这类名字**不是一对一**的。
> `E0.j`「表头」写的是 `DynamicInfoActivity$$ExternalSyntheticLambda1`，但它同时还有
> `(CollectionInfoActivity, long)` 的构造来源；`C0.c` 更夸张，`OpusInfoActivity$sam$…Observer$0` 底下合并了
> `VideoRcmdFragment` / `VideoInfoFragment` / `VideoInfoActivity` / `BangumiInfoFragment` 等一串同类 SAM 包装。
> **单看表头类名定归属会错**，必须结合 `residualsignature` 注释和实际方法签名（本例是靠签名里的
> `(long, DynamicInfoActivity, Dynamic)` 才锁定）。另一条更快、更不容易错的路径是
> `Result.java` 的 `t1.z`：它的映射行号 `42:42 / 43:43 / 45:45` 直接把人引到 `Result.onSuccess`，
> 而 `onSuccess` 自己已经判空（`if (isSuccess()) { T value = getOrNull(); if (value != null) accept(value); }`），
> 所以 **NPE 只可能在消费者 lambda 体内**，不在 `Result` 里。

### 38.2 根因

`DynamicInfoActivity` 这个 `onSuccess` lambda 里，**能被解引用成 null 的只有两处**：`dynamic.stats.reply` 与
`dynamic.userInfo.mid`（另一处 `diFragment.view!!` 见 38.5 次要防御）。

`userInfo` 可以先排除：

- `app/src/main/java/com/RobinNotBad/BiliClient/api/DynamicApi.java:808` 是 `dynamic.userInfo = userInfo;`，
  **无条件执行，且位于唯一的提前 return 之前** ⇒ 正常解析路径上 `userInfo` 恒非 null。

`stats` 则是**确证可空**：

- `app/src/main/java/com/RobinNotBad/BiliClient/api/DynamicApi.java:810-813`：

```java
if (dynamic.type.equals("DYNAMIC_TYPE_NONE")) {
    dynamic.content = "[动态不存在]";
    return dynamic;          // ← 在这里就返回了
}
```

- 而 `dynamic.stats` 的赋值在 **`:950-962`**，即 `if (modules.has("module_stat") && !modules.isNull("module_stat"))` 分支内。
  ⇒ **动态类型是 `DYNAMIC_TYPE_NONE`（动态已被删除 / 被屏蔽）时，永远不会走到 `stats` 赋值**；另外部分动态类型本身就没有 `module_stat`。
- **工程内既有两个地方都防了这一点，只有详情页漏了**：
  - `app/src/main/java/com/RobinNotBad/BiliClient/adapter/dynamic/DynamicHolder.kt:888` —— 渲染卡片时 `if (dynamic.stats != null)`；
  - `app/src/main/java/com/RobinNotBad/BiliClient/activity/article/OpusInfoActivity.kt:58-60` —— 注释原文就是
    `// 防御：抓取/解析失败时 opus.stats / opus.upInfo 可能为 null，避免 NPE 崩溃` + `if (opus.stats == null) opus.stats = Stats()`。

**结论：`DynamicInfoActivity.kt` 用 `dynamic.stats.reply` 违反了工程既有的「`stats` 可空，用前判空」契约。**
这条崩在「旧版动态（`Opus.TYPE_DYNAMIC_OLD_STYLE`）详情页」上：`OpusInfoActivity.kt:48-56` 判断是旧版动态后会
`startActivity(Intent(this, DynamicInfoActivity::class.java))` 再 `finish()`，所以正常操作也能走到这个页面。

### 38.3 改动（3 个文件）

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/model/Dynamic.java` | 新增 `public void ensureDetailFields()`：`stats == null` 时补 `new Stats()`，`userInfo == null` 时补 `new UserInfo()`；**已有值不覆盖**，可重复调用；KDoc 记了这次崩溃的来龙去脉 |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/dynamic/DynamicInfoActivity.kt` | `onSuccess` lambda 开头调 `dynamic.ensureDetailFields()`；把 `diFragment.view!!.post { … diFragment.view!!.findViewById … }` 改成 `view?.let { diView -> diView.post { … diView.findViewById<View>(…) ?: return@post … } }` |
| `app/src/test/java/com/RobinNotBad/BiliClient/model/DynamicEnsureDetailFieldsTest.kt` | **新增**，6 例（见 38.4） |

为什么把判据抽到 `model/Dynamic` 而不是像 `OpusInfoActivity` 那样内联两行 `if (x == null)`：
详情页的这条路径在 JVM 单测里**跑不到**（`analyzeDynamic` 会先撞 `android.text.TextUtils`，而本工程
`app/build.gradle` 没开 `returnDefaultValues`，桩方法直接抛 `Method … not mocked`）。抽成 POJO 上的纯方法之后，
崩溃现场就是可断言的：`Dynamic()` 手工置 `stats = null` 正是线上那份数据形态。

### 38.4 验证

- `.\gradlew.bat :app:assembleDebug --offline --no-build-cache --no-configuration-cache` → **BUILD SUCCESSFUL**（30s）。
- `.\gradlew.bat :app:assembleRelease --offline --no-build-cache --no-configuration-cache` → **BUILD SUCCESSFUL**（33s，含 `minifyReleaseWithR8`）。
  **崩的是 release 包，所以 release 链路必须单独验一次**，不能只跑 debug。
- `.\gradlew.bat :app:testDebugUnitTest`（同参数）→ **46 个测试类 / 423 例 / 0 失败 / 0 错误 / 0 跳过**
  （上一轮 45 类 / 417 例，本次 **+1 类 / +6 例**，既有用例一条未减）。
- 新增 6 例：`stats 为 null 时补成空对象`（并断言 `reply`/`like` 可读为 0，即崩溃现场那三个值）、
  `userInfo 为 null 时补成空对象`、`已有的 stats 不会被覆盖`（`assertSame` + 计数原样保留）、
  `已有的 userInfo 不会被覆盖`、`重复调用是幂等的`、`两个字段同时为 null 时一次补全`。

### 38.5 诚实边界（重要）

- **没有在真机上复现过这次崩溃**。用户只给了栈、没给触发场景，团队也没有设备。上面的根因是
  **「反混淆精确到 lambda + 源码遍历该 lambda 的每一个解引用」**得出的，属于静态确证，不是运行时确证。
- **R8 会把小方法内联进调用方**，所以严格说这一帧里也可能包含被内联进来的代码。我按「该 lambda **自己写的**解引用」
  排查，找出 `stats` 这一处确定可空；`diFragment.view!!` 作为次要防御一并去掉，但没有把它当成主因写。
- 如果打上补丁后**同一个栈还在**，那说明真凶是被内联进来的别处，需要新 mapping 重新 retrace —— 那时请把
  **触发页面 + 操作步骤**一起发过来（这次没有）。
- 次要防御的取舍：`diFragment.view` 在 `viewPager.adapter = vpfAdapter` 之后由
  `FragmentPagerAdapter` 的 `commitNow` 同步创建，**正常情况下取得到**；改掉 `!!` 是因为同一段代码上面
  （第 63-64 行）作者自己已经写了 `if (view != null)`，前后不一致才是问题。取不到时只是静默跳过「滚动到
  指定评论」的聚焦动作，不会崩、也不会影响别的功能。

### 38.6 真机验证步骤

1. 触发原场景：在动态里遇到一条**已被删除 / 被屏蔽**的动态（`DYNAMIC_TYPE_NONE`），从
   **动态 → 旧版动态详情页**进入（即 `OpusInfoActivity` 判定为 `TYPE_DYNAMIC_OLD_STYLE` 后跳的那个页面）。
2. 期望：页面正常打开，显示「[动态不存在]」，**不崩溃**；`logcat` 无 `NullPointerException`。
3. 顺带回归正常动态详情页：随便点一条普通动态进详情，评论区能正常加载（确认补默认值没有把真实统计信息吃掉）。
4. 若仍有同一栈：连同**触发页面与操作步骤**、以及新产生的 `app/build/outputs/mapping/release/mapping.txt` 一起反馈。

### 38.7 本次明确没有碰的东西

- `api/DynamicApi.java` **一行未改**：它有 `analyzeDynamic` 对 `DYNAMIC_TYPE_NONE` 提前 return 的行为，
  但**没有**在 API 层给 `stats` 补默认值 —— 因为 `DynamicHolder.kt:888` 是**依赖 `stats == null` 来隐藏点赞区的**，
  在 API 层统一补上空 `Stats` 会让这类动态的点赞按钮变成可点的「0」，属于改变渲染行为。**这是刻意的**：
  契约保持「`stats` 可空，用者判空」，只在真正需要的消费点补。
- `adapter/dynamic/DynamicHolder.kt`、`activity/article/OpusInfoActivity.kt`：本来就有防御，**未动**。
- `model/Stats.java` / `model/UserInfo.java`：未加任何字段或构造器（用的就是既有的默认构造）。
- `util/Result.java`、`util/TerminalContext.kt` 的 LiveData 链路：未动。
- `AndroidManifest.xml`：未动（未新增 Activity）。

---

## 三十九、26.10.05 第三批真机反馈：收藏夹顶部工具条三处

> 用户原话（逐字，三条一起报的）：
> 「收藏夹多选选择后取消选择不会让图标再次暗下」
> 「不进行多选时请将多选那一行删除」
> 「在列表中间时，上划会展开上面的按钮，但是按钮会导致下面列表位移和滑动手势冲突，请修复」

三条都落在**收藏夹内容页**（`FavoriteVideoListActivity`）顶部那两条工具条上（`sortBar` 排序行、
`manageBar` 多选行）。三条的成因互不相同，分别是**动画层、可见性层、判据层**。

### 39.1 三条的根因

#### (1) 取消勾选后条目不再变暗 —— item animator 把 alpha 吃了

多选态的暗/亮是 `VideoCardHolder.applySelection()` 直接写 `itemView.alpha`（选中 `1f`、
未选中 `0.45f`），而勾选/取消勾选走的是 `videoCardAdapter.notifyItemChanged(position)`。

`RecyclerView` 默认挂 `DefaultItemAnimator`，它处理 `notifyItemChanged` 的方式是**另建一个
ViewHolder 做交叉淡入**（`SimpleItemAnimator.canReuseUpdatedViewHolder()` 在
`supportsChangeAnimations = true` 时返回 `false`），并在动画收尾把新布局的 alpha 设成 `1f`。
于是：

- **勾选**（目标本来就是 `1f`）看不出问题；
- **取消勾选**（目标是 `0.45f`）被动画静默改回 `1f` → 正是用户说的「不会再次暗下去」。

这就是为什么「选中看起来正常、取消选中不正常」——不是勾选逻辑写错，是**动画的收尾状态覆盖了我们的状态**。

修法：`VideoCardAdapter.onAttachedToRecyclerView()` 里
`(recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false`。
只关「同一项内容变化」这一类动画，增删/移动动画不受影响。

#### (2) 不进入多选时也该把多选那一行收起来

`setupManageBar()` 原本无条件 `findViewById<View>(R.id.manageBar).visibility = View.VISIBLE`，
只要这个收藏夹能写，一整行就一直挂在列表上方占竖向空间——手表屏幕本来就不高。

修法：可见性改由多选态驱动（`updateManageBar()` 里
`visibility = if (selectionMode) VISIBLE else GONE`），入口改成长按条目 → 菜单里的新项「多选」。

**顺带修掉一处隐患**：`danger` 原本写死 `setOf(2)`（对应「取消收藏」）。菜单里一插新项，
下标就会漂移、危险色会标到别的操作上。已改成
`setOf(actions.indexOfFirst { it.first == "取消收藏" })`，加项不再需要人肉同步下标。

#### (3) 列表中间展开工具条和滑动手势打架 —— 展开的时机判据不完整

`ScrollRetractDecider.action()` 原判据是「向上累计滚过阈值就 `EXPAND`」。而展开 = 把条的高度从 0
动画回自然高度，这些条是**列表的兄弟节点**、位于列表上方，高度一变列表可见区域跟着变、
**列表内容整体位移**。用户正在列表中间按住屏幕拖，手指底下的条目被凭空推走 ——
就是「按钮导致下面列表位移和滑动手势冲突」。

这不是动画时长问题，是**时机**问题：收回随时可以做（内容朝手指方向让位，方向一致不打架），
但**展开必须在列表已经不会再产生位移冲突的时候**去做，也就是停在顶部（`!canScrollUp`）时。

修法：`action()` 收缩成两条路——

```kotlin
if (collapsed) {
    if (!canScrollUp && accumulated <= 0) return EXPAND   // 回到顶部才展开
    if (accumulated > THRESHOLD) return COLLAPSE           // 继续往下滚 → 顺带清零累加值
    return NONE
}
if (accumulated > THRESHOLD) return COLLAPSE
return NONE
```

**注意这里有意保留了一个反直觉分支**：已经收着时继续向下滚**仍然返回 `COLLAPSE`**（即使无动画可做），
唯一作用是让调用方把累加值清零。若返回 `NONE`，累加值会一直涨，等用户回头往上滚时先要抵消掉这些
历史正值、阈值早被吃掉，表现为「条收起来以后怎么滚都不回来」。

### 39.2 顺带修掉的一个潜在缺陷：长按抬手时补发的点击

`VideoCardHolder.bindClick()` 里的长按是 `setOnTouchListener` + `postDelayed(200)` 实现的，
`ACTION_DOWN` 返回 `false`（不吞事件，好让列表自己处理滚动）。代价是 `View.onTouchEvent` 照样会在
`ACTION_UP` 上补发一次 `performClick()`。于是**同一次长按走两条路**：

- 多选态下两条路都是 `toggleSelected(position)` → 长按先选上、抬手立刻取消，**净效果是长按什么也选不中**；
- 非多选态下则是「长按弹了菜单、抬手又跳进视频详情页」。

已加 `suppressClickAfterLongPress` 标记：长按回调一旦真的执行就立起标记，让紧随其后的那次点击自己吞掉。
`bindClick()` 里随新绑定清零，避免复用 ViewHolder 时误吞。

### 39.3 改动文件

| 文件 | 改了什么 |
|---|---|
| `app/src/main/java/com/RobinNotBad/BiliClient/util/view/ScrollRetractDecider.kt` | `action()` 展开条件收紧为「仅 `!canScrollUp` 时」；补三处注释说明为什么 |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/video/VideoCardAdapter.kt` | 新增 `onAttachedToRecyclerView()`：`supportsChangeAnimations = false` |
| `app/src/main/java/com/RobinNotBad/BiliClient/adapter/video/VideoCardHolder.kt` | 新增 `suppressClickAfterLongPress`（长按后吞掉补发的点击）；`applySelection()` 前缀解析提前到所有分支之前 |
| `app/src/main/java/com/RobinNotBad/BiliClient/activity/user/favorite/FavoriteVideoListActivity.kt` | `manageBar` 改为只在多选态可见；`showManageMenu` 增加「多选」入口、`danger` 改动态定位 |
| `app/src/test/java/com/RobinNotBad/BiliClient/util/view/ScrollRetractDeciderTest.kt` | `向上滚超过阈值就展开` 断言改为 `NONE`（口径变更）；新增 `回顶前一路向上滚都不展开回到顶部才展开`、`没收回时向上滚也不动作` |

**`app/src/main/res/layout/activity_simple_refresh.xml` 未改**：`manageBar` 在 layout 里本来就是
`visibility="gone"`，之前是代码把它点亮的，所以收起来不需要动布局。

### 39.4 验证

- `.\gradlew.bat :app:assembleDebug --offline --no-build-cache --no-configuration-cache` → **BUILD SUCCESSFUL**（43s）。
- `.\gradlew.bat :app:assembleRelease --offline --no-build-cache --no-configuration-cache` → **BUILD SUCCESSFUL**（1m 3s，含 `minifyReleaseWithR8`）。
  用户装的是 release 包，所以两条链路都跑了。
- `.\gradlew.bat :app:testDebugUnitTest`（同参数）→ **46 个测试类 / 425 例 / 0 失败 / 0 错误 / 0 跳过**
  （上一轮 46 类 / 423 例，本次 **+2 例**）。
- 编译期仅剩一条既有告警：`VideoCardAdapter.kt` 的 `Condition is always 'true'`（`videoCardList != null`，
  `videoCardList` 是非空类型）—— 历史遗留，未动。

### 39.5 诚实边界

- 三处修法都是**读代码得出的成因**，**没有真机复现**（团队没有设备）。
- (1) 我确认了「`applySelection` 写下的 alpha 没有任何别处覆盖」（全工程 grep
  `selectionMode|selectedAids|applySelection` 只有 3 个文件命中，`itemView.alpha` 只由
  `applySelection` 写），所以「取消后不暗」只能来自 RecyclerView 的动画收尾；这是**排除法**结论，
  静态成立、但没有在设备上抓过帧。
- (3) 是三条里最容易在真机上确认的一条：改前在列表中间上滑，条会边展开边把内容顶下去；
  改后应该**只在滚到顶时**才展开。若真机仍在中途展开，说明 `recyclerView.canScrollVertically(-1)`
  在这个页面上不可靠，需要改成用 `computeVerticalScrollOffset() == 0` 判断，并把观察到的现象发回来。
- (2) 与「顺带修掉的潜在缺陷」都以真机手测为准；长按 200ms 的阈值与抬手补发点击的行为
  在 JVM 单测里跑不到（`MotionEvent`/`View` 全是 Android 桩，项目也没开 `returnDefaultValues`）。

### 39.6 真机验证步骤

1. 进自己的收藏夹（点进**具体某个收藏夹**，不是收藏夹列表页）→ 顶部应**只有排序行，没有「多选」那一行**。
2. 长按任意条目 → 菜单里有「复制到…/移动到…/多选/取消收藏」，且**「取消收藏」是危险色**；
   选「多选」→ 顶部出现多选行。
3. 点若干条目：选中的条目保持原色、标题前有 `✓ `；**再点一次取消选中：该条目应重新压暗（0.45）**，
   且「删除(N)」的计数同步减少。
4. 在多选行点「退出多选」→ 多选行消失、所有条目的 `✓ ` 前缀与压暗全部复原。
5. 滚动冲突：**在列表中间**向上滑 → 排序行**不应**展开；一路滑到顶 → 排序行展开；
   展开时列表内容不应被顶得跳一下。
6. 长按返回：长按条目弹出菜单后**抬手，不应跳进视频详情页**；多选态下长按某条目应**稳定地勾选它**（不闪一下又取消）。

### 39.7 本次明确没有碰的东西

- `app/src/main/res/layout/activity_simple_refresh.xml`：未动（见 39.3 说明）。
- `RefreshListActivity` 的自动收回机制（`setupAutoHideBars`/`animateBars`/`naturalBarHeight` 缓存）：未动，
  只改了判据对象 `ScrollRetractDecider` 的返回值定义。
- `manageBar` **仍然不进** `setupAutoHideBars`：多选时条被自动收走就点不到「删除」了，这是刻意的。
- `FavoriteApi`、`VideoCardAdapter` 的勾选数据流（`selectedAids`/`selectionMode` 仍在页面侧持有）：未动。
- 布局里 `manageToggle`/`manageDelete` 由 `Activity` 持有的引用与点击回调：未动（只是可见性跟着多选态走）。

---

## 四十、Release 说明漏掉更新日志：根因与兜底（26.10.05 发版后）

### 40.1 现象

26.10.05 的 GitHub Release 正文只有三样东西：`### APK 校验值（MD5）` 表、
`<!-- update: versionCode=2610050 versionName=26.10.05 forceUpdate=false -->` 机器可读元数据、
以及 GitHub 自动生成的 `**Full Changelog**` commits 链接 —— **没有「本次更新」说明**。
用户点进 Release 看不到这版改了什么（更新日志只写在 App 内的「更新历史」页）。

### 40.2 根因

`.github/workflows/build-release.yml` 的「计算发行 tag 与发布说明」步骤里，正文分支是
`if [ -n "$INPUT_BODY" ]`，而 `INPUT_BODY: ${{ inputs.release_body }}` 只在
`workflow_dispatch` 下才有值。**推 tag 走的是 `push` 事件，`inputs.*` 恒为空**，
于是 `has_body=false`、`final_body` 只剩 MD5 表，`generate_release_notes` 再补一段 commits 摘要。
发版技能当时写的是「走 `workflow_dispatch` 并把 `release_body` 填上」，但实际发版习惯是推 tag，
这条路没人走，说明就一直漏。（工作流 `conclusion=success` 不会暴露这个问题。）

### 40.3 兜底（改完的两条路都给完整日志）

- 新增 `.github/scripts/extract_update_log.py`：从 `app/src/main/res/values/strings.xml` 的
  `update_log_current` 抽当版日志（去 `<item>` 标签、`html.unescape` 反转义、跳过空条目、
  强制 UTF-8 输出）；读不到 / 找不到该 `<string-array>` / 抽出来为空 → 打印 `::error::` 并**退出 1**。
  `update_log_current` 本来就是当版日志的单一真相（关于页读它、`:app:verifyVersionConsistency`
  拿它当锚点），所以直接抽它，不引入第二份需要维护的文案。
- 工作流：手工触发仍用 `release_body`；`log_body` 为空（即 push 事件）时自动抽取，
  **抽不到就 `exit 1`，拒绝发出没有更新说明的 Release**。`has_body` 改为依据 `log_body`，
  实际恒为 true，GitHub 不再自动补 commits 摘要。
- 文档：`AGENTS.md` 加硬要求段；`.dsh/skills/rebili-version-release/SKILL.md` §4 加硬要求与
  「别改回只取 `inputs.release_body`」，§5 加「**发布后必须回读 Release 正文**确认条数对得上，
  别只看 `conclusion=success`」。

### 40.4 验证

- 抽取脚本：直接跑 → 29 行（1 行标题【26.10.05 本次更新】+ 28 条）；`nope.xml` → `::error::` + exit 1。
- 把该步骤的 `run:` 块原样抽出来在 git bash 里模拟三条路径：
  - push（`EVENT_NAME=push, REF_NAME=26.10.05, INPUT_BODY=""`）→ 抽出 29 条、
    `has_body=true`、exit 0，`release-body.md` = 日志 + MD5 段 + 元数据；
  - fail-closed（strings.xml 只有 `update_log_items`）→ `::error::…拒绝发出没有更新说明的 Release` + exit 1；
  - 手工触发（`INPUT_BODY="…"`）→ 不走抽取脚本，body = 输入正文 + MD5 + 元数据，exit 0。
- YAML：用 DSH 自带的 `yaml` 包解析三个 workflow 文件，`build-release.yml` 结构有效，
  且断言『引用抽取脚本 / `if !` 守卫 / 空则失败 / `has_body` 依据 `log_body` / `final_body` 拼接 /
  旧分支已移除』全部通过（本机无 PyYAML，pip 走代理装不上）。
- 未做：**没有真正触发一次 push-tag 发版来端到端跑通**（`workflow_dispatch` 需要 token，本机没有；
  推测试 tag 会真的建 Release 并让中转去同步 Gitee，代价太大）。下次正经发版时按技能 §5 回读正文确认。

### 40.5 遗留

- **26.10.05 那个已发布的 Release 说明没能补上**：本机没有 `gh` CLI 也没有 GitHub token，
  只能人工在 GitHub 网页端编辑该 Release 的说明，把 `update_log_current` 的 28 条粘进去。
- 本次改动**不是新版本**，`26.10.05` 的 tag 仍指向 `8fd53af`，不需要重新发版。

---

## 四十一、26.10.09：自建反馈界面 + 自建公告源 + 匿名统计 / 崩溃自动上报（新功能）

> 起因（用户原话）：想在反馈界面加入「发送到服务器的反馈界面」和「遥测，看看有多少人会用这个应用」。
> 拍板：**用已有的 HK 服务器自建后端**；统计口径只要**匿名唯一安装数 + 日活**；
> **默认开、设置里可关**；公告**不是替换上游，而是增加一个自己的公告源**。
> 因此这一节不是「修 bug」，而是**新功能落地的完整记录**（含服务端、客户端、隐私文案三部分）。

### 41.1 结论：这个项目原本没有任何「反馈界面」

全仓 grep `反馈` / `feedback` 只命中注释；反馈渠道全是站外——QQ 群（`strings.xml:7` 的 `about_group`）
和 GitHub / Gitee issue；`strings.xml` 里那条 `about_trailer_feedback` 只是一个「有 bug 别找原开发者、
去找 TRAE 作者」的免责声明，**连链接都没有**。所以「新增反馈界面」是从零做，不是在某个页面加个按钮。

### 41.2 为什么另起一套服务端，而不是接上游 `api.biliterminal.cn`

上游那套（`api/AppInfoApi.java` 4 个接口，全指向 `https://api.biliterminal.cn`）有两个问题：
1. **不是这个 fork 的**。本 fork 的中转服务已停用（`.github/workflows/relay-notify.yml:40-45` 注释
   「换主后中转服务暂停使用（Secrets.RELAY_URL / RELAY_SECRET 未配置）」），也就是这个 fork
   **目前没有任何自己的后端**；往上游服务器灌反馈和统计，等于替别人做运营。
2. **上游已有接口的去重能力为零**。`AppInfoApi.customHeaders`（`AppInfoApi.java:110-140`）虽然带上了
   `App-Info`（versionName / versionCode / isBeta / applicationId / buildType / debugEnabled）与
   `Device-Info`（sdk / release / product / brand / device / type / id），且**故意不带 Cookie**
   （注释写明防上游开发者盗号）——所以服务器今天就能按天粗略数「启动量」，但**没有任何唯一标识**，
   数不了「多少个不同的人」。

于是自建了一整套，与上游**并存**、互不覆盖。

### 41.3 服务端（新增 `server/`，已部署到 HK 服务器并公网验证）

- 文件：`server/rebiliterminal_api.py`（单文件 FastAPI + SQLite，无 ORM）、`server/requirements.txt`、
  `server/rebiliterminal-api.service`、`server/nginx/rebiliterminal.zsapp.asia.conf`、`server/README.md`。
- 部署形态：`/opt/rebiliterminal-api/`（`rebiliterminal_api.py` + `env` + `data.db`），
  systemd 单元 `rebiliterminal-api`，uvicorn 监听 `127.0.0.1:8788`，
  nginx 反代 `https://rebiliterminal.zsapp.asia/terminal/`。
  配置 `env`（chmod 600）里是 `REBILITERMINAL_DB` 与 `REBILITERMINAL_ADMIN_TOKEN`。
- **发证书没用宝塔**：`/www/server/panel/class/acme_v2.py` 会查 `/www/server/panel/data/default.db`
  的 `sites` 表，**必须先有宝塔站点记录**；本机也没有 acme.sh。最终选**自装 acme.sh 走 webroot 模式**
  （`--issue -d rebiliterminal.zsapp.asia --webroot /www/wwwroot/rebiliterminal.zsapp.asia`）
  再 `--install-cert` 到 `/www/server/panel/vhost/cert/rebiliterminal.zsapp.asia/` + `--reloadcmd "nginx -s reload"`，
  续签靠 acme.sh 自带 cron。好处：可脚本化、可复现、不依赖面板 UI，也不往宝塔站点表里塞记录。
- 接口（**成功一律 `code=0`，失败 `code!=0` + `msg`，HTTP 恒 2xx**——沿用上游的风格）：
  | 接口 | 说明 |
  |---|---|
  | `GET /terminal/health` | 健康检查 |
  | `GET /terminal/announcement/get_list[?from=<id>]` | `id` 是自增 id **+ 1000000000**，故意和上游 id 空间错开；不带 `from` 是列表页（倒序），带 `from` 是启动弹窗的差量（只回 `id>from`、正序，于是最新那条最后弹、最终显示在最上面） |
  | `POST /terminal/telemetry/ping` | 按 `install_id` 聚合 + `daily_active(install_id, day)` 主键去重 → 唯一安装数与日活 |
  | `POST /terminal/feedback/submit` | 落库，`category` 白名单 `bug\|suggestion\|other\|content\|performance`，`content ≤ 4000` 字，同 IP 每小时 10 次 |
  | `POST /terminal/upload/stack` | 崩溃载荷，返回的 `id` 就是给用户看的报错编号 |
- 限流：进程内滑动窗口（ping 480/时、feedback 10/时、crash 30/时，按 `X-Forwarded-For` 首段）。
- 管理看板：`GET /admin?token=<REBILITERMINAL_ADMIN_TOKEN>`（HTML）+ `/admin/api/stats|feedback|crash`（JSON）
  + `POST /admin/announcement`（发公告，无需进服务器）。
- **隐私红线（重要）**：整套服务端**不存 IP**（只用 IP 做限流，不入库），不存 Cookie，
  `install_id` 是客户端随机生成的 UUID、与账号无关、不读 `ANDROID_ID` 或任何设备指纹，卸载重装即换新。

### 41.4 客户端

新增：
- `api/TerminalApi.java` —— 这条链路**唯一**的出口（`BASE_URL = https://rebiliterminal.zsapp.asia/terminal`）。
  **请求与解析分离**：`buildPingPayload` / `buildFeedbackPayload` / `buildCrashPayload` /
  `parseAnnouncements` / `mergeAnnouncements` / `dayKey` 都是纯函数，单测 `api/TerminalApiTest.kt`（14 例）。
  改字段先改纯函数和测试。
- `util/TelemetryReporter.kt` —— 启动时一天最多 ping 一次，**只有请求成功才写回日期**，断网启动不浪费当天机会。
- `util/CrashReporter.kt` + `util/CrashTrail.kt` —— `ErrorCatch` 在崩溃瞬间把异常/消息/线程/
  最近页面轨迹/uptime 塞进 Intent，`CatchActivity`（独立进程）在开关打开时自动上报。
- `activity/FeedbackActivity.kt` + `res/layout/activity_feedback.xml` —— 反馈页（分类 / 内容 4000 字计数 /
  联系方式选填 / 「附带账号 ID」开关 / QQ 群兜底 / 发送状态与报错号）。
- `app/src/test/.../api/TerminalApiTest.kt` —— 14 例，其中**专门断言载荷里不出现
  sessdata / bili_jct / dedeuserid / cookie / csrf**。

改动：
- `BiliTerminal.kt`（`onActivityResumed` 记页面轨迹）、`ErrorCatch.java`、`activity/CatchActivity.kt`、
  `activity/SplashActivity.kt`（启动时并联两件事：自建公告差量 + 匿名日活，**各自包异常**，
  自建服务器挂掉绝不能影响启动）、`activity/settings/AboutActivity.kt` + `activity_setting_about.xml`
  （关于页入口）、`activity/settings/SettingGroupActivity.kt`（设置「关于与帮助」加「反馈与统计」组，
  三个开关）、`activity/settings/AnnouncementsActivity.kt`、`util/SettingsKeys.kt`、
  `res/layout/activity_catch.xml`、`AndroidManifest.xml`（`FeedbackActivity` 以
  `android:exported="false"` 注册）。
- **入口挂关于页，不只挂设置页**：菜单结构对老用户是缓存的，新分组不会自动出现，
  只挂设置分组等于老用户看不到入口。
- **上游手动上传按钮逻辑一行没动**，自动上报是独立的一条路——而且自动上报**故意不套上游那两道限制**
  （「已登录 + 异常类型白名单」），否则最需要看的样本恰好会被挡掉。

### 41.5 隐私文案（改这一块之前先读这一节）

这个应用对用户有两句**公开承诺**，加遥测直接和它们冲突：
- `strings.xml` 的 `about_to_uncle`：「…也不会收集任何用户隐私信息。」
- `strings.xml` 的 `text_setup_introduction`：「…不会收集你的任何账号及隐私信息，请放心使用！」

**已同步改写**这两句：明确说出「默认开启一项匿名使用统计，只包含随机安装号 / 版本号 / 机型，
不含账号、Cookie 或任何设备唯一标识，可在设置里随时关闭」，并说明崩溃也会自动上报一份、同样可关。
新增的设置项说明 `desc_setting_telemetry` / `desc_setting_crash_auto` / `desc_setting_feedback_attach_mid`
也逐条列了上报字段。

**以后往上报载荷里加任何字段，必须同时改这三处文案**，否则承诺又是假的。
三个开关默认值：`TELEMETRY_ENABLE = true`、`CRASH_REPORT_AUTO = true`、`FEEDBACK_ATTACH_MID = false`
（账号 ID **默认不带**；关掉时传 `0`，服务端那条记录里根本不会有这个值，不是「传了但不显示」）。

> 光改文案还不够——用户要求「隐私要再同意一遍」，于是补了一道**可执行的**同意闸门，见 **41.8**。

### 41.6 验证

- `:app:testDebugUnitTest --rerun-tasks` → **48 个测试类 / 450 个用例全绿，0 failures 0 errors**
  （含新增 `TerminalApiTest` 14 例）。此前该套为 327 例，本轮新增集中在 `TerminalApiTest`。
- `:app:assembleDebug` → **BUILD SUCCESSFUL**（最后一次是在 `AnnouncementsActivity` 接线之后重跑的）。
- 服务端公网验证（从本机发起）：`GET /terminal/health`、`POST /terminal/telemetry/ping`
  （同 install_id 两次 → `daily_active` 只 1 行）、`POST /terminal/feedback/submit`、
  `POST /terminal/upload/stack`、`GET /terminal/announcement/get_list`（含 `?from=` 两条分支）
  全部按契约返回；http → 301 https；`nginx -t` 通过。
- 测试数据已清理：`installs` / `daily_active` / `feedback` / `crash` 清零，改前已 `cp` 备份
  `data.db.bak-<时间戳>`；公告表保留 **1 条真公告**（标题「欢迎使用 RE:哔哩终端」，
  说明反馈入口与两个开关），同时充当启动弹窗的端到端验证样本。

### 41.7 遗留与后续

- **版本号没动**（仍是 `2610090` / `26.10.09`）。这批是给「下一个版本」的功能，
  发版时按 `AGENTS.md` 与 `.dsh/skills/rebili-version-release/SKILL.md` 正常走：
  bump `app/build.gradle` + 往 `strings.xml` 的 `update_log_current` 补本次条目，
  否则 `.github/scripts/extract_update_log.py` 抽出来的 Release 说明会缺这一条（26.10.05 的教训见第四节）。
- **没有在真机上跑过**：新增的反馈页 / 关于页入口 / 崩溃自动上报 / 启动弹窗都只过了编译与 JVM 单测，
  真机回归清单（`docs/review/real-device-regression-checklist.md`）里应补：
  ① 关于页 → 反馈页能进、分类选择、4000 字上限、断网发送的报错文案、成功后输入框清空；
  ② 设置里三个开关关掉后，抓包确认不再有到 `rebiliterminal.zsapp.asia` 的请求；
  ③ 故意崩溃一次，确认崩溃页显示「报错ID」且服务端 `/admin/api/crash` 里能看到；
  ④ 公告列表页在**上游接口挂掉**时仍能显示自建公告（反向也要成立）。
- 自建服务端与上游 `AppInfoApi` 的差异、以及那条「绝不要复用带 Cookie 的单参 `NetWorkUtil.getJson`」
  的硬约束，已写进 `docs/architecture-map.md` **§6.5**（含 5 条红线），改这块之前先读那一节。

### 41.8 26.10.09 补丁：隐私「再同意一遍」（启动闸门）

**起因**：用户要求「隐私要再同意一遍」（m00722）。原实现里遥测与崩溃上报**默认就开着**，
只有两处文案承认了这件事，用户此前从未被问过——「默认开」和「用户同意」是两回事。

**取舍（用户拍的板）**：弹窗里点「不同意」= **App 照常使用，只是不上报**（不是拒绝进入应用），
两个开关直接关掉，以后可在设置里手动打开；只有隐私说明改版才会再问一次。

**两个 key 分开，是这一节的核心**
- `SettingsKeys.PRIVACY_CONSENT_VERSION`（Int，0 = 从未同意）：同意到哪个版本 → 决定**能不能上报**
- `SettingsKeys.PRIVACY_PROMPTED_VERSION`（Int，0 = 从未问过）：表过态到哪个版本（同意 / 不同意都算）
  → 决定**还要不要弹**

分开的理由：「不同意」也是一次回答，不该每次启动都再问一次；而同意版本号**保持原值**，
「可以继续用但不上报」就是这一条的自然结果，不需要额外的「拒绝」状态位。
`TerminalApi.PRIVACY_VERSION = 1`，**改隐私文案必须 +1**，否则老用户不会被再问一次。

**闸门收在 `TerminalApi` 里，不靠调用点自觉**
```java
isTelemetryEnabled()      = hasPrivacyConsent() && getBoolean(TELEMETRY_ENABLE, true)
isCrashReportAutoEnabled() = hasPrivacyConsent() && getBoolean(CRASH_REPORT_AUTO, true)
```
于是「没同意就绝不上报」对**所有**既有调用点（`TelemetryReporter`、`CatchActivity`）自动成立。
新增 `hasPrivacyConsent()` / `needsPrivacyConsent()` / `acceptPrivacyConsent()` /
`declinePrivacyConsent()` / `recordPrivacyConsentFromSettings()` 五个方法。
`acceptPrivacyConsent()` 会把两个开关**复位为 true**（同意就是同意全部）；
`declinePrivacyConsent()` 只记 prompted 版本 + 两个开关置 false；
`recordPrivacyConsentFromSettings()` **只记版本号、不碰任何开关**——用户在设置里只点了一个开关，
不能顺手把另一个也打开。

**顺带修掉的真实缺陷：`TerminalDialog` 的按钮会滚出屏幕**
`Sheet.addButton` 原本把按钮加进 `container`（= `terminal_dialog_list`，**在 ScrollView 内部**），
而 `capScrollHeight()` 把滚动区高度**钉死**在屏高 45%、滚动条又是 `scrollbars="none"`。
短正文没事，长正文会把按钮顶到看不见的地方——配上本次必要的 `setCancelable(false)`
就是**用户彻底卡在闪屏上**。修法：`layout_dialog_terminal.xml` 在 ScrollView **外面**加固定底栏
`@id/terminal_dialog_buttons`，`Sheet` 加构造参数 `bottomButtonBar: Boolean = false`
（默认 false = 原行为，既有 12 处 `confirm/alert/menu` 调用点零影响），
`addButton` 据它选容器；新增的 `TerminalDialog.choice(...)` 用 `bottomButtonBar = true`。

**为什么必须新增 `choice()` 而不是复用 `confirm()`**：`confirm()` 的取消按钮被写死成
`dismiss()`（没有回调），而且它内部是**先 `dismiss()` 再 `onConfirm()`**——
`setOnDismissListener` 触发时还拿不到「用户点的其实是确定」，两条路都会走一遍。
`choice(context, title, message, primaryText, secondaryText, hint, onPrimary, onSecondary)`
让两个按钮各带自己的回调，`hint` 非空时用 `setHint` 提示「正文较长，可上下滑动阅读」
（滚动条是隐藏的，不提示的话用户只会看到一段被截断的文字）。

**其他落地点**
- `SplashActivity.proceedSplashFlowWithPrivacyGate()`：`needsPrivacyConsent()` 为假直接 `proceedSplashFlow()`；
  为真则 `TerminalDialog.choice(...).apply { setCancelable(false) }.show()`。
  **两个调用点都改走闸门**（`onCreate` 的 UETool 权限之后、`onActivityResult` 的 UETool 分支）。
  两条路都**继续启动流程**，不拦人。
- 设置页 `TELEMETRY_ENABLE` / `CRASH_REPORT_AUTO` 两个 `switch(...)` 补 onChange：
  **只在 `on == true` 时**`recordPrivacyConsentFromSettings()`。注意 `SettingsAdapter.kt:201-207`
  的顺序是**先写 SharedPreferences、再回调 onChange**，回调里读到的已经是新值。
- 「不同意」的提示用 `MsgUtil.showMsgLong(...)`：默认路径是 sticky `SnackEvent`，
  闪屏（`class SplashActivity : Activity()`，**不是 BaseActivity**）不会消费它，
  下一个 `BaseActivity` 在 `onResume`（`BaseActivity.kt:355-361`）取出来显示并
  `removeStickyEvent`（`MsgUtil.java:121/125`）→ 刚好弹一次、弹在新页面上。**是有意为之，别改成 Toast。**
- 新增文案：`privacy_consent_title` / `privacy_consent_message`（分四条写明：匿名统计、崩溃上报、
  反馈不受影响、同意与否的后果；明确「不含账号、Cookie、位置，也不读 `ANDROID_ID` 等设备唯一标识，
  一天最多一次」）/ `privacy_consent_agree` / `privacy_consent_decline` /
  `privacy_consent_scroll_hint` / `privacy_consent_declined`。
- **老用户不需要迁移**：`getInt(key, 0)` 在键不存在时返回 0，正好等价于「从未同意」，
  于是升级后也会被问一次——这恰好就是「再同意一遍」想要的效果。

**验证**：新增 `app/src/test/java/com/RobinNotBad/BiliClient/api/TerminalPrivacyConsentTest.kt`
**7 例全绿**，关键断言是「全新安装即便两个开关默认 true 也不上报」
和「点不同意后人为把 pref 置 true 仍不上报」。
`:app:assembleDebug` + `:app:testDebugUnitTest` → BUILD SUCCESSFUL（布局改造前后各跑一轮）。
真机回归清单里应再加一条：**全新安装/清数据后首次启动必须弹一次，点不同意能正常进主界面且抓包无请求**。

---

## 四十二、26.10.09：管理控制台 WebUI（遥测 / 反馈 / 崩溃 / 公告四合一）+ 服务端并发加固

### 42.1 需求与决策

用户原话：「能搞个webui吗，一边遥测一边发公告，搞新的地址和证书」，
随后补一句「遥测/反馈/公告都放在一个webui里，对了，你就搞个python扛得住吗。。？」

决策：域名 `console.zsapp.asia`（泛解析 `*.zsapp.asia` 已指向本机，**不需要另加 DNS 记录**）；
登录用**登录页 + HttpOnly 会话 Cookie**，旧 `GET /admin?token=…` 保留作兜底。

容量结论（回应用户的质疑）：`telemetry/ping` 每安装**每天最多 1 次**（客户端写回日期），
公告每次启动 1 次差量 —— 日均请求 ≈ 2~3 × 日活。1 万日活 ≈ 0.3 req/s 均值、约 1 req/s 高峰；
10 万日活 ≈ 3 req/s 均值、25~30 req/s 高峰。单 uvicorn worker 处理「小 JSON + 一次小写入」
的天花板在几百 req/s，**瓶颈不是 Python 而是每次 commit 的 fsync**。所以真正要做的是下面两条加固。

### 42.2 服务端并发加固（两个真实隐患，都已修）

1. **阻塞 I/O 跑在事件循环上**。`telemetry_ping` / `feedback_submit` / `upload_stack`
   都是 `async def`（因为要 `await request.body()`），却在里面直接 `conn.execute(...)` 调阻塞的 sqlite3。
   平时亚毫秒没事，但一旦撞锁走 `busy_timeout=15000`，**整个事件循环会卡最多 15 秒**，
   连 `/terminal/health` 都不响应。
   修法：把所有库访问抽成同步函数 `_db_ping` / `_db_feedback` / `_db_crash` / `_db_announcement_list` /
   `_db_announcement_add` / `_db_announcement_update` / `_db_announcement_delete`，
   由 `from starlette.concurrency import run_in_threadpool` 调度。**api 层不再出现 `connect()`。**
2. **`connect()` 没设 `synchronous`** → WAL 模式下默认仍是 `FULL` → 每次 commit 都 fsync。
   改成 `PRAGMA synchronous=NORMAL`（WAL 下掉电最多丢最后一个事务，不会坏库），写吞吐提升数倍。

### 42.3 会话认证（无状态签名 Cookie）

- `_session_value(exp)` = `<exp>.<hmac_sha256(ADMIN_TOKEN, exp)>`，`exp = now + 7 天`。
  **不存服务端状态**：进程重启会话不失效，改口令则所有会话立刻失效。
- `_session_valid(request)` 拆出 `exp` 后先判过期、再用 `hmac.compare_digest` 比签名。
- `_check_token(request, token)` 扩展成三种凭据任一通过：URL 里的 `token` / `X-Admin-Token` 头 /
  会话 Cookie。**旧路径因此完全不用改**，12 处既有调用点一次都没动。
- Cookie 属性：`HttpOnly; Secure; SameSite=strict; Max-Age=604800; Path=/`。
- **CSRF 两道独立防线**：`SameSite=strict` 让跨站请求带不上 Cookie；状态变更接口额外要求
  `Content-Type: application/json`（跨站表单只能发 urlencoded/plain，必须先过 CORS 预检），
  不满足直接 415。登录接口另有 `rate_limit(ip,"login",20,3600)` 防爆破。
- **踩坑**：本地 selftest 一开始用 `TestClient(api.app)` 默认的 `http://testserver`，
  带 `Secure` 的 Cookie 在 http 下**不会被回发**，导致「登录成功但后续全 403」。
  改成 `TestClient(api.app, base_url="https://console.test")` 才对 —— 反过来说，
  这个假失败恰好证明了 `Secure` 确实生效了。

### 42.4 WebUI 前端（`server/console.html`）

单文件、**零外部依赖**：不引 CDN、不引图表库。理由是国内访问 jsdelivr / unpkg 不稳，
后台不该因为外网抖动就打不开。折线图是手写内联 SVG（柱=活跃设备、线=新增安装），
悬停数值用原生 `<title>`，不需要任何 JS 库。四个页签：公告 / 反馈 / 崩溃 + 顶部遥测卡片与图表。

- 服务端用 `GET /` 原样吐出该文件，**带 mtime 缓存**：改前端只要覆盖文件，不用重启进程。
- 会话失效（403/401）时 `location.reload()` 直接回登录页。
- 所有服务端文本过 `esc()`；公告正文用 `white-space:pre-wrap` 保留换行但绝不 `innerHTML` 原始串。
- 自动刷新只刷遥测卡片与图表（60s 一次），**不覆盖公告输入框内容**。
- 登录页是服务端常量 `CONSOLE_LOGIN_HTML`（比 WebUI 短得多，没必要单独开文件）。

### 42.5 新增接口

`GET /admin/api/announcements`（返回**内部 id**，因为它要回传给 update/delete；
客户端看到的仍是 `ANN_ID_OFFSET + 内部 id`）、`POST /admin/api/announcement`、
`POST /admin/api/announcement/update`（`{id, pinned?, active?}`）、`POST /admin/api/announcement/delete`。
`GET /admin/api/stats` 顺带扩了 `week_active` / `today_new` / `feedback_pending` / `crash_pending` /
`brands`，`daily` 每条多了 `new_installs`（**纯增量，旧字段一个没改**）。

### 42.6 nginx + 证书

新 vhost `server/nginx/console.zsapp.asia.conf`：80 + 443、HTTP 跳 HTTPS、
`/terminal/` 直接 `return 404`（控制台域名不开放客户端接口）、其余全部反代 `127.0.0.1:8788`。
安全头：HSTS、`X-Content-Type-Options`、`X-Frame-Options DENY`、`Referrer-Policy: no-referrer`，
以及一条很紧的 CSP（页面零外部资源，所以允许 inline 即可）。

**签证书的坑**：证书还没签出来时 vhost 不能引用证书文件，得先用一个只 `listen 80` 的引导配置。
第一版引导配置写了 `location / { return 404; }` 却**没给 `/.well-known/` 单独开口**，
于是 acme 的 http-01 挑战拿到 404（`Invalid response from http://console.zsapp.asia/.well-known/acme-challenge/…: 404`）。
补上 `location ^~ /.well-known/ { root /www/wwwroot/console.zsapp.asia; allow all; }` 后签发成功，
再换成完整配置。`acme.sh --install-cert` 的 `--reloadcmd "nginx -s reload"` 保证续签后自动生效。

### 42.7 备份与探活

`server/backup.sh` + cron `7 4 * * *`：用 `sqlite3 .backup`（**不是 `cp`** —— WAL 模式下
最近写入还在 `-wal` 里，直接拷会丢数据），存 `/opt/rebiliterminal-api/backups/data-<YYYYMMDD>.db`，
保留 14 天，并校验备份里的表数量（少于 5 张就非零退出）。
另加 `*/10` 的探活 cron，失败往 `health.log` 追加一行 —— **只留痕，不告警**，
要真告警还得接通知渠道（当前没有）；服务本身有 systemd `Restart` 兜底。

### 42.8 验证

- 本地 `server/selftest.py`（临时 sqlite + 临时口令，**不碰生产数据**）：**39 项断言全通过**，
  覆盖未登录 403、错误口令 401、正确口令 303 + Cookie 三个属性、会话伪造/过期被拒、
  旧 `?token=` 仍可用、表单 Content-Type 被拒 415、公告增删改 + 客户端可见性 +
  **停用后客户端拉不到**、遥测三次 ping 只算 1 安装 1 日活、反馈/崩溃入库与列表可读、退出清 Cookie。
- 公网端到端（从本机直接打，`--noproxy '*'` 绕过本机那个没启动的 10808 代理）：
  `https://console.zsapp.asia/` 200 + 证书校验通过 + 5 个安全头齐全；
  登录 401/303 与 `set-cookie: rbt_console=…; HttpOnly; Max-Age=604800; Path=/; SameSite=strict; Secure`；
  带 Cookie 取 stats `code=0`，无 Cookie 403；发布公告 `{"code":0,"id":2,"public_id":1000000002}`
  → **`rebiliterminal.zsapp.asia/terminal/announcement/get_list` 立刻能拉到** → 置顶/停用/删除全部 `code=0`，
  停用后客户端只剩 1 条，重复删除 404；`console…/terminal/health` 404、`http→https` 301、
  旧 `/admin?token=` 200；WebUI HTML 21491 字节、外部资源引用 **0** 个、全部关键 DOM 节点齐备。
  验证用的遥测行与测试公告**已从生产库清掉**，`sqlite_sequence` 复位。
- **PowerShell 传 JSON 的坑**：`curl.exe --data-raw '{"title":"中文…"}'` 里含中文时会被 Windows
  控制台代码页搞坏 → 服务端 `json.loads` 失败 → `read_json` 返回 `{}` → 报「标题和正文都不能为空」。
  正确姿势是**把请求体写成无 BOM 的 UTF-8 文件，再用 `--data-binary @file`**。

### 42.9 遗留

- 服务端 **没有告警渠道**（只有 systemd `Restart` + `health.log` 留痕）。
- 控制台目前**只有口令登录**，没有 TOTP / IP 白名单；口令泄露 = 全部后台能力。
- WebUI **没有真机/真浏览器回归记录**（环境里没有浏览器），只验证了 HTML 结构与接口联通。

