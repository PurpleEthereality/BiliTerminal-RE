# ReBiliClient 架构通读（为改功能准备）

> 通读日期：当前 `main` 分支工作区（`versionName 26.10.03`），26.10.04 复核
> 目的：改功能前先摸清**真实**架构。本文结论均基于逐文件读源码核实；`AGENTS.md` 已按本文事实重写。
> 配套阅读：`docs/review/fix-progress.md`（修复进度 + 待办总台账）、`docs/tutorial-system-redesign.md`（教程系统重做，进行中）、`docs/watch-optimization-research.md`（手表端优化调研：竞品对比 / 性能体检 / 改造清单，26.10.04）

---

## 1. 一句话结论：项目没有"新层"

旧版 `AGENTS.md` 曾声称存在两条并存架构（遗留 Java 层 + 新 Kotlin 层）。**实测第二条完全不存在**，这条幻觉已从 `AGENTS.md` 中删除，事实记录如下：

| 旧 AGENTS.md 声称 | 实际情况（已核实） |
|---|---|
| `network/api/`（Retrofit + kotlinx-serialization） | 目录**空**，0 个文件 |
| `di/`（Hilt） | 目录**空**，0 个文件 |
| `data/repository/` | 目录**空**，0 个文件 |
| `ui/*`（ViewModel/MVVM） | 只有 `ui/appearance/`（26.09.11 新增，现 5 个文件：`AppearanceManager.kt` / `ColorScheme.kt` / `CornerStyle.kt` / `FontStyle.kt` / `AppearanceApplier.kt`）+ `ui/widget/`（12 个文件）。`ui/theme/` **已并入 `ui/appearance/`**（原 `ThemeManager.kt` → `ColorScheme.kt`）；其余 `ui/base`、`ui/player`、`ui/video/viewmodel` 等子目录**已全部删除** |
| `BiliTerminalApp.kt`（@HiltAndroidApp）为入口 | **该类已于 26.10.02 整体删除**。Manifest 指向 `.BiliTerminal`（26.09.10 起是 Kotlin，原先为 `.java`）；它从未被实例化，只因 `SplashActivity` 的 UETool 逻辑引用其静态方法与常量而残留，那些已移入 `BiliTerminal.kt` 伴生对象 |

**核实方式**：全工程 `grep '@AndroidEntryPoint|@HiltViewModel|@Inject|@Module|@InstallIn'` → **0 命中**；空目录统计 → **24 个**（26.09.11 已全部删除）。

**结论**：全项目实际是**单层遗留架构**——Java 静态方法 + `org.json` 逐层拆 JSON + `startActivity` 直跳。没有 DI、没有 Repository、没有 ViewModel、没有 Retrofit 调用。

### 死依赖清单 —— **26.09.11 已全部删除**

原先 `app/build.gradle` 里有以下全工程无人使用的依赖，现已移除（连带 `ksp` 与 `kotlin.plugin.serialization` 两个插件、`BiliTerminalApp.kt` 上的 `@HiltAndroidApp`）：

```gradle
// 已删除：
implementation 'com.google.dagger:hilt-android:2.51.1'                      // 无任何注入点
ksp 'com.google.dagger:hilt-compiler:2.51.1'
implementation 'com.squareup.retrofit2:retrofit:2.11.0'                      // 无任何 Interface 声明
implementation 'com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0'
implementation 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0'      // 无 @Serializable
implementation 'androidx.navigation:navigation-fragment-ktx:2.7.7'           // 0 引用
implementation 'androidx.navigation:navigation-ui-ktx:2.7.7'
implementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2'            // 0 引用
implementation 'androidx.asynclayoutinflater:asynclayoutinflater:1.0.0'      // 项目有自实现 AsyncLayoutInflaterX
implementation 'com.google.protobuf:protobuf-javalite:3.21.12'               // ProtobufParser 是手写解析器
implementation 'com.geetest.sensebot:sensebot:4.3.5'                         // 验证码只走 WebView JS
implementation 'androidx.cardview:cardview:1.0.0'                            // 只用 MaterialCardView
debugImplementation 'com.squareup.okhttp3:logging-interceptor:4.12.0'
ksp { arg("room.schemaLocation", ...) }                                     // 项目用 SQLiteOpenHelper，无 Room
```

**改功能时的影响**：新代码直接加到 `api/` + `activity/`，沿用静态方法 + `org.json` 风格；`network/api/`、`di/`、`ui/*` 这些空壳目录已删除，别再往里加东西。

> **26.09.10 已清理**：`androidx.multidex:multidex:2.0.1` 依赖、`BiliTerminal.java` / `BiliTerminalApp.kt` 里的 `MultiDex.install(this)` 与对应的 `attachBaseContext` 覆写已删除——`minSdk 24` 下 ART 原生支持 multidex，`MultiDex.install()` 在 API 21+ 首行即返回，是纯死代码。（`multiDexEnabled true` 保留未动，minSdk ≥ 21 下本就无副作用。）

---

## 2. 入口与启动链路

```
AndroidManifest.xml:27  android:name=".BiliTerminal"   ← 真实 Application（Kotlin）
        ↓
BiliTerminal.onCreate()
   ├─ 判进程名（读 /proc/self/cmdline）
   │    ├─ 是 :error_activity（26.10.04 起崩溃页独立进程）
   │    │    → 只做 SharedPreferencesUtil / context / Logu 开关，然后直接 return
   │    └─ 主进程继续
   ├─ SharedPreferencesUtil.sharedPreferences = getSharedPreferences("default")
   ├─ context = getFitDisplayContext(this)      ← DPI 缩放包装 Context
   ├─ PerformanceManager.init(this)             ← 设备分级（26.10.04 起：有缓存直接读，
   │                                                首次先落中档、检测丢 CenterThreadPool）
   ├─ 强制更新拦截：注册 ActivityLifecycleCallbacks，
   │    onActivityPreCreated 里把任何 Activity 换成 UpdateActivity 并 finish
   ├─ ErrorCatch.init / applyLogSwitches()
   ├─ 后台异步：动态更新数、消息未读数（仅已登录 mid != 0）
   └─ checkAppUpdate()                          ← 读 Gitee（失败回落 GitHub）的 releases/latest
        ↓
SplashActivity（LAUNCHER，typewriter 动画）
   ├─ Debug 包先要悬浮窗权限（UETool）
   └─ proceedSplashFlow()
        ├─ 未完成初始设置 → SetupUIActivity
        ├─ 已完成 → SharedPreferencesUtil.loadMenuEnabled().firstOrNull()
        │            → MenuActivity.btnNames[key].second  ← 用"第一个启用的菜单项"当首屏
        └─ 异步：App token / Cookie 刷新、CookiesApi.checkCookies、AppInfoApi.check
```

**改功能注意**：
- `BiliTerminal.onCreate()` 里所有初始化都包在 `if (context == null)` 内——**多进程/重复创建时只会跑一次**。新增全局初始化要放在这个块里，否则可能被跳过。
- **`CatchActivity` 跑在 `:error_activity` 独立进程**（26.10.04 批次 3，`AndroidManifest.xml` 的 `android:process`）。主进程已崩溃时，同进程再启 Activity 容易被一起带走，所以崩溃页必须自给自足：它**不会**经过 `PerformanceManager.init`、`ErrorCatch.init`、未读轮询、更新检查。往 `CatchActivity` 加依赖前先确认那个依赖不要求这些初始化。判进程用 `BiliTerminal.currentProcessName()`（读 `/proc/self/cmdline`——`minSdk 24` 用不了 API 28 的 `Application.getProcessName()`）。
- 首屏由**用户自定义菜单顺序**决定，不是硬编码 `RecommendActivity`。改导航时注意 `loadMenuEnabled()`。
- `onActivityPreCreated` 的强制更新拦截会 **finish 掉任意 Activity**，调试时若被"莫名其妙踢到更新页"，检查 `force_update_required` 这个 SharedPreferences 键。

---

## 3. 导航体系

### 3.1 菜单表（唯一导航注册点）

`activity/MenuActivity.kt:45-59` 的 `btnNames` 是 `LinkedHashMap<String, Pair<标题, Class>>`，共 **14 个入口**：

| key | 标题 | Activity | key | 标题 | Activity |
|---|---|---|---|---|---|
| `recommend` | 推荐 | RecommendActivity | `dynamic` | 动态 | DynamicActivity |
| `short_video` | 短视频 | ShortVideoPlayerActivity | `myspace` | 我的 | MySpaceActivity |
| `popular` | 热门 | PopularActivity | `message` | 消息 | MessageActivity |
| `precious` | 入站必刷 | PreciousActivity | `local` | 缓存 | LocalListActivity |
| `ranking` | 全站排行榜 | RankingActivity | `settings` | 设置 | SettingMainActivity |
| `hotsearch` | 热搜 | HotSearchActivity | `search` | 搜索 | SearchActivity |
| `live` | 直播 | RecommendLiveActivity | `timeline` | 时间线 | TimelineActivity |

`MenuActivity` 是 `launchMode="singleTask"` 的独立页；用户在设置里排序/启用哪些项存在 SharedPreferences（`SharedPreferencesUtil.loadMenuEnabled()`）。

**新增一级页面必须改三处**（漏一处就失效）：

1. `MenuActivity.btnNames` 加一行（id → 标题 + Activity Class）。
2. `util/MenuConfig.kt:14` 的 `ALL_ITEMS` 加同一个 id —— 这是 `menu_enabled` 配置串的**单一数据源**，`MenuConfig.parse()` 遇到未知 key 会直接返回 `null`，导致整份用户菜单配置被判非法并回退默认。决定默认是否显示还要看 `FIXED_ITEMS` / `SWITCHABLE_KEYS` / `SWITCH_DEFAULTS`。
3. `AndroidManifest.xml` 注册 Activity。

> `MenuConfig` 是纯 Kotlin 对象、无 Android 依赖，已有 `app/src/test/.../MenuConfigTest.kt`——改菜单逻辑时**优先在这里加测试**。

### 3.1b「我的」页面入口配置（26.09.10 新增）

`activity/user/MySpaceActivity.kt` 的功能入口不再硬编码，由 `util/MySpaceConfig.kt` 驱动：

- 两份有序 key 串 `myspace_main` / `myspace_more`（`;` 连接），互斥且并集 = `ALL_ITEMS`；任一非法（未知 key、重复、漏项、跨列表重复）整体回退默认并写回。
- 固定项不参与配置：用户卡片永远第一（设置页里不出现），`more`（更多按钮）与 `logout`（退出登录）永远最后两位；**更多列表为空时页面上不显示「更多」按钮**。
- 入口的图标 / 文案 / 跳转统一定义在 `activity/user/MySpaceMenu.kt`，主列表与更多页（`MySpaceMoreActivity`）共用，改跳转只改这一处。
- `creative`（创作中心）仍受通用偏好开关 `creative_enable` 控制：设置页里始终可见可排序，页面上按开关决定是否渲染。
- 设置入口 `activity/settings/SettingMySpaceActivity.kt`，适配器 `adapter/MySpaceSettingAdapter.kt` 单页双分区拖拽（两区都能排序、可互相拖入）。
- 加新入口时同步 `MySpaceConfig.ALL_ITEMS` 与 `MySpaceMenu.ITEMS` 并注册 Manifest；`MySpaceConfigTest.allItems_andMenuKeys_match` 会守卫两者一致。

### 3.2 三个基类层级

```
AppCompatActivity
  └─ BaseActivity                ← 主题应用、方向、DPI/边距、EventBus 注册、onKeyDown(MENU→finish)
       ├─ InstanceActivity       ← 一级页：MENU 键/顶栏 → 打开 MenuActivity；BiliTerminal.setInstance(this)
       │    └─ RefreshMainActivity  ← 一级页 + 下拉刷新 + 上拉加载（自带菜单按钮）
       └─ RefreshListActivity    ← 二级页 + 下拉刷新 + 上拉加载（顶栏点击 = finish）
```

**选哪个基类**：

| 场景 | 继承 |
|---|---|
| 一级页面（菜单能直达） | `InstanceActivity` 或 `RefreshMainActivity` |
| 二级页面（列表 + 分页） | `RefreshListActivity` |
| 二级页面（普通） | `BaseActivity` |

**两级页面的区别在于顶栏点击行为**：`BaseActivity.onStart()` 里 `if (this !is InstanceActivity) setTopbarExit()`——即非一级页点顶栏就 `finish()`；一级页由 `InstanceActivity.setMenuClick()` 改成打开菜单。

### 3.3 导航坑（两条均已在 26.09.08 修复）

- **MENU 键双重触发**：`InstanceActivity.onKeyDown` 打开 MenuActivity 后原本继续走 `super`，被 `BaseActivity.onKeyDown` 又 `finish()` 当前页。现在前者消费按键并 `return true`。
- **`from` 参数传不出去**：`InstanceActivity.menuClick` 原本判的是刚 new 出来的空 Intent（恒 false），现改为读 `getIntent().getStringExtra("from")`。

> 新增菜单跳转时仍要注意：`MenuActivity.btnNames`、`MenuConfig.ALL_ITEMS`、Manifest 三处必须同步（见 3.1）。

---

## 4. 依赖获取方式（没有 DI）

| 依赖 | 获取方式 | 位置 |
|---|---|---|
| Application 级 Context / 工具 | `BiliTerminal.context`（**静态字段**，`@JvmField`，直接引用） | `BiliTerminal.kt:41` |
| 当前栈顶 Activity | `BiliTerminal.getInstanceActivityOnTop()`（`WeakReference`） | `BiliTerminal.kt:83` |
| 内容缓存 / 数据源 | `TerminalContext.getInstance()`（`InstanceHolder` 懒汉单例） | `TerminalContext.java:371` |
| 网络客户端 | `NetWorkUtil.getOkHttpInstance()`（`AtomicReference` 双检） | `NetWorkUtil.java:85` |
| 设置读写 | `SharedPreferencesUtil` 静态方法（`sharedPreferences` 静态字段） | `util/SharedPreferencesUtil.java` |
| 跨页事件 | **EventBus**（greenrobot），`SnackEvent` 为 sticky | `BaseActivity.kt:198,233` |
| 线程/协程 | `CenterThreadPool` 静态方法 | `util/CenterThreadPool.java` |

**这意味着**：任何新增功能都可以在任何地方 `BiliTerminal.context` / `TerminalContext.getInstance()` 直接取到全局对象，**不需要也无法用注入**。代价是全局可变状态遍地，测试困难。

---

## 5. 数据流模式

项目里只有**两种**数据流写法，没有第三种：

### 5.1 命令式（绝大多数页面）

```kotlin
CenterThreadPool.run {                       // 后台线程
    try {
        val data = XxxApi.getSomething(id)   // 同步阻塞网络请求
        runOnUiThread { adapter.setData(data) }
    } catch (e: Exception) {
        report(e)                            // BaseActivity.report → MsgUtil.err
    }
}
```

### 5.2 LiveData 式（详情页）

`TerminalContext` 提供 `getVideoInfoByAidOrBvId()` / `getArticleInfoByCvId()` / `getDynamicById()` / `getOpusById()` / `getLiveInfoByRoomId()` / `getReply()`，内部：

```
LruCache<String, Object>(10) 命中？ → MutableLiveData(Result.success(缓存对象))
                    ↓ miss
CenterThreadPool.supplyAsyncWithLiveData { fetch...().getOrThrow() }
```

- 缓存键格式：`ContentType.getTypeCode() + "_" + id`（如 `video_12345`）。
- `Result` 是项目自己实现的类（`util/Result.java`），不是 Kotlin 的。
- 缓存容量仅 10 条，且**不区分 aid/bvid**——见第 7 节坑点。

### 5.3 线程约定

`CenterThreadPool`（`util/CenterThreadPool.java`）：
- `run(Runnable)` → 实际走 `Dispatchers.IO` 协程（`Build.VERSION.SDK_INT < 17` 的原生线程池分支在 minSdk 24 下**永不执行**，是死代码）。
- `supplyAsyncWithLiveData(Callable<T>)` → `LiveData<Result<T>>`，失败自动 `MsgUtil.err(e)`。
- `observe(Future<T>, Consumer<T>)` → 自动切主线程。
- `runOnUiThread(Runnable)` → `Handler(Looper.getMainLooper()).post`。

**改功能注意**：API 层全部是**同步阻塞**方法，调用方负责丢到后台线程。若在主线程直接调 `XxxApi.getXxx()`，会触发 `NetworkOnMainThreadException` 或卡顿。

---

## 6. 网络层

### 6.1 `NetWorkUtil`（`util/NetWorkUtil.java`，709 行，唯一出口）

- **单例 OkHttpClient**，`followRedirects(false)` + 自定义拦截器手写重定向（`b23.tv` 短链走 `RedirectHandler` 回调）。
- **DNS 强制 IPv4**（`Inet4Selector`，注释称 IPv6 请求有异常）。
- **Cookie 管理**：内存缓存 `cachedCookies` + `webHeaders` 静态 `ArrayList`（**索引 1 存 Cookie 字符串**）。`putCookie`/`setCookies` 的 `synchronized` 与 `saveCookiesLocked()`（`saveCookiesFromResponse` 的加锁主体）共用同一把 `NetWorkUtil.class` 锁。
- **csrf 唯一入口**（26.10.04 批次 2，台账 A1）：`currentCsrf()`（`:432`）＝ `pickCsrf(getCachedCookies(), 快照)`（`:443`，纯逻辑、有单测），优先取实时 Cookie 里的 `bili_jct`，取不到才退回 `SharedPreferencesUtil.csrf` 快照；`saveCookiesLocked()`（`:510` 附近）在落 Cookie 时**顺手回写**快照。**所有 POST 的 csrf 都必须调它，不要再直接读 `SharedPreferencesUtil.csrf`**——`bili_jct` 随 Cookie 刷新轮换，用旧快照会静默拿 `-111`（现象是「点赞/评论偶尔点了没反应」）。原来 14 个 `api/` 类里散布 41 处直接读快照的代码已全部收敛到这一处。
- **风控重试**：`executeJsonWithRiskRetry` 对 `code == -352 / -412` 重试；`executeWithDoctypeRetry` 对返回 `<!doctype`（被风控拦成 HTML）的响应重试。重试次数/间隔读 SharedPreferences（`api_retry_max_times`、`api_retry_interval_seconds`）。
- **隐私模式**：`getJsonPrivacy()` 用 `buildGuestCookieString()` 剔除 `SESSDATA/bili_jct/DedeUserID/sid` 等登录 Cookie。
- **参数构造**：`FormData` 类，内部 `URLEncoder.encode`，默认**不**自动加 `access_key`（注释：web 接口带 access_key 会触发风控）。
- **裸 deflate 解压**：`decompress(byte[])`（26.09.11 起为本工程唯一实现，原先
  `DownloadService`/`DownloadActivity`/`PlayerActivity` 各有一份逐字相同的副本）。
  解压失败回退原数据；`Inflater` 持 native 资源，统一在 `finally` 里 `end()`。
  注意与 `api/UserInfoApi.decompressResponse`（br + gzip）**不是同一件事**，不要互相替换。

### 6.2 WBI 签名（`api/ConfInfoApi.java`）

- `signWBI(url_query)`：取 `nav` 接口的 `wbi_img` → `img_key + sub_key` → 经 `MIXIN_KEY_ENC_TAB` 重排取前 32 位得 `mixin_key` → 参数排序 + `&wts=` + `mixin_key` 做 MD5 得 `w_rid`。
- `mixin_key` **每天只刷新一次**（`getDateCurr()` 写 SharedPreferences 的 `last_wbi`）。
- 结果缓存用 3 个 `volatile` 字段（`lastWbiQuery/lastWbiWts/lastWbiSignedUrl`），**非原子**——极端并发下可能读到"query 已换、签名未换"的组合。

### 6.3 API 层组织

`api/` 目录下约 41 个类，每个对应一个 B 站功能域，**全部**通过 `NetWorkUtil` 发请求、用 `org.json` 手工拆包。没有 Retrofit、没有数据类映射（除 `model/` 下少量 POJO）。

### 6.4 明文流量与凭据保护（26.09.13 加固，改网络/清单前必读）

三条要求，改动时别破坏：

1. **不新增明文接口**。`AppInfoApi` 原先 4 处 `http://api.biliterminal.cn` 已全部改为 `https://`（实测该域 https 正常返回 200）。新的自建接口一律用 https。
2. **`AndroidManifest.xml` 不再全局开 `usesCleartextTraffic`**，改走
   `res/xml/network_security_config.xml`：`base-config` 禁止明文，只对 `bilibili.com` /
   `hdslb.com` / `bilivideo.com` / `afdiancdn.com` 放行。之所以不能一刀切禁明文，是因为解析层
   仍有若干 `"http:" + url` 的拼接（`LiveCardAdapter` 直播封面、`SearchApi` 封面/头像、
   `OpusParagraph` 图片）。**若要把这些也改成 https，必须先在真机验证对应 CDN 节点支持 https，
   再考虑把域名从白名单移除。** 新增需要明文的域名要显式加进白名单并说明原因。
3. **登录凭据不得随备份外流**。所有 Cookie / 账号态存在 SharedPreferences，
   故 `android:allowBackup="false"`，并用 `res/xml/backup_rules.xml`（API ≤30）与
   `res/xml/data_extraction_rules.xml`（API 31+）排除 `sharedpref` 与 `database` 两个 domain。
   `:brotlij` 模块的清单自带 `allowBackup="true"`，因此主清单上必须带
   `tools:replace="android:allowBackup"`，否则清单合并直接失败。

> 组件导出面已收敛：除 `SplashActivity`（LAUNCHER）与 `GetIntentActivity`（外链/分享，
> 二者有 `<intent-filter>`）外，其余 Activity 一律 `android:exported="false"`。
> **新增 Activity 默认写 false**，只有确实要被外部应用拉起时才开，并说明理由。

---

## 7. 改功能前必须知道的坑（按"会不会踩到"排序）

### 7.1 本轮已修复（26.09.08，已验证，别再当 bug 修一遍）

| # | 位置 | 原问题 | 修法 |
|---|---|---|---|
| 1 | `api/PrivateMsgApi.java` | `!has && isNull` 恒等于 `!has`，`account_info` 为 null 的普通会话被误过滤 | 改用 `isNull("account_info")`；解析抽成纯函数 `parseSessionsList()` 并补单测 |
| 2 | `activity/base/InstanceActivity.kt` | MENU 键打开菜单后继续走 `super`，被 `BaseActivity.onKeyDown` 又 `finish()` 当前页 | 消费按键，`return true` |
| 3 | `activity/base/InstanceActivity.kt` | `from` 参数判的是刚 new 的空 Intent，恒 false | 改判 `getIntent().getStringExtra("from")` |
| 4 | `util/NetWorkUtil.java` | `saveCookiesFromResponse` 无锁，与 `putCookie`/`setCookies` 的 `synchronized` 不对称 | 抽出 `saveCookiesLocked()`，纳入同一把 `NetWorkUtil.class` 锁 |
| 5 | `api/ConfInfoApi.java` | WBI 缓存三个 `volatile` 分开赋值，可能读到"query 已换、签名未换" | 合并为不可变 `WbiCache` 对象，整体替换 |
| 6 | `util/MsgUtil.java` | sticky `SnackEvent` 显示后不移除 → 注册时收一次、`onResume` 再取一次，同页弹两次 | `processSnackEvent` 显示分支同时 `removeStickyEvent` |
| 7 | `util/TerminalContext.java` | 视频缓存 aid/bvid 键不一致，bvid 路径**永远 miss** | 新增 `cacheVideo()` 双写两个键；`LruCache` 10→20 保持等效容量 |

**验证**：`:app:testDebugUnitTest` 38 个测试全绿（新增 `PrivateMsgApiTest` 6 例）+ `:app:assembleDebug` 通过。
顺带修正 `app/src/test/.../ToolsUtilTest.kt` 的期望值笔误（`0x00123456` 剥离 alpha 应为 `0x123456`，原写成 `0x000000`）。

### 7.2 更早的修复（26.08.27 快照之后，别照旧报告去改）

- `RefreshListActivity.kt:112-118`：`setRefreshing(false)` 时复位 `isLoading`（注释说明了原因）。
- `RefreshMainActivity.kt:74-88`：`goOnLoad()` 同步置成员 `isRefreshing`，`onScrolled` 用它防重入。
- `ToolsUtil.java:71-73`：`getRgb888` 已改为 `color & 0xFFFFFF`（注释保留旧实现说明）。
- `NetWorkUtil.java:100-113`：重定向前判空 scheme/host、先 `response.close()`。
- `PlayerActivity` / `UpdateManager` / `LocalListActivity` / `VideoInfoFragment` 等见 `fix-progress.md` 第二节。

**建议**：改任何文件前先 `grep` 一下对应代码是否还是旧报告里描述的样子——旧报告基于 `26.08.27` 快照，之后有 3 轮修复。

### 7.3 结构性风险（改功能时容易放大）

- **巨型类**：`activity/player/PlayerActivity.kt` 127 KB、`activity/video/ShortVideoPlayerActivity.kt` 35 KB。`service/DownloadService.kt` 曾是第二条 65 KB 的巨型类，26.10.04 批次 8（E2）已拆到 **1284 行**，实现分居 `service/download/` 的 4 个文件（见 §7.27）。改播放相关功能前先想清楚在哪个位置插入；改下载相关功能请先读 §7.27 的锁与状态契约。
- **Application 静态状态已收敛为一套**：26.10.02 起只有 `BiliTerminal.context` / `BiliTerminal.instance`（`BiliTerminal.kt` 伴生对象 `@JvmField`，`:43-44`），`BiliTerminalApp` 已整文件删除。**新代码一律用 `BiliTerminal`**。
- **测试覆盖仍偏低**：`app/src/test/` 39 个文件（38 个测试类 + 1 个共享假实现 `FakeSharedPreferences.kt`），共 **327 个用例**，对 383 个源文件（26.10.04 批次 8 实测）。已有：`HotSearchApiTest`、`FavoriteApiTest`、`FollowApiTest`、`OpusApiTest`、`PrivateMsgApiTest`、`BangumiApiTest`、`DynamicApiTest`、`TopicApiTest`、`NoteApiTest`、`WatchLaterApiTest`、`PlayerApiPbpTest`、`ReplyApiTest`、`ReplyParseActionTest`、`ReplySortTest`、`NetWorkUtilTest`、`PerformanceManagerTest`、`BangumiUpdateCheckerTest`、`MsgNotifierTest`、`ApkVerifierTest`、`SettingsKeysTest`、`UpdateReleaseTest`、`MenuConfigTest`、`MySpaceConfigTest`、`ToolsUtilTest`、`StringUtilTest`（Java）、`HotSearchAdapterTest`、`TutorialDslTest`、`DanmakuSyncTest`、`PlayerDefaultsTest`、`ViewPointSkipTest`、`ColorSchemeTest`、`CornerStyleTest`、`FontStyleTest`、`AppearanceManagerTest`、`ViewCapabilityProbeTest`、`DownloadPathSpecTest`、`DownloadProgressMathTest`、`DownloadBatchStatsTest`。改动解析/配置/主题/能力探测逻辑时补纯 JVM 单测——注入 `SharedPreferencesUtil.sharedPreferences`，用 `util/FakeSharedPreferences.kt`（26.09.11 从 `NetWorkUtilTest` 的私有内部类提取为共享助手，别再抄一份）。
- **主题色表带缓存，失效点只有一处**：`ColorScheme.getCurrentTheme()`（26.09.11 起）缓存当前色表，**只由 `AppearanceManager.setTheme()` 经 `ColorScheme.invalidateCache()` 置空**。这是刻意的——36 个属性 getter 全走它，而列表滚动时一个 item 要调多次，此前每次都重读 SharedPreferences（热路径重复 IO）。**若将来给主题 key 增加第二个写入路径（比如直接 `SharedPreferencesUtil.putString(SettingsKeys.THEME, …)`），必须同步调用 `ColorScheme.invalidateCache()`，否则改主题后色表不跟着变且在 `onResume` 重建后依然错**。守卫测试：`ColorSchemeTest.themeCache_isInvalidatedOnEverySetTheme`、`colorGetters_doNotTouchSharedPreferencesAfterFirstRead`。
- **主题体系有 3 个"裸 Activity"不参与**：`SplashActivity`、`GetIntentActivity` 不继承 `BaseActivity`（开屏/外链恒定 B站粉），`PlayerActivity` 自己 `setTheme` 但**不调 `applyWindowTheme`、也不参与 `onResume` 主题检测**。改主题相关行为时别以为全局都生效了。
- **文案硬编码**：遗留页面标题/Toast 直接写中文字符串（Manifest 里 `android:label` 也是中文），只有设置页用 `desc_*` 资源。改文案按现有风格来，别顺手抽 `strings.xml`。

### 7.4 两个视频播放器是两套独立实现（改播放前必读）

工程里**没有统一的播放层**，两个播放器各写各的，共用代码只有 `player/IjkPlayerBridge.kt` 这一小块：

| | 普通视频播放器 | 短视频播放器 |
|---|---|---|
| 入口 | `activity/player/PlayerActivity.kt`（**3087 行**） | `activity/video/ShortVideoPlayerActivity.kt`（907 行） |
| 基类 | **直接 `extends Activity`**，不走 `BaseActivity` 体系（自己实现主题、EventBus、横竖屏） | `InstanceActivity` + `ViewPager2` 竖滑翻页 |
| 播放内核 | **裸 `IjkMediaPlayer`**，`setOption`/`setDataSource`/监听器全部内联在 Activity 里 | `player/IjkPlayerBridge`（Flow 驱动状态）+ `player/DanmakuManager` |
| 弹幕 | `IDanmakuView` 内联处理（`streamDanmaku`/`createParser` 都在 Activity 里） | `DanmakuManager` |
| 轮询 | 3 个 `java.util.Timer` 线程（在线人数 5s / 倍速收起 / surface 就绪）+ 2 个主线程 `Handler` 自循环（播放进度 250ms、缓冲速度 500ms） | `IjkPlayerBridge` 内协程，250ms 更新 StateFlow |
| 预加载 | 无 | `util/VideoPreloadManager`（**只预取播放地址 URL，不预热播放器**） |

`player/` 包里**有一半是死代码**（全工程 0 引用，已核实）：

- `IjkPlayerBridge.kt` — 在用，被短视频的 `PageHolder` 直接使用。
- `DanmakuManager.kt` — 在用，**两个播放器都已接入**（26.09.10 起 `PlayerActivity` 的内联弹幕栈已删除，统一走它）。
- `PlayerSurfaceBinder.kt`（26.09.10 新增）— 在用，Surface 就绪事件回调，取代 200ms 轮询 Timer。
- `VideoPlayerCore.kt`（26.09.10 新增）— **已可用但尚无消费方**，是合并两个播放器的目标内核（多实例安全、不持 Activity、`reload()` 复用播放器）。
- `PlayerIntegrator.kt` — ~~死代码~~ **26.09.11 已删除**（全工程无一处实例化）。
- `PlayerControlDelegate.kt`（含 `GestureHandler`）— **仍未删除，待决策**：`PlayerIntegrator`（它唯一的引用方）已删，此后它全库零外部引用；但播放器合并方案 S6 明确把它列为"**决定去留**"（`docs/superpowers/specs/2026-09-10-player-core-merge.md:63`），且它带着 26.09.10 的手势 bug 修复（`isLongPressing` 复位），**删之前先定方案**。
- `PlayerScaleMode` 枚举（原在 `IjkPlayerBridge.kt`）— **26.09.11 已删除**（无人使用）。
- `DanmakuManager.loadFromProtobuf` / `toggleVisibility` / `setSpeedFactor` / `setTextSizeScale` / `setTransparency` — **26.09.11 已删除**（全库零调用；`loadFromProtobuf` 实现还是坏的：解压后从未把数据交给 parser）。连带 `PlayerSurfaceBinder.cancelAwait`、`DanmakuManager` 的 `Inflater`/`ByteArrayInputStream` import 一并删除。

**改播放相关代码时的判断顺序**：先确认要改的是"普通播放器那套"还是"短视频那套"。合并工作正在进行（见 `docs/superpowers/specs/2026-09-10-player-core-merge.md`：S1/S2/S3 已完成，S4/S5/S6 待做），所以两边文件的归属会逐步收敛；**当前 `player/` 包仍不是公共层** —— `PlayerActivity` 只用了 `DanmakuManager` 与 `PlayerSurfaceBinder`，播放器本身还是裸 `IjkMediaPlayer`。

**弹幕序列化的两个硬约束（踩过坑，别重犯）**：

1. **`DanmakuManager.createParser()` 只能在主线程、且不可并发**。`DanmakuLoaderFactory.create(TAG_BILI)` 返回的是**进程级单例** `BiliDanmakuLoader.instance()`（`BiliDanmakuLoader.java:27-38`），而 `dataSource` 是它的**实例字段**（`:29`），`load()` 写字段 + `loader.dataSource` 读字段是一段 check-then-act。并发时两个 holder 会拿到同一个数据源，表现为弹幕串台或解析为空。
2. **`createParser()` 不做任何 XML 解析**，把它挪到后台线程**没有任何收益**。`AndroidFileSource(InputStream)`（`AndroidFileSource.java:44-46`）、`BiliDanmakuLoader.load()`（`:44-46`）、`BaseDanmakuParser.load()`（`BaseDanmakuParser.java:67-70`）全都只是存引用；真解析在 `getDanmakus()` → `parse()`（`:81-89`）**懒执行**，全工程唯一调用点是 `DrawTask.java:283`，跑在 `DanmakuView` 自己的渲染线程上。所以"短视频滑动卡顿 = 弹幕解析阻塞主线程"是**错误归因**（26.09.10 已核实并回退过一次这样的改动）。

普通播放器与短视频的弹幕栈是两套（前者内联，后者走 `DanmakuManager`），统一它们是合并两个播放器时**风险最低、收益最高的第一步**，但要先解决上面第 1 条的并发约束。

3. **`DanmakuManager` 的位置回调在"播放器未就绪/正在重建"时必须返回负数**。`updateTimer` 跑在 `DanmakuView` 的渲染线程上，与主线程重建 `IjkMediaPlayer` 的动作并发；`IjkMediaPlayer` 的 native 层不是线程安全的，窗口期读到的脏位置一旦灌进 `DanmakuTimer`，**整批弹幕会被判定为"已过期"而一条都不显示**，且因为是竞态所以**间歇性**出现（26.09.10 真实踩过：合并弹幕栈时删掉了原 `if (ijkPlayer != null && isPrepared)` 守卫，导致"普通视频弹幕间歇性消失"）。对应的两个 `isPrepared` 字段也因此加了 `@Volatile`。

---

### 7.5 后台播放服务（`service/PlaybackService.kt`，26.09.25 新增）

`PlayerActivity` 退到后台且用户开了设置项 `player_background`（字面量，见 `SettingsKeys.PLAYER_BACKGROUND`）时，
`onPause` 会通过 `PlaybackService.start(this, this)` 起一个 `foregroundServiceType="mediaPlayback"` 的前台服务挂通知栏遥控。

- **播放器实例仍归 `PlayerActivity` 持有**，Service 只通过 `WeakReference<PlayerActivity>` 反查状态与下发指令
  （`serviceTogglePlay`/`serviceStopPlayback`/`serviceReportNow` 等桥接方法定义在 `PlayerActivity` 里）。这不是 MediaSession 方案：
  通知是普通 `NotificationCompat.Builder` + 两个 `PendingIntent.getService` 自定义 action。
- **通知 id 1028（播放）、1027 是 `DownloadService`**；渠道 `playback_channel` 与下载的 `biliterminal_download` 分开。
- **`onStartCommand` 第一行必须 `startForeground`**（Android 5 秒规则）；`FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限与
  Manifest 里的 `<service ... android:foregroundServiceType="mediaPlayback">` 是运行期硬性要求（Android 14 起），删任一个都会崩。
- **TimerTask 必须整体 try/catch**：Timer 的 `TimerTask` 抛未捕获异常会**永久终止整个 Timer**，进度通知从此静默失效。
- 已知限制：点通知走 `getLaunchIntentForPackage`（本项目 = `SplashActivity`）+ `FLAG_ACTIVITY_REORDER_TO_FRONT`，
  与上游一致；`PlayerActivity` 没有 `launchMode`，所以 `onNewIntent` 里那句 `finish()` 是死代码，未改动。
  当前版本**没有 wakelock**（`WAKE_LOCK` 权限未声明、`IjkMediaPlayer.setWakeMode` 未调用），熄屏能否持续播放待真机确认。

### 7.6 更新日志页按版本分页（`activity/settings/UpdateHistoryActivity.kt`，26.09.25 新增）

`res/values/strings.xml` 的 `R.array.update_log_current`（首行 = 当前版本）与 `R.array.update_history_log`
（每个 `## YYYY-MM-DD` 段 = 一个历史版本）是**唯一数据源**，不要另建静态日志表（上游曾有两份互相漂移的日志源）。
`SplashActivity` 在覆盖安装后的首次启动会自动打开该页（只弹一次，存 versionCode），
旧「更新公告」全屏弹页（`AppInfoApi.check` 里那句 `MsgUtil.showText`）已删除以避免同一次升级弹两个内容重复的页面。

### 7.7 番剧片头/片尾自动跳过（26.10.04 新增）

设计参照 PiliPlus（`bggRGjQaUbCoE/PiliPlus` @ `2515ecf`）的 `pgcSkipType` / `SkipType` 体系。

**数据来源**：`api/PlayerApi.getViewPoints(aid, cid)`（`x/player/wbi/v2` 的 `data.view_points[]`）里的 `type` —— **1 = 片头，2 = 片尾**。
在此之前全库没有一处读取 `type` 做跳转，`view_points` 只被当成「视频分段」给用户手点，所以本功能是「只差一层判定」。

**纯逻辑**：`player/ViewPointSkip.kt`（Kotlin `object`，零 Android 依赖，配 `app/src/test/.../ViewPointSkipTest.kt` 9 例）。
- `Segment(type, fromSec, toSec)` 用**秒**；`buildSegments()` 只留 `type` 为 1/2、`toSec > fromSec`、时长 ≤ `MAX_SEGMENT_SECONDS`(600s) 的段并按起点排序——上限用来挡上游偶尔下发的「0 秒 → 整集」脏区间。
- `segmentAt()` 判定区间是**左闭右开** `[from, to)`。这一点不能改成右闭：跳过后的落点正好等于 `toSec`，右闭会被自己重新命中而反复跳。
- `shouldSkip(pos, segments, handled)` 命中 `handled` 集合即返回 `null` → **每个片段只自动跳一次**。
- `keyForManualSeek()` 专门给「用户自己拖进片段」打标记用。

**接线**（全部在 `activity/player/PlayerActivity.kt`）：
- 判定挂在**既有的 250ms 主线程进度定时器** `progressChange()` 里（`maybeAutoSkipOpEd`），**没有新增 Timer**。
- 跳跃复用 `seekToPosition()`，与手动拖动走同一条路径，弹幕与外部音轨一起同步。
- 反悔：`showSkipUndoSnack()` 用 `MsgUtil.createSnack(anchor, 文案, LENGTH_LONG, MsgUtil.Action("撤回"){...})` 跳回片段起点。`lastSkippedSegment` 用来判断这条 Snackbar 是否已过期，防止「期间又跳过别的片段」后点撤回把人拽回去。
- **用户主动 seek 必须标记为已处理**：`seekToPosition()` 与 seekbar 的 `onStopTrackingTouch` 两条路径都调 `onUserSeekTo()`。不标的话，用户拖进片头想看一眼，下一轮定时器立刻又把他弹走。
- `loadViewPoints()` 的三个调用条件从「显示视频分段开启」放宽为 `needViewPoints()`（显示分段 **或** 自动跳过，任一开启）。
- 换集/换P 时重置 `skipSegments` / `skipHandled` / `lastSkippedSegment`，不带上一集的进度。

**设置项**（默认**开启**，26.10.04 后续由关闭改为开启）：
- `SettingsKeys.PLAYER_SKIP_OP_ED`（`player_skip_op_ed`）——开关本体。**默认值只有一个来源**：`player/SkipOpEdPrefs.kt` 的 `DEFAULT_ENABLED = true`，四个调用点全部引用它（`PlayerActivity.needViewPoints()` / `skipOpEdEnabled()` / `SettingTerminalPlayerActivity`），别再散落 `true`/`false` 字面量（原先四处各写 `false`，改默认值时漏一处就会「设置页显示开着、播放器却不跳」）。
- **老用户显式关过的保持关**：`SharedPreferences.getBoolean(key, def)` 的语义就是「键不存在才用 def」，所以只改默认值天然满足「新装默认开、升级后尊重旧选择」，**不需要任何迁移代码**。`SkipOpEdPrefsTest` 用「显式关过→仍为 false」钉死这条。
- `SettingsKeys.PLAYER_SKIP_OP_ED_GUIDED`（`player_skip_op_ed_guided`）——**只是「引导提示已弹过」的记账位，不出现在设置页**，别当成用户可见开关。

**为什么改成默认开启**：PiliPlus（设计参照对象）的同类开关 `pgcSkipType` 默认就是 `SkipType.skipOnce`（`lib/utils/storage_pref.dart:977-979`），B 站官方播放器同样默认跳过；且跳过之后有「撤回」兜底，误跳的代价远小于「用户根本不知道有这功能」——本轮用户反馈「没看到实现了」正是因为默认关+入口深。

**引导**：`SkipOpEdPrefs.shouldShowGuide(hasSegments, alreadyGuided) = hasSegments && !alreadyGuided`。
判据**只看「引导过没有」，与开关当前值解耦**。原实现是「`skipSegments` 空就 return、开关开着就 return、引导过就 return」，一旦默认改成开启，第二条永远命中，**这条引导就变成死代码**（真机清单第 200 条永远测不到）。文案随开关状态分两套（`guideText` / `guideActionText`）：开关已开 → 「已自动跳过片头/片尾，可在下方撤回」+「知道了」（不再劝用户开启）；未开 → 「这个视频有片头片尾，可以自动跳过」+「开启」。写 `PLAYER_SKIP_OP_ED_GUIDED` 后永不再弹（每集都弹会很烦）。

**与 PiliPlus 的差异**：PiliPlus 有 5 档 `SkipType`（`alwaysSkip` / `skipOnce` / `skipManually` / `showOnly` / `disable`，**默认 `skipOnce`**），并把片段画到进度条上（`segment_progress_bar.dart`）。本项目只做「开启 = 跳过一次 + 可撤回」这一档，也没有进度条片段标记。
若将来要补 `alwaysSkip`（用户拖回去也继续跳），把 `maybeAutoSkipOpEd` 里的 `handled` 判断去掉即可——落点是右端点，不会自我循环。

### 7.8 高能进度条 pbp 接口的两代响应结构（26.10.04 修复）

接口 `https://bvc.bilivideo.com/pbp/data`（弹幕密度曲线）。**它改过响应结构，而旧实现不会报错，只会静默画出一条空线**，所以这里的两代结构都必须留着。

- **旧形态**：数据摊在根上 —— `{"step_sec":3,"events":{"default":[...]}}`。
- **新形态**：多包一层 —— `{"modules":[{"params":{"data":{...同上的字段...}}}]}`；有些链路外面还套 `{code,message,data}`。
- **请求侧**（照 PiliPlus `_getDmTrend()` 对齐）：除了 `cid` 还要带 `aid`/`bvid`，并固定带 **`r=loader`**；**Referer 必须落在具体视频页**（`/video/{bvid}`，没有 bvid 就用 `/video/av{aid}`），站点根会被风控挡掉。
- **纯解析**：`api/PlayerApi.parseHighEnergyData(JSONObject)`（static，零网络、**不写日志**——`Logu` 走 `android.util.Log`，在 JVM 单测里会抛 not-mocked）。它按「根上的 modules → data 里的 modules → data 本身 → 根自己」四个候选依次找，**优先返回真正带 `events.default` 的那个**；都不带才退回第一个候选（至少留下 `step_sec`/`debug` 便于排查）。配套 `app/src/test/.../api/PlayerApiPbpTest.kt` 9 例。
- **`buildPbpReferer(bvid, aid)`** 也是纯函数（bvid > av号 > 站点根），单测覆盖。
- 网络侧 `getHighEnergyData(cid, aid, bvid)` 用 `new ArrayList<>(NetWorkUtil.webHeaders)` 复制一份请求头、只替换 `Referer`，**不动全局表**（全局表是 volatile copy-on-write 快照，见 `util/NetWorkUtil.java:521` 的注释）。调用点 `activity/player/PlayerActivity.kt` 的 `loadHighEnergyData()` 从 Intent 里取可选的 `bvid`——`PlayerData` 没有该字段，所以多数入口会落到 av 号 Referer。

### 7.9 列表增量刷新：`notifyItemRangeInserted` 的起点由 adapter 的 `getItemCount()` 决定（26.10.04 批次 3）

**核心事实：本项目大量 adapter 的 `getItemCount()` 是 `data.size + 1`（位置 0 塞一个头部），所以通知增量时起点要 `sizeBefore + 1`。看到 `+ 1` 不要条件反射当越界，先读对应 adapter 的 `getItemCount()`。**

- **无头部**（直接用 list 构造）：`adapter/video/VideoCardAdapter.kt:70` `getItemCount() = videoCardList.size`、`adapter/article/ArticleCardAdapter.kt:123`、`adapter/LiveCardAdapter.kt:64` → 起点就是 `lastSize`。26.10.04 修掉的 3 个搜索页（`SearchVideoFragment` / `SearchArticleFragment` / `SearchLiveFragment`）原来写成 `lastSize + 1`，是**真越界**（第 2 页起 `IndexOutOfBounds` / `Inconsistency detected`）。
- **有头部**：`adapter/ReplyAdapter.kt:556-558` `replyList.size + 1`、`adapter/dynamic/UserDynamicAdapter.kt:112-114` `dynamicList.size + 1`、`activity/video/series/SeriesInfoActivity` 的内部 adapter `data.size + 1` → 这些调用点的 `+ 1` 是**对的**（`ReplyFragment.kt:250`、`UserDynamicFragment.kt:89`、`SeriesInfoActivity.kt:86`），已核实、别动。`adapter/user/FollowGroupAdapter.kt:108` 的 `groupPosition + 1` 是分组结构，同理。
- **通知前必须先改数据，且同在主线程**：`ReplyFragment.kt:238` 的注释就是这个约定。反面教材是 `activity/video/series/UserSeriesActivity.kt`——原来只 `notifyItemRangeInserted(oldSize, seasonList.size)` 却**从没 `addAll` 进 adapter 的 list**，报出的数量和真实条数永远对不上；已改成记住第 1 页的 adapter、第 2 页起先 `seasonList.addAll(...)` 再通知（`adapter/video/SeriesCardAdapter.kt` 的 `seasonList` 因此由 `List` 放宽为 `MutableList`）。
- 配套：`activity/RefreshListActivity` 的 `getRecyclerViewCacheSize()` / `getRecyclerViewPrefetchCount()` 来自 `PerformanceManager`，与 `notifyItemRangeInserted` 无关，别混为一谈。

### 7.10 设备档位参数的唯一出口：`PerformanceManager`（26.10.04 批次 3 整理）

- **图片质量/宽度、分页大小都必须从 `PerformanceManager` 取**，不要再在调用点写死：`GlideUtil.url()` 走 `getImageQuality()`/`getImageMaxWidth()`（列表图：低端 320w/50q，其余 512w/60q），`GlideUtil.url_hq()` 走 `getHqImageQuality()`/`getHqImageMaxWidth()`（低端 512w/60q，其余 1024w/80q）。`GlideUtil` 原有的 `QUALITY_*` / `MAX_W_*` 四个常量已删除——**再引入一份常量就等于恢复"两处真相"**。
- 档位计算是 `@JvmStatic` 纯函数（`levelFromScore` / `isLowPerfLevel` / `listImageQuality` / `listImageMaxWidth` / `hqImageQuality` / `hqImageMaxWidth`），接收 `(level, highPerformanceMode)`，因此可被 JVM 单测直接覆盖；对应的"喂当前状态"getter 只是转调。
- **`getPageSize()` 只对 3 个接口生效**（`RecommendApi` popular/precious、`SeriesApi` 用户系列）。其余硬编码 `ps`/`page_size` **是有意为之**（`FavoriteApi.java:106 ps=100` 一次拉全、`MessageApi.java:380 page_size=35` 是 cursor 分页、`EmoteApi` 表情面板等），改它们要先确认分页语义。
- **首次硬件检测不在主线程**：`init()` 无缓存时先置中档返回，检测在 `CenterThreadPool` 上跑完再 `applyPerformanceSettings()`。因此**冷启动最初几十毫秒内读到的是中档参数**，属于预期行为；单测 `PerformanceManagerTest.defaultsBeforeInit_areMedium` 锁住了这套默认值。

### 7.11 更新包校验：`util/ApkVerifier.kt`（26.10.04 批次 4 新增）

**任何"把 APK 交给系统安装器"的路径都必须先过 `ApkVerifier.verify(context, apkFile)`。** 目前有两条，都已接线：

| 链路 | 校验落点 | 失败处理 |
|---|---|---|
| `util/UpdateManager.kt` → `downloadApk()` → `installApk()`（更新页走这条） | `writeResponseToFile` 之后、`onComplete` 之前 | **删掉下载的文件** + `onError("安装包校验失败：…")`。删文件是必须的：留残片会被下次 `Range` 续传当成"已下载一部分"，永远拼不出完整包 |
| `activity/DownloadActivity.kt` → `installApk()`（最老的「下成 `.bak` 再改名安装」链路） | 方法开头 | 返回 `false`，由调用方走既有「安装失败，已保存到下载文件夹」分支（该方法**不在主线程**，不要在里面弹 Toast） |

- 判定规则：包名一致 **且** 签名集合与已安装应用一致（`GET_SIGNATURES` + `getPackageArchiveInfo`）。**读不到签名一律判定失败**（失败关闭）——`ApkVerifierTest` 专门锁住这条。
- 校验只防"下载链路被换包/串包/截断"，**防不住"同一签名者发布了坏包"**，也**不校验哈希**：MD5 与安装包来自同一个响应，能改包的人也能改元数据。系统安装器本身也会拒绝换签名包，本校验的价值是**早失败 + 中文原因**。
- 纯逻辑全在 `ApkVerifier` 的 `isSamePackage` / `isSameSignature` / `signatureHex` 三个静态函数里，可 JVM 单测；只有 `verify()` 依赖 Android。

### 7.12 设置 key 的唯一来源：`SettingsKeys`（26.10.04 批次 4）

- **`player` / `play_qn` 两个键现在只有唯一来源：`util/SettingsKeys.kt:72` 的 `PLAYER`、`:74` 的 `PLAY_QN`。** 原来还有第三处定义 `SharedPreferencesUtil.player`（死字段，已删）和 13 处字面量（已全部改调常量）。看见新代码里直接写 `"player"` / `"play_qn"` 就是回退。
- **别被同名假阳性骗了**：`activity/settings/SettingMainActivity.kt:118` 的 `"player"` 是设置页**分组 id / 跳转标记**，与 SharedPreferences 键只是字面量碰巧相同，**不是** `SettingsKeys.PLAYER`（该处已加注释）。
- `SettingsKeysTest` 把两个键名字符串钉死。理由：键名是**磁盘协议**，改了不报错、只让用户设置静默回默认值（覆盖安装升级时才会被发现）。
- 其余键（`mid`、`player_show_viewpoints` 等）仍散着字面量，尚未收敛；新增键请直接加进 `SettingsKeys`。

### 7.13 `session_svr` 会话管理接口（26.10.04 批次 5 新增）

- 私信会话的「移除」与「置顶」都走 `https://api.vc.bilibili.com/session_svr/v1/session_svr/` 下的接口，封装在 `api/PrivateMsgApi.java`：`removeSession(long talkerId, int sessionType)`、`setSessionTop(long talkerId, int sessionType, boolean top)`，两者共用私有 `postSessionAction(url, talkerId, sessionType, extra)`（`talker_id`/`session_type`/`csrf_token`/`csrf`/`build=0`/`mobi_app=web`，csrf 走 `NetWorkUtil.currentCsrf()`）。参数名与响应结构照 `updateAck` 的既有写法。
- **`set_top` 的 `op_type` 与直觉相反：0 = 置顶、1 = 取消置顶。** 已抽成纯函数 `PrivateMsgApi.opTypeForTop(boolean)` 并配单测（`api/PrivateMsgApiTest.kt`），写反的后果是"点置顶实际取消置顶"——不报错、极难发现。
- **服务端没有布尔型"是否置顶"字段**：只有会话项里的 `top_ts`（微秒级时间戳，未置顶为 0）。模型侧是 `model/PrivateMsgSession.topTs` + `isTop()`；`parseSessionsList` 用 `optLong("top_ts", 0)`（缺字段按未置顶），`getNewSessionsList` 同步解析。
- **移除会话 ≠ 删除聊天记录**：`remove_session` 只把会话从列表里拿掉，对方再发消息会重新出现。UI 的二次确认文案必须说清这一点，否则用户以为记录没了。
- 会话类型常量在 `PrivateMsgApi`：`SESSION_TYPE_USER = 1`、`SESSION_TYPE_FAN_GROUP = 2`（本项目目前只处理用户私信，粉丝团未接）。
- 返回码翻译统一走 `PrivateMsgApi.sessionErrorMsg(int)`（0→空串、-101→未登录、-400→"请求错误，会话可能已不存在"、其余保留错误码）。
- UI 落点：`adapter/message/PrivateMsgSessionsAdapter.kt` 的会话项**长按弹 `AlertDialog` 菜单**（置顶/取消置顶、删除会话、查看用户主页）；`activity/message/MessageActivity.kt` 的会话加载已抽成 `loadSessions()`，adapter 通过构造参数 `onSessionsChanged` 回调它重新拉列表（服务端才是唯一真相）。

### 7.14 私信图片消息（`msg_type=2`，26.10.04 批次 5 新增）

- **content 结构是硬约定**（`bilibili-API/docs/message/private_msg_content.md:27-57`）：`url` / `width` / `height` / `imageType` / `original` / `size`。组装入口是纯函数 `PrivateMsgApi.buildImageContent(url, width, height, byteSize, imageType)`，配套 `sizeToKb(long)`（KB，三位小数）与 `imageTypeOf(String)`（去 `image/` 前缀、`jpg`→`jpeg`）。
- **`url` 必须是 B 站图床地址**，否则返回 21037「图片格式不合法，不要调戏接口啦」。所以发图必须先上传：`ImageApi.prepareImage(context, uri)`（最长边 2048 压 JPEG90，GIF/PNG 在阈值内原样透传）→ `ImageApi.uploadImage(data, fileName, mimeType, biz)`（`POST https://api.bilibili.com/x/dynamic/feed/draw/upload_bfs`）。
- **`width`/`height` 不是可选项**：不带会在客户端显示异常（接口不报错），必须用上传返回的 `UploadedImage.width/height`。
- `biz` 没有私信专用值，复用 `ImageApi.BIZ_REPLY`（`new_reply`）——21037 只校验"是不是 B 站图床"，图床本身共用一个 `upload_bfs`。
- UI 落点：`activity/message/PrivateMsgActivity.kt` 的 `imageBtn`（布局 `activity_private_msg.xml` 里是个文字按钮「图」，因为 `res/drawable/` 没有图片类图标，且新增资源文件会踩 build cache 陈旧资源坑）+ `pickImage()`（**`ACTION_GET_CONTENT`**，手表相册不一定支持 `ACTION_PICK`）+ `sendImage(uri)`。
- **已知取舍：不做 EXIF 旋转**（无 `androidx.exifinterface` 依赖、约定不轻易加库），竖拍照片方向可能与相册显示不一致，与 `WriteReplyActivity` 行为一致。

### 7.15 新消息通知（26.10.04 批次 5 的 C14 新增）

- 全库通知现有四处：`DownloadService`（渠道 `biliterminal_download`、通知 ID **1027**）、`PlaybackService`（渠道 `playback_channel`、通知 ID **1028**）、本节的 `util/MsgNotifier.kt` 新消息（渠道 `private_msg_channel`、通知 ID **1029**）、以及同文件里的追番更新（渠道 `bangumi_update_channel`、通知 ID **1030**，见 §7.16）。**新增通知必须先确认 ID 不撞车**，撞了会互相覆盖。
- `MsgNotifier` 的三个成员：`notifyNewMessages(context, privateMsgUnread, otherUnread)`、`cancel(context)`、以及两个纯函数 `shouldNotify(previousUnread, currentUnread, enabled)` / `summaryText(privateMsgUnread, otherUnread)`。纯函数被 `util/MsgNotifierTest.kt` 覆盖。
- **提醒判据是"未读变多"而不是"有未读"**：`shouldNotify` = 开关开 && `currentUnread > 0` && `currentUnread > previousUnread`。否则每次冷启动都会弹，变成骚扰。`previousUnread` 取的是 `SharedPreferencesUtil.MESSAGE_UPDATE_NUM`（上次检查后存下的总未读）。
- **触发点是 `BiliTerminal.onCreate` 里既有的未读检查**（受「消息数量检查」开关注释）：`MessageApi.checkPrivateMsgUnread()` 给 `privateMsgUnread`、`checkMessageUnread()`（at + reply）给 `otherUnread`，**实参顺序别搞反**。项目无 WorkManager / AlarmManager 依赖，也不为此新增，所以没有后台定时提醒。
- **未读检查失败不再把 `MESSAGE_UPDATE_NUM` 清零**（C14 的行为变更）：清零会让下一次成功检查把"老未读"当成新增未读，网络抖一次就重复弹通知；现在只记 `Logu.w`。改动这里前先想清楚通知会不会重复。
- Android 13+ 需要运行时 `POST_NOTIFICATIONS`，在 `activity/message/MessageActivity.kt` 的 `requestNotificationPermissionIfNeeded()` 里申请；未授权时 `notifyNewMessages` 因 `areNotificationsEnabled()` 为 false 静默放弃，**不报错**——调试"通知不弹"时先查权限/系统开关，不要只盯代码。
- 开关键：`SettingsKeys.PRIVATE_MSG_NOTIFY_ENABLE`（`private_msg_notify_enable`，默认开）；**设置入口在第一层级的「通知设置」分组**（`GROUP_NOTIFY`，`SettingGroupActivity.buildNotifyGroup()`），可搜索条目在 `SettingsIndex`（`Entry("新消息通知", …) { openGroup(GROUP_NOTIFY, "通知设置", "新消息通知") }`）。**原先埋在「内容与浏览 → 通用偏好 → 更新提醒」里，已从 `SettingPrefActivity` 移除**——同一开关留两个入口会出现「一处改了另一处不刷新」的假故障（用户会以为设置没生效）。
- **不做 RemoteInput 速回**（用户拍板）：要额外权限 + 跨进程回复广播，手表打字成本高，收益不抵复杂度；点通知 = 打开消息页（`PendingIntent` 用 `NEW_TASK or CLEAR_TOP` + `FLAG_IMMUTABLE`）。
- 两个 `notify*` 里的建渠道重复代码已抽成私有 `ensureChannel(context, id, name, description)`（Android 8.0+ 必须建渠道，重复创建同名渠道是幂等的）；再往这个文件加通知时复用它，别再抄一遍。

### 7.16 追番更新提醒（26.10.04 批次 5 的 C16 新增）

- 链路：`BiliTerminal.onCreate` 第三块（在未读检查之后、`checkAppUpdate()` 之前）→ `util/BangumiUpdateChecker.checkAndNotify(context)` → `api/BangumiApi.getFollowedBangumi()` → 与快照 diff → `util/MsgNotifier.notifyBangumiUpdates(context, titles)`（通知 ID **1030**，`PendingIntent` 指向 `activity/user/FollowingBangumisActivity`）。
- **判据是同一部番的 `new_ep.id` 变了**（`model/FollowedBangumi.newEpId`），不是总集数、也不是服务端的 `is_new`（后者会被用户在别的客户端看过之后清掉）。纯逻辑 `BangumiUpdateChecker.findUpdated(stored, current)` 只认三种情况：快照里存在、`newEpId` 变了、且当前 `newEpId > 0`。
- **首次检查只写快照、不提醒**；**这次新追的番不算更新**；**快照只在成功拉到列表后写回**（失败保持旧快照，否则下次把老集当新集重复提醒，与 §7.15 未读检查失败不清零同理）。快照是 `SharedPreferencesUtil.BANGUMI_UPDATE_SNAPSHOT`（`bangumi_update_snapshot`），内容是 `{"media_id": new_ep_id, …}` 整份替换。
- **拉取上限 10 页 × 30 条 = 300 部**（`FOLLOW_PAGE_SIZE` / `FOLLOW_MAX_PAGES`，`ps` 定义域 1-30）：冷启动不该为一个提醒把流量打满。接口是 `x/space/bangumi/follow/list`（`type=1` 追番、`follow_status=0`），与列表页用的 `getFollowingList(int, List<VideoCard>)` **是两个用途**，别合并。
- 开关键 `SettingsKeys.BANGUMI_UPDATE_NOTIFY_ENABLE`（`bangumi_update_notify_enable`，默认开），设置入口与 §7.15 同在第一层级的「通知设置」分组（同属 `buildNotifyGroup()`）；文案 `desc_bangumi_update_notify_enable`。
- **不做后台定时**（用户拍板）：项目没有也不引入 WorkManager / AlarmManager；`getFollowedBangumi()` 未登录时返回空表，`checkAndNotify` 遇到空表直接返回且**不覆盖快照**。
- 单测：`api/BangumiApiTest.kt`（纯解析）、`util/BangumiUpdateCheckerTest.kt`（快照往返 + 更新判定）、`util/MsgNotifierTest.kt` 的 `bangumiSummaryText`、`util/SettingsKeysTest.kt` 的键名钉子。

### 7.17 评论列表的两种排序：主列表走服务端、楼中楼只能客户端排（26.10.04 批次 6 的 C4）

- **主评论列表**（`activity/reply/ReplyFragment.kt`）用服务端排序：`/x/v2/reply`（翻页）的 `sort`（0=时间/1=点赞数/2=回复数）与 `/x/v2/reply/wbi/main`（懒加载）的 `mode`（0/3=仅热度、1=热度+时间、2=仅时间），由 `api/ReplyApi.getReplies`/`getRepliesLazy` 透传。`ReplyFragment.kt:370` 在 2/3 两档之间切换。
- **楼中楼**（`activity/reply/ReplyInfoActivity.kt`）**没有服务端排序可用**：`/x/v2/reply/reply` 的参数只有 `type`/`oid`/`root`/`ps`/`pn`，**没有 sort / mode**，而且每页最多只返回 20 条。所以详情页的排序开关是**纯客户端**的：`model/Reply.sortReplies(List<Reply>, int sort, int fromIndex)`，`sort` 取 `Reply.SORT_TIME`（0，即服务端返回的回复顺序，直接不动列表）或 `Reply.SORT_LIKE`（1，按 `likeCount` 降序，`Collections.sort` 是稳定排序，同热度保持时间序）。
- **`fromIndex` 必须传 1**：评论详情页的第 0 位是根评论（所有楼中楼回复的父评论），不能被排进子评论里。翻页累积时也要带上新页重新排（`sort == SORT_LIKE` 时整体 `notifyDataSetChanged`，其余情况仍走 `notifyItemRangeInserted`）。
- 排序按钮文案由 `ReplyAdapter.sortLabel()` 给出——详情页是「时间排序/热度排序」（0/1），主列表是服务端的「时间排序/热度排序」（2/3），**两套取值不一样，别共用一张表**（旧代码共用 `sorts[sort]`，导致详情页永远显示「未知排序」；而且旧详情页把这个按钮设成了 `GONE`，等于根本没有排序入口）。
- **「按时间」不用 `floor` 排**：`floor` 在部分评论区不存在（`bilibili-API/docs/comment/readme.md` 的字段表注明「若不支持楼层则无此项」），而接口返回顺序本身就是回复顺序，直接用更稳。`seek_rpid` + `min_floor` 的「定位到某条评论」没做（C4 的另一半，暂不需要）。
- 单测：`app/src/test/java/com/RobinNotBad/BiliClient/model/ReplySortTest.kt`（降序、同热度稳定、`SORT_TIME` 不动列表、`fromIndex` 之前的元素不动、null/空/越界 fromIndex 不抛）。

### 7.18 带图评论的发送闸门（26.10.04 批次 6 的 C6b）

- 带图评论是「选图 → 压缩 → 上传图床（`ReplyApi.uploadReplyImage`）→ 拿 `image_url` → 拼 `pictures` → 发评论」两段式，**选完图界面立刻就有图了，但上传还在后台跑**。`activity/reply/WriteReplyActivity.kt` 用 `pendingUploads` 记「仍在上传的张数」，发送前必须过闸：`ReplyApi.canSendReply(pendingUploads)` 为 false 时只提示 `ReplyApi.uploadPendingTip(k)`，不发送。
- **为什么必须有这道闸**：调用方拼 `pictures` 时只能拿到**已成功**的上传结果，图没传完就发送 = 评论**少图发出且没有任何提示**（本地还因为把后到的 url 补进 `resultReply.pictureList` 而显示得比实际更多）。
- **闸门要在点下的那一刻置位**（`@Volatile sending`），并在后台 `try/finally` 里松开：成功了会 `finish()`，失败/提前 return 也要松，否则用户之后再想发就永远被挡。原实现是等请求返回才置位，等待期连点会发出两条评论。
- 这两个判断是**纯函数**（`api/ReplyApi.java` 的 `canSendReply`/`uploadPendingTip`），与 `actionErrorMsg(int)` 同类；`activity_write_reply.xml` 里没有进度控件，所以「上传中」只用按钮文字（`imageText`）表达，**不做按字节的进度条**（用户拍板只做第 1 步）。

### 7.19 动态的编辑与「管理」菜单（26.10.04 批次 6 的 C7）

- **一条动态能不能编辑/删除，看服务端下发的三点菜单**：`api/DynamicApi.java` 解析 `module_more.three_point_items[].type` 时，`THREE_POINT_DELETE` → `Dynamic.canDelete`、`THREE_POINT_EDIT` → `Dynamic.canEdit`。**两个开关独立**，不能用 `canDelete` 顶替 `canEdit`；`model/Dynamic.java` 的 `canEdit` 是 C7 新增的（`isTop` 早已解析但至今没有 UI 读它）。
- **列表项的管理入口是 AlertDialog 菜单，不再是「两次长按删除」**：`adapter/dynamic/DynamicHolder.kt` 的 `getManageListener(...)`（列表版 4/5 参重载 + 详情版 2/3 参重载）→ `showManageMenu(activity, dynamic, onEdited, onDeleted)`，菜单项按 `canEdit`/`canDelete` 动态拼。三个绑定点：`adapter/dynamic/DynamicAdapter.kt`、`adapter/dynamic/UserDynamicAdapter.kt`、`activity/dynamic/DynamicInfoFragment.kt`；可见条件统一是 `canDelete || canEdit`。
- **`cell_dynamic.xml` 里 `item_dynamic_delete` 的文案已从「删除」改成「管理」**，点一下与长按都弹同一个菜单（`showDynamic` 里把它 `setOnClickListener { performLongClick() }`，不另维护一份回调）。
- **编辑走 PiliPlus 的接口**：`POST https://api.bilibili.com/x/dynamic/feed/edit/dyn`，query 要 `platform=web`、`csrf`、`x-bili-device-req-json`（`{"platform":"web","device":"pc","spmid":"333.1368"}`）、`w_dyn_req.upload_id`（`mid_秒级时间戳_四位随机数`，纯函数 `DynamicApi.buildUploadId(mid, seconds, random)`）、`w_dyn_req.meta`（`{"app_meta":{"from":"create.dynamic.web","mobi_app":"web"}}`），**整条 query 要过 `ConfInfoApi.signWBI`**；body 是 `{"dyn_req":{content:{contents},scene,meta,upload_id,…},"dyn_id_str":"<id>"}`；返回 `code`，0 为成功（`editErrorMsg(code)` 给文案）。**编辑接口没有 `timer_pub_time`**（定时发布只能走发布接口，见 C9）。
- **编辑页与发布页是同一个 Activity**：`activity/dynamic/send/SendDynamicActivity.kt` 读 `intent.getLongExtra("edit_dyn_id", -1L)` 区分；编辑模式下预填 `edit_text`、**不看 `TerminalContext` 的转发内容**（它只在 `onDestroy` 清，会把上一次转发的卡片画到编辑页上）、隐藏投票与带图入口，发送时在页内直接调 `DynamicApi.editDynamic(...)` 并把新正文回传。
- **结果回调借 Activity 上的一个槽位**：`ActivityResultLauncher` 只能在 Activity 上注册一次，没有 per-holder 回调注册点，所以 `BaseActivity` 上留了 `@JvmField var pendingDynamicEdit: ((String) -> Unit)?`，`DynamicActivity.getEditDynamicLauncher(activity)` 在结果回来时**先取走再清空**。发起编辑前由 `DynamicHolder.launchEdit` 把「怎么刷新这一条」放进去。
- **置顶 / 取消置顶**（C8）：`DynamicApi.setDynamicTop(dynId, top)` 走 `POST /x/dynamic/feed/space/set_top` 或 `/x/dynamic/feed/space/rm_top`（路径片段由纯函数 `topPath(top)` 给），正文只有 `{"dyn_str":"<id>"}`、csrf 走 query，且要求 Cookie 里 `buvid3` 非空。**入口门槛用 `canDelete`**（别人的动态不下发 `THREE_POINT_DELETE`），菜单文案按 `dynamic.isTop` 在「置顶动态 / 取消置顶」之间切换；成功后本地翻 `isTop` 并刷新那一条（首页/空间的「置顶」标记来自服务端 `module_tag`，不刷新菜单还是旧文案）。
- **编辑是有损的**：`Dynamic` 没有原始正文，只能用 `content.toString()` 预填；WEB 链接节点只存 `orig_text`（URL 丢了）、@ 与表情会退化成文本形式，**编辑保存后这些结构会按纯文本重新提交**。这是已知取舍，已写进真机清单。

---

### 7.20 定时发布（26.10.04 批次 6 的 C9）

- **字段格式是坑**：`option.timer_pub_time` 要的是**秒级时间戳（整数）**，不是 `yyyy-MM-dd HH:mm` 字符串——`DynamicApi.buildPublishOption(boolean privatePub, Integer closeComment, Integer upChooseComment, Integer timerPubTime)` 的旧注释写的是后者，26.10.04 已改成 `Integer` 并订正 javadoc（上游 web 端与 PiliPlus 传的都是 int）。**编辑接口没有这个字段**，定时只能走发布接口（create/dyn）。
- **`buildPublishOption` 原来零调用点**：C9 之前它一直没人用（死代码），现在由 `activity/dynamic/DynamicActivity.kt` 的 `writeDynamicLauncher` 调用：从结果 intent 读 `getLongExtra("timerPubTime", 0L)`，> 0 才拼 `option`，否则传 `null` 保持四条既有发布链路原样（投票分支 `publishComplex`、带图 `publishImageContent`、纯文本 `publishTextContent(text)`、带 @/表情 `publishTextContent(text, atUids, option, emoteTexts)` 都接收 `option`）。
- **UI 只给固定档**：`activity/dynamic/send/SendDynamicActivity.kt` 的 `add_timer`（布局 `res/layout/activity_send_dynamic.xml` 新增的卡片，文案「定时发布」）点开是 AlertDialog 六项：10/30/60/120 分钟后、明天 12:00、不定时。手表上输入日期时间不现实，项目里也没有 DatePicker/TimePicker 先例，所以不做自由输入；算时间走纯函数 `DynamicApi.timerSecondsAt(nowSeconds, addMinutes)`（可单测），「明天 12:00」用 `Calendar` 在页内算。选中后按钮文案变成「定时：MM-dd HH:mm」。
- **入口只在普通发布时露出**：转发走 `relayDynamic`（没有 option）、编辑接口没有定时字段，所以 `add_timer` 与 `add_pic` 一样按 `normalPublish` 显隐。
- **定时成功后不做本地插入**：动态此刻还没真正发出去，`getDynamic` 拿回的状态不对，插进列表只会显示一条「将来才发」的动态；改为提示「已设置定时发布~」并跳过本地插入，由下拉刷新兜底。
- **真机待验证**：服务端对「离现在太近」的定时（如 10 分钟后）可能直接拒（`publishComplex` 只回 -1，页面显示「发送失败」），所以 C9 的真机清单第一条就是试 10 分钟档；若被拒就把最近档改大或改为草稿箱方案（`x/dynamic/feed/get_drafts` 的 `publish_time`）。

---

### 7.21 话题（26.10.04 批次 6 的 C10）

- **两个接口，一个 `api/TopicApi.java`**：
  - 话题动态列表 `GET https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/topic`（`topic_id` 必、`offset`、`page_size=20`、`source=Web`、`features`），**整条 query 要过 `ConfInfoApi.signWBI(DmImgParamUtil.getDmImgParamsUrl(url))`**（与动态首页同一套）。返回 `data.topic_card_list.{has_more, items[], offset}`，其中 **`items[]` 是套壳** `{dynamic_card_item, topic_type}`——必须取内层 `dynamic_card_item` 再交给 `DynamicApi.analyzeDynamic(JSONObject)`（该方法已是 `public static`，话题页直接复用，不必另写解析）。`has_more == false` 时 `getTopicDynamicList` 返回空串表示到底，否则返回新的 `offset`。
  - 话题广场 `GET https://app.bilibili.com/x/topic/web/dynamic/rcmd`（`page_size=9`、`source=Web`、`web_location=333.1365`），返回 `data.topic_items[]`：`id`/`name`/`discuss`/`dynamics`/`view`。这是**唯一的话题 id 来源**：动态正文里的话题节点（`RICH_TEXT_NODE_TYPE_TOPIC`）只带搜索页跳转链接，反推不出话题 id，所以广场入口是必须的，不是可选装饰。
- **一个 Activity 两种形态**：`activity/dynamic/DynamicTopicActivity.kt` 继承 `RefreshListActivity`，读 `intent.getLongExtra("topic_id", 0L)`——没有就是广场（`TopicAdapter` 列表，`bottom = true` 单页），有就是该话题的动态列表（`TopicDynamicAdapter` + `offset` 翻页）。广场点一项**用同一个 Activity 再 start 一次**（把 `topic_id`/`topic_name` 传过去），返回即回广场，不用另写页面也不用 Fragment。
- **入口在动态页动作卡片**：`res/layout/cell_dynamic_action.xml` 新增 `MaterialButton id=topic`（文案「话题广场」，整行），点击在 `adapter/dynamic/DynamicAdapter.kt` 的 `WriteDynamic` 绑定。**没有走新菜单项**——`util/MenuConfig.kt` 的 `loadEnabled` 对老用户已存的 `menu_enabled` 直接返回，新增 key 不会自动出现，加菜单项等于只有新用户能看到。
- **话题动态列表 adpater 必须自己写**：`DynamicAdapter.kt` 硬绑 `context as DynamicActivity`（还用 `writeDynamicLauncher`/`selectTypeLauncher`），`UserDynamicAdapter.kt` 需要 `UserInfo` 且第 0 位是用户信息头，两者都不能复用。`TopicDynamicAdapter` 只复用 `DynamicHolder`（item 布局 `res/layout/cell_dynamic.xml`，长按管理菜单走 `DynamicHolder.getManageListener(activity, dynamicList, realPosition, this)`）。
- **它保留了第 0 位的头部占位**（复用 `res/layout/cell_goto.xml` 的那个 `text`，显示 `#话题名`）：`getItemCount() = dynamicList.size + 1`、`realPosition = position - 1`。不是为了好看——`getManageListener` 列表版在置顶/编辑成功后会**按 `realPosition + 1` 反推要刷新的行**，去掉头部占位会刷错行。翻页通知起点因此是 `lastSize + 1`。
- **发布器不加话题选择**（用户拍板）：发动态要带话题得先有 id，而正文里拿不到 id；既要话题选择器又要话题搜索，属另一件事。

### 7.22 笔记，仅查看（26.10.04 批次 6 的 C27）

- **链路**：视频详情页 `activity/video/info/VideoInfoFragment.kt` 新增整行 `MaterialButton id=note`（布局 `res/layout/fragment_video_info.xml`，文案「笔记」，**未登录时与「稍后再看」「转发」「视频摘要」一起 `GONE`**）→ `activity/note/NoteActivity.kt`（`BaseActivity` 子类，`intent` 只带 `aid`，可选带 `note_id` 直接看指定那篇）。Activity 里先 `SharedPreferencesUtil.mid == 0` → 提示「登录后才能看笔记喵~」；否则 `CenterThreadPool.run` 内先调 `NoteApi.getNoteIdsOfVideo(aid)` 拿私有笔记 id 列表，空则「这个视频还没有笔记」，有则取第一篇调 `NoteApi.getNoteInfo(aid, noteId)`。
- **两个接口，一个 `api/NoteApi.java`**：`GET https://api.bilibili.com/x/note/list/archive?oid=<aid>&oid_type=0&csrf=<NetWorkUtil.currentCsrf()>`（**只能查私有笔记**，无笔记时 `data` 里**没有 `noteIds` 字段**，要当空表处理）→ `data.noteIds[]` 是**字符串数组**；`GET https://api.bilibili.com/x/note/info?oid=<aid>&oid_type=0&note_id=<id>`（不带 csrf）。错误码 `79502` 笔记详情未找到、`79503` 笔记正文未找到、`79514` 公开笔记没找到，另有 `-101`/`-400`，全部走纯函数 `NoteApi.noteErrorMsg(int)`；网络层抛异常时优先用服务端 `message`。
- **`note_id` 必须取 `note_id_str`**：接口文档自己的示例里 `note_id` 是 `24508729145690110` 而 `note_id_str` 是 `"24508729145690112"`——17 位超过 2^53，走 JSON number 已经丢精度。`NoteApi.pickNoteId(JSONObject)` 固定优先 `note_id_str`、回退 `note_id`，并且**全程按字符串传参**（`model/Note.noteId` 是 `String`）。
- **正文是 Quill delta，不是 HTML，也不能复用 opus 解析**：`data.content` 是一段 JSON 序列字符串，根数组元素形如 `{"attributes":{…},"insert": …}`——`insert` 是字符串就是文本，是对象则二选一 `imageUpload`（`url`/`status`/`width`，**`width` 是"图片宽度 - 2"**）或 `tag`（`cid`/`index` 分P/`seconds` 进度/`title` 等）。属性有 `bold`/`underline`/`strike`/`color`/`background`/`list`（`ordered`|`bullet`）/`size`。`model/OpusParagraph.java` + `OpusContentAdapter` 那套处理的是**嵌套**段落结构，这里是**扁平 delta 数组**，结构不兼容，所以另写了 `model/NoteBlock.java` + 纯函数 `NoteApi.parseBlocks(String)`（坏 JSON、根是对象、`insert` 对象里两种 key 都没有——一律安全跳过，不抛）。
- **渲染不用 RecyclerView**：`NoteActivity.renderBlocks` 把 block 列表拼成**一段 `SpannableStringBuilder`** 塞进一个 `TextView`（`res/layout/activity_note.xml` 的 `note_content`），样式用 `StyleSpan`/`UnderlineSpan`/`StrikethroughSpan`/`ForegroundColorSpan`/`BackgroundColorSpan`；图片只渲染 `[图片]` 占位，视频进度 tag 渲染成 `[视频进度 mm:ss]`（有分P索引则 `[分P n mm:ss]`，格式化走纯函数 `NoteApi.formatTagSeconds(long)`）；列表项加 `n. `/`• ` 前缀。脏色值 `Color.parseColor` 包 try/catch 返回 null，不崩。
- **没做的**（用户拍板范围限定「仅查看」）：我的笔记列表页（`x/note/list`）、公开笔记（`cvid` / `x/note/publish/info`）、创建与编辑笔记、图片真实加载、点击 tag 跳转进度，全都没做。

### 7.23 稍后再看「未看完」分类（26.10.04 批次 7 的 C18）

- **判据直接用接口自带的两个字段**：`GET x/v2/history/toview/web` 的 `data.list[]` 里本来就有 `progress`（已看秒数）与 `duration`（总时长秒数），不需要查观看记录。纯函数 `api/WatchLaterApi.isUnfinished(progress, duration)`：`progress <= 0` → 不算「未看完」（那是"没播过"）；`duration <= 0` 视为未知、只看 `progress > 0`；否则 `progress < duration`。注意 `progress` 是**秒**、`VideoCard.progress` 也是秒，而 `PlayerData.progress` 是毫秒，别混。
- **`model/VideoCard.java` 新增 `public long duration = 0;`**：只有稍后再看接口会填，其它来源保持 0（未知）。它是 `Parcelable`，字段**只能追加在末尾**且构造器读取与 `writeToParcel` 写法必须成对（文件里有注释警告：顺序错位会静默串数据）。
- **筛选条是 `res/layout/activity_simple_refresh.xml` 里默认 `gone` 的 `filterBar`**：两个等宽 `TextView`（`filterAll`「全部」/`filterUnfinished`「未看完」）。和 `loadMoreTip` 一样放在 `SwipeRefreshLayout` **外面**，只有 `WatchLaterActivity` 在 `onCreate` 里改成 `VISIBLE`——所以对其它 `RefreshListActivity` 页面零影响。**没有做成 adapter 的头部项**：那会改变所有业务 adapter 的 `viewType`/`adapterPosition` 语义（本项目已有"头部占位导致 `+1` 通知起点"的坑，见 §7.9）。
- **切档不重新请求**：`activity/user/WatchLaterActivity.kt` 里 `allList` 存接口全量、`shownList` 是交给 adapter 的那个引用；切档只按 `WatchLaterApi.filterUnfinished` 重填 `shownList` + `notifyDataSetChanged()`。**删除时两张表都要改**（按对象 `allList.remove(card)`，按位 `shownList.removeAt(position)`），否则切档会"复活"已删条目。选中档位用 `ColorScheme.PRIMARY`，另一档用 `onCreate` 时从 `filterAll.currentTextColor` 抓到的默认色（不写死颜色常量）。
- **删除手势没动**：仍是原来的「4 秒内连点两次长按才删」，C18 只加分类。

### 7.24 收藏夹：排序 + 复制 / 移动（26.10.04 批次 7 的 C19）

- **「收藏夹排序」= 收藏内容列表的排序，不是给收藏夹本身排序**：快照里没有这种接口；能做的是 `GET x/v3/fav/resource/list` 的 `order` 参数，取值 `mtime`（收藏时间）/`view`（播放量）/`pubtime`（投稿时间）。常量在 `api/FavoriteApi.java`：`ORDER_FAV_TIME`/`ORDER_VIEW`/`ORDER_PUBTIME`。
- **老接口的收藏时间叫 `fav_time` 不叫 `mtime`**：`api/FavoriteApi.legacyOrder(order)` 负责映射（`mtime → fav_time`，其余原样）。`getFolderVideos`（老 `x/space/fav/arc`，只在没有 media_id 时兜底）与 `getFolderVideosNew`（新接口）各有一个带 `order` 的重载，旧签名保留并委托，调用点不用全改。
- **排序必须重发请求，筛选不用**：`sortBar` 切档 = `page = 1` + `bottom = false` + 清 `videoList` + 重拉第一页；这与 C18 的 `filterBar`（纯本地过滤、只 `notifyDataSetChanged`）是两种东西，别抄错。
- **`sortBar` 也是 `res/layout/activity_simple_refresh.xml` 里默认 `gone` 的分组**（三个等宽 chip：`sortFavTime`/`sortView`/`sortPubtime`），只有 `FavoriteVideoListActivity` 会点亮，其它 `RefreshListActivity` 页面零影响。选中档位用 `ColorScheme.PRIMARY`，另两档用 `onCreate` 时从 chip 的 `currentTextColor` 抓到的默认色。
- **自己的收藏夹现在也带 `media_id`**：`adapter/favorite/FavoriteFolderAdapter.kt` 点击时补传 `mediaId` 与 `readOnly=false`（`adapter/favorite/UserFavoriteFolderAdapter.kt` 传 `readOnly=true`）。页面判定：`readOnly = getBooleanExtra("readOnly", mediaId > 0)`（没带该 extra 的旧调用方按原逻辑兜底），`writable = !readOnly && mediaId > 0`。**`media_id` 来自 `FavoriteApi.getFavoriteFolders` 里 `fid → media_id` 的映射，拿不到就是 0**，此时退回老接口、不提供复制/移动，长按仍是原来的「连点两次长按删除」。
- **复制 / 移动走 `POST x/v3/fav/resource/copy` ∥ `/move`**：参数 `src_media_id`/`tar_media_id`/`mid`/`resources`/`platform=web`/`csrf`。`resources` 格式是 `{avid}:2` 用逗号分隔（2 = 视频稿件），拼装用纯函数 `FavoriteApi.buildResources(cards)`（跳过 `aid <= 0`），错误码文案用 `FavoriteApi.resourceErrorMsg(code)`（含 `11010` 内容不存在）。**C20 的多选删除（`x/v3/fav/resource/batch-del`）复用同一套 `buildResources`/`resourceErrorMsg`**。
- **长按弹菜单而不是直接删**：`writable` 时弹「复制到…」「移动到…」「取消收藏」；复制/移动的目标收藏夹从 `FavoriteApi.getFavoriteFolders(mid)` 里选并**排除当前这个**（`mediaId` 相同或 `mediaId <= 0` 的不列）。移动成功要把那一条从 `videoList` 移除（`removeItem` 走 `notifyItemRemoved` + `notifyItemRangeChanged`，删空则 `showEmptyView()`）。

---

### 7.25 收藏夹多选删除（26.10.04 批次 7 的 C20）

- **接口**：`POST x/v3/fav/resource/batch-del`，参数 `resources`（C19 的 `buildResources()`，`{avid}:2` 逗号分隔）/`media_id`/`platform=web`/`csrf`；`api/FavoriteApi.batchDeleteResources(mediaId, cards)` 在 `mediaId <= 0` 或资源为空时**直接返回 `-400` 不发请求**（和 `copyResources`/`moveResources` 一样的前置校验），错误文案复用 `resourceErrorMsg()`。
- **`manageBar` 是 `res/layout/activity_simple_refresh.xml` 里第三个默认 `gone` 的分组**（前两个是 C18 的 `filterBar`、C19 的 `sortBar`）：两个等宽 chip `manageToggle`（「多选」/「退出多选」）与 `manageDelete`（「删除」/「删除(n)」）。只在 `writable`（自己的收藏夹且有 `media_id`）时点亮；没勾选任何条目时「删除」压暗到 `alpha=0.5`，点了只提示「先选几条吧~」。
- **勾选状态在页面，不在 adapter**：页面持有 `LinkedHashSet<Long> selectedAids` 并把同一个引用交给 `VideoCardAdapter.selectedAids`；`VideoCardAdapter.selectionMode` 只影响 `onBindViewHolder` 末尾那次 `holder.applySelection(selectionMode, selectedAids.contains(aid))`。
- **选中样式不动任何布局 id**：`adapter/video/VideoCardHolder.applySelection` 用「未选中条目 `itemView.alpha = 0.45f`」+「标题前加 2 字符前缀」（`TextUtils.concat`，选中 `"✓ "`、未选中 `"　 "` 全角空格对齐，加之前先 `subSequence(2, …)` 剥旧前缀）。**没有给 `cell_video_list.xml` 加 checkbox**——那里的 id 是跨包事实协议（见第 7 节开头），为画个勾不值得冒险。
- **多选模式下的手势语义**：点条目 = 勾选/取消勾选（覆盖虚拟合集点击与进视频详情页），长按也走勾选、不再弹 C19 的管理菜单。**切排序会先 `exitSelectionMode()`**（`switchSort` 里），否则 `selectedAids` 会指向被清空的旧列表。
- **删除流程**：确认框文案「确定把选中的 N 条从收藏夹里移除吗？」→ `CenterThreadPool.run { batchDeleteResources(mediaId, targets) }` → code 0 时提示「已删除 N 条」+ 本地 `videoList.removeAll(targets)` + `notifyDataSetChanged` + 空则 `showEmptyView()` + 退出多选；非 0 用 `resourceErrorMsg(code)`。

### 7.26 关注分组增删改（26.10.04 批次 7 的 C21）

- **三个写接口**（都在 `api/FollowApi.java`，走 `application/x-www-form-urlencoded` + `csrf`）：创建 `POST x/relation/tag/create` 参数 `tag`（**最长 16 字符**，`TAG_NAME_MAX_LENGTH = 16`）→ `createFollowTag(name)`；重命名 `POST x/relation/tag/update` 参数 `tagid`/`name` → `renameFollowTag(tagid, name)`；删除 `POST x/relation/tag/del` 参数 `tagid` → `deleteFollowTag(tagid)`。三者都经私有 `postTag(url, formData)` 统一取值 `code`（缺 `code` 记 `-1`）。
- **错误码文案集中在 `FollowApi.tagErrorMsg(code)`**：22101 名称有不允许字符 / 22102 分组数量超限 / 22103 分组名过长 / 22104 分组不存在 / 22106 同名分组已存在；本地预校验是纯函数 `FollowApi.checkTagName(name)`（空/全空白 → 「分组名不能为空」，trim 后 > 16 → 「分组名最多 16 个字」），两个纯函数都有 `api/FollowApiTest` 单测。
- **不新建输入页**：创建与重命名都复用 `activity/InputDialogActivity.kt`（extras `title`/`initial_text`/`hint`，回传 `input_text`），调用点照抄 `activity/video/local/LocalListActivity.kt` 的 `pendingInputCallback` + `registerForActivityResult` 范式。改名/删除入口是**长按分组标题**弹 `AlertDialog` 两项菜单（`adapter/user/FollowGroupAdapter.kt` 新增 `setOnGroupLongClickListener`），不是新按钮。
- **`groupBar` 是 `res/layout/activity_simple_refresh.xml` 里第四个默认 `gone` 的分组**（前三个见 §7.23/§7.24/§7.25），只有关注列表的「分组模式」才在 `setupGroupBar()` 里点亮，里面只有一行「+ 新建分组」。
- **系统分组不可编辑**：`isEditableTag(tag) = tag.tagid > 0`——默认分组（`tagid = 0`）与特别关注（`-10`）长按只提示「默认分组和特别关注不能改名或删除」。
- **分组模式不再过滤 `count == 0` 的分组**：原先 `loadGroupMode()` 只 `addGroup` 关注数 > 0 的分组，导致刚建好的空分组在列表里看不到、也就没法改名/删除；现在全部列出（空分组展开为空）。
- 增删改成功后一律**整页重拉**（重新 `loadGroupMode()` 造新 adapter——`RefreshListActivity.setAdapter` 只是赋值，没有「只能设一次」的守卫，安全）。

### 7.27 下载服务分层：`DownloadService` 已拆分（26.10.04 批次 8 的 E2）

`app/src/main/java/com/RobinNotBad/BiliClient/service/DownloadService.kt` 从 **1593 行**拆到 **1284 行**，实现按关注点搬到 `service/download/` 下四个新文件（同一 `service` 包的子包，**不是**新模块）。**对外 API 一字未改**——Java/Kotlin 调用点照旧写 `DownloadService.startDownload(...)` / `DownloadService.getDownloadProgress(...)`，`Companion` 里保留全部 10 个 `@JvmStatic` 字段与 24 个 `@JvmStatic` 函数做转发。

| 文件 | 关注点 | 关键点 |
| --- | --- | --- |
| `service/download/DownloadPathSpec.kt`（72 行） | 分片路径、分段数、分段区间 | 纯函数，无 IO 无 Context；`DownloadPathSpecTest` 72 行 |
| `service/download/DownloadProgressMath.kt`（60 行） | `progressForBytes` / `pseudoProgress` / 批次统计 | 纯函数；`DownloadProgressMathTest` 159 行 + `service/DownloadBatchStatsTest` 136 行 |
| `service/download/DownloadProgressStore.kt`（143 行） | 进程级状态：进度映射、`pausedMap`、累计字节、速度采样 | `internal object`；顶层 `DownloadProgressInfo` 是 **public**（被 public 门面返回） |
| `service/download/DownloadRepository.kt`（157 行） | DB 访问（`DownloadSqlHelper` 的增删查改） | `firstDown` 与视频元信息写入一起搬入 |
| `service/download/DownloadNotifier.kt`（122 行） | 通道、两个 Builder、每秒进度、`notifyExit` / `notifyCompletion` | **普通 class 持 Service 引用**，由 `onCreate` new 出来 |

**改下载相关代码前必读的三条**：

1. **锁：`start(Long)` 一行未搬**，仍是 `Companion` 的 `@JvmStatic @Synchronized`（`javap`：`DownloadService$Companion.start(long)` = `public final synchronized`、静态桥 = `public static synchronized`），`started` 的 check-then-act 全靠它；`batchRunning` 守卫（审计 S6）也在原位。**`speedLock`（`DownloadProgressStore` 内）与 Companion 监视器是两把独立的锁，不得互换或合并**——速度采样全程只认 `speedLock`。
2. **刻意保留的现状，不要"顺手修"**：`speedStr` / `isSpeedMode` / `activeDownloadsCount` **不是** volatile（UI 线程读、下载线程写是既有数据竞争）；`totalBytesDownloaded` 允许被减（失败分片回滚）；死代码 `clear()` 与从未赋值的 `toastTimer` 原样保留。
3. **`pausedMap` 必须还是同一个实例**：`activity/video/local/DownloadListActivity.kt` 直接对它 add/remove，`Companion.pausedMap` 的 getter 直接返回 `DownloadProgressStore.pausedMap`，**不能复制**。同理 `notifyTimer` / `statusBuilder` / `completionBuilder` / `notifyManager` 仍声明在 Service 实例上（notifier 只读写、不另存），因此 `notifyTimer` 由 `private` 放宽为 `internal`。

**行为契约不变的部分**：channel id `biliterminal_download`、前台通知 `1027`、`notifyExit` 的 `2`、`notifyCompletion` 的 `id % 100 + 100`、download 表 11 列 / version 4、SP 键（`aria2_enabled` / `aria2_split` / `parallel_download_videos`）。**已知的可见性变化**：`DownloadProgressInfo` 的 FQN 由 `DownloadService$Companion$DownloadProgressInfo` 变为 `com.RobinNotBad.BiliClient.service.download.DownloadProgressInfo`（全仓无显式引用，按旧 FQN 反射的代码会失配）。

### 7.28 顶部工具条滚动自动收回（26.10.05 批次 8 新增）

- **能力在基类 `activity/base/RefreshListActivity.kt`，默认关闭**：子类调 `setupAutoHideBars(vararg bars: View?)` 才生效（不调 = 零影响，所以对其它几十个列表页安全）。`activity_simple_refresh.xml` 里的 `filterBar`/`sortBar`/`manageBar`/`groupBar` 是 `SwipeRefreshLayout` **之上的兄弟节点**，不在 RecyclerView 内，**不会随列表滚走**，只能手动改 `layoutParams.height` 做收放。**没做成 adapter 头部项**：那会重映射所有业务 adapter 的 `viewType`/`adapterPosition`（同 §7.23 的理由）。
- **判据抽成纯函数 `util/view/ScrollRetractDecider.kt`**：`action(accumulated: Int, collapsed: Boolean, canScrollUp: Boolean): Int` 返回 `NONE`/`COLLAPSE`/`EXPAND`（`THRESHOLD = 12`）。View 层在 JVM 单测里是 Android 桩，断言写了也是假的（见 §36.7 第 1 条），所以判据必须与 Android 解耦才有真单测。
- **展开只有「回到顶部」一条路（26.10.05 第三批真机反馈后收紧，见 `docs/review/fix-progress.md` §三十九）**：`EXPAND` 只可能在 `!canScrollUp && accumulated <= 0` 时返回，列表**中间**向上滚一律 `NONE`。原因是**动画的高度变化会顶动列表内容**——条是列表的兄弟节点、位于列表上方，高度一变可见区域跟着变、内容整体位移；用户正按住屏幕拖动时手指底下的条目被推走，表现为「条和滑动手势打架」。收回则随时可以（内容朝手指方向让位，方向一致不冲突）。**旧口径「向上累计滚过阈值就展开」是错的，不要改回去。**
- **两条反直觉但必须保留的设计**：①**已收回时继续向下滚仍返回 `COLLAPSE`**，作用是让调用方清零累加值——若返回 `NONE`，累加值会一直涨，用户往上滚时先要抵消历史正值，表现为「条收起来以后怎么滚都不回来」；②**`collapsed && !canScrollUp && accumulated <= 0` 强制 `EXPAND`**——已在顶部还收着就必须展开，因为列表到顶后不会再产生负 `dy`，否则永远展不开。
- **`naturalBarHeight` 必须缓存**：条是 `wrap_content`，收回时高度被压成 0，0 高度量不出「原本多高」，否则**收得回去、展不回来**。
- **`animateBars` 的三个易错点**：①收回只处理 `visibility == VISIBLE && naturalBarHeight > 0` 的条——**不可见的条不能收**，否则会被记进 `collapsedBars`，展开时把本该 `GONE` 的条点亮；②`barsAnimator?.cancel()` 后**必须立刻置 `null`**（`cancel()` 会同步回调旧动画的 `onAnimationEnd`，那时若还指着旧动画就会把 height 复位成 `WRAP_CONTENT`），并在 `onAnimationEnd` 里用 `if (barsAnimator !== animation) return` 挡下被取消的动画；③**多条共用一个 `ValueAnimator`**（高度按同一条 0..1 进度算），每条各起一个动画会因启动微差错位。
- **接线点**：`activity/user/WatchLaterActivity.kt` 接 `filterBar`（并在 `reloadForFilter()` 里先 `expandAutoHideBars()`，否则点了「未看完」条还是收着的）；`activity/user/favorite/FavoriteVideoListActivity.kt` 接 `sortBar`（并在 `switchSort()` 里 `expandAutoHideBars()`）。**`manageBar` 故意不接**：多选模式下用户正勾着视频，条收走就没法点删除。（同批次的长按操作面板见 `util/LongPressPrefs.kt` 与 `docs/review/fix-progress.md` §37.1。）

---

## 8. UI 基建速查（新增页面临摹用）

### 8.1 完整继承体系

```
AppCompatActivity
  └─ BaseActivity
       ├─ InstanceActivity              ← 一级页（菜单键/顶栏 → MenuActivity）
       │    └─ RefreshMainActivity      ← 一级页 + 分页（布局 activity_simple_main_refresh）
       └─ RefreshListActivity           ← 二级页 + 分页 + 空视图（布局 activity_simple_refresh）

Fragment → BaseFragment → RefreshListFragment   ← Fragment 版列表页
```

`BaseActivity` 提供：主题应用（7 套主题，色表经 `ColorScheme.getCurrentTheme()` 缓存，仅由 `AppearanceManager.setTheme` 失效）、横竖屏、DPI/边距、**系统栏 insets 避让（`applySystemBarInsets`，含刘海）**、`getLayoutManager()`（横屏按「每列 ≥220dp」返回 `CustomGridManager`，下限 2 列）、`asyncInflate`（先显 loading 布局再替换）、`onBackPressed` 受 `back_disable` 开关、EventBus 自动注册/注销 + sticky `SnackEvent` 重放、主题变更 `onResume` 自动 `recreate()`、重写 `isDestroyed()`。

`InstanceActivity` 额外：`onCreate` 里 `BiliTerminal.setInstance(this)`；**顶栏点击不自动绑定**，须手动 `setMenuClick()`。

**顶栏曾是公共组件，但那次收敛被回滚了**：`res/layout/cell_topbar.xml` 目前**全库 0 处引用**（26.09.11 实测：149 个布局里只有 7 个含 `<include>`，且都不含 `cell_topbar`），实际是各页手抄顶栏。历史经过见 `docs/review/fix-progress.md:216`——「44 个布局换成 `<include>`」那一轮在真机实测顶栏吃满整屏后**已整体还原**。所以本段旧描述（"44 个布局共用"）**与现状相反**，要重做收敛请先读 `docs/visual-experience-report.md` 的方案再动手。

### 8.2 新列表页模板（必须做这 4 步）

`super.onCreate()` 之后：

```kotlin
setPageName("标题")                          // 1. 顶栏标题
setMenuClick()                               // 2. 仅 RefreshMainActivity 需要
setOnRefreshListener { load(1) }             // 3. 不注册则 swipeRefresh 永远转圈（基类 onCreate 里是 isEnabled=false, isRefreshing=true）
setOnLoadMoreListener { page -> load(page) } // 4. page 已由基类自增，别再 ++
```

加载完成**必须**调 `setRefreshing(false)`——它是 `isLoading`/`isRefreshing` 的唯一复位信号（只改 `swipeRefreshLayout.isRefreshing` 不算）。失败走 `loadFail(e)`（会 `page--` 并复位）。到底置 `bottom = true`。

**选型**：菜单入口页 → `RefreshMainActivity`；返回式页面且要空视图 → `RefreshListActivity`。

| | RefreshListActivity | RefreshMainActivity |
|---|---|---|
| 父类/顶栏 | BaseActivity，返回式（自动绑） | InstanceActivity，菜单式（须 `setMenuClick`） |
| 防重入字段 | private `isLoading` | protected `isRefreshing` |
| 并发保护 | 仅 500ms 时间戳 | `synchronized` + 100ms |
| 触发阈值 | `findLastVisibleItemPosition >= itemCount-4`，IDLE 也查 | 完全可见项 `>= itemCount-3` 且 `!canScrollVertically(1)` |
| 空视图 | 有 `showEmptyView/hideEmptyView` | **无**（布局里有 `emptyTip` 但无人管理） |
| 性能 | `PerformanceManager` 动态缓存 + 独立 RecycledViewPool | 固定 cacheSize 10、pool max 20 |

### 8.3 Adapter 写法

**注意**：`ui/widget/recycler/` 下的 `AbstractAdapter` / `BaseAdapter` / `BaseHolder` 三件套**全工程没有任何子类**，已于 **26.09.11 删除**（连同 `WrapContentLinearLayoutManager`）。现役适配器一律 `extends RecyclerView.Adapter<XxxHolder>()` + Holder 直接继承 `RecyclerView.ViewHolder`。该目录现在只剩 `CustomLinearManager` / `CustomGridManager` 两个 LayoutManager。

**照抄对象**：`adapter/video/VideoCardAdapter.kt` + `adapter/video/VideoCardHolder.kt` 这一对（header/footer 需要时自己写，别找现成基类）。

### 8.4 现成交互工具

`MsgUtil`（任意线程可调）：
- `showMsg(str)` / `showMsgLong(str)` → 走 EventBus sticky `SnackEvent`；`toast/toastLong` 为降级 Toast。
- `err(Throwable)` 按异常类型自动分流文案（IOException→网络错误、JSONException→带详情、IndexOutOfBounds→Adapter 错误、SQLException→SQL）。
- `showText(title, content)` → `ShowTextActivity`；`showDialog(title, content[, wait])` → `DialogActivity`。

弹窗（`util/TerminalDialog.kt`，26.10.05 新增，**唯一入口**）：

四个静态方法，全部返回 `AlertDialog`，调用方自行 `.show()`：

| 方法 | 用途 | 对应旧的裸写法 |
|---|---|---|
| `menu(context, title, items, danger, onPick)` | 菜单型，点一下执行一个动作 | `setItems`（原 7 处） |
| `singleChoice(context, title, items, checked, onPick)` | 单选型，`▸`/`›` 双态引导符 | `setSingleChoiceItems`（原 2 处） |
| `confirm(context, title, message, confirmText, cancelText, confirmIsDanger, onConfirm)` | 确认型，取消=次级灰 / 确认=危险红 | `setMessage`+`setPositiveButton`（原 8 处） |
| `alert(context, title, message, buttonText)` | 纯提示，单按钮 | `setMessage`+单按钮 |

行为约定（改之前先读这几条）：
- `menu` **先 dismiss 再回调**——回调里常会再弹一个框（「删除评论」→ 二次确认），不关会叠在一起。
- `singleChoice` **不自动关闭**——选季场景要先做越界校验再由调用方 `dialog?.dismiss()`；`checked` 越界会被夹到合法范围。
- `confirm` **保留按钮行**（菜单/单选刻意去掉了按钮行，手表纵向空间比一个「确定」值钱）；`cancelText` 传空串则不显示取消按钮。
- 破坏性项（删除/取消收藏）**必须**进 `danger`，别让用户靠文案猜。
- 颜色只读 `?attr/`，文件内无色值常量；`attrColor` 带兜底默认值。

对话框 Activity（Intent extra 传参 + `registerForActivityResult`）：

| Activity | 入参 | 返回 |
|---|---|---|
| `DialogActivity` | title、content、wait_time | 无（只能点按钮） |
| `ConfirmDialogActivity` | title、content | RESULT_OK / RESULT_CANCELED |
| `ListDialogActivity` | title、`items`(StringArrayList) | `selected_position` |
| `InputDialogActivity` | title、initial_text、hint | `input_text`（已 trim，不校验空） |

### 8.4b 重复项合并后的公共落点（26.09.11 新增，别再手抄）

做「重复代码合并」时把 6 类重复收敛成了下列单一落点。**要写这几件事时直接用它们，不要重新手抄一份**。

| 落点 | 取代了 | 说明 |
|---|---|---|
| `adapter/video/VideoQuickCache.handle(context, videoCard)` | 3 份逐字节相同的 `handleQuickCache` | 视频卡列表长按的「快速缓存」，按 `cache_default_quality` 分支 |
| `adapter/LogListAdapter<T>` + `res/layout/cell_log.xml` | 2 份流水 adapter + 2 份 MD5 相同的布局 | 经验 / 硬币变化记录；delta 文案由调用方以 `Row` 映射传入（两者文案不同，刻意未统一） |
| `util/NetWorkUtil.decompress(byte[])` | 3 份 `Inflater(true)` | CDN 裸 deflate 响应；`api/UserInfoApi.decompressResponse` 是 br+gzip，**算法不同不可合并** |
| `util/TimeUtil` | 11 处 `new SimpleDateFormat` | 时间格式化统一入口，ThreadLocal 缓存；`PATTERN_DATE_TIME_12H` 是历史遗留的 12 小时制，勿顺手改 `HH` |
| `BiliTerminal.jumpToUser(context, mid)` | 16 处手抄 Intent | 所有「跳用户主页」；等价于 `Intent().setClass(UserInfoActivity).putExtra("mid", mid)` |
| `ui/widget/RotaryEncoderSupport` | 3 份表冠滚动 + 1 处开关读取 | 三个 `Rotary*` 控件的公共逻辑；控件各自保留事件接入方式（监听器 vs `dispatchGenericMotionEvent`） |
| `util/ViewCapabilityProbe` | 2 处直接调 `View.hasOn*ClickListeners()` | 框架方法「本机可能有、也可能被裁掉」时的反射探测 + 降级，见 8.5 第 11 条 |
| `util/TerminalDialog` | 17 处内联 `AlertDialog.Builder`（7 菜单 + 2 单选 + 8 确认） | 26.10.05 收口；弹窗样式问题在**主题层**不在调用点，根因与迁移清单见 `docs/review/dialog-redesign-progress.md` |

> `Rotary*` 三件套的差异是**刻意保留**的：`RecyclerView`/`ScrollView` 走
> `setOnGenericMotionListener` 且滚动后抢焦点，`NestedScrollView` 走 `dispatchGenericMotionEvent`
> 覆写且**不**抢焦点。helper 用 `requestFocus` 参数区分，改它等于改手表手感。

### 8.5 这一层额外的坑

1. `InstanceActivity` 不自动绑顶栏 → 子类忘调 `setMenuClick()` 则顶栏点击无反应。
2. `CenterThreadPool.observe(future, consumer)` **空 catch 静默吞异常**（`CenterThreadPool.java:147-148`）。
3. `RefreshMainActivity.kt:43` 把 layoutManager 强转 `LinearLayoutManager`——换 StaggeredGrid 会 CCE（横屏给的 `CustomGridManager` 是 GridLayoutManager 子类，安全）。
4. 主题/密度变化触发 `recreate()`，子类 `onResume` 的一次性逻辑会重跑。
5. 覆写 `eventBusEnabled()` 返回 false 会连 sticky Snackbar 一起失效。
6. `RefreshListFragment` 的 `setRefreshing` 只切 UI，不复位状态；也没有 `hideEmptyView`。
7. `setAdapter/setRefreshing/showEmptyView` 内部已切主线程，但直接 `recyclerView.adapter =` / `notifyItemRangeInserted` 必须自己回主线程。
8. **改 `Guideline.setGuidelinePercent` 不会自动重新布局**：`Guideline.onMeasure` 恒 `setMeasuredDimension(0,0)`，自身尺寸不随 percent 变化，父级若是 `wrap_content` 的 ConstraintLayout 就不会重算，必须手动 `requestLayout()`。登录页二维码缩放（`QRLoginFragment.kt`）踩过这个坑；另外宽度变化要带动高度得靠 `app:layout_constraintDimensionRatio`。
9. **`wrap_content` 的 RelativeLayout 里不能放 `layout_alignParentBottom` 的子元素**：只要有一个"贴底"子元素，RelativeLayout 的 `wrap_content` 就会被撑成父容器高度。公共顶栏 `cell_topbar.xml` 第一版就是这么写的（1dp 分割线贴底），结果**顶栏直接吃满整屏、列表被顶到屏幕外**（真机实测 `top` bounds = `[0,114][1080,2394]`）。现在顶栏根节点是竖向 LinearLayout，内层 RelativeLayout 只放标题/时钟（`BaseActivity.setRound()` 需要 RelativeLayout.LayoutParams）。
10. **`<include>` 建议显式写 `android:layout_width/layout_height`**：不写时行为依赖被包含布局根节点的参数，排查困难。另外注意：`cell_topbar.xml` 目前**已无任何 include 引用**（那次 44 处替换被整体回滚，见 8.1 节），未来若重做收敛再照本条办。
11. **别裸调「理论上一定存在」的框架方法——部分手表框架会把它裁掉**（26.09.13 真机崩溃）。`android.view.View.hasOnLongClickListeners()` 是 API 15 就有的公开 API，`android-34` 的 class 文件里也确实有（`javap` 验证），但某手表的 `/system/framework/framework.jar` 里没有，`BaseActivity.setupTopbarLongPressToHome()` 一调就抛
    `NoSuchMethodError: No virtual method hasOnLongClickListeners()Z in class Landroid/view/View;` —— **`onStart` 里崩，页面全打不开**，且编译期、Lint、单测全都查不出来。
    统一走 `util/ViewCapabilityProbe.probeBoolean(view, "方法名") { 名, 异常 -> 日志 }`：能反射调通就走框架，调不通返回 `null` 让调用方走降级分支（自己是标志位即可），探测结论进程级缓存、只失败一次。返回 `null` 是**降级信号**不是错误，调用方必须处理。

---

### 8.6 新增一个设置项的完整链路

设置项 key 有**两套定义处**，新增时统一加在 `util/SettingsKeys.kt`（`SharedPreferencesUtil` 里那 36 个常量是历史遗留，不要重复声明）：

1. `util/SettingsKeys.kt` 加 `const val XXX = "xxx"`。
2. 对应设置页加条目：`activity/settings/SettingGroupActivity.kt`（字符串驱动，按 `desc_*` 惯例加资源；这是**唯一**该动 `strings.xml` 的地方）。
3. `activity/settings/SettingsIndex.kt` 的 `build()` 加一条 `Entry(name, desc) { ... }`——否则全局设置搜索找不到这一项。

**放哪个分组**：界面尺寸类进 `buildUIGroup()`（`group_type = "ui"`）；外观类（配色/圆角/字体）进
`buildAppearanceGroup()`（`GROUP_APPEARANCE = "appearance"`，26.09.11 新增的独立一屏，
由 `SettingGroupActivity` 的 `group_type` 分发，非独立 Activity）。若新设置需要自己的页面，
看 `AGENTS.md`「新增设置子页面有两种形态」——只是列表项就别新建 Activity。

读写统一走 `SharedPreferencesUtil.getXxx(key, default)` / `putXxx(key, value)`。
**外观类设置例外**：写入必须走 `AppearanceManager.setXxx()`，它负责递增外观版本号（见 §8.7）。

---

### 8.7 外观三模块：配色 / 卡片圆角 / 字体（26.09.11 起）

**位置**：`ui/appearance/`。三个模块**完全独立**（各自一个 key、互不干涉），
由一个门面统一收口读写与「外观已变更」通知。

| 文件 | 角色 | key | 档位 |
|---|---|---|---|
| `AppearanceManager.kt` | 门面：快照 + 版本号 + **唯一写入入口** | `appearance_version` | — |
| `ColorScheme.kt` | 配色：7 套主题（**只读模块**，原 `ui/theme/ThemeManager.kt`） | `theme_selector` | 7 套 |
| `CornerStyle.kt` | 卡片圆角 | `ui_corner_radius` | `square`（默认）/ `rounded` |
| `FontStyle.kt` | 自定义字体（用户从文件管理器选字体文件） | `ui_font_path` | 有 / 无（默认无） |
| `AppearanceApplier.kt` | 把外观（自定义字体等）落到视图树上：`applyToContentView`，由 `BaseActivity.kt:377` 调用 | — | — |

**分层约定（别打破）**
- **模块**（`CornerStyle`/`FontStyle`）只放：候选值常量、显示名、纯函数（规整、档位→数值）、读取。
  **不放写入**——写入一律走门面，这样「递增版本号」不会漏。
- **门面**只做「汇总快照 + 唯一写入 + 版本号」，**绝不做几何计算**（手表性能优先）。

**版本号机制**：`AppearanceManager.version()` 是一个存在 SharedPreferences 的 Int，
任何外观写入都 +1。Activity 只需记住自己创建时的版本号、`onResume` 比一次，
就知道要不要重建——**不会随模块增加而增加比较项**（新增第 4 个模块不需要改 `BaseActivity`）。
> 现状（26.10.04 复核）：**已接入**——`BaseActivity.kt:93` 在 `onCreate` 记录 `appliedAppearanceVersion = AppearanceManager.version()`，
> `onResume`（`:340`）发现版本号变化即 `recreate()`。新增外观模块不用改 `BaseActivity`。

**两条不可破坏的性能约定**
1. **未启用自定义字体 = 渲染路径零开销**：`FontStyle.typeface()` 返回 null 时
   `CustomFont.applyToContentView` 立即 return，一次视图树遍历都不做。这是绝大多数用户的状态。
   守卫测试：`FontStyleTest.shouldLoad_isFalseWhenNoFontConfigured`。
2. 圆角**只决定「用哪个主题属性取值」**，不做运行时几何计算，也不在 `RecyclerView` 绑定路径上做额外工作。

### 8.7.0 弹窗的主题属性接入（26.10.05 新增，改弹窗/加主题前必读）

**弹窗样式由主题属性决定，不由调用点决定。** 全工程 17 处裸 `AlertDialog.Builder` 已收口到
`util/TerminalDialog.kt`（见 §8.4），但真正让弹窗「灰底白字 + 粉按钮 + 方角」的是**主题层缺三个属性**。

**三个此前全工程 0 声明的属性**（grep `app/src/main/res` 全域）：

| 属性 | 谁在用 | 缺了会怎样 |
|---|---|---|
| `alertDialogTheme` | AlertDialog 选哪个颜色 overlay | 走 `Theme.MaterialComponents` 自带的 alert overlay，**不读本主题的定制** |
| `colorOnSurface` | Material 正文/标题文字色 | 回退 Material 默认近白 → 「白字」。**`android:colorForeground` 救不了它**（Material 正文不读 colorForeground） |
| `colorError` | 破坏性按钮色 | 删除类操作没有危险语义 |

**「粉按钮」的确切来源**：`setPositiveButton`/`setNegativeButton` 的文字色走 `colorAccent`，
这是 7 族主题里**唯一都设了**的属性，B站粉与经典终端恰好都是 `#FF6699`。
即：弹窗底色跟随 `colorSurface`（半接入），文字与按钮走 Material 默认 overlay（未接入）。

**修复形态**：`themes.xml` 里 7 份 `ThemeOverlay.<X>.Dialog`（parent 一律
`ThemeOverlay.MaterialComponents.Dialog.Alert`），各含四项：
`colorSurface` / `colorOnSurface` / `colorError` / `android:background=@drawable/dialog_background`；
每族主题加一行 `<item name="alertDialogTheme">@style/ThemeOverlay.<X>.Dialog</item>`。
`dialog_background.xml` = `solid ?attr/colorSurface` + `stroke 1dp ?attr/colorPrimary` + `corners @dimen/card_round`。

> **`Sheet.init` 里 `dialog.window?.setBackgroundDrawable(ColorDrawable(TRANSPARENT))` 不能删**：
> 不做的话 AppCompat 会在圆角 drawable 底下垫一层方角底衬，圆角白做——这正是「像安卓原生的一样」的直接原因。

> **教训（判断「某属性全工程缺失」的正确姿势）**：必须**同时 grep 相邻属性做对照组**。
> 本次最初的错误结论是「弹窗层从未接入主题系统」——被「7 族主题都声明了 `colorSurface`」
> 与「XML 侧 71 处 `?attr/colorSurface`/`colorPrimary` 引用」直接推翻。真相是**缺其中三个**，
> 不是整层缺失。只 grep 目标属性会把「半接入」误读成「未接入」，方案方向跟着全偏。

**已知遗留**：`styles.xml` 的 `CardStyle`/`ButtonStyle` 等组件样式内**全是硬编码静态色**
（`@color/card_dark` / `@color/pink` 等），切主题后不跟随；与 `docs/visual-experience-report.md`
§2.12「86 处布局引用静态调色板」同源。本次未动。

### 8.7.1 圆角的生效机制（26.09.11 落地，改圆角前必读）

**结论：圆角靠 XML 里的具体 dimen + 运行时套用，不走主题属性。**

> **走过的弯路（别再走一遍）**：最初用主题属性做运行时切换——`<item name="cardCornerRadius">?attr/appCornerRadius</item>`
> 配合 `theme.applyStyle(覆盖样式, force=true)`。**真机实测半径变成 0**：卡片角完全没被裁，
> 连原来 `@dimen/card_round` 的 6/10dp 都丢了。维度属性的 `?attr/` 间接层在这里没有解析成功。
> 该方案已整体回退（`appCornerRadius` 属性、两个覆盖样式、8 处主题兜底全部删除）。

**实际机制**
1. `CardStyle*`/`ButtonStyle*`（`styles.xml` + `themes.xml` 共 7 套）的 `cardCornerRadius`/`cornerRadius`
   一律写**具体 dimen** `@dimen/card_round`——即默认「方角」档的值。真源是 `dimens.xml`
   + `values-w300dp/dimens.xml`（手表 6dp / 宽屏 10dp）。
2. 只有选「圆角」档时才需要运行时覆盖：`CornerStyle.needsRuntimeOverride()` 为 true 时，
   `AppearanceApplier` 在视图树里把 `card_round_large`（手表 12dp / 宽屏 16dp）套上去。
   **默认档不触发任何遍历**，这就是「零开销」的落点（守卫测试 `needsRuntimeOverride_isFalseForDefaultOnly`）。
3. 覆盖对象**必须包含普通 `androidx.cardview.widget.CardView`**，不能只认 `MaterialCardView`：
   实测 `dumpsys activity top` 里两者同时存在（设置索引页的卡片就是普通 `CardView`），
   而普通 CardView 不读 `materialCardViewStyle`，XML 的 `cardCornerRadius` 对它无效，
   只有 `setRadius()` 能改。漏掉它就会出现「有的卡片跟着档位变、有的不变」。
4. 覆盖必须挂在 `ViewGroup.OnHierarchyChangeListener` 上，不能只在 `onContentChanged` 走一遍：
   `SettingMainActivity` 的卡片是在 `asyncInflate` 回调里**程序化 `addView`** 加进容器的，
   发生在遍历之后。该监听器同时覆盖 `RecyclerView` 的 item 挂载（也走 `addView`）。

**已知未覆盖**
- **10 个 shape drawable 仍直接用 `@dimen/card_round`**，不跟随档位
  （`background_card`、`background_card_borderless`、`background_edittext`×3、
  `background_grey_cardview`、`background_privatemsg_send`、`background_searchbar`、
  `background_searchhistory`）。`shape` 的 `<corners android:radius>` 读不到主题属性，
  也拿不到 Typeface 那样的运行时覆盖入口；要修得改控件（`ShapeableImageView` 等）或另做一次 drawable 遍历。
- **不走 `BaseActivity` 的三个界面**（开屏/外链/播放器）不生效。
- layout 级内联圆角（头像 28dp、投票按钮 18dp、三个 8dp cell、`item_account` 12dp）
  属「尺寸派生圆角」，按设计豁免。

**「方角」的语义（已对上游实测核实）**：上游 BiliClient（gitee `develop`，HEAD `f2b1aca`）
全项目唯一圆角是 `@dimen/card_round` = **6dp**，经主题 `materialCardViewStyle` 全局下发；
**上游没有 0dp 直角外观，也没有任何圆角设置项**。所以 `square` 档的值是 `card_round`
（手表 6dp / 宽屏 `values-w300dp` 10dp），语义是「还原原项目」，不是「做成直角」。
本项目偏离上游之处是给 6 套主题硬编码了 12dp，那是 `rounded` 档。

**默认档位的观感影响（修正早先「零变化」的说法）**
默认 `square`，对**默认主题「经典终端」是零变化**（它本来就走 `@dimen/card_round`）；
但**另外 6 套主题的卡片圆角会从 12dp 变为 6dp**——那 12dp 是主题化改造时复制 `CardStyle`
引入的漂移（5 套主题各抄了一份 12dp），不是刻意的设计取值，本次借模块化收敛回原项目取值。

### 8.7.2 自定义字体的生效机制（26.09.11 落地）

用户在设置 →「界面与外观」→「外观设置」→「自定义字体」里，用文件管理器挑一个
TTF/OTF/TTC，应用把它**拷进私有目录**（`filesDir/custom_font/custom_font.ttf`）并全局应用。

**为什么是「拷贝」而不是记住 URI**
1. `minSdk 24`，`Typeface.Builder(FileDescriptor)` 要 API 26 用不了；安全可用的只有
   `Typeface.createFromFile(File)`，它需要一个**真实路径**。
2. 用户随时可能删除/移动源文件；记 URI 还得处理 `takePersistableUriPermission`。拷一份最稳。

**为什么不用 `LayoutInflater.Factory2`（更漂亮的做法）**
`LayoutInflater.setFactory2()` 只在**从未设过** factory 时可用，否则抛 `IllegalStateException`。
而 `BaseActivity : AppCompatActivity`，AppCompat 已在 `super.onCreate()` 里装好 factory
（还带着 Material 的控件替换，`MaterialButton`/`MaterialTextView` 靠它，**圆角模块也依赖它**）。
用自己的 factory 顶掉它会让 `<Button>` 退回普通 Button、圆角失效——比字体问题严重得多。
公开 API 没有干净办法把两个 factory 串起来，所以走遍历。

**实际机制**
- `BaseActivity.onContentChanged()`（`setContentView` 之后必被触发，同时覆盖普通布局与
  `asyncInflate` 的替换布局）→ `CustomFont.applyToContentView(this)`。
- 遍历静态视图树，只对 `TextView` 调 `setTypeface`，用 `!==` 跳过已套好的。
- 列表项由 `RecyclerView` 复用、绑定发生在遍历之后，故对遍历中遇到的每个 `RecyclerView`
  挂 `OnChildAttachStateChangeListener`，只处理**新挂上来的** item 视图。
- **保留粗体/斜体**：自定义字体按 NORMAL 解析，直接 `setTypeface` 会抹掉 `android:textStyle="bold"`，
  所以按原样式派生（`Typeface.create(base, style)`，按样式缓存）。

**性能代价（手表优先，必须说清楚）**：未启用时零开销；启用后每次 `onContentChanged` 跑一次遍历。
这套代价是**用户主动开启**换来的，不是所有人付。

**已知边界**
- **不走 `BaseActivity` 的界面不生效**：`SplashActivity`、`GetIntentActivity`、`PlayerActivity`
  （三者都不继承 `BaseActivity`）。开屏无正文、外链页极简，播放器以视频为主。
- **晚建的视图**：`OnHierarchyChangeListener` 只能覆盖「父容器已被遍历过」之后加进来的子视图；
  如果整个容器本身也是遍历之后才挂上去的，那一支仍会漏。
- 只改字体外观，**不改字号**。原计划的「字号 4 档 + 字族 2 选」（token 收敛 + `scaleFactor`）
  已按需求变更取消：`ui_font_scale` / `ui_font_family` 两个 key 随之删除。

**自救入口（别删）**：「外观设置」页的「恢复系统字体」按钮是**常驻**的，不藏在「已安装」条件后面。
自定义字体最危险的失败模式是**字体缺中文字形 → 界面全变方框、文字读不了**，那时用户只能靠
这一条固定位置的按钮回到系统字体。把它改成条件显示等于把唯一退路藏起来。
（若连设置页都进不去，最后的退路是系统设置里清除应用数据。）

---

## 9. API 层映射表（40 个类，改功能时定位用）

按功能域分组。`api/` 下 38 个 Java + 2 个 Kotlin（`HotSearchApi.kt`、`ShortVideoFeedApi.kt`）。

### 视频与播放

| 类 | 职责 | 关键方法 |
|---|---|---|
| `VideoInfoApi` | 视频详情/tag/在看/AI 总结 | `getVideoInfo(String)/(long)`、`getTags`、`getInfoByJson`、`getWatching`、`getVideoConclusion` |
| `PlayerApi` | 播放地址/字幕/下载/跳外部播放器 | `getVideoDash`、`getVideo`、`getBangumi`、`getSubtitleLinks`、`jumpToPlayer` |
| `DanmakuApi` | 弹幕收发 | `sendVideoDanmakuByAid/ByBvid`、`getVideoDanmakuSegment`、`getAllVideoDanmaku` |
| `InteractionVideoApi` | 互动视频分支 | `getEdgeInfo` |
| `RecommendApi` | 推荐/热门/入站必刷/相关 | `getRecommend`、`getPopular`、`getPrecious`、`getRelated` |
| `RankingApi` | 排行榜 | `getRanking` |
| `HotSearchApi.kt` | 热搜 | `getHotSearch`、`parseHotSearch` |
| `SearchApi` | 搜索/建议/默认内容 | `search`、`searchType`、`getBangumiFromSearchResult`、`getSearchSuggestions`、`getDefaultSearchContent` |
| `HistoryApi` | 历史记录 | `getHistory`、`reportHistory`、`deleteHistory` |
| `WatchLaterApi` | 稍后再看 | `getWatchLaterList`、`add`、`delete` |
| `ShortVideoFeedApi.kt` | 短视频 Feed | `fetchFeedPage`、`fetchVideoUrl` |

### 动态、专栏与番剧

| 类 | 职责 | 关键方法 |
|---|---|---|
| `DynamicApi` | 动态列表/详情/发布/转发/点赞/@ | `getDynamicList`、`getDynamic`、`publishComplex`、`relayDynamic`、`analyzeDynamic` |
| `OpusApi` | 图文动态与专栏正文（HTML 抓取） | `getOpus`、`likeOpus`、`analyzeCommentInfo`、`analyzeParagraphs` |
| `ArticleApi` | 专栏 cv | `getArticle`、`like`、`addCoin`、`opusId2cvid` |
| `VoteApi` | 投票 | `createVote`、`doVote`、`getVoteInfo`、`parseVoteInfo` |
| `SeriesApi` | 合集/系列 | `getUserSeries`、`getSeriesInfo`、`getSeriesByJson` |
| `BangumiApi` | 番剧/追番 | `getFollowingList`、`getBangumi`、`getSections`、`getMdidFromEpid` |
| `TimelineApi` | 番剧时间表 | `getTimeline` |

### 用户与社交

| 类 | 职责 | 关键方法 |
|---|---|---|
| `UserInfoApi` | 用户/空间/关系/资料 | `getUserInfo`、`getUserSpaceInfo`、`followUser`、`updateUserInfo`、`uploadAvatar` |
| `FollowApi` | 关注/粉丝/分组 | `getFollowingList`、`getFollowerList`、`getFollowTags` |
| `ReplyApi` | 评论 | `getReplies`、`getRepliesLazy`、`getRootReply`、`getReplyCount`、`sendReply`、`sendDynamicReply`、`likeReply`、`dislikeReply`、`deleteReply`、`topReply`、`uploadReplyImage`（`actionErrorMsg` 统一错误码文案；`topActionFor` 纯函数） |
| `PrivateMsgApi` | 私信 | `getPrivateMsg`、`getSessionsList`、`sendMsg` |
| `MessageApi` | 消息中心/未读/消息设置 | `getUnread`、`checkMessageUnread`、`getLikeMsg/getReplyMsg/getAtMsg`、`getSystemMsg` |
| `EmoteApi` | 表情包 | `getEmotes`、`getEmoteTexts`、`getMyPackages`、`setPackage`、`analyzeEmotePackages` |
| `ImageApi` | 发动态/评论配图（选图压缩 → 上传图床） | `prepareImage`、`uploadImage`、`sniffImageType`（底层复用 `ReplyApi.uploadReplyImage`） |
| `VipApi` | 大会员 | `getVipInfo`、`addExperience` |
| `CreativeCenterApi` | 创作中心 | `getVideoStat`、`getBeUPTime` |
| `ExpLogApi` / `CoinLogApi` / `ElectricApi` / `LoginRecordApi` | 经验/硬币/充电/登录记录流水 | 各自一个 `getXxx()` |

### 收藏与直播

| 类 | 职责 | 关键方法 |
|---|---|---|
| `FavoriteApi` | 收藏夹 | `getFavoriteFolders`、`getFolderVideosNew`、`addFavorite`、`parseFavoriteState` |
| `LikeCoinFavApi` | 视频三连/点赞/投币 | `triple`、`like`、`coin`、`favorite`、`getVideoStats` |
| `LiveApi` | 直播 | `getRecommend`、`getFollowed`、`getRoomInfo`、`getRoomPlayInfo`、`analyzeLiveRooms` |

### 登录、鉴权与基建

| 类 | 职责 | 关键方法 |
|---|---|---|
| `LoginApi` | 登录（扫码/TV/密码/短信/SSO） | `getLoginQR`、`getLoginState`、`passwordLogin`、`smsLogin` |
| `CookiesApi` | Cookie/buvid/ticket | `checkCookies`、`genWebHeaders`、`getWebBuvids`、`genBiliTicket` |
| `CookieRefreshApi` | Web Cookie 刷新 | `cookieInfo`、`getCorrespondPath`、`refreshCookie` |
| `AppTokenRefreshApi` | access_token 续期 | `refreshAppToken` |
| `ConfInfoApi` | **WBI 签名**（全 api 层依赖） | `signWBI`、`getWBIMixinKey`、`sortUrlParams` |
| `AppInfoApi` | 公告/崩溃上报/赞助/检查更新 | `check`、`getAnnouncementList`、`uploadStack`、`getSponsors` |
| `BilibiliIDConverter` | av/bv 互转（纯函数） | ~~`bvtoaid`、`aidtobv`~~ **26.09.11 已删除**（全工程 0 调用） |

### 网络出口的 3 个例外

40 个类里只有 3 处**绕过** `NetWorkUtil` 自建 Request，因此不受它的解压/重试/Cookie 管理保护：

1. `ReplyApi.java:190-208` `uploadReplyImage`（multipart，不处理 br/gzip）
2. `UserInfoApi.java:291-303` `updateUserInfo`（自带 `decompressResponse`）
3. `UserInfoApi.java:330-348` `uploadAvatar`

三者均手工拼 Cookie 头。改这几个方法时不要假设 NetWorkUtil 的行为。

### 纯函数可测点

已抽成 static/object、只依赖 `org.json`、可直接 JVM 单测：
`HotSearchApi.parseHotSearch`、`FavoriteApi.parseFavoriteState`/`buildMediaId`、`OpusApi.analyzeCommentInfo`/`analyzeParagraphs`、`VoteApi.parseVoteInfo`、`EmoteApi.analyzeEmotePackages`、`LiveApi.analyzeLiveRooms`、`BangumiApi.analyzeSection`/`analyzeEpisode`、`VideoInfoApi.analyzeTags`/`analyzeUgcSeason`/`getInfoByJson`、`ReplyApi.analyzeReplyArray`、`SeriesApi.getSeriesByJson`、`DynamicApi.parseAtContent`、`ConfInfoApi.getWBIMixinKey`/`sortUrlParams`、`CookiesApi.hmacSha256`。（原列表里的 `BilibiliIDConverter.bvtoaid`/`aidtobv` 已于 26.09.11 随死代码删除。）

**不可单测**（内部发网络 / 依赖 Context / 弹 UI）：`DynamicApi.analyzeDynamic`、`MessageApi` 全部解析（SpannableString）、`PrivateMsgApi.getPrivateMsgList`、`LikeCoinFavApi.getVideoStats`。

**测试覆盖现状（26.10.04 批次 8 实测）**：`app/src/test/` 38 个测试类 / 327 个用例；api 层只有 10 个类的解析函数被覆盖（`HotSearchApiTest`、`FavoriteApiTest`、`FollowApiTest`、`OpusApiTest`、`PrivateMsgApiTest`、`BangumiApiTest`、`DynamicApiTest`、`TopicApiTest`、`NoteApiTest`、`WatchLaterApiTest`，另有 `PlayerApiPbpTest`、`ReplyApiTest`、`ReplySortTest` 覆盖部分纯逻辑）；`ui/appearance/` 下有 4 个测试类——`ColorSchemeTest` 覆盖 7 套主题的 `key → style` / `key → 色表` / 中文显示名映射、无 key 时的默认值、以及色表缓存的失效与读取次数；`CornerStyleTest` 覆盖圆角两档与「档位 → 覆盖样式」映射；`FontStyleTest` 覆盖字体文件头校验（含 WOFF 专门拒绝）与「未配置不加载」的性能约定；`AppearanceManagerTest` 覆盖外观版本号与唯一写入入口；`PerformanceManagerTest` 覆盖档位换算与图片/分页参数（见 §7.10）；`ApkVerifierTest` 覆盖更新包校验的判定规则（见 §7.11）；`SettingsKeysTest` 把 `player`/`play_qn`/`bangumi_update_notify_enable` 三个磁盘键名钉死（见 §7.12）；`PrivateMsgApiTest` 另覆盖会话列表解析（含 `top_ts`）、`opTypeForTop` 的 0/1 映射（见 §7.13）与图片消息 content 的字段/单位换算（见 §7.14）；`MsgNotifierTest` 覆盖新消息通知的提醒判据与正文拼装（见 §7.15）以及追番更新的文案；`BangumiApiTest` 覆盖追番列表解析（见 §7.16）；`BangumiUpdateCheckerTest` 覆盖追番快照序列化与更新判定（见 §7.16）。

### API 层的坑

1. **解析里发网络**：`DynamicApi.java:482` 在 `analyzeDynamic` 内调 `BangumiApi.getMdidFromEpid`；`PrivateMsgApi.java:54,70` 在解析里调 `UserInfoApi` → 无法纯测 + N+1 请求。
2. **解析依赖 UI/Context**：`DynamicApi.java:685`（`BiliTerminal.context`）、`MessageApi.java:128/145/232/320`（SpannableString）、`LikeCoinFavApi.java:71`（弹窗）、`AppInfoApi.java:33-85`（网络 + SharedPreferences + 弹窗混在一起）。
3. **api 类做 UI 跳转/下载**：`PlayerApi.java:44-51`（startActivity）、`53-99`（DownloadService）、`339-415`（拼 Intent）。
4. **重复实现**：视频卡片解析 **21 处**（`RankingApi`、`RecommendApi`×4、`WatchLaterApi`、`SearchApi`×3、`SeriesApi`、`FavoriteApi`×2、`UserInfoApi`、`HistoryApi`、`BangumiApi`、`MessageApi`×3、`DynamicApi`×2、`VideoInfo.java`。**本节此前写的「7 份」是过时数据**，26.08 快照写的「19 处」也偏低——26.10.04 实测 21 处（`SearchApi.java:177` 的番剧搜索分支与 `DynamicApi.java:826` 是漏计项）；`ReplyApi.sendReply` 两份；`DanmakuApi` 发送两份；`VideoInfoApi.getVideoInfo` 两份；解压逻辑 `NetWorkUtil` 已有一份、`UserInfoApi.java:354` 又写一份。**改一处记得 grep 其余几处。**
5. **参数写错**：`PlayerApi.java:308` `.put("fnvar",0)`（应为 `fnver`）；`DanmakuApi.java:93` `segment_index` 从 1 开始（`:80` 的 javadoc 却说从 0，调用点 `:133`/`:144`）。**（`ReplyApi.likeReply` 硬编码 `type=1` 已于 26.09 修复：`ReplyApi.java:296-297` 改为显式 `REPLY_TYPE_VIDEO` 的兼容重载，真实类型由 `ReplyAdapter.kt:328/347` 传入。）**
6. **硬编码**：弹幕 XML 地址重复 3 处（`PlayerApi.java:110,248,326`）；URL 散落在方法体内，无常量表。**（`AppInfoApi` 的 4 处明文 `http://` 已于 26.09.13 全部改为 `https://`，见 `AppInfoApi.java:143,162,184,207` 与 §6.4。）**
7. **全局可变状态**：`SearchApi.java:24-25` 的 `static seid/search_keyword`（多入口搜索会串）、`ConfInfoApi.java:41-43` 的 WBI 缓存、`LoginApi.java:29-30`。
8. **SharedPreferences key 混用（csrf 部分已于 26.10.04 批次 2 收敛，`player`/`play_qn` 于批次 4 收敛）**：字面量 `"csrf"`（`HistoryApi:32,84`、`WatchLaterApi:49,60`、`DanmakuApi:33`）与常量 `SharedPreferencesUtil.csrf`（`EmoteApi:50`）曾并存，现已统一改调 `NetWorkUtil.currentCsrf()`（见 §6.1）；`"player"`/`"play_qn"` 的 13 处字面量已全部改调 `SettingsKeys.PLAYER`/`PLAY_QN`，第三处定义 `SharedPreferencesUtil.player` 死字段已删（见 §7.12）。其余 key（`mid`、`player_show_viewpoints` 等）仍有字面量，尚未收敛。

---

## 10. 动手前的检查清单

1. 确认要改的类属于哪一层：`activity/`（UI）→ `api/`（网络+解析）→ `util/`（基建）→ `model/`（数据）。
2. 网络请求**必须**在 `CenterThreadPool.run {}` 里调，API 方法是阻塞的。
3. 新增页面：选基类（第 3.2 节）→ 注册 Manifest → 如需菜单入口再改 `MenuActivity.btnNames` + `loadMenuEnabled` 默认值。
4. 新增 API：加在 `api/` 下对应类里，用 `NetWorkUtil.getJson/post` + `FormData`，纯解析部分抽成 `static` 函数以便单测。
5. 改动后跑 `./gradlew.bat :app:assembleDebug`（无 CI，编译通过 + 真机手测为准）；解析逻辑改动补 `app/src/test/` 单测。
6. 改完顺手看一眼 `docs/review/fix-progress.md` 的待办，如果修掉了其中的条目就更新它。
