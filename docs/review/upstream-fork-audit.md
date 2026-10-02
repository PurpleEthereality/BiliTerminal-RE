# 同源分叉对比审计：cyq114514/Re-BiliTerminal

> 生成日期：2026-10-02
> 审计对象：`https://github.com/cyq114514/Re-BiliTerminal`（克隆于 `.dsh/ref/Re-BiliTerminal`，HEAD `201c68f` v1.1.2）
> 对比基线：本项目 HEAD `2d2b63b`（v26.09.24）
> 结论：**两个分支同源于同一个提交 `2d2b63b`，之后各自演进。对方 8 个 release 中有 3 个是纯安全/稳定性修复，这些修复本项目一个都没有。**

---

## 〇、为什么这次对比有效

对方仓库的提交历史中存在 `2d2b63b chore: v26.09.24 发版，版本号更新与本次更新日志写入` —— **与本项目 HEAD 是同一个提交**。因此两边的差异不是"血统差异"，而是"同一起点后的分叉选择"：

- **本项目**走功能路线：教程系统重构、外观三模块（配色/圆角/字体）、经典终端主题。
- **对方**走安全与稳定性路线：修上报失效、修播放器卡死、修 TLS/重定向/日志泄漏、修下载记录丢失。

这不是谁对谁错。但导致的结果是：**对方已经踩过并修完的坑，本项目全部还在**。

> 说明：本文档只记录**已在对方代码中核实到修复实现**的问题。每一条都给出了对方的具体改法和本项目的现状代码位置，可直接作为修复依据。

---

## 〇·附、修复状态总览

> 本报告列出的缺陷项已参考上游分叉 `cyq114514/Re-BiliTerminal` 逐条修复完毕，代码分布在 16 个提交中。
> 逐条实现细节、每个子代理的取舍理由与验证记录见 **`docs/review/fork-fix-worklog.md`**。

| 组 | 覆盖条目 | 提交 |
|---|---|---|
| 网络基础层 | S1 / S2 / S3、P9 / P10 / P11 / P15 | `3c1e261` |
| 下载与后台线程 | P4 / P5 / P16 / P17 / P18 / P20 / P21 | `34e5bc9` |
| 播放器与弹幕 | P2 / P3 / P27 / P28 / P30 | `d292950` |
| 列表基类与视频详情页 | P7（含审计漏掉的两个平级基类）/ P8 / P29 + P22 的一半 | `fd947f0` |
| 评论区与互动 | P22（另一半）/ P26 / F3 的「点赞类型」与「图片上传」两项 | `3058512` |
| 番剧与播放进度 | P1 / P13 / P19 / P23 / P24 / P25 | `07e9705` |
| 搜索 / UI 生命周期 / 登录退出 | P12 / P31 / P32 / P33 | `d676a77` |
| CI 覆盖面（E3） | 新增 PR 与主干 CI，发版前先跑单测 | `09623cd` |
| 版本号一致性（E1）与文档纠偏 | `verifyVersionConsistency` 任务 + UpdateManager 加固 + readme/FEATURES 纠偏 | `4d88b98` `8996a1a` `f26407e` |
| 补齐审计漏核的闪退点（P6 第 1 / 6 / 7 项的复核） | P34 / P35 / P36（P37 / P38 在修） | `8cf777a` |
| Release 产物可追溯性（E2） | Release 说明与附件同时提供 `md5sums.txt` | `6b2dea1` `265a820` |
| E2 判定更正 | 崩溃堆栈上传能力本项目已有、上游反而隐藏了按钮 | `a9c15e7` |
| 报告与工作日志 | 本报告 + `docs/review/fork-fix-worklog.md` | `31a1d3e` `786d7ce` |

**验证**：`./gradlew :app:assembleDebug :app:testDebugUnitTest` 通过，**112 个单测 0 失败 0 错误**；`verifyVersionConsistency` 在
配置缓存开/关两种路径下均通过，且把更新日志锚点改错时会如实失败。

**仍未修复**（属功能差距或需产品决策，不是"已确认缺陷"）：

- **F1** 后台/熄屏继续播放、**F2** 搜索番剧、**F4** 更新后首次启动自动展示当版日志、**F5** 更新日志按版本选项卡（见 §四）。—— 这四项连同 **P23 周期上报**、**P37/P38** 已由 Wave 2 的并行子代理接手。
- **F3** 中未做的几项：转发引用原作者、发布选项（`option` JSON）、动态置顶 / 可见范围 / 编辑、动态卡片评论数入口、话题页、图文详情接口直取。（带图发动态、表情 `type 9` 渲染已并入 Wave 2）
- **P6 第 1 项已核实完毕**（见 P34–P38），**第 6 / 7 项也已核实完毕**（对方 26f1742 的主题是"主线程别读 `ijkPlayer.currentPosition`"，本项目仍有 8 处待改）；**P14** 经核实其修复理由在本项目不成立，未动。
- **P23 的周期上报**（上游 `PROGRESS_REPORT_INTERVAL_MS = 15000`）与**切P即时上报**尚未接线；目前只有"退出播放器时上报"。
- **E2** 的**远程（自动）崩溃上报**：双方都没有。—— 但需澄清：本项目**已有用户手动上传崩溃堆栈**的能力（`api/AppInfoApi.java:196 uploadStack` → `https://api.biliterminal.cn/terminal/upload/stack`，由 `activity/CatchActivity.kt:59-76` 接线），而上游反而**把这个按钮隐藏掉了**（对方 `activity/CatchActivity.java:61 btn_upload.setVisibility(View.GONE)`）。所以这一项本项目**不落后**，无需"修复"。
- ~~**E2 的 release MD5 表**~~ ✅ **已修**（`6b2dea1`）：Release 说明与附件同时提供 `md5sums.txt`。

## 一、对方 8 个 Release 的内容概览

> 对方的 tag 只有 7 个（`v1.0.2`、`v1.0.2-fix1`、`v1.1.0`、`v1.1.0-fix1`、`v1.1.1`、`v1.1.1-fix`、`v1.1.2`），**没有 `v1.0.0` tag** —— 1.0.0 与 1.0.2 被压进了同一个初始提交 `077d985 Initial commit: Re:BiliTerminal 1.0.2`。因此 1.0.0 与 1.0.2 的差异**无法逐行 diff**，下表两行取自各自的 release 正文。下表共 8 行（正文口径），tag 口径为 7 个。

| 版本 | 主要内容 | 相关提交 |
|---|---|---|
| 1.0.0 | 修复番剧播放进度无法保存；历史记录支持显示番剧；修复从历史处续播 | — |
| 1.0.2 | 播放中自动保存进度（意外退出最多丢 15 秒）；弹幕缓存；历史记录点番剧直达续播；稍后再看显示进度 | — |
| **1.0.2-fix1** | **修复番剧断点续播上报静默失效；修复跳转后弹幕卡死 / 退出播放后整个应用卡死** | `48bbf21` |
| 1.1.0 | 带图发动态 / 带图发评论；投票查看与参与；置顶/可见范围/编辑与发布选项；转发自动引用；`#话题#` 话题页；图文动态详情提速（2~3 秒 → 1 秒内） | — |
| 1.1.0-fix1 | 修复搜索建议不显示（适配B站接口结构变更）；修复联想卡片回弹、搜索后输入法无法拉起；低性能设备转场优化 | — |
| 1.1.1 | 番剧详情页新增选集（多季/OVA 快速切换、上次观看定位、全N话直达）；修复评论区偶发卡死；修复多P视频进度丢失；设置→关于新增历史更新日志 | — |
| **1.1.1-fix** | **安全加固：旧系统恢复证书校验、重定向白名单、登录凭证不再进日志**；修复下载卡死/进度异常/记录丢失；修复多个闪退点 | `26f1742` |
| 1.1.2 | 播放器支持后台/熄屏继续播放并挂出通知栏遥控；搜索新增番剧分类；修复动态/专栏评论点赞、偶发掉登录、消息中心卡死、番剧进度时好时坏等问题；播放失败可一键重试；升级网络组件修复安全漏洞 | — |

### 对方在工程规范上值得借鉴的地方

对方 release note 的**通行做法**：

1. **明确的 `versionCode` / `versionName`**（如 `versionCode 20261003 / versionName 1.1.1-fix`）
2. **ABI 明确标注**（`armeabi-v7a`、`x86`，并写明"不含 arm64"）
3. **覆盖安装说明**："同签名版本，直接覆盖安装即可，无需卸载、不会丢登录态"
4. **正文条目化**：每条都写"改了什么 + 用户可见的效果"，而不是"优化体验"这类空话

**但下面两项必须限定**（我逐条抓取 7 个 Release 正文后核实，不能笼统说"每次都提供"）：

| 项 | 实际覆盖 |
|---|---|
| MD5 校验表 | **只有 v1.0.2-fix1 / v1.1.1 / v1.1.1-fix 有**。v1.0.2、v1.1.0、v1.1.0-fix1 没有；**v1.1.2 起改由 CI 自动出包后也丢了** |
| 实机测试清单 | **只有 v1.0.2-fix1 有**（"已在 安卓9 / W527 手表 / ColorOS16 一加13 三台设备实测通过"） |

本项目在第 1–4 项上均无固定做法。

### 工程规范维度：互有胜负，不是单方面落后

| 维度 | 本项目 | 对方 |
|---|---|---|
| Release 校验值（MD5） | ❌ 无 | ◐ 3/7 个 tag 有，v1.1.2 起丢失 |
| 实机测试清单 | ❌ 无 | ◐ 仅 v1.0.2-fix1 |
| **单元测试** | ✅ **17 个测试文件 / 112 个用例** | ❌ **0 个**（全仓 `*Test*` 只命中 `app/src/main/java/com/RobinNotBad/BiliClient/activity/settings/TestActivity.java`，那是应用内 Activity，不是测试） |
| **架构文档** | ✅ **`docs/` 共 6 篇，含 675 行 `architecture-map.md`** | ❌ **无 `docs/` 目录** |
| **CI 触发面** | ◐ 仅 `workflow_dispatch` + `push: tags:['*']` → **PR 无任何自检** | ✅ `.github/workflows/ci.yml` 另含 `pull_request: branches:[main]` + `push: branches:[main]` + `push: tags:['v*']` |
| 两侧 CI 是否跑测试 | ❌ 只 `:app:assembleRelease` | ❌ 只 `:app:assembleDebug` / `:app:assembleRelease` |
| Issue 模板 | ❌ 无 | ✅ `.github/ISSUE_TEMPLATE/{bug_report,feature_request,config}.yml` |
| 构建配置可移植性 | ❌ CI 需用 `sed` 删掉 `gradle.properties` 里的 `org.gradle.java.home` 与代理（等于承认被个人环境污染） | ◐ tag 时把 keystore 从 `KEYSTORE_B64` base64 解码到 `$HOME/release.jks`，缺失则产出未签名包 |
| ABI 覆盖 | ✅ `armeabi-v7a` / `arm64-v8a` / `x86` | ◐ 仅 `armeabi-v7a` / `x86`（readme:204 自认"arm64 设备走 32 位兼容模式"、:247"极少数纯 64 位设备无法安装"） |

---

## 二、🔴 安全问题（对方已修，本项目已修 ✅）

### S1. 手动重定向可把带 Cookie 的请求转发到任意域名

> ✅ **本项目已修**（`3c1e261`）：手动跟跳改为域名白名单，非白名单域名不再转发带 Cookie 的请求

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/util/NetWorkUtil.java:88-122`

```java
.followRedirects(false)
.addInterceptor(chain -> {
    ...
    if (response.isRedirect() && location != null) {
        if (request.url().host().equals("b23.tv") && !isSslRedirect && (handler = request.tag(RedirectHandler.class)) != null) {
            handler.handleRedirect(location);
        } else {
            // 手动跟进重定向前必须先关闭原响应，否则连接泄漏
            response.close();
            Request newRequest = request.newBuilder()
                    .url(location)
                    .build();
            return chain.proceed(newRequest);   // ← 对 location 无任何域名校验
        }
    }
    return response;
})
```

**问题**：`else` 分支对 `location` **不做任何校验**就 `chain.proceed`。而 `chain.proceed` 会沿用原请求的全部头部，其中 `NetWorkUtil.java:462-484` 的 `webHeaders` 第一项就是：

```java
add("Cookie");
add(getCachedCookies());     // 登录态 Cookie
```

**已被实际触发**：`app/src/main/java/com/RobinNotBad/BiliClient/util/LinkUrlUtil.java:130` 是唯一实现了 `RedirectHandler` 的调用点：

```java
Response response = NetWorkUtil.get(url, NetWorkUtil.webHeaders, location -> handleWebURL(context, location));
```

它处理的是 **`b23.tv` 短链**，而短链的跳转目标由外部内容控制。虽然该路径下 `handler != null` 会走第一分支，但 **`b23.tv` 之外的任何域名返回的重定向都会走 else 分支**（例如视频简介中的外链经过对方服务器 302）。

**后果**：携带 `SESSDATA` / `bili_jct` 的请求可被转发到攻击者控制的域名 → 登录态整体窃取。

**对方的修复**（`26f1742`，`NetWorkUtil.java:83-94`）：

```java
HttpUrl target = HttpUrl.parse(location);
//手动跟跳必须有安全边界：目标要在 B 站域名白名单内才携带请求头跟随，
//否则原样返回（绝不把带 Cookie 的请求转发给任意域名）；
//跳数通过 request tag 累计，防恶意循环重定向打爆调用栈
if (target == null || !isBilibiliHost(target.host())) return response;
int hops = request.tag(Integer.class) != null ? request.tag(Integer.class) : 0;
if (hops >= 5) return response;
Request newRequest = request.newBuilder()
        .url(target)
        .tag(Integer.class, hops + 1)
        .build();
return chain.proceed(newRequest);
```

**注意**：本项目版本**既无域名白名单，也无跳数上限**。缺少跳数上限意味着恶意循环重定向可打爆调用栈。

---

### S2. 登录凭证明文进入日志

> ✅ **本项目已修**（`3c1e261`）：移除凭证明文日志，`Logu` 增加调用方定位

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/api/CookieRefreshApi.java:87-88`

```java
String refreshToken_new = result.getJSONObject("data").getString("refresh_token");
Logu.v("新的RefreshToken", refreshToken_new);

String cookies_new = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "");
Logu.v("新的cookies", cookies_new);          // 完整 Cookie 串（含 SESSDATA / bili_jct）
```

**问题分析**：

- `Logu.LOGV_ENABLED` 默认为 `false`（`app/src/main/java/com/RobinNotBad/BiliClient/util/Logu.java:11`），所以默认不输出。但这依赖一个**运行期可变开关**。
- 更严重的是 `Logu.w()` / `Logu.e()`（`Logu.java:30/34`，双参重载 `:58/62`）**无条件输出**，没有任何脱敏。同类调用如 `CookieRefreshApi.java:105` 的 `Logu.e("Cookie刷新失败", "新Cookie缺少DedeUserID，回退旧登录态")` 虽然当前不打印凭证本身，但**没有任何机制阻止后续有人在 `Logu.e` 里拼接凭证**。

**对方的重定向日志修复方向**：给 `Logu` 加凭证脱敏，并移除这几处调用点。对方同时还修了 `Logu.getCaller()` 的硬编码栈下标（见 §4 附带项）。

---

### S3. trust-all SSL 是死代码，但不应保留

> ✅ **本项目已修**（`3c1e261`）：trust-all 死代码删除

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/util/NetWorkUtil.java:131-152`

```java
public synchronized static OkHttpClient.Builder setOkHttpSsl(OkHttpClient.Builder okhttpBuilder) {
    if (Build.VERSION.SDK_INT > 22) return okhttpBuilder;
    try {
        final X509TrustManager trustAllCert = new X509TrustManager() {
            public void checkClientTrusted(...) { }      // 空实现
            public void checkServerTrusted(...) { }      // 空实现
            ...
        };
        okhttpBuilder.sslSocketFactory(new SSLSocketFactoryCompat(trustAllCert), trustAllCert);
```

**本项目实际不受影响**：`app/build.gradle:21` 是 `minSdk 24`，因此 `Build.VERSION.SDK_INT > 22` **恒真**，trust-all 分支**永远不会执行**。

（对方 `minSdk 19`，所以对它是真实漏洞，其修复是改用系统默认 `TrustManagerFactory`，同时保留 `SSLSocketFactoryCompat` 以维持旧设备的 TLSv1.1/1.2 兼容。）

**但建议删除**：保留这段代码会给人"这里有兼容处理"的假象。一旦将来有人把 `minSdk` 降到 22 以下，它会**立即变成真实的中间人漏洞**。

---

## 三、🔴 功能与稳定性问题（逐条核实：对方已修 / 本项目现状）

> 本节 P 系列已参考上游分叉逐条修复完毕（每条标题下附 ✅ 标记与提交号），验证方式见 `docs/review/fork-fix-worklog.md`。

### P1. 观看进度上报静默失效（csrf 与实时 Cookie 错位）

> ✅ **本项目已修**（`07e9705`）：上报用的 CSRF 改为从实时 Cookie 派生（`api/HistoryApi.java:115-120 currentCsrf()`），Cookie 轮换后不再必然 -111

**这是对方 `1.0.2-fix1` 的核心修复**，原始症状描述：

> 上报凭证不再读本地快照 `csrf` / `mid`，改为从实时 Cookie（`bili_jct` / `DedeUserID`）派生。原实现下 Cookie 轮换后两者错位，所有 POST 返回 `-111` 而 GET 正常，表现为「只有观看记录上报静默失效」——同一份代码在不同设备/登录时机表现不同（安卓9 与手表复现，ColorOS 正常）。

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/api/HistoryApi.java:32`

```java
+ "&progress=" + (progress >= 0 ? progress : "")
+ "&platform=pc"
+ "&csrf=" + SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "");
```

`:84` 的 `deleteHistory` 同样读取 `SharedPreferencesUtil.csrf`。

**根因**（已在本项目中核实）：

- `SharedPreferencesUtil.csrf` **只在登录成功或刷新 Cookie 那一刻写入**，写入点仅 4 处：`activity/settings/login/PasswordLoginFragment.kt:219`、`QRLoginFragment.kt:395`、`SMSLoginFragment.kt:270`、`SpecialLoginActivity.kt:62`，以及 `api/CookieRefreshApi.java:110`。
- 而 `NetWorkUtil.saveCookiesFromResponse`（`NetWorkUtil.java:373`）在**每一个响应**上都会把服务端轮换后的新 `bili_jct` 落进 `cookies` 字段（`:385` `saveCookiesLocked`），**却不同步 `csrf` 字段**。
- 两者一旦错位 → 所有 POST 返回 `-111`，GET 正常 → 表现为"观看记录时好时坏/完全不生效"。

**本项目内部口径不一致（重要线索）**：`api/MessageApi.java:378/406/423` **已经**使用实时 Cookie 派生：

```java
+ NetWorkUtil.getInfoFromCookie("bili_jct", ...)
```

`api/CookieRefreshApi.java:80/92` 同样。**只有 `HistoryApi` 仍读旧字段**。

全库读取 `SharedPreferencesUtil.csrf` 的位置共 **12 处**，建议逐处核对是否应改为实时派生。

**对方的修复**（`HistoryApi`）：

```java
private static String currentCsrf() {
    String csrf = NetWorkUtil.getInfoFromCookie("bili_jct",
            SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
    if (csrf != null && !csrf.isEmpty()) return csrf;
    return SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "");
}

private static long currentMid() {
    String midStr = NetWorkUtil.getInfoFromCookie("DedeUserID",
            SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
    if (midStr != null && !midStr.isEmpty()) {
        try { return Long.parseLong(midStr); }
        catch (NumberFormatException ignored) { }
    }
    return SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0);
}
```

对方在同一提交中还做了两项相关修复：

- **`start_ts` 夹到 `>= 0`**，避免设备时钟偏慢时被服务端判 `-400`。
- **上报前若 `mid` 为 0，用实时 Cookie 补一次解析**，避免已登录却被判未登录。
- **上报返回码落日志**（`logReportResult`，用 `peekBody` 读返回体不消费流），因为"上报是静默失败成本最高的调用之一"，`-101`（未登录）/`-111`（csrf失效）/`-400` 无从排查。

---

### P2. 弹幕绘制线程轮询 `getCurrentPosition()` → 退出播放后整个应用卡死

> ✅ **本项目已修**（`d292950`）：弹幕位置回调改读 `@Volatile video_now`，不再在 DanmakuView 渲染线程调 JNI；`player/DanmakuManager.kt` 的 `updateTimer` 加去重

**对方的机制描述（原文）**：

> DFM 的 `updateTimer` 回调不再轮询 `ijkPlayer.getCurrentPosition()`。该回调跑在弹幕同步/绘制线程，而 `getCurrentPosition()` 是取原生锁的 JNI 调用，既是弹幕时间轴被旧位置拽住的直接原因，也是绘制线程卡住后主线程 `release()` 内 `join()` 无限等待、整个应用卡死的共因。

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/player/DanmakuManager.kt:172-181`

```kotlin
override fun updateTimer(timer: DanmakuTimer) {
    // 回调返回负数表示"当前拿不到可信的播放位置"，此时必须**跳过**本次更新。
    // 这个守卫是必需的：IjkMediaPlayer 的 native 层在 setDataSource / prepareAsync /
    // release 期间并非线程安全，而本回调运行在 DanmakuView 的渲染线程上，与主线程
    // 重建播放器的动作并发。一旦把窗口期的脏位置灌进 timer，整批弹幕会被判定为
    // "已过期"而一条都不显示 —— 表现为间歇性的"弹幕没了"。
    val pos = onCurrentPositionMs()
    if (pos >= 0) timer.update(pos)
}
```

**问题**：本项目的注释（`:173-178`）**已经识别出了同一个根因**——"该回调运行在渲染线程"、"`IjkMediaPlayer` 的 native 层在 setDataSource / prepareAsync / release 期间并非线程安全"。但只加了"返回负数就跳过"的**症状级守卫**，没有解决"在渲染线程上做取原生锁的 JNI 调用"这个**根本问题**。守卫能过滤掉"拿不到位置"，但过滤不掉"取位置这个动作本身会阻塞"。

**对方要求的正确架构**：播放位置由**后台定时器**维护（`video_now` 字段），`updateTimer` 只读这个内存值，**绝不碰 JNI**。对方同时做了：

- 退出链路（`finish` / `onPause` / `onStop` / `onDestroy`）与 `MediaSession` 状态更新**不再在主线程调 `getCurrentPosition()`**，统一读 `video_now`。
- 新增 `pendingDanmakuSeekMs`：弹幕下载慢于取流时 `DanmakuView.seekTo` 会被静默丢弃，导致断点续播必然命中——在弹幕 `prepared` 后补做 seek。
- 新增 `syncDanmakuIfDrifted()`：以"时间轴停止推进且播放器领先"为判据，做带冷却的校正。
- 释放播放器前**先置 `isPrepared = false` 并置空引用**。

**本项目相关现状**：`PlayerActivity.kt` 全文有 22 处 `currentPosition` 直接调用（`:530/538/544/982/986/1249/1613/1836/1837/1868/1875/1934/1952/2328/2407/3001/3005` 等），其中 `:1247-1249` 已经有一条注释说明"切清晰度/切分页时主线程正在…"，说明作者已意识到主线程调用的风险，但只做了局部规避。

---

### P3. `Thread.join()` 无超时 → 主线程可被无限阻塞

> ✅ **本项目已修**（`d292950`）：`DanmakuFlameMaster` 三处 `join()` → `join(2000)`（含上游漏掉的 `controller/DrawHandler.java`）

**现状代码（三个调用点全部无超时）**：

| 文件 | 行号 | 代码 |
|---|---|---|
| `DanmakuFlameMaster/src/main/java/master/flame/danmaku/ui/widget/DanmakuView.java` | 171 | `handlerThread.join();` |
| `DanmakuFlameMaster/src/main/java/master/flame/danmaku/controller/CacheManagingDrawTask.java` | 256 | `mThread.join();` |
| `DanmakuFlameMaster/src/main/java/master/flame/danmaku/controller/DrawHandler.java` | 332 | `thread.join();` |

**对方的修复**（`CacheManagingDrawTask.java:253` 前后）：

```java
if (mThread != null) {
    try {
        //带超时：end() 在 DFM 的 QUIT 流程里被调用，而 QUIT 又会被主线程的 release() 等待，
        //缓存线程一旦卡住，整条销毁链就跟着挂死
        mThread.join(2000);
        if (mThread.isAlive())
            android.util.Log.w("CacheManager", "缓存线程未在 2s 内退出，放弃等待");
    } catch (InterruptedException e) {
        e.printStackTrace();
    }
    mThread.quit();
    mThread = null;
}
```

**为什么本项目特别容易中招**：`DanmakuFlameMaster` 是**本方 fork 进来自行维护的模块**（非外部依赖）。这意味着：

1. 本项目继承了上游 DFM 的所有阻塞缺陷；
2. **不会有任何人因为"这是库的问题"去修它**——因为它就在本仓库里，属于本方代码。

**与 P2 的关系**：P2 让绘制线程卡住，P3 让主线程在卡住时无限等待。**两者叠加才是"整个应用卡死"**，必须一起修。

---

### P4. 数据库升级导致下载记录全部丢失

> ✅ **本项目已修**（`34e5bc9`）：数据库升级只在 ALTER 真失败时才 drop 重建

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/helper/sql/DownloadSqlHelper.kt:23-36`

```kotlin
override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion != newVersion)
        try {
            if (oldVersion == 3 && newVersion == 4) {
                db.execSQL("ALTER TABLE download ADD COLUMN download_type TEXT DEFAULT 'video'")
                db.execSQL("ALTER TABLE download ADD COLUMN audio_url TEXT")
            } else {
                db.execSQL("drop table if exists download")
                onCreate(db)
            }
        } catch (e: Throwable) {
            MsgUtil.err(e)
        }
}
```

**问题**：`oldVersion == 3 && newVersion == 4` 是一个**精确相等**判断。任何 `oldVersion` 为 0/1/2 的用户升级到 4，都会落入 `else` 分支执行 **`drop table`** —— **历史下载记录全部丢失**。

**对方的修复**（改为逐级迁移 + 守卫）：

```java
if (oldVersion >= newVersion) return;
try {
    //逐级迁移：任何旧版本升上来都先尝试 ALTER 补列，保住历史下载记录；
    //只有表结构异常（ALTER 失败，如列已存在/表损坏）才降级重建
    if (oldVersion < 4) {
        try {
            db.execSQL("ALTER TABLE download ADD COLUMN download_type TEXT DEFAULT 'video'");
            db.execSQL("ALTER TABLE download ADD COLUMN audio_url TEXT");
        } catch (Throwable e) {
            db.execSQL("drop table if exists download");
            onCreate(db);
        }
    }
} catch (Throwable e) {
    MsgUtil.err(e);
}
```

关键改动：`oldVersion == 3` → **`oldVersion < 4`**；并在 `ALTER` 失败时才降级重建。

---

### P5. `ErrorCatch` 抢在崩溃页显示前杀进程

> ✅ **本项目已修**（`34e5bc9`）：`ErrorCatch` 杀进程前先 `Thread.sleep(300)`，让崩溃页有机会显示

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/ErrorCatch.java:36-51`

```java
try {
    Intent intent = new Intent(context, CatchActivity.class);
    intent.putExtra("stack", writer.toString());
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(intent);
} catch (Throwable t) {
    t.printStackTrace();
}

throwable.printStackTrace();
android.os.Process.killProcess(android.os.Process.myPid());   // ← 立即杀
```

**问题**：`startActivity` 是**异步**的。紧跟着 `killProcess` 会让 `CatchActivity` **经常来不及启动**就被杀掉——这直接削弱了本项目**唯一的崩溃现场收集手段**。

**对方的修复**：

```java
throwable.printStackTrace();
//startActivity 是异步的，立即杀进程崩溃页来不及起来；给系统一小段时间完成页面启动
try {
    Thread.sleep(300);
} catch (InterruptedException ignored) {
}
android.os.Process.killProcess(android.os.Process.myPid());
```

> 注：本项目 `ErrorCatch` 的初始化点有两处：`BiliTerminal.kt:197` 与 `BiliTerminalApp.kt:170`。后者所属的 `BiliTerminalApp` 类**从未被实例化**（参见既有审查），实际生效的是 `BiliTerminal.kt:197`。

---

### P6. 其他对方修复项：逐条核实结果

对方 `1.1.1-fix` / `1.1.2` 清单在本项目的逐条核实结果。

"审计时判定"列是**首次审计当时的结论**（✅ 已有防护 / ❌ 仍存在 / ◐ 部分），保留原样以便对照；"本轮处置"列是**参考上游修复后的最终状态**，提交号对应关系见 `## 〇·附、修复状态总览`。

| # | 对方描述 | 审计时判定 | 本轮处置 | 详见 |
|---|---|---|---|---|
| 1 | 动态列表、搜索页、转发类型判断、无UP主视频详情页的闪退 | ◐ 部分核实：**搜索建议乱序/门禁问题已确认**；动态列表、转发类型判断、无UP主视频详情页**本轮未单独核实** | ✅ 已补核实并修复：无UP主详情页 `staff[0]` 越界（P35，`8cf777a`）、动态 `official_signs` 越界（P36，`8cf777a`）、`List.of` 平台 API 误用（P34，`8cf777a`）、搜索建议乱序与门禁丢结果（P32，`d676a77`）；动态列表在后台线程突变（P37）已并入 Wave 2 | P32 / **P34–P38** |
| 2 | 后台任务异常不再直接杀死进程 | ❌ 仍存在 | ✅ 已修（`34e5bc9`）：协程体加 `try/catch(Throwable)`，`ErrorCatch` 的 `killProcess` 前加 `Thread.sleep(300)` | P21 |
| 3 | 下载：损坏文件导致解压卡死 | ❌ **仍存在（100% CPU 死循环）** | ✅ 已修（`34e5bc9`）：补 `needsInput()/needsDictionary()` 与 `i == 0` 双保险跳出 | P15 |
| 4 | 下载：HTTP 错误响应被存成视频 | ✅ **已有防护，且比对方更严** | ✅ 无需改动（本项目三条下载路径都有响应码校验，对方只补了一处） | 见下 |
| 5 | 下载：进度通知静默失效 | ❌ 仍存在 | ✅ 已修（`34e5bc9`）：`TimerTask.run()` 整体 `try/catch(Throwable)` | P17 |
| 6 | 播放器：非正常退出路径的资源释放 | ◐ 与 P2/P3 同源，本轮无新增独立证据 | ✅ 已修（`d292950`，P30）；另本轮核实本项目 `onDestroy`（`PlayerActivity.kt:1648-1655`）**无条件清理**，比上游 26f1742 的写法更彻底 | P30 |
| 7 | 播放器：快进/快退、切清晰度不再有主线程卡住的风险 | ◐ 与 P2/P3 同源，本轮无新增独立证据 | ✅ 已修（`d292950`，P2，弹幕回调线程不再直接调 JNI）；**本轮补核实**：上游 26f1742 的主题是"主线程别读 `ijkPlayer.currentPosition`"，本项目仍有 8 处，已连同 P23 一起派给子代理 | P2 / P30 / **P34–P38** |
| 8 | 番剧选集边界：空季误入、切季瞬间自动定位错位 | ❌ 仍存在（可 `IndexOutOfBoundsException`） | ✅ 已修（`07e9705`，P19） | P19 |
| 9 | 消息中心下滑加载卡死/闪退 | ❌ **仍存在（三类独立缺陷）** | ✅ 已修（`34e5bc9`，P18）：整文件重写，成功/失败两条路径都复位 | P18 |
| 10 | 打开应用时网络波动被要求重新登录 | ◐ 部分残留（DedeUserID 缺失已防，返回 false 后仍清登录态） | ✅ 已修（`fd947f0`，P29）：只有本地确实取不到 `SESSDATA` 才 `resetLogin()` | P29 |
| 11 | 退出登录改为真正在服务端注销会话 | ◐ 调了服务端，但用 GET 且无 csrf，会话不保证失效 | ✅ 已修（`d676a77`，P31）：改 POST + `csrf=`，并校验返回 `code == 0` | P31 |
| 12 | 弱网下点赞、投币、发弹幕不重复执行 | ◐ **网络层已等价、客户端无去重** | ✅ 已修（`3058512`，P22）：`VideoInfoFragment` 三个请求标志 + `ReplyAdapter`/`DynamicHolder` 的 `likingRpids` 去重 | P22 |
| 13 | 视频播放失败明确提示原因并可重试，不再误以为已播完 | ❌ **仍存在**：`onError` 返回 false 被 ijkplayer 转成 onCompletion | ✅ 已修（`d292950`，P27）：`onError` 改 `return true` 阻断误判，配套 `playerError` 标志与重试路径 | P27 |
| —— | **（我方额外发现，对方清单未列）** | | | |
| 15 | 下载失败时删除已完成视频文件夹 | ❌ 仍存在（**真实数据丢失，对方也未修**） | ✅ 已修（`34e5bc9`，P16）：改只清同层临时文件，不递归删目录 | P16 |
| 16 | `started` / `exitCode` 无 `@Volatile`、`start()` 无同步 | ❌ 仍存在 | ✅ 已修（`34e5bc9`，P20） | P20 |
| 17 | 番剧播放进度**整条链路缺失**（读不到也写不了） | ❌ **仍存在，且与对方 v1.0.0 修复对应** | ✅ 已修（`07e9705`，P23）：新增心跳接口 `reportHistoryPgc`、`type=all`、epid 定位；周期/切P即时上报并入 Wave 2 | P23 |
| 18 | 多P续播不校验 `last_play_cid` → 续播跳到错误位置 | ❌ 仍存在 | ✅ 已修（`07e9705`，P24）：`adoptLastPlayTime` + `normalizeProgress` | P24 |
| 19 | 切分P后观看记录记到旧分P | ❌ 仍存在 | ✅ 已修（`07e9705`，P24）：`finish()` 回传 `cid`、`switchToOnlinePage` 回写 Activity 的 `cid` | P24 |
| 20 | WBI 密钥按天缓存 + 先写标记后取密钥 + `sortUrlParams` 丢参数 | ❌ 仍存在 | ✅ 已修（`07e9705`，P25）：TTL 30 分钟、成功后落时间戳、类锁、首个 `=` 切分、MONTH+1 | P25 |
| 21 | 番剧续播上报用 `type=archive` → pgc 记录永不出现 | ❌ 仍存在 | ✅ 已修（`07e9705`，P23）：改 `type=all` 并按 `business` 分派 | P23 |
| 22 | 评论区无世代号/无超时/无空页重试，数据改动在主线程外 | ❌ 仍存在 | ✅ 已修（`3058512`，P26）：`loadGeneration` + 30s 超时 + 空页重试 + 数据改动移入主线程 | P26 |
| 23 | 音频模式切换无销毁守卫 → 重建播放器无人释放 | ❌ 仍存在 | ✅ 已修（`d292950`，P28） | P28 |
| 24 | 搜索建议乱序无保护 + `hasFocus` 门禁丢结果 + 输入法不弹 | ❌ 仍存在 | ✅ 已修（`d676a77`，P32）：代际号 + `post { showSoftInput }` + `clearComposingText` | P32 |
| 25 | `asyncInflate` 就绪即硬切，低性能设备过渡动画丢失 | ❌ 仍存在 | ✅ 已修（`d676a77`，P33）：`AsyncLayoutInflaterX.fadeIn()` + 取消标志 + onDestroy 保护 | P33 |

**第 4 项（HTTP 错误存成视频）的"已有防护"证据** —— 本项目三条下载路径都有响应码校验，比对方补的范围更全：

- `service/DownloadService.kt:1117-1122`（`downFileNormal`）：
  ```kotlin
  // 校验响应码：防盗链失败(403)或URL过期时会返回非2xx，错误页不能当文件写入
  if (!response.isSuccessful) {
      Logu.e("DownloadService", "下载失败，HTTP ${response.code}: ${file.name}")
      response.close()
      return ERR_NETWORK
  }
  ```
- `service/DownloadService.kt:1249`（`downFileSpeedSingle`）`if (!response.isSuccessful) return ERR_NETWORK`
- `service/DownloadService.kt:1390`（`downloadSegment`）`if (!resp.isSuccessful || resp.body == null) throw IOException("HTTP ${resp.code}")`

对方 1.1.1-fix 只补了 `downFile` 一处；本项目三处齐全。**

---

### P7. `bottom` 字段缺 `@Volatile`：后台线程写、主线程读

> ✅ **本项目已修**（`fd947f0`）：四个基类的 `bottom` 加 `@Volatile`。**审计当初漏了两个平级基类**：`activity/base/RefreshMainActivity.kt:23`、`activity/search/SearchFragment.kt:30`，本轮一并补上

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/activity/base/RefreshListActivity.kt:30` 与 `app/src/main/java/com/RobinNotBad/BiliClient/activity/base/RefreshListFragment.kt:24`

```kotlin
var bottom: Boolean = false
```

**问题**：写入点遍布约 40 个子类，且**都在网络回调（后台线程）里**，例如：

- `activity/dynamic/DynamicActivity.kt:211` `bottom = (offset == -1L)`
- `activity/message/NoticeActivity.kt:90` `bottom = cursor!!.is_end`
- `activity/search/SearchVideoFragment.kt:57` `} else bottom = true`

读取点却在主线程 —— `activity/base/RefreshListActivity.kt:81`：

```kotlin
private fun checkLoadMore() {
    if (listener == null || bottom || isLoading || swipeRefreshLayout.isRefreshing) {
```

`checkLoadMore()` 由 `onScrolled` 调用（主线程）。普通的 Kotlin `var` 没有任何 happens-before 边，主线程可能长期读到陈旧的 `false`，列表到底后仍不断触发 `goOnLoad()`。

**对方的修复**（`RefreshListActivity.java:28-29`，`RefreshListFragment.java:33`）：

```java
//加载更多监听器在子类里于后台线程写、滚动监听在主线程读，必须 volatile 保证可见性（与 RefreshListFragment.bottom 保持一致）
public volatile boolean bottom = false;
```

**本项目两处基类都缺 `@Volatile`**（Kotlin 对应写法是 `@Volatile var bottom`）。

---

### P8. `VideoInfoFragment.playerData` 竞态：后台赋值未完成时点播放 → NPE

> ✅ **本项目已修**（`fd947f0`）：`playerData` 加 `@Volatile`，并改为「局部变量装配 + 拿到 getVideo 结果后一次性发布」，避免主线程读到半成品

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/activity/video/info/VideoInfoFragment.kt`

```kotlin
124:    private var playerData: PlayerData? = null
...
281:                playerData = videoInfo!!.toPlayerData(0)
282:                PlayerApi.getVideo(playerData!!, false)
283:                if (playerData == null) return@run          // ← 死代码：282 行已 !! 解引用
284:                HistoryApi.reportHistory(videoInfo!!.aid, playerData!!.cidHistory, (playerData!!.progress / 1000).toLong())
```

三处问题：

1. `:124` 声明处无 `@Volatile`，而赋值发生在后台线程（历史上报任务）。
2. `:283` 的判空是**死代码** —— 上一行 `:282` 已经 `playerData!!`，真为 null 时先抛 NPE，永远走不到这一行。
3. `:617` 点击播放路径完全没有守卫：

```kotlin
617:        if (videoInfo!!.pagenames.size() == 1) PlayerApi.startGettingUrl(playerData!!)
```

`:647` 的 `.putExtra("data", playerData)` 同样未判空。**后台历史上报任务未完成时点播放即 NPE。**

**对方的修复**（`VideoInfoFragment.java:144` / `:646-655` / `:673-681`）：

```java
144:    private volatile PlayerData playerData;
...
646:        //playerData 由后台线程的历史上报任务赋值，任务未完成时点播放会 NPE
647:        if (playerData == null) {
                 ...（提前 return / 兜底）
655:        if (videoInfo.pagenames.size() == 1) PlayerApi.startGettingUrl(playerData);
```

且把 `:302-308` 改成先判空再解引用、并补了 `progress > 0` 条件：

```java
302:                playerData = videoInfo.toPlayerData(0);
303:                PlayerApi.getVideo(playerData, false);
304:                if (playerData == null) return;
307:                if (playerData.progress > 0)
308:                    HistoryApi.reportHistory(videoInfo.aid, playerData.cidHistory, playerData.progress / 1000);
```

---

### P9. `OpusApi` 手动跟跳：无域名白名单，且协议相对地址会直接抛异常

> ✅ **本项目已修**（`3c1e261`）：手动跟跳纳入域名白名单，并处理协议相对地址

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/api/OpusApi.java:43-48`

```java
for (int i = 0; i < 5; i++) {
    String location = response.header("Location");
    if (location == null || location.isEmpty()) break;
    response.close();
    response = NetWorkUtil.getHtml(location);      // ← location 原样当 URL 用
}
```

两个独立缺陷：

1. **无 host 校验**：`NetWorkUtil.getHtml()`（`util/NetWorkUtil.java:242-251`）会附加 `webHeaders`，其中第一项就是登录态 Cookie（`NetWorkUtil.java:462-465`）。`Location` 指向任何域名都会带着 `SESSDATA` 跟过去。这与 S1 是**同类的第二处**（S1 修的是 `NetWorkUtil` 拦截器，这里是业务层自己写的跟跳循环）。
2. **协议相对地址会崩**：`NetWorkUtil.java:245` 是 `new Request.Builder().url(url).get()`；若 `Location` 为 `//www.bilibili.com/opus/123` 这种协议相对形式，OkHttp 会抛 `IllegalArgumentException: Expected URL scheme 'http' or 'https' but no scheme was found`。

注：本处**有** 5 跳上限，不存在循环重定向打爆调用栈的问题（这一点比 S1 好）。

**对方的修复**（`OpusApi.java:56-70`）：

```java
// Location 可能是 //开头的协议相对地址（Request.Builder.url 会直接抛
// IllegalArgumentException），用 HttpUrl.resolve 基于当前 URL 解析出绝对地址；
// 这里每次 get 都带完整登录 Cookie，跳转目标必须过 B 站域名白名单，绝不把凭据跟到任意域
for (int i = 0; i < 5; i++) {
    String location = response.header("Location");
    if (location == null || location.isEmpty()) break;
    HttpUrl current = HttpUrl.parse(response.request().url().toString());
    HttpUrl target = current != null ? current.resolve(location) : null;
    response.close();
    if (target == null || !NetWorkUtil.isBilibiliHost(target.host())) {
        return opus;    //非 B 站域名的跳转不带凭据跟进，放弃抓取
    }
    response = NetWorkUtil.get(target.toString());
}
```

---

### P10. `SharedPreferencesUtil` 读取方法无空保护 → 静态初始化器内 NPE（启动即崩，且不可恢复）

> ✅ **本项目已修**（`3c1e261`）：`SharedPreferencesUtil` 读取方法补空保护

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/util/SharedPreferencesUtil.java`

```java
 61:    public static SharedPreferences sharedPreferences;
...
 67:    public static String getString(String key, String def) {
 68:        return sharedPreferences.getString(key, def);        // ← 无空检查
 69:    }
 75:    public static int getInt(String key, int def) {
 76:        return sharedPreferences.getInt(key, def);            // ← 无空检查
 83:    public static long getLong(String key, long def) { ... }  // ← 无空检查
 91:    public static boolean getBoolean(String key, boolean def) { ... }
103:    public static float getFloat(String key, float def) { ... }
```

5 个读取方法全部无空保护。而 `sharedPreferences` 直到 Application 启动才赋值（`BiliTerminal.kt:147`）：

```kotlin
144:    override fun onCreate() {
145:        super.onCreate()
146:        if (context == null) {
147:            SharedPreferencesUtil.sharedPreferences = getSharedPreferences("default", MODE_PRIVATE)
```

**触发链**：`util/NetWorkUtil.java:462-465` 的静态初始化器会去读 Cookie——

```java
462:    public static final ArrayList<String> webHeaders = new ArrayList<>() {{
463:        add("Cookie");
464:        add(getCachedCookies());                 // → SharedPreferencesUtil.getString(...)
```

而 `getCachedCookies()`（`NetWorkUtil.java:54-61`）在 `cachedCookies == null` 时调 `SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")`。

**只要有任何代码在 `BiliTerminal.onCreate` 第 147 行之前触碰到 `NetWorkUtil`（例如新增一个 ContentProvider、提前引用网络工具类），就会在静态初始化器里抛 NPE。** 静态初始化器抛出的异常会被包装成 `ExceptionInInitializerError`，之后该类在本进程内**永久不可用** —— 不是单次崩溃，而是整个进程的网络功能报废。

**严重度说明（避免夸大）**：本项目**当前**的启动顺序下未观察到该崩溃，属于**潜伏缺陷**：它由类加载顺序决定，任何一次看似无关的重构都可能把它变成"启动即崩"。

**对方的修复**（`SharedPreferencesUtil.java:55-59`）：

```java
//所有读取方法都做空保护：webHeaders 等静态初始化器会在 Application.onCreate 赋值 sharedPreferences
//之前触发类加载，不保护的话一个读取顺序变化就是启动即 NPE
public static String getString(String key, String def) {
    return sharedPreferences != null ? sharedPreferences.getString(key, def) : def;
}
```

（`getInt`/`getLong`/`getBoolean` 同样逐一加了空保护。）

---

### P11. `NetWorkUtil.webHeaders`：可变 ArrayList 跨线程共享，无同步、非 volatile

> ✅ **本项目已修**（`3c1e261`）：`webHeaders` 共享可变列表改为不可变快照

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/util/NetWorkUtil.java`

```java
462:    public static final ArrayList<String> webHeaders = new ArrayList<>() {{ ... }};
...
485:    public static void refreshHeaders() {
486:        webHeaders.set(1, getCachedCookies());     // ← 原地改写共享可变对象
487:    }
```

`webHeaders` 被所有网络请求读取（`NetWorkUtil.java:246-247` 逐对遍历成 header、`:171` 拷贝、`:233`/`:356`/`:360` 直接透传），同时又被 `refreshHeaders()`（登录/刷新 Cookie 时经 `setCookiesString()`，`:66-70`）在别的线程原地改写索引 1。

`ArrayList` 本身不保证可见性，字段也既非 `volatile` 也非不可变 —— 登录后其他线程可能继续读到旧 Cookie。

**对方的修复**：`public static volatile ArrayList<String> webHeaders`（`NetWorkUtil.java:480`），且初始化不再依赖 `NetWorkUtil` 自身其它静态状态（见 P10）。

---

### P12. `ViewPagerFragmentAdapter` 基类选错 → 页面重建时 `Fragment already added` 崩溃

> ✅ **本项目已修**（`d676a77`）：`ViewPagerFragmentAdapter` 基类改 `FragmentPagerAdapter` 并复用已实例化的 Fragment，消除 `Fragment already added`

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/adapter/viewpager/ViewPagerFragmentAdapter.kt:7-15`

```kotlin
class ViewPagerFragmentAdapter(fm: FragmentManager, private val fragmentList: List<Fragment>) :
    FragmentStatePagerAdapter(fm) {

    val fm: FragmentManager = fm

    override fun getItem(position: Int): Fragment {
        if (position < 0 || position >= fragmentList.size) {
            return Fragment()
        }
        return fragmentList[position]        // ← 返回调用方共享的实例
    }
```

`getItem()` 返回的是**调用方传入的共享 Fragment 实例**，而 `FragmentStatePagerAdapter` 在页面销毁重建或状态恢复时会**重新调用 `getItem()`** —— 把同一个实例再 `add` 一次，抛 `IllegalStateException("Fragment already added")`。

使用点共 5 个 Activity：`activity/article/OpusInfoActivity.kt:72`、`activity/dynamic/DynamicInfoActivity.kt:51`、`activity/settings/login/LoginActivity.kt:30`、`activity/user/info/UserInfoActivity.kt:47`、`activity/video/info/VideoInfoActivity.kt:72` 与 `:102`。

**对方的修复**：换基类为 `FragmentPagerAdapter(fm, BEHAVIOR_SET_USER_VISIBLE_HINT)`，并加了 `instantiatedFragments` 映射与 `getFragment(position)` 访问器。对方源码里的注释说明了理由：

```java
//基类用 FragmentPagerAdapter 而不是 FragmentStatePagerAdapter：
//1. 这里的翻页都是固定少量页（登录/详情/搜索等 2~4 个 tab），本就该常驻；
//2. FragmentStatePagerAdapter 在页面销毁重建或恢复时会重新调 getItem()，
//   而 getItem 返回的是调用方共享的实例，重复 add 会抛 IllegalStateException("Fragment already added")；
//   FragmentPagerAdapter 会先按 tag 找回 FragmentManager 里的已有实例并 attach，不会重复添加。
```

---

### P13. 外部播放器播放本地视频：未走 FileProvider 授权，且 `getVideoUri()` 是死代码 + authority 拼错

> ✅ **本项目已修**（`07e9705`）：authority 改为 `context.getPackageName() + ".FileProvider"`，本地视频改走 FileProvider URI + 读权限授权

这一条对应 P6 表第 14 项，已核实：**本项目仍存在**。

**现状代码 1**：`app/src/main/java/com/RobinNotBad/BiliClient/api/PlayerApi.java:395`（`aliangPlayer` 分支）

```java
intent.setData(Uri.parse(playerData.videoUrl));      // ← 无条件走 Uri.parse

if (!playerData.isLocal()) {                          // ← 只在非本地时补头部
    Map<String, String> headers = new HashMap<>();
    headers.put("Cookie", SharedPreferencesUtil.getString("cookies", ""));
    ...
}
```

本地视频时**没有任何 FileProvider 分支、也没有 `FLAG_GRANT_READ_URI_PERMISSION`**，直接把裸路径交给外部播放器；在 targetSdk 34 + 分区存储下外部应用无权限读取该文件。

**现状代码 2**：`app/src/main/java/com/RobinNotBad/BiliClient/api/PlayerApi.java:417-419`

```java
public static Uri getVideoUri(Context context, String path) {
    File file = new File(path);
    return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
```

两个问题叠加：

- **全项目无任何调用点** —— `getVideoUri` 只在自身声明处出现（`grep -rn "getVideoUri" app/src/main` 仅命中 `PlayerApi.java:417`），是死代码。
- **authority 拼写与清单不符，一旦被调用就抛异常**。清单 `app/src/main/AndroidManifest.xml:45-46` 声明的是：

```xml
<provider android:name="androidx.core.content.FileProvider"
    android:authorities="com.RobinNotBad.BiliClient.FileProvider"
```

即 `...BiliClient.FileProvider`（大写 F），而 `getVideoUri()` 拼的是 `...BiliClient.fileprovider`（小写 f）。`FileProvider.getUriForFile` 在找不到匹配 authority 时抛 `IllegalArgumentException: Couldn't find meta-data for provider with authority com.RobinNotBad.BiliClient.fileprovider`。

> 附带证据：本项目其它两处 FileProvider 调用用的是**正确**的大写形式 —— `activity/DownloadActivity.kt:177` `packageName + ".FileProvider"`、`util/UpdateManager.kt:235` `"${context.packageName}.FileProvider"`。也就是说同一份代码里存在两种拼法，只有 `PlayerApi` 这一处是错的。

**对方的修复**（`PlayerApi.java:293-299` / `:309-310`）：

```java
if (playerData.isLocal()) {
    intent.setData(getVideoUri(context, playerData.videoUrl));
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
} else {
    intent.setData(Uri.parse(playerData.videoUrl));
    ...（Cookie / Referer / agent / progress）
}
...
public static Uri getVideoUri(Context context, String path) {
    return FileProvider.getUriForFile(context, context.getPackageName() + ".FileProvider", new File(path));
}
```

（authority 改为与清单一致的 `.FileProvider`。）

---

### P14. 直播弹幕 WebSocket 自建 `OkHttpClient`（严重度低，但对方的修复理由在本项目不成立）

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/activity/player/PlayerActivity.kt:1525` / `:1539`

```kotlin
1525:                okHttpClient = OkHttpClient()                                  // ← 每次进直播间新建
1539:                liveWebSocket = okHttpClient!!.newWebSocket(request, listener)
```

**真实差异**（只说站得住的）：不走 `NetWorkUtil.getOkHttpInstance()`（`util/NetWorkUtil.java:85-128`），因此少了该实例的 `connectTimeout(8s)` / `readTimeout(16s)` 调优，也不共享连接池与调度器，每次进直播间多建一套线程与连接资源。

**对方给出的两条理由在本项目不成立，需澄清**：对方改法与说明是"复用全局客户端（补旧系统 TLS 兼容与超时）"。但

- **旧系统 TLS 这条对 minSdk 24 无意义**：`util/NetWorkUtil.java:131-133` 的 `setOkHttpSsl()` 第一行就是 `if (Build.VERSION.SDK_INT > 22) return okhttpBuilder;` —— 本项目 minSdk 24，该分支永不执行。对方 minSdk 19 才有这个问题。
- **超时是真实差异但影响面小**：默认值与 8s/16s 不同，不构成功能缺陷。

**建议**：改为复用全局实例（顺带消除每直播间新建开销），但**不必**把它当成安全或兼容性缺陷。

---

### P15. 🔴 下载弹幕解压遇损坏数据 → 100% CPU 死循环（对方已修，本项目未修）

> ✅ **本项目已修**（`3c1e261`）：解压循环补 `needsInput() || needsDictionary()` 与 `i == 0` 两个退出条件

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/util/NetWorkUtil.java:564-575`（`public static byte[] decompress(byte[] data)`）

```java
564:        Inflater decompresser = new Inflater(true);
...
569:        byte[] buf = new byte[2048];
570:        while (!decompresser.finished()) {
571:            int i = decompresser.inflate(buf);
572:            out.write(buf, 0, i);
573:        }
```

**问题**：循环只以 `finished()` 为退出条件，**没有 `needsInput()` / `needsDictionary()` 检查，也没有 `if (i == 0) break`**。当弹幕数据被截断或损坏时，`inflate()` 会**恒返回 0**、`finished()` **恒为 false** —— 循环变成 100% CPU 空转。

而且它**不抛异常**（`Inflater.inflate` 返回 0 是合法返回值），所以外面 `catch` 根本兜不住：

- 唯一调用点 `service/DownloadService.kt:1446`（在 `downDanmaku()`，`:1433-1463`）：`val decompressBytes = NetWorkUtil.decompress(response.body!!.bytes())`
- 该处 `catch` 只捕 `IOException`。

**后果**：下载弹幕时遇到一个损坏/不完整的响应，下载服务的线程进入死循环 —— 表现为"下载卡死"，且因为占满一个核，手表这类低性能设备上整个应用都会卡到不可用。

> 已核实全仓库只有这一处 `Inflater`（其余 `LayoutInflater` 与此无关）。

**对方的修复**（分叉把该函数放在 `DownloadService` 里）：

```java
while (!decompresser.finished()) {
    //数据截断/损坏时 inflate 恒返回 0 且 finished() 恒 false，不 break 会 100% CPU 死循环卡死整个下载服务
    if (decompresser.needsInput() || decompresser.needsDictionary()) break;
    int i = decompresser.inflate(buf);
    if (i == 0) break;
    o.write(buf, 0, i);
}
```

---

### P16. 🔴 下载失败时递归删除整个视频文件夹 → 用户已下好的视频被删（**对方也未修**）

> ✅ **本项目已修**（`34e5bc9`）：**上游也没修这条**。改为只清 `<下载目录>` 一层的临时文件（`util/FileUtil.java` 新增 `cleanDownloadTempFiles`），不再递归删掉整个成品目录

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/service/DownloadService.kt:1487-1500`（`onDestroy()`）

```kotlin
if (section != null) {
    val id = section!!.id
    val folder = section!!.getPath()
    section = null
    CenterThreadPool.run {
        notifyExit(exitMessage!!)
        if (exitCode != NORMAL) {
            setState(id, "none")
            FileUtil.deleteFolder(folder)        // ← 整目录递归删除
        }
        refreshDownloadList()
    }
}
```

**删除范围被低估**：`FileUtil.deleteFolder` 是**递归整目录删除**（`app/src/main/java/com/RobinNotBad/BiliClient/util/FileUtil.java:37-57`：`isFile` 直接 `delete()`，否则遍历子项递归，末尾再删目录本身）。而路径来源是

`model/DownloadSection.java:77-85` `getPath()` → `util/FileUtil.java:128-132` `getVideoDownloadPath(title, child)` —— **`child` 为空时返回的正是 `<下载根>/<标题>` 本身**。

所以对单 P（`video_single`）项：`cover.png` / `danmaku.xml` / `video.mp4` / `audio.m4a` / `.video_meta.json` **整个文件夹一起被删**——包括用户上一次成功下载好的正片。多 P 项只删该 P 的子目录，影响小一些。

**触发条件很宽**：`exitCode` 是 `@JvmStatic var exitCode: Int = 0`（`DownloadService.kt:54`，**无 `@Volatile`**），`:540` 每次 `onStartCommand` 置 `ERR_UNKNOWN`，**只有整批跑完（`:562`）才置 `NORMAL`**。也就是说服务在批次结束前被系统回收、用户手动停止、或中途有其他错误，`exitCode != NORMAL` 都成立 → 删目录。

**对方修了吗？没有。** 已逐字比对：分叉 `service/DownloadService.java` 的 `onDestroy()` 是同一段 `if (exitCode != NORMAL) { setState(id, "none"); FileUtil.deleteFolder(folder); }`。

> 因此这是一条**参考分叉同样存在的缺陷**，不是"对方已修、我方未修"。之所以仍列在本节，是因为它属于**真实数据丢失**：建议本项目主动修（`exitCode != NORMAL` 时不应删已完成的成品文件，最多删临时分片）。

---

### P17. 🟠 下载进度通知可被异常永久静默（对方已修，本项目未修）

> ✅ **本项目已修**（`34e5bc9`）：`TimerTask.run()` 整体 `try/catch(Throwable)` + 日志

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/service/DownloadService.kt:1011-1031`（`startNotifyProgress()`）

```kotlin
notifyTimer!!.schedule(object : TimerTask() {
    override fun run() {
        DownloadService.sampleSpeed()
        if (section == null || notifyTimer == null) return
        val overall = DownloadService.computeOverallProgress(DownloadService.getAll() ?: emptyList())
        statusBuilder.setContentText("总进度 " + (overall * 100).toInt() + "% · " + (section?.name_short ?: "下载中"))
        statusBuilder.setProgress(100, (overall * 100).toInt(), false)
        notifyManager.notify(FOREGROUND_ID, statusBuilder.build())
    }
}, 500, 1000)
```

`run()` 体内**没有任何 try/catch**，而它每一步都可能抛：`sampleSpeed()`、`getAll()`（`:226-248` 内部虽有 catch，但返回后 `computeOverallProgress` 与 `section!!` 仍可能抛）、`notifyManager.notify`。

**根因（关键机制）**：`TimerTask.run()` 抛出的**未捕获异常会永久终止整个 `Timer`** —— 定时器从此不再执行任何任务，进度通知**永久静默**，且不会有任何日志或崩溃提示。

**对方的修复**：`run()` 整体包 `try { ... } catch (Exception e) { e.printStackTrace(); }`，分叉源码注释原文：

> `//TimerTask 抛未捕获异常会永久终止整个 Timer，进度通知从此静默失效`

> 同一机制也适用于 P2 提到的"所有 `TimerTask` 加异常兜底" —— 本项目需一并排查 `PlayerActivity` 里的 TimerTask。

---

### P18. 🔴 消息中心：三类独立缺陷（对方已修，本项目未修）

> ✅ **本项目已修**（`34e5bc9`）：三类缺陷一并修，`activity/message/NoticeActivity.kt` 整文件重写：成功/失败两条路径都复位 `setRefreshing(false)`、`type` 为空的 `system` 分支直接收口不再 `cursor!!`、数据改动全部移入 `runOnUiThread` 并加 `isDestroyed` 守卫。**语义取舍**：首屏失败置 `bottom = true` 解开卡死但不自动重试（基类 `goOnLoad` 会先 `page++`，重试会产生重复条目）

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/activity/message/NoticeActivity.kt`（全文仅 99 行）

#### (a) 首次加载失败 → 翻页永久卡死、下拉转圈永不停

```kotlin
 57:            } catch (e: Exception) {
 58:                e.printStackTrace()
 59:            }
```

catch 分支**只打印**：不置 `bottom`、不调 `setRefreshing(false)`、不提示、不 `finish()`。

而 `activity/base/RefreshListActivity.kt:44` 已经先把 `swipeRefreshLayout.isRefreshing = true`；`RefreshListActivity.kt:81` 的分页入口第一句就是：

```kotlin
if (listener == null || bottom || isLoading || swipeRefreshLayout.isRefreshing) {
    return
}
```

`isLoading` 是 `RefreshListActivity.kt:33` 的 `private var isLoading: Boolean = false`，**只有 `setRefreshing(false)`（`:162-165`）会复位**。于是一次网络失败之后，`isRefreshing` 永远为 true → **之后任何下滑都不再触发加载**，转圈动画也永远不停。

#### (b) `system` 类型分页必然 NPE 且永不终止

```kotlin
 47:            "system" -> {
 48:                messageList = MessageApi.getSystemMsg() as MutableList<MessageCard>
 49:            }
```

该分支**从不给 `cursor` 赋值**；`continueLoading` 的 system 分支（`:85-87`）同样不赋值，紧接着：

```kotlin
 90:                    bottom = cursor!!.is_end        // ← cursor 必为 null → NPE
```

NPE 被 `:92-96` 的 catch 吞掉后只做 `page--; setRefreshing(false)`，**从不置 `bottom = true`** → 用户每次滑到底都会重复失败一次。

**同一路径还有第二个入口**：`NoticeActivity.kt:25` `pageType = intent.getStringExtra("type")` **无 null 检查**。若 `type` 缺失，`when` 全不匹配、`cursor` 保持 null，走到 `:90` 同样是 NPE。

#### (c) 列表数据在后台线程被改、非空断言、无销毁守卫

- `:28` 起是 `CenterThreadPool.run {`，`:34-48` 在**后台线程**给 `cursor`、`messageList` 赋值；`:52` `noticeAdapter = NoticeAdapter(this, messageList)` 也在后台线程构造。
- `:89` `runOnUiThread { noticeAdapter!!.notifyItemRangeInserted(lastSize, messageList.size - lastSize) }` —— `lastSize` 与 `messageList` 在**后台线程读取**，`noticeAdapter!!` / `cursor!!` 双重非空断言；adapter 未就绪即 NPE。
- `:91` `setRefreshing(false)` 在**后台线程**调用。
- **全程无 `isDestroyed()` 守卫**：Activity 已销毁时后台回调仍会继续改 UI 状态。

**对方的修复**（`NoticeActivity.java`，201c68f）：`if (pageType == null) { finish(); return; }`；列表只在主线程初始化与变更（`runOnUiThread { if (isDestroyed()) return; ... }`）；`continueLoading` 先取局部变量 `MessageCard.Cursor cur = cursor;`，`"system"` 或 `cur == null` 时直接 `bottom = true; setRefreshing(false); return;`，`default:` 分支也置 `bottom`；`bottom = newCursor != null && newCursor.is_end`；异常分支改 `runOnUiThread(() -> bottom = false)`。并同时把 `RefreshListActivity.java` 的 `bottom` 改为 `volatile`（即 P7）。

> **同族小问题**：`activity/message/PrivateMsgActivity.kt:257` 的 `else runOnUiThread { MsgUtil.showMsg("没有更多消息了") }` **不复位** `isLoadingMore`（`:233` 置 true）→ 之后每次滑到顶都重复弹同一提示。

---

### P19. 番剧选集空季 → `IndexOutOfBoundsException`

> ✅ **本项目已修**（`07e9705`）：抽出 `firstSectionWithEpisodes()` / `currentEpisode(): Bangumi.Episode?`，切季拒绝空季，下标 `coerceIn`

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/activity/video/info/BangumiInfoFragment.kt`

```kotlin
219:        // 只兜底"整个 sectionList 为空"
...
256:        // 季切换（AlertDialog 单选）
262:            selectedEpisode = 0
268:            ... setData(空)
269:            ... scrollToPosition(0)
...
239 / 304:  episodeList[selectedEpisode]        // ← 无边界校验
```

`:219-228` 只兜底了"**整个** sectionList 为空"这一种情况。`:256-276` 的季切换**允许选中一个 `episodeList` 为空的季** → `:268` 用空列表 `setData` + `:262` 把 `selectedEpisode` 归 0 → 之后 `:239` / `:304` 读 `episodeList[selectedEpisode]` 抛 `IndexOutOfBoundsException`。另外 `:234` 首季为空时选集区是一片空白（无占位文案）。

**对方的修复**（`BangumiInfoFragment.java:550-553` / `:609-629`）：`switchSeason` 要求目标季至少有一个非空分区，否则不切；切季后**对选中下标做 clamp**，空季回退 `sectionList.get(0)`。

---

### P20. `started` / `exitCode` 无 `@Volatile`，`start()` 无同步（对方已修，本项目未修）

> ✅ **本项目已修**（`34e5bc9`）：五个字段加 `@Volatile`，`start()` 改 `@JvmStatic @Synchronized`

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/service/DownloadService.kt`

```kotlin
 53:    @JvmStatic var started: Boolean = false        // 无 @Volatile
 54:    @JvmStatic var exitCode: Int = 0               // 无 @Volatile，P16 直接依赖它
 55:    @JvmStatic var percent: Float = 0f
 56:    @JvmStatic var state: Int = 0
...
429:    fun start(first: Long) {
430:        if (started) return;                       // 检查与置位之间无任何同步
431:        started = true;
```

`if (started) return; started = true;` 这个"检查后置位"（check-then-act）没有 `synchronized`、字段也非 `volatile` → 两个线程可同时通过检查、同时置位，之后并发写同一批文件。

**对方的修复**：`started` / `exitCode` / `percent` / `state` / `section` 全部加 `volatile`；`if (started)` 分支补 `MsgUtil.showMsg("下载队列已在进行中")` 给用户明确反馈。

> 本项目 `AGENTS.md` 已把"`DownloadService.start()` 无同步，可并发写同一文件"记为已知坑 —— 属**已知但未修**。本条只断言"无同步/无 volatile"这一代码事实，未追全部调用点来证明并发调用确实可达。

---

### P21. 后台任务未捕获异常仍会直接杀死进程（对方已修，本项目未修）

> ✅ **本项目已修**（`34e5bc9`）：协程体补 `try/catch(Throwable)` + `MsgUtil.err`。**保留了 `ErrorCatch` 的 `killProcess`**（与上游一致）

**防护缺口（决定性）**：`app/src/main/java/com/RobinNotBad/BiliClient/util/CenterThreadPool.java:79-82`

```java
BuildersKt.launch(COROUTINE_SCOPE, EmptyCoroutineContext.INSTANCE, CoroutineStart.DEFAULT,
    (CoroutineScope scope, Continuation<? super Unit> continuation) -> {
        runnable.run();                     // ← 无 try/catch
        return Unit.INSTANCE;
    });
```

没有 `CoroutineExceptionHandler`，协程体内的未捕获异常会走到默认处理 → 交给 `Thread.setDefaultUncaughtExceptionHandler`。

而且 `CenterThreadPool.java:59-67` 的 static 块只在 `SDK_INT < JELLY_BEAN_MR1` 时把 `COROUTINE_SCOPE` 置 null —— **本项目 minSdk 24 → `COROUTINE_SCOPE` 恒非 null，`run(Runnable)` 永远走协程分支**，异常必然落到默认处理器。

**杀进程侧**：`app/src/main/java/com/RobinNotBad/BiliClient/ErrorCatch.java:45-46`

```java
throwable.printStackTrace();
android.os.Process.killProcess(android.os.Process.myPid());
```

注册链路确凿：`BiliTerminal.kt:197-198` 在 `onCreate` 调 `ErrorCatch.getInstance()` + `init(context)`，而 `ErrorCatch.java:27-29` 执行 `Thread.setDefaultUncaughtExceptionHandler(this)`；`AndroidManifest.xml:27` 是 `android:name=".BiliTerminal"`。

**对方的修复**：**并未移除 `killProcess`**，而是两头补：

1. `CenterThreadPool.java` 在协程体内 `try { runnable.run(); } catch (Throwable e) { MsgUtil.err(e); }`，注释原文：
   > `//协程体内未捕获异常会直接崩掉整个应用，无 CoroutineExceptionHandler 兜底`
2. `ErrorCatch.java` 在 `killProcess` 前加 `Thread.sleep(300)`（理由见 P5：`startActivity` 是异步的）。

∴ **根因是"兜底缺失"**，本项目两头都没有。

**连带的资源问题（同类，未修）**：`CenterThreadPool.java:42-57` 的 `getThreadPoolInstance()` 每次调用都 `new ThreadPoolExecutor(...)`，只有 `if (THREAD_POOL.compareAndSet(null, pool)) return pool;` 成功才返回，失败就**丢弃刚建好的对象**（白白创建线程池）；`ArrayBlockingQueue<>(20)` 为有界队列；`bestThreadPoolSize / 2` 可能算得 0。对方相应改为 `new LinkedBlockingQueue<>()` 与 `Math.max(2, ...)`。本项目实际是否走该分支影响有限。

---

### P22. 弱网重复点赞 / 投币 / 发弹幕：网络层已等价，**客户端缺去重**

> ✅ **本项目已修**（`3058512`）：客户端点击路径全部加互斥（`ReplyAdapter.kt:75 likingRpids` 实例级、`DynamicHolder.kt:59` **companion 级**，因为 ViewHolder 会被回收复用），另一半在 `fd947f0`（`VideoInfoFragment` 的点赞/投币/三连标志）。**有意偏离上游**：不照抄 `WriteReplyActivity` 那个「成功后才置位」的 `sent`（请求途中连点仍会重复发送）

#### 已经有防护的部分（不要误报为缺陷）

**本项目 POST 本就不自动重试** —— `app/src/main/java/com/RobinNotBad/BiliClient/util/NetWorkUtil.java:331-345` 的 `post(...)` 末尾直接执行，没有任何重试包装：

```java
Request request = requestBuilder.build();
return client.newCall(request).execute();
```

对照 GET 路线确实有重试：`NetWorkUtil.java:268-314` `executeWithDoctypeRetry`（`maxRetries` + doctype 风控重试）、`NetWorkUtil.java:205-230` `executeJsonWithRiskRetry`（`-352` / `-412` 重试）。而点赞/投币/弹幕/三连**全部走 `post`**（`api/LikeCoinFavApi.java:19/28/37/46`、`api/DanmakuApi.java:46-47`）。

所以对方 1.1.2"给 POST 加 `retryEnabled=false`"的效果，**本项目天然等价**，这一项无需改动。

#### 仍存在的缺口：客户端点击路径无任何互斥

"已点赞/已投币"状态位**一律只在服务端返回成功之后才更新**，整个点击路径没有 `isRequesting` 标志、没有按钮禁用、没有防抖：

- `activity/video/info/VideoInfoFragment.kt:359-362`（`layout_like` 点击内）：
  ```kotlin
  val result = LikeCoinFavApi.like(videoInfo!!.aid, if (videoInfo!!.stats.liked) 2 else 1)
  if (result == 0) {
      videoInfo!!.stats.liked = !videoInfo!!.stats.liked
  ```
- `VideoInfoFragment.kt:396-398`（投币）：`val result = LikeCoinFavApi.coin(videoInfo!!.aid, 1)`，唯一守卫是 `if (videoInfo!!.stats.coined < videoInfo!!.stats.coin_limit)`，成功后 `if (++coinAdd <= 2) videoInfo!!.stats.coined++`。
- `VideoInfoFragment.kt:524`（长按三连 `LikeCoinFavApi.triple(aid)`）：`isTripleInProgress`（声明 `:137`）**只用于手势取消延迟任务**（见 `:555-558` `cancelTripleAction`），**不是请求去重**。
- `adapter/ReplyAdapter.kt:313`：`if (ReplyApi.likeReply(oid, reply.rpid, true) == 0) { reply.liked = true ... }`，所在 `CenterThreadPool.run {` 在 `:308`，`if (!reply.liked)` 判断在后台线程内 —— 同族。
- `adapter/dynamic/DynamicHolder.kt:675`：`if (DynamicApi.likeDynamic(dynamic.dynamicId, true) == 0) { dynamic.stats.liked = true ... }`（取消分支 `:693`）—— 同族。
- 弹幕：`activity/player/PlayerActivity.kt:1763` `val result = DanmakuApi.sendVideoDanmakuByAid(...)`，位于 `findViewById<View>(R.id.danmaku_send).setOnClickListener`（`:1753`）内；唯一拦截是 `editText.text.toString().isEmpty()`，**无"发送中"标志**。间接缓解是 `:1758-1759` 点击后即 `card_danmaku_send.visibility = View.GONE`，但 `btn_danmaku_send`（`:1745-1748`）可立刻重新打开卡片再次发送。

**对照组（证明本项目并非普遍缺这个模式）**：`activity/reply/WriteReplyActivity.kt:105-119` **就有**该保护 —— `if (!sent) { CenterThreadPool.run { ... sent = true ... } } else MsgUtil.showMsg("正在发送中")`。点赞/投币/弹幕没有照抄这个既有模式。

> **残余风险（标注为推断，未实测）**：`NetWorkUtil.java:81-126` 的 `getOkHttpInstance()` 未设置 `retryOnConnectionFailure(false)`；`app/build.gradle:231` 是 `com.squareup.okhttp3:okhttp:4.12.0`，该项默认 `true`，会在**连接层失败**（连不上 / 连接在响应途中被掐断）时静默重发同一请求 —— 在"服务端已执行、响应途中断开"的窗口内仍可能重复。属框架行为推断，**未实测**。

---

### P23. 🔴 番剧播放进度：整条链路缺失（读不到、也写不了）

> ✅ **本项目已修**（`07e9705`）：整条链路补齐：新增心跳上报 `api/HistoryApi.java:52-104 reportHistoryPgc(...)`、播放器读侧改走 `getLastPlayProgress`（pgc playurl 的 result 本来就不返回 `last_play_*`，原来那套读法永远得 0）、历史列表 `type=archive` → `type=all`；并接线到 `activity/video/JumpToPlayerActivity.kt` 与 `model/PlayerData.java` 的 `epid/seasonId/seasonType`

这是**对方 v1.0.0 就修好的能力**，本项目**完全不具备**。它比 P1（csrf 快照错位）更根本：P1 只是"上报失败"，这里是"根本没有番剧上报/续播这条链路"。

#### 写侧：完全没有番剧上报

- `activity/player/PlayerActivity.kt` **全文不含 `HistoryApi`**，也无任何上报调用。
- 唯一的退出上报在 `activity/video/JumpToPlayerActivity.kt:47-56`，且只调 `HistoryApi.reportHistory(...)`（archive 维度）。
- `grep -rn heartbeat app/src/main` → **0 命中**。对方用的是 `reportHistoryPgc` 心跳（`HistoryApi.java:98-149`，`type=4`，带 `epid` / `sid` / `mid` / `played_time`）。

#### 读侧：播放器不读续播进度

`api/PlayerApi.java:295-331` 的 `getBangumi` **完全不读续播进度**，只取 `videoUrl` / `danmakuUrl` / `qn`。对方源码注释（`PlayerApi.java:193`）说明了原因：pgc `playurl` 的 `result` **不返回** `last_play_*`，必须另走接口。

对方的做法：`getLastPlayProgress`（`PlayerApi.java:216-230`）+ `findProgressMsByAid` 兜底。

#### 历史侧：番剧记录永不落入历史列表

- `api/HistoryApi.java:46` 的历史列表请求 URL 用 **`type=archive`**。对方注释（`HistoryApi.java:213`）明确指出：`type=archive` **只返回投稿视频，pgc 必须用 `type=all`**。
- `api/HistoryApi.java:68-70` 构造 `VideoCard` 时**不设置 `type` / `epid` / `progress`**；`model/VideoCard.java` **根本没有 `epid` / `progress` 字段**。
- `grep -rn media_bangumi app/src/main` 的赋值只出现在 `api/BangumiApi.java:37` 与 `api/DynamicApi.java:481` —— **历史链路永远不会产出 `media_bangumi` 类型**，所以 `adapter` 里 `VideoCardHolder.kt:63` 的 `media_bangumi` 分支对历史列表毫无作用。
- 加上第 5 条（详情页无定位逻辑），"历史记录点番剧直达续播"这张牌在客户端**从数据到 UI 全断**。

#### 相关的既有缺陷（P1 已记）

`api/HistoryApi.java:32` 的 csrf 读的是 SharedPreferences 快照，而对方改为 `currentCsrf()` 从实时 Cookie `bili_jct` 派生。另外本项目**没有** `currentMid()`（从 `DedeUserID` 派生）也没有 `logReportResult()`（上报失败零日志）→ 上报静默失效时**查不到任何原因**。

---

### P24. 多P续播跳到错误位置 + 切分P后记录记到旧分P

> ✅ **本项目已修**（`07e9705`）：`adoptLastPlayTime(lastPlayCid, requestCid, lastPlayTime)` 校验 cid 后才采用续播位置；`normalizeProgress` 做秒/毫秒探测 + 24h 上限；`PlayerActivity.finish()` 回传 cid，切P时同步自己的 `cid` 字段

#### (a) 不校验 `last_play_cid`：选 P3 会从 P2 的位置开始

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/api/PlayerApi.java:146-152`（`getVideoDash`）与 `:266-272`（`getVideo`）

```java
cidHistory = last_play_cid;
progress   = last_play_time;      // ← 不校验 last_play_cid 是否等于本次请求的 cid
```

只在 `cidHistory == 0` 时才兜底。因此若用户上次看到 P2 的第 300 秒，这次点开 P3，服务端返回的 `last_play_cid` 仍是 P2、`last_play_time` 仍是 300 → **P3 从第 300 秒开始播**。

**对方的修复**：抽出 `adoptLastPlayTime(lastPlayCid, requestCid, lastPlayTime)` —— **cid 不一致时一律归 0**（`PlayerApi.java:237-240`），再经 `normalizeProgress`（`:246-260`）规范化。

#### (b) 切分P / 退出时不上报，且回传不带 cid

- **切 P 前不上报旧 P**：`activity/player/PlayerActivity.kt:2113-2127` `switchToPage` 直接切走。
- **退出时回传的 result 里没有 cid**：`PlayerActivity.kt:2997-3009` 的 `finish()` 只回传 `progress` / `isPlaying` / `isDanmakuEnabled` / `quality`。
- 于是 `activity/video/JumpToPlayerActivity.kt:47-56` 只能用**进入时拿到的旧 `data.cid`** 去上报 → **切换过分P的会话，观看记录会记到旧分P上**。

**对方的修复**：切走前先 `reportProgressNow(true)`（`PlayerActivity.java:2889-2890`），并在 result 里 `putExtra("cid", cid)`；`JumpToPlayerActivity.java:52-54` 以回传的 `cid` 为准（`finalCid`）再上报。

#### (c) 顺带：本项目完全没有"播放中周期上报"

对方有 `PROGRESS_REPORT_INTERVAL_MS = 15000` 的周期上报（`PlayerActivity.java:187`、`:1311-1319`）+ `onPause` / `onStop` / `onDestroy` 三处兜底（`:2118` / `:2133` / `:2152`）。本项目 `PlayerActivity.kt` 无任何上报 → 应用被杀死时进度全丢。

---

### P25. WBI 密钥：按天缓存 + 先写标记后取密钥 + `sortUrlParams` 丢弃含 `=` 的参数

> ✅ **本项目已修**（`07e9705`）：WBI 密钥 TTL 改 30 分钟、`signWBI` 加类锁、**成功后才写 `last_wbi_time`**（原来是先写标记再取密钥，一次失败污染整整一天）、`sortUrlParams` 改按首个 `=` 切分、`getDateCurr` 的 MONTH 补 `+1`

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/api/ConfInfoApi.java`

```kotlin
 74:    // 按"天"缓存：当天命中就直接读 wbi_mixin_key
 79:    putInt("last_wbi", getDateCurr())          // ← 先写标记
 81-82: ...                                     // ← 再取密钥，失败也已污染整天
137-140: fun getDateCurr(): Int = ... (MONTH 未 +1)
103-134: fun sortUrlParams(...) 用 split("=") 后判断 length == 2
```

三处独立缺陷：

1. **按天缓存**：服务端轮换密钥后，当天之内**不会刷新**。`grep -rn "last_wbi_time\|WBI_KEY_TTL"` → **0 命中**，无 `synchronized`。
2. **先写标记、后取密钥**（`:79` 在 `:81-82` 之前）：**一次取密钥失败就会污染当天所有 WBI 请求** —— 整个当天的 WBI 签名全错。
3. **`sortUrlParams` 用 `split("=")` 并判断 `length == 2`**：值里含 `=` 的参数（如 base64 或带 `=` 的值）会被**整条丢弃**，导致签名与实际请求参数不一致。

此外 `getDateCurr()`（`:137-140`）的 `MONTH` **没有 +1**（`Calendar.MONTH` 从 0 开始）。

**对方的修复**（26f1742 + 201c68f）：`WBI_KEY_TTL_MS = 30 * 60 * 1000`，条件改为 `mixin_key.isEmpty() || now - last_wbi_time > TTL`，**取密钥成功后才落 `last_wbi_time`**；类级锁 + 以**第一个 `=`** 切分 + `MONTH + 1`。

> 对应症状：对方 release note 里的"番剧**观看进度时好时坏**"。本项目因为整条番剧上报链路都缺失（P23），这个具体症状在此**无法同路径复现**，本条的判定是**纯代码层**的。

---

### P26. 评论区：无世代号、无超时、无空页重试，且数据改动在主线程之外

> ✅ **本项目已修**（`3058512`）：世代号 `loadGeneration` + 30s 超时 + 空页带原游标重试（`MAX_EMPTY_PAGES = 5`）+ 数据改动全部移入主线程；`api/ReplyApi.java:140-166 getRepliesLazy` 重写为可区分 空页/结束/错误

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/activity/reply/ReplyFragment.kt`（250 行）

- **无世代号**：`grep loadGeneration` → 0 命中。`refresh`（`:209-241`）也没有世代号 → **在途的旧翻页请求会把结果写进刷新后的新列表**。
- **无超时**：`grep LOAD_DEADLINE_MS` → 0 命中。
- **无空页重试**：`:157-181` 是单次请求返回即结束；对方是 `while` + `emptyPages < 5` + deadline 上限。
- **用的是不重试的 API 变体**：`ReplyApi.getRepliesLazy`（`api/ReplyApi.java:101`），本项目没有对方的 `getRepliesLazyWithRetry`。

- **数据改动不在主线程**：
  - `:190` `replyList!!.add(pos, reply)`
  - `:198-199` `childMsgList.add(...)` / `childCount++`
  
  这两处都在 `runOnUiThread {` **之前**执行。`:187` 的 `layoutManager` 还是**直接强转**。
- **删除路径**：`adapter/ReplyAdapter.kt:367` 的 `replyList.removeAt(realPosition)` 在 `:363` 的 `CenterThreadPool` **后台线程**里执行，`:368` 才 `runOnUiThread` 通知 adapter → 后台改数据、主线程读数据。

**对方的修复**：把列表改动与 `removeAt` 全部收回主线程（并把 `notifyItemRangeChanged` 的 count 改为 `size() + 1 - position` 防越界）；26f1742 另加 `volatile isEnd`、`synchronized loadedRpids`、把 `endReached` 收敛到主线程。

---

### P27. 🔴 播放失败被伪装成"播放完毕"，且没有任何重试入口

> ✅ **本项目已修**（`d292950`）：`setOnErrorListener` 末尾改 `return true`，阻断 `IjkMediaPlayer` 把错误改判成 `onCompletion()`（原来失败会被当成"播放完毕"并自动跳下一P）；配套 `playerError` 标志与 `retryAfterPlayerError()` 重试入口

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/activity/player/PlayerActivity.kt:878-882` —— 这就是 `onError` 的**全文**：

```kotlin
ijkPlayer!!.setOnErrorListener { _, what, extra ->
    val EReport = "播放器可能遇到错误！\n错误码：" + what + "\n附加：" + extra
    Logu.e("ijk-err", EReport)
    false            // ← 返回 false = "未处理"
}
```

没有错误态标志、没有用户提示、没有重试入口。

**为什么"返回 false"会变成"播放完毕"（机制已在本仓库内置的 ijkplayer 里实证）**：

1. `ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/AbstractMediaPlayer.java:106`
   ```java
   return mOnErrorListener != null && mOnErrorListener.onError(this, what, extra);
   ```
2. `ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/IjkMediaPlayer.java:1013-1017`
   ```java
   case MEDIA_ERROR:
       if (!player.notifyOnError(msg.arg1, msg.arg2)) {
           player.notifyOnCompletion();          // ← 返回 false 就改发"播放完成"
       }
   ```
3. 于是流程落进 `PlayerActivity.kt:848-876` 的 `setOnCompletionListener` —— 置 `finishWatching = true`、按钮复位、**自动切下一P**。

**用户看到的现象**：视频加载失败时，界面表现为"这一集播完了"，然后自动跳下一集（或停在结束态）——**根本不知道是出错**。

**无重试入口**：`PlayerActivity.kt` 全文 grep `重试|retry|playerError|重新载入` 只命中 `:1985` 的 `MsgUtil.showMsg("切换失败，请重试")`，而那是**音频模式切换**的提示，与本项无关。

**对方的修复**：加 `playerError` 标志；`onError` 内 `playerError = true` + `MsgUtil.showMsgLong(EReport)` + UI 复位 + **`return true`**；`controlVideo()` 开头 `if (playerError) { retryAfterPlayerError(); autohideReset(); return; }`；`retryAfterPlayerError()` 走"释放 → 重建 → `setDisplay()`"，其内含 `if (destroyed || isFinishing()) return;` 守卫。

---

### P28. 音频模式切换无销毁守卫 → 重建的播放器无人释放（native 泄漏）

> ✅ **本项目已修**（`d292950`）：音频模式切换加 `if (destroyed || isFinishing()) return@runOnUiThread` 守卫

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/activity/player/PlayerActivity.kt:1978-1982`（`toggleAudioOnlyMode()` 定义于 `:1947-1999`）

```kotlin
runOnUiThread {
    ijkPlayer = IjkMediaPlayer()          // ← 无 destroyed / isFinishing 守卫
    progress_history = currentPosition
    setDisplay()
}
```

该回调前有一段 `CenterThreadPool` 线程内的 `sleep(100)`，所以**执行时页面可能已经退出**。而此时 `onDestroy` 已经跑完：

- `PlayerActivity.kt:1453` `destroyed = true`
- `PlayerActivity.kt:1462-1463` `ijkPlayer?.release(); ijkPlayer = null`

→ 回调里**新建**的 `IjkMediaPlayer` 再也没人释放，`setDisplay()` 还会往已销毁的 Surface 上挂 —— **native 层泄漏**（每次触发漏一个解码器实例）。入口为 `:2906` `btn_audio_only.setOnClickListener { toggleAudioOnlyMode() }`。

**对方的修复**：在 `ijkPlayer = new IjkMediaPlayer();` 之前加 `if (destroyed || isFinishing()) return;`。

**附带差异（属 `48bbf21`，非 1.1.2，标为存疑）**：本项目 `:1961-1964` 先 `ijkPlayer!!.stop(); ijkPlayer!!.release()`，`:1969-1970` 才置 `isPrepared = false; isPlaying = false`，且**未把引用置空**；对方改为**先摘标志并置 null，再 release**。不过本项目的进度定时器走主线程（`:1018` `if (!destroyed) mainHandler?.postDelayed(this, 250)`），与 `release` 同线程、不交错 —— **能否实际复现存疑**，不作为独立结论。

---

### P29. ◐ SplashActivity：网络波动会清空登录态（对方已修，本项目部分残留）

> ✅ **本项目已修**（`fd947f0`）：Cookie 刷新失败/异常一律只记日志，仅当本地连 `SESSDATA` 都取不到才 `resetLogin()`

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/activity/SplashActivity.kt:83-106`

```kotlin
 85:            val cookieInfo = CookieRefreshApi.cookieInfo()      // 在 try 内
...
102:        } catch (e: JSONException) {
103:            MsgUtil.showMsgLong("登录信息过期，请重新登录！")
104:            resetLogin()
105:        }
```

解析源 `app/src/main/java/com/RobinNotBad/BiliClient/api/CookieRefreshApi.java:32-36`：

```java
public static JSONObject cookieInfo() throws IOException, JSONException {
    ...
    return result.getJSONObject("data");     // ← code!=0 或 data 缺失即抛
}
```

→ **一次网络波动导致 `data` 缺失，就会走到 `resetLogin()`**（`:108-113` 清 mid / csrf / cookies / refresh_token），用户被迫重新登录。

#### 已经有防护的部分（不要误报为缺陷）

`api/CookieRefreshApi.java:99-109` **已经**处理了 `DedeUserID` 缺失，且有明确注释：

```java
// 新 Cookie 中可能缺失 DedeUserID（服务端异常/部分写入），此时回退到旧值，避免 parseLong("") 崩溃
String dedeUserId = NetWorkUtil.getInfoFromCookie("DedeUserID", cookies_new);
...
} catch (NumberFormatException) {
    Logu.e("Cookie刷新失败", "新Cookie缺少DedeUserID，回退旧登录态");
    NetWorkUtil.setCookiesString(cookies_old);
    return false;
}
```

→ **不会崩**。所以"崩溃"这一层本项目已覆盖。

#### 残余差距

返回 `false` 之后，`SplashActivity.kt:93-99` 的 `else` 分支**仍然** `MsgUtil.showMsgLong("登录信息过期，请重新登录！"); resetLogin()` —— 即"刷新失败"依然等价于"清登录态"。对方改为**沿用本地已存的 mid 继续完成刷新**，不清登录态。

另有两处对方有、本项目无的小项：

- `SplashActivity.kt:213` 只 `catch (e: JSONException)`，对方补了 `catch (Exception e)` 泛化兜底（外层 `:200` 的 `catch (e: Exception)` 只记日志）。
- 对方在 `onDestroy` 里 cancel `splashTimer`，本项目无。

---

### P30. 播放器退出路径的资源释放与主线程阻塞（与 P2 / P3 合并处理）

> ✅ **本项目已修**（`d292950`）：先摘标志、再 release、再置空引用

**说明（避免夸大）**：P6 表第 6、7 项（"非正常退出路径的资源释放"、"快进/快退与切清晰度不再有主线程卡住的风险"）在本轮审计中**没有取得独立于 P2 / P3 的新证据**。它们与本项目已确认的两条根因同源：

- **P2**：弹幕绘制线程轮询 `ijkPlayer.getCurrentPosition()`（取原生锁的 JNI 调用）→ 绘制线程卡住后主线程 `release()` 内 `join()` 无限等待。
- **P3**：`Thread.join()` 无超时 → 主线程可被无限阻塞。

**本轮唯一新增的独立线索**是本节的 `isPrepared` / 引用置空顺序（见 P28 的"附带差异"），且**存疑**。

**结论**：这两项应在修 P2 / P3 时一并复核，不单独列修复项。

---

### P31. ◐ 退出登录：调了服务端接口，但用的是无效的 GET 形式

> ✅ **本项目已修**（`d676a77`）：`exitLogin()` 改 POST + `csrf=`，并返回是否成功；本地登录态清理与服务端注销放进同一个后台任务，消除 csrf 被提前清空的竞态

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/api/UserInfoApi.java:212-220`

```java
public static void exitLogin() {
    try {
        String url = "https://passport.bilibili.com/login/exit/v2";
        NetWorkUtil.get(url, NetWorkUtil.webHeaders);      // ← GET，无 csrf，结果未检查
    } catch (Exception e) {
        e.printStackTrace();
    }
}
```

**调用点**：`app/src/main/java/com/RobinNotBad/BiliClient/activity/user/MySpaceActivity.kt:129-137`

```kotlin
129:        CenterThreadPool.run { UserInfoApi.exitLogin() }
130-135:      ...无条件清 cookies / mid / csrf / refresh_token / access_key / cookie_refresh
136:        MsgUtil.showMsg("账号已退出")
137:        jumpToLogin()
```

→ **本地一定清干净，但服务端会话不保证失效**（返回值根本没看）。这意味着 `SESSDATA` / `refresh_token` 在服务端仍然是活的。

**对方的修复**：

```java
String csrf = NetWorkUtil.getInfoFromCookie("bili_jct", SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
NetWorkUtil.post("https://passport.bilibili.com/login/exit/v2", "csrf=" + csrf, NetWorkUtil.webHeaders);
```

分叉源码注释原文：

> `//正式的注销端点是 POST + csrf：GET 不带参数调不动它，服务端会话（含 refresh_token）不会被失效`

---

### P32. 搜索页：建议乱序无保护 + `hasFocus` 门禁丢结果 + 输入法不弹

> ✅ **本项目已修**（`d676a77`）：建议请求加代际号（慢响应不再覆盖新响应、失焦不再丢结果）、点输入框 `post { showSoftInput }` 弹输入法、建议点击前 `clearComposingText()`、`afterTextChanged` 加 `if (refreshing) return`、`requestFragmentFocus()` 加焦点守卫

#### 重要更正：**"搜索建议永远为空"这个 bug 在本项目不存在**

对方 1.1.0-fix1 修的是"B 站 suggest 接口词表从 `data` 移到 `result` 字段，解析改双字段兼容"。

本项目 `app/src/main/java/com/RobinNotBad/BiliClient/api/SearchApi.java:164-188` **读的就是 `result`**：

```java
175:        if (response.getInt("code") == 0 && response.has("result")) {
176:            JSONObject result = response.getJSONObject("result");
177:            if (result.has("tag") && !result.isNull("tag")) {
178:                JSONArray tagArray = result.getJSONArray("tag");
...
                    String value = tagObj.getString("value");
```

且 `git log -1 -- app/src/main/java/com/RobinNotBad/BiliClient/api/SearchApi.java` 显示该文件自 `4d34790 Initial commit` 起就是这个写法，全项目 `main/suggest` 仅此一处。**判定：本项目在字段解析上已有防护，不存在 `data` 那个 bug。**

> 残余风险（低）：它是**单字段**、无双字段兼容 —— 如果 B 站再切回 `data`，建议会静默为空。可顺手加双字段兼容。

#### 仍存在的缺陷 1：建议请求无代际保护 → 慢响应覆盖新响应、失焦丢结果

- 全项目 grep `suggestionGeneration|generation` → **0 命中**。
- `activity/search/SearchActivity.kt:232-234` 只能取消**尚未派发**的延时任务：
  ```kotlin
  if (suggestionRunnable != null) {
      handler.removeCallbacks(suggestionRunnable!!)
  }
  ```
  而 `:245-266` 里的 `Thread { ... }.start()` **已经派发出去，无法取消** → 慢的旧响应会覆盖新响应。
- 显示门禁是 `:249` `if (keywordInput.hasFocus()) {` → 输入框**短暂失焦就会把整包结果丢弃**，症状与对方描述的"建议永远不出现"相同。

**对方的修复**：引入 `suggestionGeneration` 代际令牌，回调首行 `if (gen != suggestionGeneration || refreshing || isFinishing() || isDestroyed()) return;`。

#### 仍存在的缺陷 2：建议卡片回弹 + 输入法不弹（两项都缺）

- **`hasFocus` 门禁仍在**：`:238`（空串分支）与 `:249`（建议结果分支）。
- **无输入法补拉**：`grep -rn "showSoftInput" app/src/main/java` → **零命中**。全项目只有 `:407-411` 的 `manager.hideSoftInputFromWindow(...)`；也**没有** `keywordInput.setOnClickListener`（`setOnClickListener` 只出现在 `:178` searchBtn、`:197` searchHistoryAdapter、`:216` searchSuggestionsAdapter、`:275` top）。清单里只有 `AndroidManifest.xml:502` 的 `android:windowSoftInputMode="adjustResize"`。
- **`requestFragmentFocus()` 会把焦点从输入框抢走**：`:519-531`（`recyclerView.isFocusable = true; isFocusableInTouchMode = true; requestFocus()`），调用点是 `:516`（`onScrolled` 内）与 `:476`（`searchKeyword` 内），**无 `hasFocus` 守卫**。
- **缺 `clearComposingText`**：`:216-221` 建议点击只做 `setText` + `setSelection` + `searchKeyword`；全项目 `clearComposingText` 零命中 → 组词状态下点击建议会残留候选。
- **`afterTextChanged` 无 `if (refreshing) return`**：`:229-230` 直接 `val keyword = s.toString()`（`refreshing` 字段在 `:54` 定义、`:406/428/481` 使用）。
- **`onFocusChange` 分支判空不全**：`:120-134` 的 `:123` `if (keyword.isEmpty() || !suggestionsEnabled) {` 缺 `|| searchSuggestions.isEmpty()` → 焦点恢复时旧建议列表会盖回结果页。

**对方的修复**：`keywordInput` 点击 `v.post(() -> imm.showSoftInput(v, SHOW_IMPLICIT))`；建议点击先 `clearComposingText()`；`afterTextChanged` 首行 `if (refreshing) return;`；`searchKeyword` 内 `suggestionGeneration++; handler.removeCallbacks(suggestionRunnable); runOnUiThread(() -> suggestionsRecyclerview.setVisibility(View.GONE));`；`requestFragmentFocus` 首行 `if (keywordInput.hasFocus()) return;`。

---

### P33. `asyncInflate` 内容就绪即硬切 → 低性能设备"过渡动画丢失"

> ✅ **本项目已修**（`d676a77`）：`AsyncLayoutInflaterX` 新增按帧驱动的 `fadeIn(View, BooleanSupplier)`，`cancel()` 真正生效（`mCancelled` + 清 Handler 队列），`BaseActivity` 在 onDestroy 取消并在回调里判 `isDestroyed`

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/activity/base/BaseActivity.kt:396-407`

```kotlin
protected fun asyncInflate(id: Int, callBack: InflateCallBack) {
    setContentView(R.layout.activity_loading)
    AsyncLayoutInflaterX(this).inflate(id, null) { view, layoutId, _ ->
        setContentView(view)          // ← 就绪即硬切，无 alpha 淡入、无帧驱动
        ...
```

`util/AsyncLayoutInflaterX.java:66-79` 只在主线程 Handler 回调里交付视图（`request.callback.onInflateFinished(request.view, request.resid, request.parent)`），全文件 grep `alpha|animate|Animator|Choreographer` → **零命中**。文件里唯一的 `:231 public void cancel()` 与 `AGENTS.md` 记载一致 —— **从未被调用**。

**在低性能手表上**：布局加载耗时超过系统转场时长，内容就绪时系统转场已经结束，于是"过渡动画丢失"、画面生硬跳变。

**对方的修复**（`c36715a`，在 `BaseActivity` 同名回调内）：

```java
view.setAlpha(0f);
Choreographer.getInstance().postFrameCallback(...)   // 逐帧驱动
alpha = Math.min(Math.min(frameCount / 3f, elapsed / 300f), 1f)   // 3 帧 + 300ms 双上限
// 不受系统 TRANSITION_ANIMATION_SCALE 影响；帧未完且 !isDestroyed() 才续帧，否则置 1f
```

对方同时在"关于"页加了动画缩放诊断显示（W527 ROM 实测 `TRANSITION_ANIMATION_SCALE = 0`）。

> 注：本项目 `docs/tutorial-system-redesign.md` 与既有审查已记录 `AsyncLayoutInflaterX.cancel()` 从未被调用、`BaseActivity.asyncInflate` 无生命周期保护（20 页在用）—— 本节补充的是**淡入缺失**这一层。

---

### P34. ✅ `util/DmImgParamUtil.java` 用了 API 30 才有的 `List.of(...)` → Android 7~10 上必崩

> ✅ **本项目已修**（`8cf777a`）：改用 `new JSONArray(new Object[]{...})` 构造，与上游同义

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/util/DmImgParamUtil.java:113-114`

```java
.put("wh", List.of(f114(width, height)))
.put("of", List.of(f514(y, x)))
```

**问题**：`java.util.List.of` 是 **Android 11（API 30）** 才加入的静态方法。本项目 `minSdk 24`，且 `app/build.gradle:85-88` 的 `compileOptions` **没有开启 `coreLibraryDesugaringEnabled`**（AGP 的 core library desugaring 也不覆盖 `of()` 系列）⇒ **Android 7~10 上调用必然 `NoSuchMethodError`**。

**可达路径很宽**：`getDmImgParams()`（`:19-30`）无条件调用 `generateDmImgList()` 与 `generateDmImgInter()`，后者就是出事的那一段；再经 `getDmImgParamsUrl()`（`:32-39`）被
- `api/UserInfoApi.java:100` 与 `:152`（取用户信息）
- `api/DynamicApi.java:503` 与 `:540`（带图发动态 / 发评论）

引用。也就是说**在 Android 7~10 上打开用户信息页就会崩**。

**修法**（顺带删掉因此未使用的 `import java.util.List;`）：

```java
.put("wh", new JSONArray(new Object[]{f114(width, height)}))
.put("of", new JSONArray(new Object[]{f514(y, x)}))
```

**全库复查**：修完后 `Map.of(` / `List.of(` / `Set.of(` / `Collectors.` / `.stream()` **零命中**。

> **教训**：这类"高版本平台 API 在低版本 `NoSuchMethodError`"编译器、Lint、单测**全都查不出来**（编译期只校验类型存在），只有实机或 lint 的 `NewApi` 检查能发现 —— 与上一轮修的 `hasOnLongClickListeners` 同族。新增代码时不要用 `of()` 系列。

---

### P35. ✅ 无 UP 主 / 联合投稿视频详情页：`videoInfo.staff[0]` 越界闪退

> ✅ **本项目已修**（`8cf777a`）：加 `isNullOrEmpty()` 守卫

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/activity/video/info/VideoInfoActivity.kt:94`

```kotlin
ReplyFragment.newInstance(videoInfo.aid, 1, videoInfo.stats.reply, seek_reply, videoInfo.staff[0].mid)
```

**问题**：`videoInfo.staff` 未必非空。`api/VideoInfoApi.java:198-229` 的填充条件是**两个都成立**：① `videoInfo.isCooperation == true` 且返回的 `staff` 数组非空；② `data.optJSONObject("owner") != null`。联合投稿 `staff` 为空、或 UP 已注销 / 被隐藏导致 `owner` 缺失时，`videoInfo.staff` 就是**空列表** ⇒ `IndexOutOfBoundsException`，详情页直接起不来 —— 正是对方描述的"无 UP 主视频详情页闪退"。

**修法**：`val upMid = if (videoInfo.staff.isNullOrEmpty()) 0L else videoInfo.staff[0].mid`，再把 `upMid` 传给 `ReplyFragment.newInstance(...)`。

> 同族未动：`model/VideoInfo.java:108-110 toCard()` 里也有 `staff.get(0).name`，但 `toCard()` **全库零调用者**（死代码），本轮未动。

---

### P36. ✅ 用户动态页：`official_signs[userInfo.official]` 越界闪退

> ✅ **本项目已修**（`8cf777a`）：改 `coerceIn`

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/adapter/dynamic/UserDynamicAdapter.kt:221-229`

```kotlin
holder.verifyName.text = official_signs[userInfo.official]
```

**问题**：`official_signs` 是固定 10 项的文案数组，而 `userInfo.official` 直接取自服务端 `official.role`，**不保证落在 0~9**（上游注释称实测见过 10）⇒ 越界闪退。

**修法**：`val officialIdx = userInfo.official.coerceIn(0, official_signs.size - 1)`（与上游 `Math.max(0, Math.min(userInfo.official, official_signs.length - 1))` 等价）。

---

### P37. 🚧 动态列表在后台线程突变 `dynamicList`

**现状代码**：`app/src/main/java/com/RobinNotBad/BiliClient/activity/dynamic/DynamicActivity.kt:140-153`

转发/发布成功后，在 `CenterThreadPool.run { }` 的**后台线程**里执行 `dynamicList!!.add(0, dynamic)`，随后才 `runOnUiThread { notifyItemInserted(1) }`。上游 26f1742（`DynamicActivity.java:126-129`）把 `add` **移进 `runOnUiThread`**，注释：「list 突变与 Adapter 通知必须在同一线程」。

**状态**：已并入 Wave 2，派给子代理（连同 P38），连同 `:253` 附近的 `notifyItemInserted(1)` 一起检查同类问题。

---

### P38. 🚧 动态 Adapter 的 `onViewRecycled` 不清 holder 图片缓存

**现状代码**：`adapter/dynamic/DynamicAdapter.kt` 与 `adapter/dynamic/UserDynamicAdapter.kt` 的 `onViewRecycled`

`DynamicHolder` 内部有"同 URL 跳过加载"的图片缓存，复用前若不清理，recycled 的 holder 会**残留上一条动态的头图 / 配图**。上游 26f1742 在 `DynamicAdapter.java:137-141` 与 `UserDynamicAdapter.java:120-124` 补了：

```java
if (holder instanceof DynamicHolder) ((DynamicHolder) holder).clearImageCache();
```

**状态**：已并入 Wave 2，派给子代理（需先在 `DynamicHolder` 里按上游语义补出 `clearImageCache()`）。

---

> **P6 表第 1 / 6 / 7 项的补充说明**：本轮把对方 26f1742 提交（1.1.1-fix）**逐文件读完**后，才定出 P34–P38 五条。其中 P34/P35/P36 是**真实闪退点**（已修），P37/P38 是列表与位图复用缺陷（在修）。另有 6 条核实后**确认本项目无缺口**，见下节表格。

---

### 已核对但**不是**缺陷（记录以免重复排查）

| 位置 | 核对结论 |
|---|---|
| `activity/MenuActivity.kt:157-161` | 对方 `MenuActivity.java:183` 加了 `instanceof` 守卫；本项目该处**已有**双重守卫（`instance != null && instance.lifecycle.currentState != Lifecycle.State.DESTROYED`），`:162` 的 `btnNames[name]!!` 也被 `:156` 的 `containsKey(name)` 保护。**无需改动。** |
| `util/NetWorkUtil.java:131-160` 的 trust-all `X509TrustManager` | 与 S3 一致：minSdk 24 下 `SDK_INT > 22` 恒真，是死代码。对方因 minSdk 19 才需要真修。 |
| `activity/MenuActivity.kt` 的 `as` 强转 | 全文件无硬 `as` 强转（grep 无命中），对方的"菜单强转"修复对本项目不适用。 |
| `app/src/main/AndroidManifest.xml:34` `android:networkSecurityConfig="@xml/network_security_config"` | 本项目**已做**明文流量按域名白名单收敛（v1.1.2 对应项），且 `:28` `allowBackup="false"`。**无缺口。** |
| `activity/player/PlayerActivity.kt:274` `bottom_buttons`、`ShortVideoPlayerActivity.kt:315` `bottomControl` | 均为视图字段，与 P7 的 `bottom` 状态字段无关，不受 P7 影响。 |
| `activity/video/info/VideoInfoFragment.kt:580-584` | 对方 `VideoInfoFragment.java:547-556` 修的是"三连成功后**在后台线程**改 ImageView"；本项目该处**已经是** `runOnUiThread { coin/like/fav.setImageResource(...) }`。**无需改动。** |
| `api/DynamicApi.java:385` | 对方 26f1742 修 `Pattern.compile("@" + name)` 未转义导致昵称含正则元字符时抛 `PatternSyntaxException`；本项目**已用 `Pattern.quote(key)`**（连注释都与上游一致）。**无需改动。** |
| `api/DynamicApi.java:594` 与 `:611` | 对方补 `modules == null` 时 `dynamic.userInfo = new UserInfo();` 防下游 bind NPE；本项目结构不同 —— `:594` 先 `UserInfo userInfo = new UserInfo();`、`:611` 无条件 `dynamic.userInfo = userInfo`。**无需改动。** |
| 全库 Java 文件里的 `Map.of(...)` | 对方 26f1742 因 `minSdk < 30` 把 `Map.of` 改成静态块 `HashMap`；本项目修完 P34 后全库已无 `Map.of` / `Set.of` / `List.of`。**无需改动。** |
| `activity/player/PlayerActivity.kt:1178-1180` | 对方 26f1742 才给 `onlineTimer` 补"重建前 `cancel()`"；本项目**已有** `onlineTimer?.cancel(); onlineTimer = null`。**本项目更优。** |
| `activity/player/PlayerActivity.kt:1648-1655` | 对方 `onDestroy` 在 `isFinishing == false` 时提前 `return`，会漏掉 native 播放器与 5 个 Timer 的释放；本项目用 `destroyed` 标志后**无条件清理**。**本项目更优。** |
| `activity/player/PlayerActivity.kt:172` / `:319` | 对方修 `danmaku_url.equals("")` 的 NPE；本项目 Kotlin 侧是 `danmaku_url: String = ""` + `intent.getStringExtra("danmaku") ?: ""`，不可能 NPE。**无需改动。** |

---

## 四、🟡 功能差距（对方有，本项目无 / 部分有）

### F1. 后台/熄屏继续播放 + 通知栏遥控（v1.1.2 核心功能）

对方实现要点：前台服务保活 + 通知栏遥控（播放/暂停、关闭、进度条）+ 点通知回到播放页。

**本项目现状**：

- `PlayerActivity.kt` **已有** `MediaSession`（22 处引用，`initMediaSession()` 位于 `:1548`，`updateMediaSessionPlaybackState()` 被多处调用）。
- **但没有前台服务**：全库 `startForeground` / `startForegroundService` 只出现在 `service/DownloadService.kt:440/534/537`（下载用）。

**后果**：熄屏或切后台播放会被系统杀。**这对本项目（手表 + 低内存设备）是最实际的功能缺口。**

---

### F2. 搜索番剧

对方：搜索结果左右滑动到「番剧」页，可直接搜番剧并进详情页观看。

**本项目现状**：`api/SearchApi.java:57-59` 的 `search_type` 参数由调用方传入，`:105` 已经能解析"番剧卡片" —— **底层解析能力已存在，缺的是 UI 入口**。

---

### F3. 其他对方有、本项目的逐条核实结果

| 功能 | 对方版本 | 本项目状态 |
|---|---|---|
| 番剧选集区（多季/剧场版/OVA 切换 + 高亮正在播放） | v1.1.1 | ◐ **部分有**：有水平选集条 + 季下拉 + 选中高亮（`BangumiInfoFragment.kt:209-236`、`:256-276`、`MediaEpisodeAdapter.kt:25-31`）；缺①顶部季选项卡②「正在播放」标记（无 `ic_episode_playing`）③集卡片副标题（`:74` 只显示 `title`） | F3-a |
| 历史更新日志子页面（按版本分选项卡） | v1.1.1 | ◐ **有页面、无版本维度**：按 `## YYYY-MM-DD` 日期分组，非版本选项卡 → 见 F5 | F5 |
| 应用更新后首次启动自动展示当版日志 | v1.1.1 | ❌ **无**：`SharedPreferencesUtil.java:52` 的 `last_version` 是**死键**（0 处使用）→ 见 F4 | F4 |
| 带图发动态（最多9张，GIF/PNG透明原样上传） | v1.1.0 | ❌ **无**。唯一半成品：`DynamicApi.java:86 publishComplex(contents, pics, option, topic, scene, attachCard, otherArgs)` 有 `pics` 参数位、`:94-96` `if (pics != null) reqBody.put("pics", pics)`，但 4 个调用点（`DynamicActivity.kt:128`、`DynamicApi.java:144/156/199`）**全传 null**；`SendDynamicActivity.kt`（202 行）只有表情按钮与投票编辑区；无 `api/ImageApi.java` | F3-b |
| 带图发评论（根评论附图） | v1.1.0 | ✅ **有**：`WriteReplyActivity.kt:59-72`/`:153-175`/`:178-230`、`ReplyApi.java:185-196 uploadReplyImage` → `upload_bfs`、`:228-232 sendReply` 带 pictures。**但压缩策略劣于对方**：`WriteReplyActivity.kt:206-215 compressImage` 一律 `BitmapFactory.decodeStream` + `compress(JPEG,100)`，**GIF 动图与 PNG 透明必丢**；对方是 GIF >20MB 才拒、PNG ≤8MB 原样透传 | F3-c |
| 动态投票查看与参与（图片投票、多选） | v1.1.0 | ✅ **有**：`VoteInfoActivity.kt`（363 行，`:191 multiChoice = info.choice_cnt > 1`、`:219-227` 图片投票、`:289-298 VoteApi.doVote`）；卡片内联 `DynamicHolder.kt:262 showVoteCard`、`:403 doVote(vote_id, listOf(...))`（**只能单选**）。⚠️ `VoteInfoActivity` 的启动点只有 `OpusContentAdapter.kt:381-382` 与 `ReplyAdapter.kt:449-450`，**动态卡片上没有跳转入口** → 若要"在动态流里多选/图片投票"，应降级为部分有 | F3-d |
| 置顶/取消置顶自己动态 | v1.1.0 | ❌ **无**：全仓 `set_top\|rm_top\|setTop` 0 命中；`model/Dynamic.java:23 isTop` 只是**显示用**标记（由 `DynamicApi.java:611-616` 从 `module_tag` 的 `"置顶"` 文案解析） | F3-e |
| 动态可见范围切换 | v1.1.0 | ❌ **无**：`private_pub\|private_pub_setting\|setPrivatePub` 0 命中；「仅自己可见/所有人可见/可见范围」0 文件命中 | F3-e |
| 编辑自己纯文字动态 | v1.1.0 | ❌ **无**：`edit/dyn\|upload_id\|editDynamic\|编辑动态` 0 命中；`SendDynamicActivity.kt:120-138` 只回传 `text` + `voteDraft`，无编辑模式 | F3-e |
| 动态正文话题/BV号/链接可点击跳转 | v1.1.0 | ◐ **部分有**：动态正文 **@** 可点击（`DynamicApi.java:687` → `StringUtil.setSingleAt`）；**opus 图文正文与评论正文**的 BV/网页链接可点击（`OpusContentAdapter.kt:397`、`model/Reply.java:127`；`StringUtil.java:195/236/323/344-356`）。**缺**：(a) **动态正文**里 BV/网页链接不可点击 —— `DynamicApi.java:654-691 analyzeTextContent` 把 `RICH_TEXT_NODE_TYPE_WEB` 当纯文本 `append(orig_text)`（`:675-681`），且 `DynamicHolder.kt:476-480` **不调 `setLink`**；(b) **`#话题#` 进话题动态页：无** —— 无 `TopicDynamicActivity`，`feed/topic\|getTopicDynamicList` 0 命中，`LinkUrlUtil.java:25-30` 的 `TYPE_` 常量只到 `TYPE_UID=4`（无对方的 `TYPE_TOPIC=5`/`TYPE_VOTE=6`） | F3-f |
| 图文动态详情改为接口直取（提速到 1 秒内） | v1.1.0 | ❌ **无**：仍抓 SSR 网页（`TerminalContext.java:202-208/213-218` → `OpusInfoActivity.kt:46` → `:220-230 fetchOpus` → `OpusApi.java:24`）；`OpusApi.java:33-36` 拼 `opus/` 或 `read/cv` URL，`:41-57` 循环 3 次抓 HTML + 手动跟最多 5 层 301，`:59 JsonUtil.search(html,"detail","")` 抠 SSR JSON。**接口方案 `OpusApi.java:150-161`（`x/polymer/web-dynamic/v1/detail` + `analyzeOldStyleDynamic`）整段被注释掉**。注：**旧式动态详情本来就是接口直取**（`DynamicApi.java:367-368 getDynamic`），慢的只是 opus 图文这条 | F3-g |
| 动态/专栏评论点赞 | v1.1.2 | ❌ **无**：`ReplyApi.java:243-249 likeReply(long oid, long root, boolean action)` 内 `:245` **硬编码 `&type=1`**，调用点只有 `ReplyAdapter.kt:313`/`:332`，都不传 type。而 `ReplyApi.java:31-35` 的常量**齐全**（`REPLY_TYPE_DYNAMIC=17`、`ARTICLE=12`），只是没用上 | F3-h |
| 表情渲染为表情图片（不再以纯文本发出） | v1.1.0 | ❌ **无**：`SendDynamicActivity.kt:140-142` 把表情以**纯文本**回填编辑框（`editText.append(data.getStringExtra("text"))`）再原样发出；`DynamicApi.java:211-251 parseAtContent` 只产出 type 1（text）/ type 2（@），**无 type 9** | F3-i |
| 转发动态/分享视频到动态时自动引用原作者 | v1.1.0 | ❌ **无**：`DynamicApi.java:155 relayVideo(String,Map,long)`、`:171/:198 relayDynamic(...)` 签名里**没有任何作者/原文参数**；调用点 `VideoInfoFragment.kt:102` 只传 `(text, atUids, videoInfo.aid)` | F3-i |
| 发布动态选项（仅自己可见/关闭评论/定时发布） | v1.1.0 | ❌ **无**：`DynamicActivity.kt:128-130` 调 `publishComplex(contents, null, null, null, 1, attachCard, null)`，`option` 位传 null 且**无处构造**；`dynamic_control` 相关布局无 `optVisibility`/`optComment`/`optTimer` | F3-j |
| 动态卡片显示评论数、点击直达评论区 | v1.1.0 | ❌ **无**：`res/layout/cell_dynamic.xml` 的 id 清单**无 `item_dynamic_comment`**；`DynamicHolder.kt` 未使用 `stats.reply`，likes 区（`:654-712`）之外无评论入口 | F3-k |
| 「分支维护者」分区（设置→关于） | v1.1.2 | ➖ 无（属对方品牌信息，**不需要补**） | —— |

> 注：既有文档 `docs/功能对比报告-BiliTerminal-vs-ReBiliClient.md` 对比的是**另一个仓库**（`PianoEthan/BiliTerminal`，v3.1.0-Qx，minSdk 14），与本文档对象不同，无重复。

---

### F4. （功能缺失）"更新后首次启动自动展示当版日志"

**现状**：`app/src/main/java/com/RobinNotBad/BiliClient/util/SharedPreferencesUtil.java:52` 定义了 `last_version` 这个键，但 `grep -rn last_version app/src/main/java/` **只命中这一处定义，0 处使用** —— 是一个**死键**。

`MenuActivity.kt` 无版本比较、`SplashActivity.kt:169-225` 无、`UpdateHistoryActivity` 的唯一入口是 `activity/settings/AboutActivity.kt:122`（需用户手动进"关于"页）。

**对方的实现**：`MenuActivity` 中 `VERSION_NAME != last_version` 时写回 + `UpdateLog.indexOf(version)` + 启动 `UpdateLogActivity(version_index)`。**依赖"版本选项卡"式更新日志（见 F3 第 9 条），本项目不具备该数据模型。**

---

### F5. （功能差距）"历史更新日志"是按日期分组，不是按版本选项卡

**本项目其实已经有这个页面**（这一点容易被误判为"无"）：

- 入口：`activity/settings/AboutActivity.kt:121-124` → `UpdateHistoryActivity`
- 注册：`AndroidManifest.xml:109-113`，`exported=false`，label="历史更新日志"
- 实现：`activity/settings/UpdateHistoryActivity.kt:20-62` 读 `R.array.update_history_log`（数据源 `res/values/strings.xml:349` 起），按 `## YYYY-MM-DD` **日期分组**渲染

**缺的是"版本维度"**：对方是 `util/UpdateLog.java`（`LOG[版本][条目]` + `count` / `version` / `items` / `indexOf`）+ `VersionTabAdapter` + `activity_update_log.xml` + `version_index` Intent 参数 —— 本项目**这些类与布局全部不存在**。

差异的实际后果：F4（更新后自动展示当版日志）无法实现；用户也无法按版本跳转查看。

---

## 五、工程规范差距

### E1. 发布产物的校验与可追溯性

> ✅ **本项目已修**（`4d88b98`）：新增 `verifyVersionConsistency` Gradle 任务（校验 strings.xml 更新日志锚点与 config.json），接入 `check` 与 CI；`util/UpdateManager.kt` 改为显式兼容字符串/原生类型并在解析失败时记日志；`readme.md` badge 与 `docs/FEATURES.md` 基线已纠偏。提交 `8996a1a` = UpdateManager，`f26407e` = 文档

对方 release note 的通行做法：**versionCode/versionName + ABI 说明 + 覆盖安装说明**，并**对部分版本**附 MD5 校验表 / 实机测试清单（逐版本覆盖情况见 §1 的表）。

本项目：`readme.md:7` 的 badge 停在 **26.09.07**；`docs/FEATURES.md:6` 写"适用版本 **26.08.14**"（落后 5 个版本）；版本号分散在 `app/build.gradle:29-30`、`config.json:2-3`（**被 .gitignore 排除，CI 无法校验**）、`app/src/main/res/values/strings.xml:342`。三处靠人工同步，本次审计就发现 `readme.md` 与 `docs/FEATURES.md` 均已漂移。

配套缺陷：`config.json:6` 把 `"forceUpdate"` 写成了**字符串** `"false"`（应为布尔），能工作只因 `util/UpdateManager.kt:113` 的 `json.optBoolean("forceUpdate", false)` 会把字符串 `"false"` 容错解析；同理 `:109` `json.optInt("versionCode", 0)` 依赖字符串解析、`:115` 用 `versionCode == 0` 判断"配置文件格式错误"。**这不是设计上的容错，是配置写错类型后被解析器救回来** —— 一旦 `config.json` 里出现真正无法解析的值，行为将不可预期。

### E2. 崩溃可观测性

> ✅ **本项目已修**（`34e5bc9` 修 P5，`6b2dea1` 补 release MD5 表）
>
> 澄清一个此前的误记：本项目**并非**只有"崩溃页来不及显示"这一个问题 ——
> 它其实**已经有用户手动上传崩溃堆栈**的能力，而上游反而没有把这条路走通。详见下。

**① 崩溃页被抢在显示前杀进程** —— 见 P5。`ErrorCatch` → `CatchActivity` 这条链本身没问题，
问题是 `ErrorCatch.java` 在 `startActivity` 之后**立刻** `killProcess`，而 `startActivity` 是异步的。
上游的做法是补 `Thread.sleep(300)`；本项目已照做（`app/src/main/java/com/RobinNotBad/BiliClient/ErrorCatch.java:48-51`）。

**② 崩溃堆栈上传** —— 本项目**已有**，而且比上游完整：

| | 本项目 | 对方 |
|---|---|---|
| 上传接口 | `api/AppInfoApi.java:196 uploadStack` → `https://api.biliterminal.cn/terminal/upload/stack` | 有同样的 `AppInfoApi` |
| 上传入口 | `activity/CatchActivity.kt:59-76` 接线 `R.id.upload_btn`，走 `CenterThreadPool.run{}` + `runOnUiThread` 回填报错 ID | ❌ `activity/CatchActivity.java:61` 在 `if (stack != null)` 分支里**无条件** `btn_upload.setVisibility(android.view.View.GONE)` —— 按钮被隐藏，用户根本点不到 |
| 上传门控 | `activity/CatchActivity.kt:46-57` `allowUpload`：只放行"未知的崩溃原因"，`NumberFormatException`/`UnsatisfiedLinkError`/`JSONException`/`OutOfMemoryError` 四类已知原因直接禁用按钮并提示"此类型报错不可上传" | 无（因为整条路都关着） |
| 未登录处理 | `activity/CatchActivity.kt:61-62` 提示"我们不对未登录时遇到的问题负责"并跳过上传 | 无 |
| 失败重试 | `activity/CatchActivity.kt:71` `if (res.code == -1) btnUpload.isEnabled = true` | 无 |

**仍然没有的**：远程**自动**崩溃上报（不需要用户点击、下次启动静默上报）。
这一点**双方都没有**，本项目不落后，因此不作为缺陷处理。

**新增的**：release 产物的 MD5 校验值（`6b2dea1`），见本档上方"仍未修复"清单的更正 —— 详见 §五 附带项。

### E3. CI 覆盖面

> ✅ **本项目已修**（`09623cd`）：新增 `.github/workflows/ci.yml`：`push: branches:[main]` + `pull_request: branches:[main]` + `workflow_dispatch`，跑 `assembleDebug` + `testDebugUnitTest` 并上传报告；`build-release.yml` 在 `assembleRelease` 前插入单测步骤，测试红不再能发版

| | 本项目 `.github/workflows/build-release.yml` | 对方 `.github/workflows/ci.yml`（91 行） |
|---|---|---|
| 手动触发 | ✅ `workflow_dispatch` | ✅ `workflow_dispatch` |
| tag 触发 | ✅ `push: tags:['*']` | ✅ `push: tags:['v*']` |
| **PR 触发** | ❌ **无** | ✅ `pull_request: branches:[main]` |
| **分支推送触发** | ❌ **无** | ✅ `push: branches:[main]` |
| 构建命令 | `./gradlew :app:assembleRelease` | `:app:assembleDebug --stacktrace`，tag 时再 `:app:assembleRelease` |
| 跑单元测试 | ❌ **无**（grep `test\|lint\|check` 零命中） | ❌ **无** |
| 签名 | 依赖本地 keystore 配置 | tag 时从 `KEYSTORE_B64` 环境变量 base64 解码到 `$HOME/release.jks`，缺失则产出未签名包 |
| 产物 | APK + 自动创建 GitHub Release | Debug/Release artifact + `gh release view/upload/create` |
| 其他 | —— | `.github/ISSUE_TEMPLATE/{bug_report,feature_request,config}.yml` |

**本项目最实际的缺口是"没有 PR 触发"**：任何 PR 都不会被编译，合入后才发现构建失败。加一行 `pull_request: branches:[main]` 即可，成本极低。

**双方共同缺口是没有测试步骤**：本项目已有 112 个用例、对方 0 个用例，都**不在任何自动化路径中执行**。本项目只需在 CI 里加一步 `./gradlew :app:testDebugUnitTest`，就能让这些用例真正起作用；`build.bat` / `b.bat` 同样只 build/install，也不跑测试。双方也都**没有运行时 smoke 验证** —— 26.09.13 的 `NoSuchMethodError` 类崩溃，编译期、Lint、单测都查不出。

---

## 六、修复优先级建议

> 排序依据：**用户可感知的损失 × 触发概率 × 修复成本**。S 系列是安全问题，P 系列是功能与稳定性，F 系列是功能差距。

> **📌 执行状态**：以下第一至第五优先的 S / P 系列条目**已全部修复并提交**（逐条对应关系见卷首「〇·附、修复状态总览」与各条目标题下的 ✅ 标记）。**第六优先**中只有 F3 的两项（评论点赞类型、评论图片上传）顺带修掉，其余功能补齐仍未做。

### 第一优先（安全问题，建议立即修）

| # | 问题 | 位置 | 修复要点 |
|---|---|---|---|
| **S1** | 重定向可外泄登录 Cookie | `util/NetWorkUtil.java:112-120` | 加 B 站域名白名单 + 跳数上限（对方实现见 §2 S1） |
| **S2** | 凭证进日志 | `api/CookieRefreshApi.java:87-88` | 删除这两行；给 `Logu` 加凭证脱敏 |

> S1 是本次审计中**唯一一条"可在正常使用中触发、且导致登录态整体外泄"**的问题。触发点 `b23.tv` 短链位于常规链路（`LinkUrlUtil.java:130`）。同族的 **P9**（`api/OpusApi.java:43-48` 手动跟跳无域名白名单，且协议相对 `Location` 会直接抛异常）应一并修 —— 它与 S1 是同一模式的第二次出现。

### 第二优先（数据丢失 / 资源耗尽，用户会直接感知）

| # | 问题 | 位置 | 修复要点 |
|---|---|---|---|
| **P16** | 下载失败时递归删除整个视频文件夹，**已下好的视频一起没** | `service/DownloadService.kt:1487-1500`、`util/FileUtil.java:128-132` | `onDestroy` 里不要 `deleteFolder`；`getVideoDownloadPath(title, "")` 不要返回目录本身；改为只清 `.part` 等临时文件。**对方也未修，这是双方共有的坑** |
| **P15** | 下载弹幕解压遇损坏数据 → 100% CPU 死循环 | `util/NetWorkUtil.java:569-573` | 补 `if (decompresser.needsInput() \|\| decompresser.needsDictionary()) break;` + `if (i == 0) break;`（对方实现见 §3 P15） |
| **P4** | 数据库升级丢失下载记录 | `helper/sql/DownloadSqlHelper.kt:26` | `oldVersion == 3` → `oldVersion < 4`；加 `oldVersion >= newVersion` 守卫 |
| **P21** | 后台线程未捕获异常直接杀进程 | `util/CenterThreadPool.java:79-82`、`ErrorCatch.java:45-46` | 协程体补 `catch (Throwable)`；`killProcess` 前 `Thread.sleep(300)` |

### 第三优先（功能性静默失效 —— 用户会以为"这功能不存在"）

| # | 问题 | 位置 | 修复要点 |
|---|---|---|---|
| **P1** | 观看进度上报静默失效 | `api/HistoryApi.java:32`、`:84` | 改用实时 Cookie 派生 csrf/mid；另查其余 10 处 csrf 读取点 |
| **P27** | 播放失败被伪装成"播放完毕"，且无重试入口 | `activity/player/PlayerActivity.kt:878-882` | `onError` 置错误态并 **`return true`**（返回 false 时 ijkplayer 会改发 onCompletion），加提示与重试入口 |
| **P23** | 番剧播放进度整条链路缺失（读不到也写不了） | `api/HistoryApi.java`、`api/PlayerApi.java:295-331`、`JumpToPlayerActivity.kt:47-56` | 补 `reportHistoryPgc`；`getBangumi` 读 `last_play_*`；历史列表改 `type=all` 并解析 `epid/progress` |
| **P25** | WBI 密钥按天缓存 + 先写标记后取密钥 + `sortUrlParams` 丢弃含 `=` 的参数 | `api/ConfInfoApi.java:74-83`、`:79`、`:103-134` | 30 分钟 TTL、成功后才落 `last_wbi_time`、按首个 `=` 切分、MONTH+1 |
| **P17** | 下载进度通知可被异常永久静默 | `service/DownloadService.kt:1011-1031` | TimerTask 体内整体 try/catch |
| **P18** | 消息中心三类独立缺陷（首次失败永久卡死 / system 分支 NPE / 后台改 UI） | `activity/message/NoticeActivity.kt:47-49`、`:57-59`、`:90` | catch 里复位 `bottom` 与 `setRefreshing(false)`；`cursor` 判空；数据改动收敛到主线程 |
| **P24** | 多P续播跳到错误位置 + 切分P后记录记到旧分P | `api/PlayerApi.java:146-152`、`:266-272`、`activity/player/PlayerActivity.kt:2113-2127`、`:2997-3009` | 续播前校验 `last_play_cid == 请求 cid`；切P前上报；`finish()` 回传 cid |
| **P22** | 弱网下点赞 / 投币 / 发弹幕会重复执行 | 见 §3 P22 的六个调用点 | 对照 `activity/reply/WriteReplyActivity.kt:105-119` 的 `sent` 标志模式加客户端互斥 |
| **P26** | 评论区无世代号 / 无超时 / 无空页重试，且数据改动在主线程之外 | `activity/reply/ReplyFragment.kt`、`adapter/ReplyAdapter.kt:367` | 加 `loadGeneration` + deadline + 空页重试；`removeAt` 移回主线程 |

### 第四优先（播放器卡死与退出，需一起修）

| # | 问题 | 位置 | 修复要点 |
|---|---|---|---|
| **P2** | 渲染线程调 JNI | `player/DanmakuManager.kt:172-181` | 位置改由后台定时器维护，`updateTimer` 只读内存值 |
| **P3** | `join()` 无超时 | DFM 三个文件（见 §3 P3） | `join(2000)` + `isAlive()` 告警 |

> P2 与 P3 是同一根因的两半，**必须一起修**：只修 P3 会让卡住的渲染线程继续存在；只修 P2 则主线程仍可能在别处无限等待。**P30**（退出路径的资源释放与主线程阻塞）与本组同源，一并复核。

### 第五优先（其他并发与生命周期缺陷）

| # | 问题 | 位置 | 修复要点 |
|---|---|---|---|
| **P20** | `started` / `exitCode` 无 `@Volatile`，`start()` 无同步 | `service/DownloadService.kt:53-56`、`:429-430` | 加 `@Volatile` + `synchronized` |
| **P7** | `bottom` 缺 `@Volatile`（后台写、主线程读） | `activity/base/RefreshListActivity.kt:30`、`activity/base/RefreshListFragment.kt:24` | 加 `@Volatile`（对方 `RefreshListActivity.java:28-29` 有明确注释） |
| **P8** | `playerData` 竞态：后台赋值未完成时点播放即 NPE | `activity/video/info/VideoInfoFragment.kt:124`、`:617`、`:647` | 加 `@Volatile` + 各处判空；删掉 `:281-284` 的死代码 `if (playerData == null) return@run` |
| **P10** | `SharedPreferencesUtil` 读取方法无空保护 → 静态初始化器内 NPE（启动即崩且本进程内不可恢复） | `util/SharedPreferencesUtil.java:67`、`:75`、`:83`、`:91`、`:103` | 五个读取方法都加 `sharedPreferences != null ? ... : def` |
| **P11** | `webHeaders` 可变 ArrayList 跨线程共享 | `util/NetWorkUtil.java:462` | 改 `volatile`（对方 `NetWorkUtil.java:480`） |
| **P12** | `ViewPagerFragmentAdapter` 基类选错 → `Fragment already added` | `adapter/viewpager/ViewPagerFragmentAdapter.kt:7-8` | 换 `FragmentPagerAdapter` + 实例映射；5 个 Activity 在用 |
| **P19** | 番剧选集空季 → `IndexOutOfBoundsException` | `activity/video/info/BangumiInfoFragment.kt:219-228`、`:239`、`:304` | 切季后 clamp + 空季回退到非空分区 |
| **P28** | 音频模式切换无销毁守卫 → 重建的播放器无人释放（native 泄漏） | `activity/player/PlayerActivity.kt:1978-1982` | 新建前加 `if (destroyed \|\| isFinishing()) return;` |
| **P29** | SplashActivity 网络波动会清空登录态 | `activity/SplashActivity.kt:93-99` | 刷新失败时沿用本地 mid，不要 `resetLogin()` |
| **P31** | 退出登录用 GET 且无 csrf，服务端会话不失效 | `api/UserInfoApi.java:212-220` | 改 POST + `csrf=` |
| **P32** | 搜索建议乱序无保护 + `hasFocus` 门禁丢结果 + 输入法不弹 | `activity/search/SearchActivity.kt:232-234`、`:249`、`:519-531` | 加建议代际号；`requestFragmentFocus()` 加 hasFocus 守卫；点击输入框补 `showSoftInput`；补 `clearComposingText` |
| **P33** | `asyncInflate` 就绪即硬切，低性能设备过渡动画丢失 | `activity/base/BaseActivity.kt:396-407`、`util/AsyncLayoutInflaterX.java` | `setAlpha(0f)` + `Choreographer` 帧驱动淡入（对方 `c36715a`） |

### 第六优先（功能补齐）

F1（后台播放 —— 缺口最实际，但本项目 `targetSdk 34` 使成本高于对方：需 `foregroundServiceType="mediaPlayback"` 与 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限）、F2（搜索番剧 UI 入口）、F4（`last_version` 是死键，从未实现"更新后首次启动展示当版日志"）、F5（更新日志改为按版本分选项卡），以及 §4 F3 表中的各项。

### 附带项（低优先，但顺手）

- **S3**：删除 `NetWorkUtil.java:131-152` 的 trust-all 死代码（本项目 `minSdk 24` 下不生效，但留着是隐患）。
- **`Logu.getCaller()` 栈下标硬编码**：`util/Logu.java` 的 `Thread.currentThread().getStackTrace()[4]` 依赖调用深度，不同重载深度不同。对方改为"向前找第一个 `Logu` 之外的帧"：

  ```java
  StackTraceElement[] stack = Thread.currentThread().getStackTrace();
  StackTraceElement caller = stack[stack.length - 1];
  for (int i = 3; i < stack.length; i++) {
      if (!stack[i].getClassName().equals(Logu.class.getName())) {
          caller = stack[i];
          break;
      }
  }
  ```
- **`Cookies.set()` 的 null 处理**：对方给 `util/Cookies.java` 的 `set(String key, String value)` 加了 `if (value == null) { cookieMap.remove(key); return; }`，避免 `toString()` 拼出 `"key=null"` 字面量污染 Cookie 串。

---

## 七、核实方法说明

本文档中标注为"现状代码"或给出文件路径 + 行号的内容，全部经过在本仓库中直接读取源码核实。方法分两层：

1. **对方侧**：克隆 `https://github.com/cyq114514/Re-BiliTerminal` 到 `.dsh/ref/Re-BiliTerminal`（HEAD `201c68f`，18 个提交）；用 `git show <commit>` 逐条读取修复实现；用 GitHub API 抓取 7 个 tag 的 Release 正文与 tag 注释；用 `git ls-tree` 核实对方文件布局、ABI 配置与 CI 配置。
2. **本项目侧**：先用 `grep -rn` 大面积定位，再对每个命中点用 `sed -n 'Np'` 逐行回读确认，最后记录文件路径与行号。

§3 P6 与 §4 F3 中此前标"未核实"的条目**现已全部逐条核实完毕**：由 4 个并行审计子代理分别负责**动态**、**番剧**、**下载与消息中心**、**播放器/登录/搜索**四个方向，结论见 P15–P33 与 F3–F5。子代理返回的结论我均抽样回读原文复核过。

### 本轮的自我更正（记录以免后人重复误判）

| 我最初的判断 | 核实后的结论 | 错在哪 |
|---|---|---|
| 本项目没有 CI | **错**：`.github/workflows/build-release.yml` 存在且已被 git 跟踪 | 只看目录名没看内容 |
| `ViewCapabilityProbe` 的反射会被 R8 破坏，需要 keep 规则 | **错**：唯一反射点 `util/ViewCapabilityProbe.kt:103` 的目标是 `android.view.View`，框架类永不被重命名/剥离 | 没核对"被反射目标是框架类还是应用类" |
| `RefreshListActivity.bottom` 从不被赋值 | **错**：约 40 处赋值，Kotlin 子类写的是无点号的 `bottom = true` | grep 模式写成了 `.bottom\s*=` |
| "搜索建议永远为空"（对方 1.1.0-fix1 修的）在本项目存在 | **错**：`api/SearchApi.java:164-188` 读的就是 `result` 字段，自 `4d34790 Initial commit` 起如此 | 直接套用了对方的 bug 描述，没有先读本项目代码 |
| 对方每次发布都提供 MD5 表 | **需限定**：只有 v1.0.2-fix1 / v1.1.1 / v1.1.1-fix 有，v1.1.2 起丢失 | 只抽查了一个 Release |
| 对方自建 `OkHttpClient` 是为了旧系统 TLS 兼容 | **在本项目不成立**：`util/NetWorkUtil.java:131-133` 的 `if (Build.VERSION.SDK_INT > 22) return okHttpBuilder;` + minSdk 24 ⇒ trust-all 分支是死代码 | 把对方的修复理由当成对本项目也成立的解释 |

对方的修复实现主要来自提交 `48bbf21`（1.0.2-fix1）、`26f1742`（1.1.1-fix）、`c36715a`（1.1.0-fix1）、`201c68f`（1.1.2），可通过以下方式查看：

```bash
cd .dsh/ref/Re-BiliTerminal
git show 48bbf21
git show 26f1742
git show c36715a
git show 201c68f
```

> `.dsh/` 已被 `.gitignore` 排除（提交 `0318dd7` "忽略 .dsh/ 代理脚手架目录"），该克隆不会进入版本库。
