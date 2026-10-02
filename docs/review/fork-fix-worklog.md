# ReBiliClient 修复工作日志（参考分叉 cyq114514/Re-BiliTerminal）

> 起因：`docs/review/upstream-fork-audit.md` 完成对比审计后，开始按对方实现逐条修复。
> 约束：不修改 `docs/review/fix-progress.md` 与 `docs/review/audit-2026-09-24.md`（他人在用），本文件是本轮修复的独立记录。
> 分支：`main`，起始 HEAD `2d2b63b`（v26.09.24）。

## 修复清单与状态

图例：⬜ 未开始 · 🟨 进行中 · ✅ 代码已完成 · 🔬 已编译通过 · ⏸ 暂缓（原因见备注）

### Wave 1 — 按文件所有权分组，互不重叠，并行修复

| 组 | 负责文件 | 修复项 | 状态 |
|---|---|---|---|
| **A** 网络基础层 | `util/NetWorkUtil.java`、`api/OpusApi.java`、`util/Cookies.java`、`util/Logu.java`、`util/SharedPreferencesUtil.java`、`api/CookieRefreshApi.java` | S1 重定向白名单、S3 删 trust-all 死代码、P11 `webHeaders` volatile、P15 解压死循环、P9 OpusApi 跟跳、S2 凭证进日志、P10 SharedPreferences 空保护、`Logu.getCaller()` 栈下标、`Cookies.set()` null | ✅ |
| **B** 下载与后台线程 | `service/DownloadService.kt`、`util/FileUtil.java`、`helper/sql/DownloadSqlHelper.kt`、`util/CenterThreadPool.java`、`ErrorCatch.java`、`activity/message/NoticeActivity.kt`、`activity/message/PrivateMsgActivity.kt` | P16 删已完成文件夹、P17 TimerTask 静默、P20 volatile/synchronized、P4 数据库升级丢记录、P21 异常杀进程、P5 崩溃页、P18 消息中心三缺陷 | ✅ |
| **C1** 播放器与弹幕 | `activity/player/PlayerActivity.kt`、`player/DanmakuManager.kt`、`DanmakuFlameMaster/**` | P27 播放失败伪装成播放完毕、P28 音频切换销毁守卫、P30 释放顺序、P2 渲染线程调 JNI、P3 `join()` 无超时 | ✅ |
| **C2** 列表基类与入场页 | `activity/base/RefreshListActivity.kt`、`activity/base/RefreshListFragment.kt`、`activity/video/info/VideoInfoFragment.kt`、`activity/SplashActivity.kt` | P7 `bottom` 加 `@Volatile`、P8 `playerData` 竞态、P29 Splash 网络波动清登录态、P22 部分（点赞/投币/三连去重） | ✅ |
| **D** 番剧与进度链路 | `api/HistoryApi.java`、`api/PlayerApi.java`、`activity/video/JumpToPlayerActivity.kt`、`api/ConfInfoApi.java`、`activity/video/info/BangumiInfoFragment.kt` | P1 csrf 错位、P23 番剧进度整链路、P24 多P cid、P25 WBI、P19 空季越界、P13 FileProvider authority | ✅ |
| **E1** 评论与互动 | `activity/reply/ReplyFragment.kt`、`adapter/ReplyAdapter.kt`、`api/ReplyApi.java`、`adapter/dynamic/DynamicHolder.kt`、`activity/reply/WriteReplyActivity.kt` | P26 评论区世代号/超时/重试/线程、P22 部分（评论/动态点赞去重）、F3-h 评论点赞 `type`、F3-c 图片上传兼容 | ✅ |
| **E2** 搜索与 UI 生命周期 | `activity/search/SearchActivity.kt`、`activity/base/BaseActivity.kt`、`util/AsyncLayoutInflaterX.java`、`adapter/viewpager/ViewPagerFragmentAdapter.kt`、`api/UserInfoApi.java`、`activity/user/MySpaceActivity.kt` | P32 搜索页三项、P33 `asyncInflate` 淡入+生命周期保护、P12 ViewPager 基类、P31 exitLogin 改 POST+csrf | ✅ |

**为什么这样分组**：并行修改的前提是**同一文件只能有一个属主**。审计项本身是跨文件的（例如 P22 的点赞去重点分布在 `VideoInfoFragment`、`ReplyAdapter`、`DynamicHolder`、`PlayerActivity`），所以按**文件**切分而不是按**条目**切分，跨文件的条目由多个组各修自己那一半。七组全部并行派发，各组均被禁止越界改文件、禁止自行跑 gradle。

**跨组遗留由我在收口时补完**（见文末"收口补完"一节）：P7 漏掉的两个平级基类、P23 的 `PlayerData` 字段与接线、P24 的 `PlayerActivity` 回传 cid、P2 在短视频播放器的同根因残留。

## 已完成明细

### A 组（网络基础层）✅ 6 文件

- **S1 重定向域名白名单** — `util/NetWorkUtil.java:110-134`（`getOkHttpInstance` 拦截器 else 分支）：`HttpUrl target = request.url().resolve(location); if (target == null || !isBilibiliHost(target.host())) return response;`，命中白名单才 close 旧响应并 `chain.proceed(request.newBuilder().url(target).build())`；另加 `hops >= 5` 停止跟随。白名单在 `:164-166` + `:174-183`：`b23.tv`、`bilibili.com` 及子域、`bilibili.cn` 及子域、`.bilivideo.com`、`.hdslb.com`，以及 akamai 镜像的**精确主机名**（刻意不用 `.akamaized.net` 后缀——共享域后缀放行等于放行任意第三方）。
  用 `url().resolve()` 而非 `HttpUrl.parse()`：兼容协议相对（`//host/path`）与相对路径 Location。用 `isRedirect() && location != null` 前置判断，**命中不了白名单就原样返回该 3xx**（fail-closed，不静默放行、不 close）。
  已实测不受影响：`util/UpdateManager.kt:22` 的 123pan 配置 URL 会 302 跳到 `…cdn.123clouddisk.com`，但 `UpdateManager.kt:28-35` 用的是**独立 OkHttpClient**（不走本拦截器、不带 Cookie）。
- **S3 删除 trust-all 死代码** — `NetWorkUtil.java:147-157`：`setOkHttpSsl` 方法体收敛为 `return okhttpBuilder;`，删除 `if (SDK_INT > 22) return …` 与其后 trust-all 的 `X509TrustManager` 匿名实现 + `SSLSocketFactoryCompat` 分支和 4 个 import。保留方法签名与 `synchronized`（`helper/CustomGlideModule.kt:46` 与本类 `:89` 仍在调用）。grep 确认全项目无 `TrustAllCerts` 匹配。**`util/SSLSocketFactoryCompat.java` 现已无任何引用（仅自引用）**，不在允许修改列表故未删，建议后续清理轮次删掉。
- **P11 `webHeaders` 线程安全** — `NetWorkUtil.java:499` 改 `public static volatile ArrayList<String> webHeaders`；`:502-526` 新增 `private static ArrayList<String> buildWebHeaders()` 每次返回独立新表；`:527-529` `refreshHeaders()` 改整体替换（删掉原 `webHeaders.set(1, …)`）。copy-on-write + volatile，读线程要么拿到完整旧快照、要么完整新快照。已 grep 确认全项目**无任何调用点原地修改** `webHeaders`。
- **P15 解压死循环** — `NetWorkUtil.java:610-623`：循环内先 `if (decompresser.needsInput() || decompresser.needsDictionary()) break;`，再 `int i = decompresser.inflate(buf); if (i == 0) break;`。原有 `finally { out.close(); decompresser.end(); }` 保留。
- **P9 OpusApi 跟跳无保护** — `api/OpusApi.java:51-57`：`HttpUrl target = response.request().url().resolve(location); response.close(); if (target == null || !NetWorkUtil.isBilibiliHost(target.host())) return opus;`，复用了 A 组的 `NetWorkUtil.isBilibiliHost`。
- **S2 凭证明文进日志** — `api/CookieRefreshApi.java:83-90`：删掉 `Logu.v("新的RefreshToken", …)` 与 `Logu.v("新的cookies", …)`，改打长度；同类点 `NetWorkUtil.java:428-430` 原打印整条新 Cookie，改为只打键名。
- **P10 SharedPreferences 空保护** — `util/SharedPreferencesUtil.java` 的 `getString:67`/`getInt:78`/`getLong:87`/`getBoolean:96`/`getFloat:109` 全部改为 `sharedPreferences != null ? … : def`。`getString` 原样返回 `def`（**不把 null 换成空串**，因为 `BiliTerminal.kt:159-161` 有 `getString("force_update_*", null)` 的调用点）。
- **`Logu.getCaller()` 栈下标** — `util/Logu.java:70-88`：删掉硬编码 `getStackTrace()[4]`，改为从 `i=3` 起找第一个 className 不等于 Logu 的帧，找不到则回退最后一帧。
- **`Cookies.set()` null** — `util/Cookies.java:29-33`：`if (value == null) { cookieMap.remove(key); return; }`（原来 put(null) 会被拼成字面量 `"key=null"` 污染 Cookie 串）。

**A 组遗留**：①拦截器的 `hops` 计数实际不会累加（OkHttp 应用拦截器里 `chain.proceed` 不重入本拦截器），`hops>=5` 是防御性死逻辑，**与上游 26f1742 完全一致**；多跳目前靠调用方循环（`OpusApi` 的 `for i<5`）。②未命中白名单时返回 3xx 原响应（不 close），调用方若只读 code 不消费 body 可能连接泄漏，这是"返回原响应"的必然结果。

### B 组（下载与后台线程）✅ 7 文件 / +185 −50

- **P16 下载失败误删已下好视频** — `util/FileUtil.java` 新增 `public static void cleanDownloadTempFiles(File folder)`；`service/DownloadService.kt:1505-1516` 的 `onDestroy` 里 `FileUtil.deleteFolder(folder)` → `cleanDownloadTempFiles(folder)`。
  方案：**只删本任务的下载中间产物，绝不删正式成品** —— 只删 `<folder>` 这一层的 `*_new.mp4`、`*_new.m4a`、`*.bak` 与 `.DOWNLOADING` 标记，**不递归、不删任何目录**。原 `deleteFolder` 是递归整目录删，而 `getVideoDownloadPath(title, "")`（`util/FileUtil.java:128-132`）在 `child` 为空时返回的就是成品目录本身，单 P 项会连 `video.mp4`/`audio.m4a`/`cover.png`/`danmaku.xml` 一起删。分叉也没实现这条，故为自判方案。
- **P17 下载进度通知静默失效** — `DownloadService.kt:1020-1045`：`TimerTask.run()` 整体 `try/catch(Throwable)` + `Logu.e`。TimerTask 抛未捕获异常会**永久终止整个 Timer**。
- **P18 消息中心三缺陷** — `activity/message/NoticeActivity.kt` 整文件重写（160 行）：(a) 抽出 `loadFirstPage()`，成功/失败**两条路径都 `setRefreshing(false)`**，catch 另置 `bottom = true`（否则基类 `RefreshListActivity.kt:44` 置的 `isRefreshing=true` 永不复位）；(b) `type == null` 直接 `finish()`；system 分支显式 `cursor = null; bottom = true`；`continueLoading` 先取局部 `val cur = cursor`，不再 `cursor!!`；(c) 数据只在 `runOnUiThread` 内改，每块开头 `if (isDestroyed) return@runOnUiThread`。
  **语义取舍**：首屏失败置 `bottom = true` ⇒ 解开卡死但不再自动重试（需退出重进）。不用 `bottom = false` 是因为基类 `goOnLoad` 会先 `page++` 再回调，重试时 cursor 仍为 null（首次请求用 `(0,0)` 不消费 cursor），会产生重复条目。
- **P18 附带** — `activity/message/PrivateMsgActivity.kt:257-262` else 分支补 `isLoadingMore = false`。
- **P4 数据库升级丢记录** — `helper/sql/DownloadSqlHelper.kt:23-43`：`if (oldVersion >= newVersion) return` + 内层 try 做 ALTER，**只有 ALTER 真失败**才 drop + `onCreate`。
- **P20 竞争条件** — `DownloadService.kt:53-59` 五个字段 `started/exitCode/percent/state/section` 全加 `@Volatile`；`:432-446` 改 `@JvmStatic @Synchronized fun start(first: Long)`。
- **P21 后台异常秒杀 App** — `util/CenterThreadPool.java:79-90`：协程体 `try { runnable.run(); } catch (Throwable e) { MsgUtil.err(e); }`。按分叉做法**保留 `killProcess`**，只堵住后台任务的异常源。
- **P5 崩溃页被抢跑** — `ErrorCatch.java:45-52`：`killProcess` 前 `Thread.sleep(300)`（`startActivity` 是异步的，之前立刻杀进程崩溃页来不及起来）。

**B 组遗留**：`CenterThreadPool` 的 `THREAD_POOL` 回退分支未加兜底（`SDK_INT < JELLY_BEAN_MR1` 才走，minSdk 24 下不可达）。

### C1 组（播放器与弹幕）✅ 5 文件 / +140 −10

- **P27 播放失败被伪装成"播放完毕"** — `activity/player/PlayerActivity.kt:890-911`（`MPPrepare` 内 `setOnErrorListener`）：原来 `Logu.e` 后 `return false`，被 `ijkplayer-java/.../IjkMediaPlayer.java:1013-1017` 的 `if (!player.notifyOnError(…)) player.notifyOnCompletion();` 改判成 onCompletion（用户看到"这集看完了"+自动跳下一 P）。现在：`val firstError = !playerError; playerError = true` → 只首次 `MsgUtil.showMsgLong(EReport)` 提示 + 主线程复位播放键/暂停弹幕/更新 MediaSession → **`return true` 阻断改判**。
  配套：`:239` 新增 `playerError` 字段、`:841` `MPPrepare` 开头清错误态、`:1309-1313` `controlVideo()` 开头 `if (playerError) { retryAfterPlayerError(); autohideReset(); return }`、`:1341-1380` 新增 `retryAfterPlayerError()`（重建实例 + 续播，带销毁守卫）。所有重载路径都经 `setDisplay()`→`onReadyForPrepare`→`MPPrepare`，错误态必被清除，不会卡死。
- **P28 切听视频模式在页面销毁后重建播放器** — `:2067-2075`：第二个 `runOnUiThread` 开头加 `if (destroyed || isFinishing()) return@runOnUiThread`。原实现在 CenterThreadPool 线程 `sleep(100)` 后才回主线程 `ijkPlayer = IjkMediaPlayer()`，而 `onDestroy` 已置 `destroyed=true` 并 release/置空 ⇒ 重建出永不释放的 native 实例。
- **P30 释放顺序** — `:2049-2055`：`isPrepared/isPlaying` 提到 `stop()/release()` **之前**，release 后补 `ijkPlayer = null`。
  **能否复现的判断**：审计报告里"进度定时器读到已释放实例"**无法复现**——`progressChange()` 的 runnable 是 `mainHandler?.postDelayed(this, 250)`，`release()` 也走 `runOnUiThread`，同一主线程不可能交错。**真实窗口在弹幕回调线程**（`isPrepared` 仍 true、`ijkPlayer` 非 null 时对已 release 实例取 `currentPosition`，即 P2 那条路）；P2 修完后窗口消失。此处改动是低成本防御性加固（也防 IJK 自身回调线程），正常路径行为不变。
- **P2 弹幕渲染线程直接调 JNI** — `:1274-1281` `bindDanmakuView()` 里位置回调由 `if (isPrepared) ijkPlayer?.currentPosition ?: -1L else -1L` 改为 `if (isPrepared) video_now.toLong() else -1L`；`video_now` 加 `@Volatile`（`:194`），只由主线程 `progressChange()` 250ms 定时器写。
  `player/DanmakuManager.kt:192-206`：`updateTimer` 改为 `val pos = onCurrentPositionMs(); if (pos < 0) return; if (pos == lastTimerPos) return; lastTimerPos = pos; timer.update(pos)`；新增 `@Volatile private var lastTimerPos = -1L`（`:54-61`）；构造参数 `onCurrentPositionMs` 的 KDoc 增补"不要在本回调里直接读播放器"。
  **为什么必须去重**：`DanmakuTimer.update(pos)` 是绝对赋值（`DanmakuTimer.java:28-30`），而 DFM 在 `syncTimer` 里每帧 `timer.add(d)` 自走时钟（`DrawHandler.java:462`）。`video_now` 每 250ms 才变一次，若每帧都回灌同一个旧值，会把 DFM 自走时钟钉死成 4Hz、弹幕明显卡顿（这也是上游为何干脆把 `updateTimer` 整个置空）。去重后 = DFM 自走 + 位置真变时纠偏一次。
  **无回归论证**：短视频播放器 `ShortVideoPlayerActivity.kt` 传的是每帧都变的位置 ⇒ 去重等价于原行为；也正因不能改它的调用方式，**没有**改 `DanmakuManager` 的构造签名。
  线程依据（本次重新核实）：`DrawHandler.java:140 mUpdateInNewThread = (availableProcessors() > 3)` ⇒ 多核设备回调跑在专用 `UpdateThread("DFM Update")`，否则跑 DanmakuView 的 HandlerThread；`DanmakuView.pause()`（主线程）→ `DrawHandler.pause():601-605` → `syncTimerIfNeeded():478-481`、以及 `handleMessage(QUIT):288` 也可能在**第三个线程**触发同一回调 ⇒ 这正是 `lastTimerPos` 用 `@Volatile` 的原因（32 位设备 Long 撕裂读）。
- **P3 `join()` 无超时** — 三处全覆盖：`DanmakuFlameMaster/.../ui/widget/DanmakuView.java:170-176`（`stopDraw()`）、`.../controller/CacheManagingDrawTask.java:255-261`（`CacheManager.end()`）、`.../controller/DrawHandler.java:331-337`（`quitUpdateThread()`）：`join()` → `join(2000)` + `if (isAlive()) android.util.Log.w(…)`，每处 +5/−1 行。三个文件都没有 `import android.util.Log`，故用全限定名。
  **与上游差异**：上游 `48bbf21` 只改了 `DanmakuView.java` 与 `CacheManagingDrawTask.java`，**漏了 `DrawHandler.java`**；这里按审计报告表格把第三处补上。全仓 `grep '\.join()'`（app/src + DanmakuFlameMaster/src + ijkplayer-java，排除 build）已无无参 join。
  **是否必须动库**：是。这三处 `join()` 在库的 `release()/stopDraw()/quitUpdateThread()` 内部直接调用，项目侧没有任何钩子可绕过。

**C1 组遗留**：①`ijkPlayer = null` 后那 100ms 窗口内若触发 seek/切清晰度等老代码的 `ijkPlayer!!`，会从 use-after-release 变成 NPE（窗口极短、与上游一致）；②P27 返回 true 后错误不再触发 onCompletion，需用户点一次播放键重试（上游行为）；③DFM `join(2000)` 超时后继续往下走，线程真卡死会残留一个活线程（避免整个应用卡死的取舍）。

### C2 组（列表基类 / 视频详情页 / 入场页）✅ 4 文件

- **P7 `bottom` 加 `@Volatile`** — `activity/base/RefreshListActivity.kt:40`、`activity/base/RefreshListFragment.kt:34`（只加注解 + 中文 KDoc，未改子类）。
- **P8 `playerData` 竞态** — `activity/video/info/VideoInfoFragment.kt:133-134` `@Volatile private var playerData`；`:309-315` 后台任务改「局部变量装配 + getVideo 之后一次性发布」（**有意偏离上游**：先发布会让主线程读到 `videoUrl == null` 的半成品）；删掉原 `:283 if (playerData == null) return@run` 死代码；`:670-682 playClick()` 与 `:706-720 startDownloadFlow()` 先判空 + 校验 `data.aid != videoInfo!!.aid`；新增行为：`getVideo` 回填的 `progress == 0` 时**跳过** `reportHistory`（否则会把服务端该分P续播进度覆盖成 0，与上游 201c68f 一致）。
- **P29 Splash 网络波动清登录态** — `activity/SplashActivity.kt:60-65` 新增 `onDestroy { stopTypewriter() }`；`:95-99` 新增 `hasLocalSession()`；`:101-144` 重写 `checkCookieRefresh()`：`cookieInfo()`/`refreshCookie()` 返回 false / 任何异常一律**只记日志不清登录态**，仅 `!hasLocalSession()`（本地 Cookie 串取不到非空 `SESSDATA`）才 `resetLogin()`；`:253` 的 catch 由 `JSONException` 放宽为 `Exception`。
- **P22 部分（点赞/投币/三连去重）** — `VideoInfoFragment.kt:153-160` 三个 `@Volatile` 标志 `isLikeRequesting/isCoinRequesting/isTripleRequesting`；`:387-391`(like)、`:431-435`(coin)、`:574-575`(triple) 请求前置位、后台 `finally` 复位。
  **有意偏离**：不照抄 `WriteReplyActivity.kt:105-119` 的 `sent`（该标志只在成功返回后置位，请求途中连点仍会重复发送）；不复用 `isTripleInProgress`（长按按下时就已置 true，会把请求本身挡掉）。

**C2 组遗留**：`bottom` 从不复位是改动前就存在的行为，子类里只有 4 处 `bottom = false`（`DynamicActivity.kt:191`、`ReplyFragment.kt:312`、`ReplyInfoActivity.kt:179`、`SearchFragment.kt:167`），本轮未动。

### D 组（番剧与播放进度链路）✅ 5 文件

- **P1 csrf 错位** — `api/HistoryApi.java:115-120` 新增 `currentCsrf()`（优先从实时 Cookie 派生 `bili_jct`，取不到才回退 `SharedPreferencesUtil.csrf`）——根因是 `NetWorkUtil.saveCookiesFromResponse` 只更新 cookies 字段、不同步 csrf 快照，服务端轮换后上报必然 −111；`:35-47 reportHistory`、`:226-231 deleteHistory` 改用它；`:45-46` 接住 `okhttp3.Response`，`:147-161 logReportResult()` 用 `peekBody(1024*1024).string()` 不消费 body 地解析 `code != 0` 打 `Logu.e`。
- **P23 API 层** — `api/HistoryApi.java:52-104` 新增 `reportHistoryPgc(long aid, long cid, long epid, long seasonId, int seasonType, long progress)`，`HEARTBEAT_URL = "https://api.bilibili.com/x/click-interface/web/heartbeat"`、`HEARTBEAT_TYPE_SEASON = 4`、`HEARTBEAT_SUB_TYPES = {1,2,3,4,5,7}`，`progress <= 0` 早退，`isKnownSeasonType(seasonType)` 门控 `sub_type`。`:174` 历史列表 `type=archive` → **`type=all`**；`:186-192` business 取 `history.business` → 顶层 `business` → 缺省 archive 且只保留 archive/pgc；`:197-201` pgc 标题拼 `long_title`；`:213` pgc 卡片置 `type = "media_bangumi"`；`:243-286` 新增 `findProgressMsByAid(long aid)`（最多 5 页 type=all，命中 `oid==aid && progress>0` 返回 `progress*1000`）。
- **P23 播放侧** — `api/PlayerApi.java:328-337` `getBangumi` 改为 `getLastPlayProgress`（`:358-376`，`ConfInfoApi.signWBI("https://api.bilibili.com/x/player/wbi/v2?aid=..&cid=..")` 取 `data.last_play_time`）→ 失败兜底 `HistoryApi.findProgressMsByAid(aid)` → `normalizeProgress(…)`。**根因**：pgc playurl 的 result 不返回 `last_play_*`，沿用投稿那套读法永远得 0。
- **P24(a) 多P续播校验** — `api/PlayerApi.java:149-152`(getVideoDash) 与 `:271-274`(getVideo) 统一走 `:379-382 adoptLastPlayTime(lastPlayCid, requestCid, lastPlayTime)`（cid 不匹配或 time<=0 即返回 0）；`:390-409 normalizeProgress(raw, durationMs)` 做秒/毫秒探测 + `:44 MAX_PROGRESS_MS` 24h 上限。
- **P25 WBI** — `api/ConfInfoApi.java:61` 新增 `WBI_KEY_TTL_MS = 30L * 60 * 1000`；`:83` `signWBI` 全程 `synchronized (ConfInfoApi.class)`；`:90` 过期才重取；`:94-95` **成功后才写** `last_wbi_time`（删掉旧的先写标记——一次失败污染一整天的根因）；`:117-133 sortUrlParams` 改首个 `=` 切分；`:154-158 getDateCurr` 的 MONTH 改 `(MONTH + 1)`。
- **P19 空季越界** — `activity/video/info/BangumiInfoFragment.kt:263-273 firstSectionWithEpisodes()`、`:275-282 currentEpisode(): Bangumi.Episode?`（越界/空返回 null）、`:221-231` 起始季改用首个有剧集的季、`:284-310 getSectionChooseDialog` 拒绝切到空季、`:312-341 getEposideChooseDialog` 下标 `coerceIn`、`:343-349 refreshReplies` 走安全取值。
- **P13 FileProvider authority** — `api/PlayerApi.java:499-502 getVideoUri` 的 authority 改 `context.getPackageName() + ".FileProvider"`（对齐 `AndroidManifest.xml:45-46`）；`:473-479` 本地文件走 `getVideoUri` + `FLAG_GRANT_READ_URI_PERMISSION`。

**D 组的 VideoCard 规避代价**：不改 `model/VideoCard.java`，pgc 卡片只置 `type="media_bangumi"`（`adapter/video/VideoCardHolder.kt:63` 只需 `aid` 就能进番剧详情页），进度靠既有 `viewStr` 承载；代价是 epid/progress 不随卡片传出 ⇒ **历史列表进番剧详情页无法直接定位上次观看的集**、列表也做不了进度条。补法：`HistoryApi.java:208-213` 加两行赋值（留给 Wave 2）。

### E1 组（评论区与互动）✅ 5 文件 / +470 −149

- **P26 评论区四问题全部已修**（`activity/reply/ReplyFragment.kt`）：
  1. **缺世代号** — `:47-48` 新增 `@Volatile private var loadGeneration: Int = 0`；`refresh()` 开头 `:309 val generation = ++loadGeneration`；`continueLoading()` 记 `:189 val generation = loadGeneration`。四条异步路径全部比对：后台请求前后各一次（`:197`、`:203`）、`runOnUiThread` 内三次（`:226`、`:240`、`:259`）、失败/异常路径比对后才动界面（`:208`、`:269`、`:325`、`:332`、`:362`）。非当前世代直接 `return`，绝不碰 `replyList`。**为什么**：翻页/换排序/换视频时旧请求的响应会覆盖新列表，这就是评论区"跳来跳去"的根因。
  2. **缺超时兜底** — `:58 private const val LOAD_DEADLINE_MS = 30_000L`；`continueLoading` 算 `deadline`（`:190`），循环条件含 `System.currentTimeMillis() < deadline`（`:195`）。**每条路径都复位加载状态**（逐条核对）：`:182-184` 空载早退、`:208` 失败走 `loadFail()`（内部 `setRefreshing(false)`）、`:230`/`:251` 成功、`:262` 循环退出、`:269` 异常走 `loadFail(e)`、`:335` `!isAdded` 分支。
  3. **空页无重试** — `:213-217` 收到新返回码 `ReplyApi.PAGE_EMPTY` 时 `emptyPages++` 并 `continue`（**带原游标重试同一页**，不推进 `pagination`）；上限 `:62 MAX_EMPTY_PAGES = 5`，与 30s 截止共同封顶。**一处有意的行为偏离**：循环因重试耗尽/超时退出时设 `isEnd = true; bottom = true`（封口，`:260-261`），而上游只在 `finalEnd` 时才封口。**为什么**：否则滚动监听（基类只在 `!bottom` 时触发）会不停地重新发起加载；封口后用户下拉刷新即可恢复（`refresh()` 会把 `isEnd`/`bottom` 清回 false，`:311-312`）。
  4. **线程违规** — `:282-303 notifyReplyInserted` 整个方法体（含 `list.add`、`childMsgList.add`、`childCount++`）包进 `runOnUiThread`；`:286` 强转改 `recyclerView.layoutManager as? LinearLayoutManager ?: return@runOnUiThread`；`:289-296` 用上游的 `−1/+1` 换算（`findFirstCompletelyVisibleItemPosition()` 是 adapter 位号，第 0 位是"写评论"头部）；`:297` 楼中楼加下标保护。`adapter/ReplyAdapter.kt:392 replyList.removeAt(realPosition)` 从后台线程移进 `:387` 的 `runOnUiThread`，加 `:391` 越界保护，`:395` 计数按上游改为 `replyList.size + 1 - position`。
- **P22 部分（评论/动态点赞去重）** — `ReplyAdapter.kt:75 private val likingRpids: MutableSet<Long> = Collections.synchronizedSet(HashSet<Long>())`；`:315-318` 开头 `if (!likingRpids.add(reply.rpid)) { MsgUtil.showMsg("正在处理中"); return@setOnClickListener }`，请求体包 `try { … } finally { likingRpids.remove(reply.rpid) }`。`DynamicHolder.kt:59` 同法但**放在 companion object 里**（`:682` 判定、`:728` 清理）——**为什么**：ViewHolder 会被回收复用，实例字段会随复用被重置而失去去重效果。
- **F3-h 评论点赞硬编码 `type=1`** — `api/ReplyApi.java:311` 新增 4 参 `public static int likeReply(long oid, long root, int type, boolean action)`，arg 改为 `"&type=" + type`；`:296` 3 参重载保留并委托到 `REPLY_TYPE_VIDEO`。调用点 `ReplyAdapter.kt:328`/`:347` 改传 `replyType`。
  **能不能判断评论区类型？能，且不需要任何超出 5 个文件的改动**：`ReplyAdapter` 构造参数第 8 个就是 `val replyType: Int`，**原本就被用于 `deleteReply(oid, reply.rpid, replyType)`**，只是点赞没用上。已核实全部构造点：`ReplyFragment.kt:176` 传 `replyType`（值来自 `:120 replyType = type`），`VideoInfoActivity.kt:68/:94` 硬传 `1`，`DynamicInfoActivity.kt:47` 传 `dynamic.comment_type`（`DynamicApi.java:419`），`OpusInfoActivity.kt:68` 传 `opus.commentType`（`OpusApi.java:189`，且 `:193` 有 `if (opus.commentType == 0) opus.commentType = 17;` 兜底），`ReplyInfoActivity.kt:86/:167` 传 `type.typeCode`。**关键论证：现在点赞用的 `type` 与拉取评论列表用的 `type` 是同一个字段，所以只要能正常拉到评论，点赞的 type 就是对的。**
- **F3-h 附带（P26 空页根因）** — `api/ReplyApi.java:140-166` 重写 `getRepliesLazy` 解析块并新增返回码常量 `PAGE_ERROR=-1`/`PAGE_OK=0`/`PAGE_END=1`/`PAGE_EMPTY=2`（`:44-53`）。原来"`replies` 为空就返回 1 到底"并把 `next_offset` 丢掉，是空页无法重试的根因。现在空页时若 `isEnd || TextUtils.isEmpty(pagination)` 才返回 `PAGE_END`，否则返回 `PAGE_EMPTY` 并带回**原游标**（首页不给重试机会）。
- **F3-c 图片上传兼容** — `activity/reply/WriteReplyActivity.kt` 删除原 `compressImage`，新增 `prepareImage`（`:232-256`）分流：GIF 超 20MB 才拒、否则原样 `img_<now>.gif`+`image/gif`（`:243-245`）；PNG ≤8MB 原样 `img_<now>.png`+`image/png`（`:247-249`）；其余按最长边 2048 采样后压 JPEG 90。类型判定 `resolveImageType`（`:262-274`）优先 `contentResolver.getType()`，缺失时读 12 字节按文件头魔数嗅探（`:276-290`，GIF/PNG/JPEG/WEBP，**必须 `and 0xFF`**，Java byte 有符号）。`ReplyApi.java:231` 新增 4 参 `uploadReplyImage(imageData, fileName, mimeType, biz)`；`:218` 3 参重载保留并固定 `image/jpeg`+`BIZ_REPLY`。
  **biz 从 `new_dyn` 改为 `new_reply`**：新增常量 `ReplyApi.java:39/41 BIZ_DYNAMIC="new_dyn"`、`BIZ_REPLY="new_reply"`；调用点 `WriteReplyActivity.kt:196-198` 传 `ReplyApi.BIZ_REPLY`。**为什么**：评论图不属于动态投稿，用 `new_dyn` 会被服务端按动态图处理。另加 `getDeclaredSize`（`:298-304`）在读进内存前先探体积，超 25MB 直接拒，避免手表 OOM。
  **放弃的**：①EXIF 旋转转正（`app/build.gradle` 里没有 `exifinterface` 依赖，硬约束禁止引新库；代价是竖拍照片可能仍按原始方向上传，**与改动前行为一致，不是回归**）；②动图 WEBP 透传（上游也没做，统一走 JPEG 压缩分支）；③`buildPictures()` 的 `img_src` 字段名（审计已判定"已有且可用"，保持不动）。

**E1 组遗留**：①`getRepliesLazy` 返回码语义变了（新增 2），**全仓无其他调用方**（已 grep 确认）；②空页重试没有 sleep，极端情况下会连发 5 次请求（有封顶）；③`MsgUtil.showMsg/showMsgLong` 在后台线程调用是否安全未实测（按"内部自己 runOnUiThread"处理）。

### E2 组（搜索 / UI 生命周期 / 登录退出）✅ 6 文件 / +230 −32

- **P32 搜索页** — `activity/search/SearchActivity.kt`：`:64` 新增 `private var suggestionGeneration = 0`；`:268-269` 非空分支自增并捕获 `gen`；`:276` 回调首行 `if (gen != suggestionGeneration || refreshing || isFinishing || isDestroyed) return@runOnUiThread`（**删掉原 `keywordInput.hasFocus()` 门禁**——部分 ROM 组词期短暂失焦会把结果整包丢掉）；`:441-443` `searchKeyword` 内也自增 + 收起建议面板；`:197-202` `keywordInput.setOnClickListener { v -> v.post { imm.showSoftInput(v, SHOW_IMPLICIT) } }`（**必须先 `post`**，否则同一轮点击会撤回刚 grant 的焦点；此前全工程零 `showSoftInput`）；`:130` 条件加 `|| searchSuggestions.isEmpty()`；`:236` 建议点击前 `clearComposingText()`；`:251` `afterTextChanged` 首行 `if (refreshing) return`；`:554` `requestFragmentFocus()` 首行 `if (keywordInput.hasFocus()) return`。
- **P33 淡入 + 生命周期** — `util/AsyncLayoutInflaterX.java:282` 新增 `public static void fadeIn(View, BooleanSupplier)`（`setAlpha(0f)` + `Choreographer` 按帧驱动，`alpha = min(min(frameCount/3f, elapsed/300f), 1f)`，**不读系统动画缩放**，缩放置 0 的 ROM 也照播；3 帧下限保证至少跨 3 次绘制，300ms 只作兜底）；`:76` 新增 `private volatile boolean mCancelled`；`:80` `mHandlerCallback` 改 `final`；`:85` handleMessage 首行 `if (mCancelled) { releaseRequest(request); return true; }`；`:261-262 cancel()` 置标志 + 清 Handler 队列，删掉原先无效的 `mHandlerCallback = null;`。
  `activity/base/BaseActivity.kt:69` 新增 `private var pendingAsyncInflater: AsyncLayoutInflaterX? = null`；`:353-354` onDestroy 里 `cancel()`；`:414-420` 回调首行 `if (isDestroyed) return@inflate`、`setContentView(view)` 后 `AsyncLayoutInflaterX.fadeIn(view) { isDestroyed }`。
  **影响面**：20 个页面共用 `asyncInflate`；行为变化只有 onDestroy 多一次 `cancel()` + 回调多一层守卫 + 约 3 帧/300ms 淡入，**无签名变化**，`InflateCallBack`/`onContentChanged`/`AppearanceApplier` 均未动。已进线程池的 inflate 无法中断，只是丢弃结果——代价是一次无用 inflate，但绝不再回调宿主。
- **P12 ViewPager 基类** — `adapter/viewpager/ViewPagerFragmentAdapter.kt:24` 基类由 `FragmentStatePagerAdapter(fm)` 改为 `FragmentPagerAdapter(fm, FragmentPagerAdapter.BEHAVIOR_SET_USER_VISIBLE_HINT)`（加 `@Suppress("DEPRECATION")`）；`:36/:47/:52/:57` 新增 `instantiatedFragments` 映射 + `getFragment(position): Fragment?`。理由：`getItem()` 返回调用方共享实例，`FragmentStatePagerAdapter` 重建时会重复 add 抛 `IllegalStateException("Fragment already added")`。调用点 5 个 Activity / 6 处（`OpusInfoActivity.kt:72`、`DynamicInfoActivity.kt:51`、`LoginActivity.kt:30`、`UserInfoActivity.kt:47`、`VideoInfoActivity.kt:72` 与 `:102`），全部只用 `setAdapter + offscreenPageLimit`，**无人调用** `saveState/restoreState/getFragment/findFragmentByTag`，`VideoInfoActivity` 两分支互斥 ⇒ 不破坏调用点。
- **P31 exitLogin** — `api/UserInfoApi.java:225-236` `exitLogin()` 改返回 `boolean`：取 `bili_jct` csrf → `NetWorkUtil.post("https://passport.bilibili.com/login/exit/v2", "csrf=" + csrf, NetWorkUtil.webHeaders)` → 校验 `isSuccessful`/`body != null`/`code == 0`；`activity/user/MySpaceActivity.kt:127-154 handleLogout()` 把「服务端注销 → 清 6 项本地登录态」放进同一个 `CenterThreadPool.run{}`（消除旧 fire-and-forget 下 csrf 被提前清空的竞态），无论成败都清本地并在 UI 线程提示。

**E2 组遗留**：退出登录失败文案是否要更委婉（未定）。

## 收口补完（我在七组回报后自己做的）

七组按文件所有权切分，因此有几处**跨文件的收尾**没有任何组有权完成。这些由我在汇总时补上：

1. **P7 补两个平级基类**（C2 组报告发现审计漏了）——`activity/base/RefreshMainActivity.kt:23` 与 `activity/search/SearchFragment.kt:30` 的 `var bottom` 各加 `@Volatile` + 中文说明。`RefreshMainActivity` 的使用方是 `DynamicActivity`（写入点 `DynamicActivity.kt:211`）、`RecommendActivity`、`RecommendLiveActivity`、`HotSearchActivity`；`SearchFragment` 的写入方是 `SearchVideoFragment.kt:51/:57`、`SearchArticleFragment.kt:49/:55`、`SearchLiveFragment.kt:60/:66`、`SearchUserFragment.kt:53/:59`。**注意 `SearchFragment.bottom` 带自定义 setter**（`:31-35`，`field = value` 后按 `page` 决定 `showEmptyView` 或弹"已经到底啦OwO"），加注解时不能破坏 setter 结构。
2. **P23 接线（三步）**——D 组只交付了 API 层，`reportHistoryPgc` 交付时**零生产调用者**。补完：
   - `model/PlayerData.java`：新增 `public long epid/seasonId` 与 `public int seasonType`（放在 `cidHistory` 之后、`type` 之前），并在 `PlayerData(Parcel)`（`:42-61`）与 `writeToParcel`（`:80-99`）**末尾按同一顺序**补 `epid/seasonId/seasonType` 的读写（原本两者顺序严格对应，最后两项是 `cids` 与 `audioUrl`；`cidHistory` 与 `dashData` 一样不参与序列化）。
   - `model/Bangumi.java`：`Episode.toPlayerData()` 新增带参重载 `toPlayerData(long seasonId, int seasonType)`，设 `data.epid = id`（`Episode.id` 就是 epid）；无参重载保留并委托 `(0, 0)`。**季信息不在 Episode 上**——它挂在父级 `Bangumi.Info`（`season_id` 在 `:13`、`type` 在 `:14`，由 `api/BangumiApi.java:88`/`:92` 解析），所以必须由调用方传入。
   - `activity/video/info/BangumiInfoFragment.kt:249`：`episode.toPlayerData()` → `episode.toPlayerData(bangumi!!.info.season_id, bangumi!!.info.type)`。
   - `activity/video/JumpToPlayerActivity.kt:60-72 reportProgressAsync`：替换原 TODO，改为 `if (data.epid != 0L && data.seasonId != 0L) HistoryApi.reportHistoryPgc(data.aid, cid, data.epid, data.seasonId, data.seasonType, progressSec) else HistoryApi.reportHistory(data.aid, cid, progressSec)`。**缺 seasonId 时不冒险走心跳**（`sid=0` 会被服务端判参数错误 −400），退回投稿视频的上报方式。
3. **P24 收尾：`PlayerActivity` 必须回传 cid，且切P时要同步自己的 `cid` 字段**——D 组在 `JumpToPlayerActivity.kt:46-48` 已经读 `resultIntent.getLongExtra("cid", fallbackCid)`，但 C1 组没有在 `PlayerActivity` 里放这个 extra（grep `putExtra("cid"` 零命中），所以那段 fallback 永远走 `fallbackCid`，接线等于没生效。补完：
   - `activity/player/PlayerActivity.kt:3090-3100 finish()`：新增 `result.putExtra("cid", cid)`。
   - `:2259-2262 switchToOnlinePage` 的 `runOnUiThread` 里新增 `if (playerData.cid > 0) cid = playerData.cid`。**根因**：`switchToOnlinePage` 每次新建一个局部 `PlayerData` 并把真实 cid 放进它（`:2247 playerData.cid = cidValue`），**从不回写 Activity 的 `cid` 字段**（`:254`），而 `doSwitchPage` 后续拉弹幕、字幕、看点（`:2408 playerData.cid = cid`）以及 `finish()` 回传用的都是这个字段 ⇒ 切P后弹幕/字幕/看点/进度上报**全部还挂在切P前的那个 cid 上**。这比审计报告描述的"只影响进度回传"更严重。
4. **P2 在短视频播放器的同根因残留**（C1 组报告指出，该文件不在它的白名单内）——`activity/video/ShortVideoPlayerActivity.kt:686-691` 的 `DanmakuManager` 位置回调原来直接读 `playerBridge.currentPosition`（`player/IjkPlayerBridge.kt:203 mediaPlayer?.currentPosition`，是 **JNI**），而该回调跑在 DanmakuView 渲染线程上，与主线程重建播放器并发。改为读主线程维护的 `videoNow`（`:343-346` 加 `@Volatile` + 说明，该值由 `:831-846` 的 `state.collect` 在主线程写）。`DanmakuManager` 现在会去重，所以回调返回同一个旧值无害。

## Wave 2 — 待办

| 修复项 | 说明 | 状态 |
|---|---|---|
| P23 周期上报接线 | `reportHistoryPgc` 已有调用者（退出时上报），但还缺**播放中的周期性上报**（上游 `PROGRESS_REPORT_INTERVAL_MS = 15000`，`PlayerActivity.java:187`/`:1311-1319`）与**切P时的即时上报**（上游 `:2889-2890 reportProgressNow(true)`）。都落在 `activity/player/PlayerActivity.kt` | 🚧 子代理 `b1f2df72` |
| P23 历史列表定位上次观看集 | pgc 卡片的 `epid/progress` 不随 `VideoCard` 传出（D 组规避代价），需在 `HistoryApi.java:208-213` 补两行赋值，并让番剧详情页接住 | 🚧 子代理 `46b9c008` |
| F3 系列功能补齐 | 带图发动态、表情 type 9、转发引用原作者、发布选项、置顶/可见范围/编辑动态、评论数入口、话题页 | 🚧 子代理 `3914761b` |
| F3 图文详情接口直取 | `api/OpusApi.java` 那段被注释掉的接口实现能不能用 | 🚧 子代理 `35f9f3ca` |
| F1 后台播放 | 需 `foregroundServiceType="mediaPlayback"` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`（本项目 targetSdk 34，成本高于对方） | 🚧 调研子代理 `bbfcdaee` |
| F2 搜索番剧 | 功能类 | 🚧 子代理 `73fe7d9e` |
| F4 当版日志 / F5 版本选项卡 | 功能类 | 🚧 子代理 `2908490c` |
| P34–P38 新发现的闪退点 | 见下方「Wave 4」 | 🚧 部分已修 |
| E1 版本号单一数据源 / E3 CI | | ✅ 见 Wave 3 |
| E2 release MD5 表 | | ✅ 见 Wave 3 |

## Wave 3 — 工程规范（E3 CI 与 E1 版本一致性）✅

### E3：CI 只构建不测试，PR 完全不编译

原状：仓库只有 `.github/workflows/build-release.yml`，触发仅 `workflow_dispatch` + `push: tags:['*']`（本项目 tag 形如 `26.09.07`，**无 `v` 前缀**），`grep -nE 'test|lint|check'` 零命中 —— 112 个单测在任何自动化路径里都不执行，测试全红也能发版；而且**没有任何 PR 触发**，任何拉取请求都不编译。

- 新增 `.github/workflows/ci.yml`（`name: CI 编译与单测自检`）：`push: branches:[main]` + `pull_request: branches:[main]` + `workflow_dispatch`，`permissions: contents: read`，`concurrency` 按 ref 取消旧运行。步骤链：检出 → JDK 17（temurin）→ `android-actions/setup-android@v3` → `chmod +x gradlew` → **适配 Linux 构建环境**（与 `build-release.yml` 同一套 sed：删掉 `org.gradle.java.home` 与代理行，`-Xmx4096m`→`-Xmx2048m`，`kotlin.daemon.jvmargs -Xmx8192m`→`-Xmx2048m`，并用 `grep -nE 'java.home|proxyHost|proxyPort'` 确认无残留，有残留就 `exit 1`）→ 版本号一致性校验 → `assembleDebug` → `testDebugUnitTest` → 上传单测报告 → 上传 Debug APK（`if-no-files-found: error`）。`timeout-minutes: 45`。
- 改 `build-release.yml`：①在 `:app:assembleRelease` **之前**插入 `:app:testDebugUnitTest` —— 测试红不再能发版；②把"计算发行 tag"步骤的 inputs 全部改由 `env:`（`EVENT_NAME`/`REF_NAME`/`INPUT_TAG`/`INPUT_BODY`）传入 shell，不再直接内插 `${{ }}`，并输出 `has_body=true|false`（多行发布说明用 `body<<RELEASE_BODY_EOF` heredoc 写进 `$GITHUB_OUTPUT`）；③Release 的 `body` 与 `generate_release_notes` 改读这两个输出。原来用 `inputs.release_body == ''` 表达分支，在 tag push 事件下 inputs 恒为空、只是**碰巧**算对。
- 已校验：两份 YAML 都能被 `yaml.safe_load` 解析；手工模拟 `workflow_dispatch`（带多行 Markdown body）与 tag push（不带 body）两条路径，`$GITHUB_OUTPUT` 生成结果都正确。
- 说明：对方仓库的 `ci.yml` 触发面更宽（多了 `pull_request` 与 `tags:['v*']`，还带 Issue 模板），但**同样没有测试步骤、单测 0 个**；本项目的 CI 现在比对方多跑了 112 个单测。

### E1：版本号没有单一数据源

三处版本源：`app/build.gradle:23 versionCode 2609240` / `:24 versionName "26.09.24"`（唯一真正决定安装包的）、`app/src/main/res/values/strings.xml:342` 的更新日志锚点 `【26.09.24 本次更新】`（用户可见）、根目录 `config.json`（**被 `.gitignore` 排除**，随 APK 单独部署给更新检查接口；`versionCode`/`versionName`/`forceUpdate` 都写成了 JSON **字符串**）。实际已经漂移过：`readme.md:7` 的 badge 停在 `26.09.07`，`docs/FEATURES.md:5` 声称"适用版本 26.08.14 及后续版本"。

- `readme.md:7` badge → `version-26.09.24`。
- `docs/FEATURES.md:5` 改成诚实的基线声明（内容基线 26.08.14；之后新增的独立「外观设置」页、卡片圆角与自定义字体、滑动控制播放进度、经典终端主题、「我的」页入口自定义排序与分区、关于页改版尚未补入；版本说明以「我的 → 关于 → 更新日志」为准）。
- `app/build.gradle` 新增 `verifyVersionConsistency` 任务（`group = 'verification'`）：配置期捕获 `android.defaultConfig.versionName/versionCode` 并用 `inputs.property` / `inputs.file` 显式声明，`doLast` 校验 strings.xml 锚点与 config.json 两处；config.json 不存在就跳过（CI 上没有它）；`forceUpdate` 写成字符串时只 `logger.warn` 不失败（`UpdateManager` 已兼容，不该让 CI 因此整体变红）。**刻意不挂 `assemble`**，只挂 `check`（`tasks.matching { it.name == 'check' }.configureEach`）并由 CI 显式调用，避免本地改完版本还没同步文档时连编译都被挡住。
- 三路径实测：`--no-configuration-cache` 通过；**开启配置缓存也通过**（`Configuration cache entry stored.`）；把锚点临时改成 `26.09.25` 后 `EXIT=1` 并报 `版本号不一致：` + `src/main/res/values/strings.xml 里找不到更新日志锚点「【26.09.24 本次更新】」`；还原后 md5 与改动前一致（`0fee020acf60e673365812b1780e61bf`）、`git diff` 干净、再跑通过。
- `util/UpdateManager.kt` 加固：`parseConfig` 原来用 `json.optInt("versionCode", 0)` / `json.optBoolean("forceUpdate", false)` —— 因为 JSON 里写的是字符串，靠 `optXxx` 的字符串容错才**碰巧**读对；一旦真写成别的类型，就会被静默容错成默认值，表现为"发布了新版本客户端却查不到更新"。改为私有助手 `readIntField(json, "versionCode")` / `readBooleanField(json, "forceUpdate", false)`，兼容字符串与原生类型，无法解析时 `Logu.e(TAG, …)` 记日志再返回默认值；格式正确时行为不变。

### E2：发布产物的可追溯性 —— Release 附 MD5 校验值 ✅

原状：release 只上传 APK 本体，用户无法核对下载到的包是否完整 / 是否被替换过。上游也只在部分版本（v1.0.2-fix1 / v1.1.1 / v1.1.1-fix）提供过 MD5 表。

- `build-release.yml` 在 `:app:assembleRelease` 之后新增「生成 APK 校验值（MD5）」步骤：对 `app/build/outputs/apk/release/*.apk` 逐个算 MD5，写出**标准两列格式**的 `md5sums.txt`（用户在 APK 同目录直接 `md5sum -c md5sums.txt` 就能校验），同时把「MD5 + 文件名 + 字节数」写进 `$GITHUB_OUTPUT`。
- `md5sums.txt` 作为 Release 附件一并上传（`files:` 改成两行 glob），发布说明里也附一份 —— 两条路都留着，避免 GitHub 在 `generate_release_notes` 模式下改写 `body` 时把它弄丢。
- **两个自己踩到的坑**（已修，记下来免得再犯）：
  1. `$GITHUB_OUTPUT` 的多行值**末尾不带换行**（GitHub 会把 delimiter 前那个换行吃掉），所以拼 Markdown 代码围栏时必须显式补 `\n`，否则收尾的 ``` 会粘到最后一行校验值后面。本机用"忠实复刻 GitHub 解析语义"的脚本才复现出来 —— 直接 `println` 出来的字符串是带换行的，会漏掉这个 bug。
  2. `md5sums.txt` 一开始写成 `MD5  文件名  (4096 字节)`，第三列会让 `md5sum -c` 把 `(4096 字节)` 当成文件名的一部分而全部 FAIL。改成标准两列，尺寸只在发布说明里展示。
- 本机模拟了 tag push（无 body）与 `workflow_dispatch`（有 body）两条路径，`$GITHUB_OUTPUT` 解析正确、围栏闭合正确、`md5sum -c` 退出码 0。

## Wave 4 — 补齐审计报告里"未单独核实"的闪退点（P34–P38）

审计报告 §三 P6 表第 1 行（对方 1.1.1-fix 的"动态列表、搜索页、转发类型判断、无UP主视频详情页的闪退"）此前只有"搜索建议乱序"那半边被核实过，另半边一直标着**"本轮未单独核实"**。对方对应的提交是 `26f1742`，我把它的 diff 逐文件读完后，把这类"闪退点"在本项目逐条对了一遍：

| 编号 | 缺陷 | 本项目现状 | 处置 |
|---|---|---|---|
| **P34** | `util/DmImgParamUtil.java:113-114` 用 `List.of(...)` | ❌ **真实存在，且触发面很大** | ✅ 已修 |
| **P35** | `activity/video/info/VideoInfoActivity.kt:94` 取 `videoInfo.staff[0].mid` | ❌ 真实存在 | ✅ 已修 |
| **P36** | `adapter/dynamic/UserDynamicAdapter.kt:228` 取 `official_signs[userInfo.official]` | ❌ 真实存在 | ✅ 已修 |
| **P37** | `activity/dynamic/DynamicActivity.kt:143` 在后台线程改 `dynamicList` | ❌ 真实存在 | 🚧 派给 `3914761b` |
| **P38** | 两个动态 Adapter 的 `onViewRecycled` 不清 holder 的图片缓存 | ❌ 真实存在 | 🚧 派给 `3914761b` |
| —— | `VideoInfoFragment` 三连在后台线程改 View | ✅ **本项目已有防护**（`runOnUiThread`），上游 26f1742 才补的 | 无需处理 |
| —— | `DynamicApi.parseAtContent` 的 `Pattern.compile("@" + name)` 未转义 | ✅ **本项目已有 `Pattern.quote(key)`**（`api/DynamicApi.java:385`），连注释都和上游一样 | 无需处理 |
| —— | `DynamicApi` 里 `modules == null` 未兜底 `userInfo` → 下游 NPE | ✅ **本项目结构不同，无此问题**：`api/DynamicApi.java:594` 先 `UserInfo userInfo = new UserInfo()`，`:611` 无条件 `dynamic.userInfo = userInfo` | 无需处理 |
| —— | 对方用 Java `Map.of(...)` 在 minSdk<30 上不可用 | 本项目 `DynamicApi` 是 Java 但**全库已无 `Map.of`/`Set.of`/`List.of`**（见 P34 修完后复查） | 无需处理 |

### P34：`List.of` 是 API 30 才有的静态方法 → Android 6~10 上 `NoSuchMethodError` ✅

- `util/DmImgParamUtil.java` 里 `getDmImgParams()`（`:19-30`）无条件调用 `generateDmImgList()` 与 `generateDmImgInter()`；`generateDmImgInter()`（`:97-116`）末尾两行用了 `List.of(...)`。
- `List.of` 是 **Android 11（API 30）** 才加入 `java.util.List` 的静态方法，而本项目 `minSdk 24`，且 `app/build.gradle:85-88` 的 `compileOptions` **没有 `coreLibraryDesugaringEnabled`**（AGP 的 core library desugaring 也不覆盖 `List.of`/`Map.of`/`Set.of` 这几个）⇒ 在 Android 7~10 上执行到这两行必然 `NoSuchMethodError`。
- **可达路径**：`getDmImgParamsUrl()`（`:32-39`）← `api/UserInfoApi.java:100` 与 `:152`（取用户信息！）、`api/DynamicApi.java:503` 与 `:540`（带图发动态/发评论）。也就是说**在 Android 7~10 上打开用户信息页就会崩**。
- 修法（与上游 `26f1742` 一致）：`new JSONArray(new Object[]{f114(width, height)})` / `new JSONArray(new Object[]{f514(y, x)})`；顺带删掉因此变成未使用的 `import java.util.List;`。
- 修完后全库复查 `Map.of(` / `List.of(` / `Set.of(` / `Collectors.` / `.stream()`：**零命中**。

**教训**：这类"高版本 API 在低版本 `NoSuchMethodError`"的问题，编译器、Lint、单测**全都查不出来**（它们只校验编译期类型），只有实机或 `lint` 的 `NewApi` 检查能发现。上一轮修的 `hasOnLongClickListeners` 是同一族问题。

### P35：`videoInfo.staff[0]` 越界 → 无 UP 主的视频详情页闪退 ✅

- `api/VideoInfoApi.java:198-229`：`staff_list` 只在两种情况下被填充 —— ① `videoInfo.isCooperation == true` 且 `data` 里有非空 `staff` 数组；② `data.optJSONObject("owner") != null`。两种情况都不成立时（联合投稿的 `staff` 为空、稿件 UP 已注销/被隐藏导致 `owner` 缺失），`videoInfo.staff` 就是**空列表**。
- `activity/video/info/VideoInfoActivity.kt:94` 直接 `videoInfo.staff[0].mid` ⇒ `IndexOutOfBoundsException`，整个详情页起不来。这正是对方 release 说明里的"无UP主视频详情页闪退"。
- 修法（与上游一致）：`val upMid = if (videoInfo.staff.isNullOrEmpty()) 0L else videoInfo.staff[0].mid`，把 `upMid` 传给 `ReplyFragment.newInstance`。
- 顺带记录：`model/VideoInfo.java:108-110 toCard()` 里同样有 `staff.get(0).name`，但 **`toCard()` 全库零调用者**（死代码），本轮未动 —— 将来若启用需一并加兜底。

### P36：`official_signs[userInfo.official]` 越界 ✅

- `adapter/dynamic/UserDynamicAdapter.kt:221-229`：`official_signs` 是固定的 10 项文案数组，而 `userInfo.official` 直接来自服务端 `official.role`，**不保证落在 0~9**（上游注释明确写了"实测见过 10"）。
- 修法（与上游 `Math.max(0, Math.min(...))` 等价）：`userInfo.official.coerceIn(0, official_signs.size - 1)`。

### P37 / P38：派给子代理 `3914761b`（它独占这些文件）

- **P37** `activity/dynamic/DynamicActivity.kt:138-153`：`CenterThreadPool.run { ... dynamicList!!.add(0, dynamic) }` 在**后台线程**改数据源，`runOnUiThread { dynamicAdapter!!.notifyItemInserted(0) }` 在**主线程**通知 —— 两边无同步。上游把 `add` 移进了 `runOnUiThread`。
- **P38** `adapter/dynamic/DynamicAdapter.kt:126-127` 与 `adapter/dynamic/UserDynamicAdapter.kt:104-105` 的 `onViewRecycled` 只有 `super` 调用；上游加了 `clearImageCache()`（对方注释："复用前清掉『同 URL 跳过加载』的缓存，否则 recycled 的 holder 可能残留上一个动态的头图/配图"）。需要在 `DynamicHolder` 里补这个方法。

## 环境说明（重要）

本机是 Linux（工作目录在 Windows NTFS 分区 `/run/media/zise/ECB28636B28604F4/...`）。原 `local.properties` 与 `gradle.properties` 都指向 Windows 路径（`D:\Program Files\android-sdk`、`C:\Program Files\Java\jdk-17`），在 Linux 下无法直接构建。

**坑 1：JDK 路径。** 系统默认 java 是 25，必须显式指定 JDK 17。

**坑 2：Windows 版 SDK 在 Linux 上被 AGP 判为"已损坏"。** 用户的 SDK 是 Windows 版（`aapt.exe`/`aapt2.exe`/`aidl.exe`/`zipalign.exe`/`*.dll`/`*.bat`），AGP 在 Linux 上按**无扩展名**查找这些工具，存在性检查失败，直接报
`java.lang.IllegalStateException: Installed Build Tools revision 34.0.0 is corrupted. Remove and install again using the SDK Manager.`
（`DefaultSdkLoader.getTargetInfo(DefaultSdkLoader.java:187)` ← `SdkHandler.initTarget(SdkHandler.java:131)`）。
Linux 侧没有任何现成 Android SDK，全机器也没有 Gradle 缓存，故采取**叠加 SDK** 方案（**不改用户的 Windows SDK**）：

- 建 `/home/zise/android-sdk-linux/`：
  - `platforms/android-34` → 软链到 Windows 版（`android.jar` 26 MB、`core-for-system-modules.jar` 1.4 MB 都是跨平台 jar）
  - `licenses`、`platform-tools` → 软链
  - `build-tools/34.0.0/` → 真目录，**逐个软链** Windows 版全部文件，并补 Linux 无扩展名别名：`aapt`→`aapt.exe`、`aapt2`→`aapt2.exe`、`aidl`→`aidl.exe`、`zipalign`→`zipalign.exe`、`apksigner`→`apksigner.bat`、`d8`→`d8.bat`、`dx`→`dx.bat`、`dexdump`→`dexdump.exe`、`split-select`→`split-select.exe`、`bcc_compat`→`bcc_compat.exe`、`llvm-rs-cc`→`llvm-rs-cc.exe`、`lld`→`lld.exe`
- 可行性依据：AGP 8.x 的 aapt2 走 **Maven 版**（`com.android.tools.build:aapt2-proto:8.5.2-11315950` 与 `apksig-8.5.2.jar` 均已在缓存，实测生效），`dex` 走 AGP 自带 R8；项目**无 `.aidl` 文件、无 renderscript** ⇒ 那些假别名只用于通过存在性检查，不会被真正执行。
- **已实测跑通完整构建**：`BUILD SUCCESSFUL in 3m 1s`，`92 actionable tasks: 86 executed, 5 from cache, 1 up-to-date`，`error:` 计数 0，`:app:compileDebugKotlin`、`:app:compileDebugJavaWithJavac`、`:app:packageDebug` 全部执行，产出 4 个 split APK（`app-arm64-v8a-debug.apk` 16227729 B、`app-armeabi-v7a-debug.apk` 14727498 B、`app-universal-debug.apk` 29928635 B、`app-x86-debug.apk` 17262786 B）。

**坑 3：网络 ~200 KB/s**（直连 dl.google.com 187 KB/s、本机代理 `127.0.0.1:2080` 212 KB/s、阿里云 google 镜像反而 117 KB/s）。`settings.gradle` 已配阿里云 public + google() + mavenCentral() + jitpack，**换镜像没用，是带宽瓶颈**。依赖缓存 `~/.gradle/caches` 已到 604M，主要依赖已拉齐。

`local.properties` 的 `sdk.dir` 已改为 `/home/zise/android-sdk-linux`，**原始内容备份在 `.dsh/local.properties.windows-backup`**。

> 收工前必须还原 `local.properties`（见文末待办），否则用户回 Windows 会构建失败。

## 验证方式

```bash
cd /run/media/zise/ECB28636B28604F4/Users/ASUS/Desktop/DEVELOP/ReBiliClient

# 编译 + 单测（本机唯一可行形式）
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug :app:testDebugUnitTest \
  -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64 \
  --no-configuration-cache --no-build-cache > .dsh/logs/build2.log 2>&1
echo "EXIT=$?"          # 必须看这个，不要看管道
```

> 注意 `AGENTS.md` 的坑：改动 `res/` 文件集合后 `mergeDebugResources` 可能回放陈旧缓存，需 `clean` 与 `assemble` **分两次**调用并带 `--no-build-cache --no-configuration-cache`。
> 另外：构建日志**不要用 `| tail -N` 收尾**，管道会掩盖 gradle 的退出码（首次构建就是这样误报 exit 0）。

## 待办

- [x] Wave 1 七组全部完成
- [x] 全量编译通过（`BUILD SUCCESSFUL`，0 error）
- [x] 112 个单测全绿（`build2.log` / `build3.log`：tests=112 failures=0 errors=0 skipped=0）
- [x] Wave 1 八个修复提交（`3c1e261` … `d676a77`）+ `ci:` 提交 `09623cd` + `docs:` 提交 `31a1d3e`
- [x] Wave 3：CI 落地（E3）+ 版本号一致性守卫与文档纠偏（E1）
- [ ] 还原 `local.properties` 的 Windows 路径（备份在 `.dsh/local.properties.windows-backup`）
- [ ] 更新 `docs/review/upstream-fork-audit.md`，给已修条目打上"本项目已修"标记
- [ ] E2 剩余：release 发布说明里的 MD5 表（对方只在 v1.0.2-fix1 / v1.1.1 / v1.1.1-fix 有）
