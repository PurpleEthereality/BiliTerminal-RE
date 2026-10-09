# 修复台账（活文档）

> 这是**唯一**的当前状态 + 待办台账。完整历史正文已归档到 `docs/archive/review/fix-progress-history.md`（§一~§四十三 逐批次流水，4777 行）。
> 什么时候写这里：修完一个 bug / 关掉一个待办 / 每轮真机验证后。**别再往下追加长流水**——超过一屏就并进「历史章节索引」那一行。
> 架构与坑看 `docs/architecture-map.md`；功能清单看 `docs/FEATURES.md`；真机清单看 `docs/review/real-device-regression-checklist.md` 与 `docs/review/device-test-plan.md`。

## 1. 当前版本状态

- **版本号**：`26.10.10`（`app/build.gradle` 的 `versionName`），`versionCode 2610102`。
- **本版 tag**：`26.10.10.1`。
- **上一版**：`26.10.10` / `2610101`（正常发版）已占用 tag `26.10.10`——release immutability 下被 Release 用过的 tag **永久不可复用**，所以补丁版只能用 `26.10.10.1`。
- **下次递增**：先看 `app/build.gradle:23-24`，并保留 `26.10.10.1` 这种「同版本名 + 补丁后缀 tag」的习惯；`versionCode` 递增、`versionName` 可不变。
- **构建命令**：
  ```
  .\gradlew.bat :app:assembleDebug
  .\gradlew.bat :app:testDebugUnitTest
  .\gradlew.bat :app:assembleRelease
  ```
- **改了 `res/` 文件集合时**：`:app:clean` 与后续命令**必须分两次 Gradle 调用**，且带 `--no-build-cache --no-configuration-cache`，否则会回放陈旧资源（症状是 `Unresolved reference 'R.layout.xxx'`）。
- **版本一致性**：`:app:verifyVersionConsistency` 以 `strings.xml` 的 `update_log_current` 为锚点校验版本一致性；发版说明也由 CI 从它抽取（抽不到直接拒绝发版）。
- **单测（静态计数）**：**49 个测试类 / 460 个用例**，对 395 个源文件。计数方式：`app/src/test/` 下 49 个 `*Test.kt|java`，`app/build/test-results/testDebugUnitTest/*.xml` 的 `<testcase>` 求和为 460。
- **⚠️ 这是静态计数，不是本轮跑通的结果**：本机最近一次 test-results 时间戳为 `2026-10-09T12:24:26`，早于 26.10.10 补丁，属陈旧结果。发版前必须跑一次 `:app:testDebugUnitTest`（必要时 `--rerun-tasks`，防 build cache 回放）确认全绿。
- **用例数一律以机器产出为准**：读 `app/build/test-results/`，队友口述数字不进入文档。
- **本轮紧急撤回**：「自动跳过片头片尾」处于**撤回状态**（26.10.10 用户明确要求，非缺陷）——设置入口已隐藏、运行期恒不生效，代码与存档保留。恢复清单 6 步见 §2.2 第一条与 §3.3 末尾。

## 2. 未完成待办

### 2.1 真机未验证（无设备 / 无登录态）

- [ ] **评论带图 12066**：`img_size` 小数截断是已确证的确定性缺陷，但**与 12066 的因果关系未建立**。
  - 12066 在本地快照与上游 collector 均无文档（`bilibili-API/docs/comment/action.md` 错误码表只到 12001-12052）。
  - 验证方法：真机在 `ReplyApi.sendReply` 失败分支打印**完整响应体**取回 `message` 原文。§二十九(第二处) / §36.5
- [ ] **笔记「无法正常加载」根因未确证**：已排除 csrf、`note_id_str` 精度设计、UI 层。
  - 仍存假设：H1「服务端按 `num` 解析 17 位 `note_id` 丢精度 → 79502」、H2「空 `csrf` 被当参数错误」。**头等未验证项**。
  - 验证方法：同一 id 分别按字符串 / 按 num 各发一次对比。§29.1 / §29.5 / §36.5
  - `api/NoteApi.java:259-276`（getNoteInfo 不带 csrf，与 `bilibili-API/docs/note/info.md:65-71` 一致）
  - 未改清单：`note_id` 传参方式一行未动；`activity/video/` 下无任何写入（`VideoInfoFragment.kt:563-569`）
- [ ] **稍后再看「未看完」第二层待真机**：本地判据已修，但服务端 `viewed=2` 的真实语义未确认。若仍为空 → 抓 `history/toview/web` 的请求 `viewed` 值与响应体。§三十五 / §36.5
- [ ] **收藏夹排序行**：逐条排查后**未发现可修缺陷**（两处入口汇合到同一个 `FavoriteVideoListActivity`）。
  - 真机排查指令：`adb logcat -s "debug-收藏夹排序"`，正常应 `sortBar.visibility=0 mediaId=<非0> fid=<非0> readOnly=false`。
  - 临时诊断埋点在 `activity/user/favorite/FavoriteVideoListActivity.kt` 的 `setupSortBar()` 末尾，倾向保留。§二十二 / §36.5
- [ ] **关注分组移动成员未真机验证**：`FollowGroupAdapter.getUserOwnerGroup(position)` 的位置算术只做过静态复核。§三十六 / §36.5
- [ ] **片头片尾跳过**：跳过逻辑本身（`player/ViewPointSkip.kt`）一行未动，行为改变全在默认值 / 引导判据上；真机清单第 199/200 条未跑（且 §四十三 已整体撤回）。§三十三 / §36.5
- [ ] **评论侧「长按双弹窗」未复现**（`res/layout/cell_reply_list.xml` 兄弟节点分析证明布局层面不可能）。§30.2 / §30.4
- [ ] **`MANAGE_DEDUP_MS = 400L` 去重未真机验证**：`app/build.gradle` 只有 junit 与 org.json，**无 Robolectric**，`SystemClock` 无法进 JVM 单测。§30.3 / §30.4
- [ ] **300×300 表盘上弹窗实际可点性未在真机确认**。§30.4
- [ ] **未决项：`activity/reply/ReplyInfoActivity.kt` 分页插入起点 `+2` 疑似越界** ——「发现但未修改」。
  - 建议单独立项 + 真机复现（楼中楼详情页 → 切时间排序 → 持续下滑翻页 → 观察 `IndexOutOfBoundsException` / `Inconsistency detected` / 新页错位）。
  - 判越界前必须先确认**宿主页第 0 位放了什么**：同一个 `ReplyAdapter`，在 `activity/reply/ReplyFragment.kt:250`（主评论列表）第 0 位是「头部占位」，在 `ReplyInfoActivity`（楼中楼详情页）第 0 位却是**根评论**，前提正好相反。§30.5b
- [ ] **动态详情页线上 NPE 未真机复现**：用户只给了混淆栈、未给触发场景。若补丁后同一栈还在，说明真凶是被 R8 内联进来的别处，需新 mapping 重新 retrace，并附触发页面 + 操作步骤。§38.5 / §38.6
- [ ] **收藏夹顶部工具条三处修法均未真机复现**（团队无设备）：
  - (1) 取消勾选不暗（排除法：`itemView.alpha` 只由 `VideoCardHolder.applySelection()` 写）。
  - (3) 若真机仍在列表中途展开，说明 `recyclerView.canScrollVertically(-1)` 不可靠，需改用 `computeVerticalScrollOffset() == 0`。§39.5
- [ ] **滚动收放与长按面板的真机手感未验证**：动画与手势走 View 层，JVM 单测覆盖不到（正因如此才抽出 `util/view/ScrollRetractDecider.kt`）。§37.3 / §37.2
- [ ] **新增反馈页 / 关于页入口 / 崩溃自动上报 / 启动弹窗全部未真机跑过**（条目已补进回归清单第 216~222、223~227 条，**尚未执行**）：
  - ① 关于页 → 反馈页能进、分类、4000 字上限、断网报错文案、成功清空；
  - ② 三个开关关掉后抓包确认无到 `rebiliterminal.zsapp.asia` 的请求；
  - ③ 故意崩溃一次，崩溃页显示「报错ID」且服务端 `/admin/api/crash` 可见；
  - ④ 公告页在上游接口挂掉时仍能显示自建公告（反向也要成立）。§41.7
- [ ] **隐私同意闸门未真机验证**（条目已补进回归清单第 228~231、236 条，**尚未执行**）：全新安装 / 清数据后首次启动必须弹一次；点「不同意」能正常进主界面且抓包无请求。§41.8

### 2.2 功能与工程待办

- [ ] **「自动跳过片头片尾」处于撤回状态**，恢复清单 6 步未执行（撤回为 26.10.10 用户明确要求，非缺陷）。§3.3
- [ ] **26.10.05 那个已发布的 Release 说明没能补上**：本机无 `gh` CLI 也无 GitHub token，只能人工在网页端编辑，把 `update_log_current` 的 28 条粘进去。§40.5
- [ ] **服务端没有告警渠道**：只有 systemd `Restart` + `health.log` 留痕（`*/10` 探活 cron 失败只追加一行，要真告警还得接通知渠道）。§42.9
- [ ] **管理控制台只有口令登录**，没有 TOTP / IP 白名单；口令泄露 = 全部后台能力。§42.9
- [ ] **控制台 WebUI 没有真机 / 真浏览器回归记录**（环境无浏览器），只验证了 HTML 结构与接口联通。§42.9
- [ ] **话题广场仍是单页**（`bottom = true`，不翻页）；`page_info.has_more` 已在数据里，将来要翻页可直接接。§三十四
- [ ] **`WriteReplyActivity.kt` 的 `pendingUploads++/--`（`:224`/`:253`）未加同步保护**：已发现主线程入口与后台 `finally` 各改一次、无同步，「与本次用户报的 bug 无关，改动会引入无法验证的风险」→ **发现但不动**。§29.5
- [ ] **§32 遗留的视觉面未动**：
  - 新增 `colorError` 语义色**未重新做对比度测量**（`docs/archive/visual-experience-report.md` §2.25：`#EBE0E2` 叠 `#FF6699` 约 2.1:1）；
  - §2.12「86 处布局引用静态调色板」与 `styles.xml` 组件样式硬编码同源，本次未动。§三十二
- [ ] **收藏夹排序行「更醒目」的视觉标识没做**（无依据，且会动 `res/layout/activity_simple_refresh.xml`）。§二十二
- [ ] **`docs/review/real-device-regression-checklist.md` 第 159 条未改**（不属该轮范围）。§二十二
- [ ] **「个性化设置」开关落点需用户确认**：`long_press_panel_enable` 落在 `activity/settings/SettingPrefActivity.kt`（`setPageName("偏好设置")`，「内容与浏览 → 通用偏好」进入）的「功能」组、紧挨「长按复制」；用户心里的「个性化设置」若是别处，搬走只需改这一行。§37.1
- [ ] **`DynamicApiTest` 曾失败的 2 例（`DynamicApiTest.kt:151`/`:203`）与 `NoteApiTest.kt:198` 1 例**：**已闭环**（§36.3 修掉并全绿），修法见归档「长期结论」；此处仅作登记。
- **功能类 backlog 一律看** `docs/superpowers/plans/2026-08-27-new-features-roadmap.md`，不在本文件展开。

### 2.3 明确不做（别再排期）

- **F4 漫画**：快照 `bilibili-API/docs/manga/` 无任何「追漫/收藏漫画列表」接口，也无漫画搜索接口，项目内漫画实现为零（只有 `activity/user/VipActivity.kt:79-80` 会员权益文案）。
- 用户 26.10.04 明确「漫画算了」，记 **不做**（不是暂缓：暂缓意味着还打算做）。§三十一

### 2.4 视觉技术债（26.10.10 从 `docs/archive/visual-experience-report.md` 回迁；数字均为当时 grep 实测）

> 真机验证项已并进 `docs/review/real-device-regression-checklist.md` 第十三节（第 238~247 条）。下面只留「代码里真的还没做」的部分。

- [ ] **顶栏仍是 45 份手抄复制品**：`cell_topbar.xml` 与 `include layout="@layout/cell_topbar"` 全工程 **0 匹配**，`android:id="@+id/top"` 在 **45 个 layout** 里各自手写（`activity_simple_refresh.xml:9`、`activity_menu.xml:10`、`activity_player.xml:144` …），`@dimen/topbar_height`（30dp / w300dp 36dp）**0 引用**（死 token）；退出靠 `BaseActivity.kt:182-197 setTopbarExit()` 代码兜。**批次 4 的公共顶栏成果实际已被回退**（详见 `docs/architecture-map.md` §8.1/§8.5/§7.4），顶部返回是 45 个页面的唯一返回入口、高度无 48dp 保证。值得做（风险中）。
- [ ] **无障碍：`contentDescription` 覆盖率 4.3%**：全 `res/layout` 仅 5 处（`activity_loading.xml:46`、`cell_follow_group.xml:46`、`cell_loading.xml:9`、`fragment_qr_login.xml:57`、`item_vote_option.xml:20`=`@null`），而 `<ImageView|<ImageButton` 共 **115 处** → 缺口 **110**；装饰图也没有 `importantForAccessibility="no"`。TalkBack 全站不可用。可脚本化批量补，风险低。
- [ ] **播放器触控热区大量 <48dp**：`activity_player.xml` 28dp 高 11 处（`:310/319/328/337/347/357/367/388/397/406/418`）、37dp 5 处（`:226/236/245/255/265`）、进度条 `:201`=20dp / `:464`=32dp / `:645`=35dp；该文件对 `@dimen/touch_min`（48dp）**0 引用**。（2.17「点下去零反馈」已修，尺寸没改。）
- [ ] **无自适应图标**：`res/mipmap*` 只有 `mipmap-nodpi/`（约 52 个 png/webp），无 `mipmap-anydpi-v26`、无 `ic_launcher_foreground/background`；桌面图标形状由系统强制裁切。需要设计资产，非纯代码任务。
- [ ] **残留 px 单位与全透明短写色**：`layout_height="0px"` `activity_download.xml:23`、`cell_video_local.xml:87`，`1px` `activity_player.xml:16/17`（共 5 处）；`background="#6000"` `activity_search.xml:129`，`background="#0000"` `cell_article_end.xml:117/140/164` + `fragment_video_info.xml:229/252/276`（共 7 处）。一次改完，零风险。
- [ ] **过小字号**：`textSize="8|9|10|11sp"` 共 **57 处**，其中真正不可读的 6 处是 8sp（`activity_player.xml:181`）、9sp（`activity_captcha_webview.xml:33`）、10sp（`activity_feedback.xml:66/109`、`activity_player.xml:479/493`）；`android:alpha` 在 layout 下仍有 **142 处**（文字靠 alpha 拉层级基本没动）。先修那 6 处成本极低。
- [ ] **浅色主题 `Theme.BiliClient.Light` 是死样式**：`themes.xml:45` 定义、全工程 **0 引用**（活的是 7 套 `Theme.*` + `Theme.*.NoSwipe.AppCompat`）；弹幕颜色仍无设置项（grep `danmaku_.*color` 0 匹配）。二选一：接线或删掉。
- [ ] **`colors.xml` 别名膨胀**：`<color name=` **≥328 条**（`zhihu_*`/`iqiyi_*`/`purple_fantasy_*`/`rainbow_*`/`classic_gray_*`/`terminal_*` 各约 60 条，绝大多数指向同一 hex）。有了 `ui/appearance/ColorScheme.kt` 这个真源后，别名层是纯负担；清理需一批回归。风险中。

**别再重复排查（已排除）**：WebView 白底闪屏**不存在**；菜单按钮 `MaterialButton` 默认 `minHeight=48dip` **已达标**；`ui/widget/recycler/` 与 `fragment_empty.xml`、`BiliTerminalApp.kt` 是**死代码**（不当在用组件排查）。**不做**：裸 `Activity` 收编（`PlayerActivity`/`ImageViewerActivity`/`SplashActivity`/`GetIntentActivity`）——播放器保持黑色系统栏是有意为之，且 `PlayerActivity.kt` 3000 行，收益不抵回归风险（理由已进 `docs/architecture-map.md` §8.7.3）。**可接受**：`CardStyle`/`ButtonStyle` 强制 `clickable+focusable`；间距/圆角 token 已建但布局未全量改用；同构布局重复；布局层静态色已清干净（`@color/(card_dark|pink|divider_dark|pink_light)` 在 `res/layout` 0 处）。

## 3. 最新状态快照（26.10.09 ~ 26.10.10）

### 3.1 自建反馈 / 公告 / 匿名统计 / 崩溃上报（§四十一）

- **起因（用户原话）**：想在反馈界面加入「发送到服务器的反馈界面」和「遥测，看看有多少人会用这个应用」。
- **拍板**：用已有的 HK 服务器自建后端；统计口径只要**匿名唯一安装数 + 日活**；**默认开、设置里可关**；公告**不是替换上游，而是增加一个自己的公告源**。
- **为什么另起一套**：原本全仓没有任何反馈界面（全仓 grep `反馈`/`feedback` 只命中注释，渠道全是站外 QQ 群 / GitHub / Gitee issue；`about_trailer_feedback` 只是免责声明**连链接都没有**）。
- 上游 `api/AppInfoApi.java`（4 个接口，指向 `api.biliterminal.cn`）不是本 fork 的；本 fork 的中转服务已停用（`.github/workflows/relay-notify.yml:40-45` Secrets 未配置），**目前没有任何自己的后端**。
- 上游 `AppInfoApi.customHeaders`（`AppInfoApi.java:110-140`）虽带 `App-Info`/`Device-Info` 且故意不带 Cookie，但**没有任何唯一标识**，数不了「多少个不同的人」。

**服务端（新增 `server/`，已部署 HK 并公网验证）**

- 文件：`server/rebiliterminal_api.py`（单文件 FastAPI + SQLite，无 ORM）、`server/requirements.txt`、`server/rebiliterminal-api.service`、`server/nginx/rebiliterminal.zsapp.asia.conf`、`server/README.md`；另有 `server/console.html`、`server/selftest.py`、`server/backup.sh`（§四十二）。
- 部署：`/opt/rebiliterminal-api/`（`rebiliterminal_api.py` + `env`(chmod 600) + `data.db`）；systemd 单元 `rebiliterminal-api`；uvicorn 监听 `127.0.0.1:8788`；nginx 反代 `https://rebiliterminal.zsapp.asia/terminal/`。`env` 里是 `REBILITERMINAL_DB` 与 `REBILITERMINAL_ADMIN_TOKEN`。
- 证书**绕开宝塔**：`/www/server/panel/class/acme_v2.py` 会查 `default.db` 的 `sites` 表、必须先有宝塔站点记录，本机也无 acme.sh → **自装 acme.sh 走 webroot 模式**（`--issue -d rebiliterminal.zsapp.asia --webroot /www/wwwroot/rebiliterminal.zsapp.asia`），再 `--install-cert` 到 `/www/server/panel/vhost/cert/…` + `--reloadcmd "nginx -s reload"`，续签靠 acme.sh 自带 cron。
- 接口沿用上游风格：**成功一律 `code=0`，失败 `code!=0` + `msg`，HTTP 恒 2xx**。
  - `GET /terminal/health`；
  - `GET /terminal/announcement/get_list[?from=<id>]`（id = 自增 id **+ 1000000000**，故意与上游 id 空间错开；不带 `from` 是倒序列表页，带 `from` 是启动弹窗差量、只回 `id>from` 且正序）；
  - `POST /terminal/telemetry/ping`（按 `install_id` 聚合 + `daily_active(install_id, day)` 主键去重）；
  - `POST /terminal/feedback/submit`（category 白名单 `bug|suggestion|other|content|performance`，content ≤ 4000 字，同 IP 每小时 10 次）；
  - `POST /terminal/upload/stack`（返回的 `id` 就是给用户看的报错编号）。
- 限流：进程内滑动窗口（ping 480/时、feedback 10/时、crash 30/时，按 `X-Forwarded-For` 首段）。
- 管理看板：`GET /admin?token=<REBILITERMINAL_ADMIN_TOKEN>`（HTML）+ `/admin/api/stats|feedback|crash`（JSON）+ `POST /admin/announcement`。
- **隐私红线（改动前必读）**：整套服务端**不存 IP**（只用 IP 限流、不入库），不存 Cookie；`install_id` 是客户端随机 UUID、与账号无关、不读 `ANDROID_ID` 或任何设备指纹，卸载重装即换新。

**客户端新增（名称已按 `app/src/main/java` 核实）**

- `api/TerminalApi.java` —— 这条链路**唯一**出口（`BASE_URL = https://rebiliterminal.zsapp.asia/terminal`）。
- 请求与解析分离：`buildPingPayload` / `buildFeedbackPayload` / `buildCrashPayload` / `parseAnnouncements` / `mergeAnnouncements` / `dayKey` 都是纯函数。
- 单测 `app/src/test/java/com/RobinNotBad/BiliClient/api/TerminalApiTest.kt`（14 例，专门断言载荷里不出现 sessdata / bili_jct / dedeuserid / cookie / csrf）。
- `util/TelemetryReporter.kt`：启动时一天最多 ping 一次，**只有请求成功才写回日期**（断网启动不浪费当天机会）。
- `util/CrashReporter.kt` + `util/CrashTrail.kt`：`ErrorCatch` 在崩溃瞬间把异常/消息/线程/最近页面轨迹/uptime 塞进 Intent，`CatchActivity`（独立进程）在开关打开时自动上报。
- `activity/FeedbackActivity.kt` + `res/layout/activity_feedback.xml`（分类 / 4000 字计数 / 联系方式选填 /「附带账号 ID」开关 / QQ 群兜底 / 发送状态与报错号），清单注册 `android:exported="false"`。
- 其他改动：`BiliTerminal.kt`（`onActivityResumed` 记页面轨迹）、`ErrorCatch.java`、`activity/CatchActivity.kt`、`activity/SplashActivity.kt`、`activity/settings/AboutActivity.kt` + `activity_setting_about.xml`、`activity/settings/SettingGroupActivity.kt`、`activity/settings/AnnouncementsActivity.kt`、`util/SettingsKeys.kt`、`res/layout/activity_catch.xml`、`AndroidManifest.xml`。
- `SplashActivity` 启动**并联**两件事（自建公告差量 + 匿名日活），**各自包异常** —— 自建服务器挂掉绝不能影响启动。
- **入口挂关于页，不只挂设置页**：「关于与帮助」加「反馈与统计」组（三个开关）；菜单结构对老用户是缓存的，新分组不会自动出现，只挂设置分组等于老用户看不到入口。
- **上游手动上传按钮逻辑一行没动**；自动上报**故意不套上游那两道限制**（「已登录 + 异常类型白名单」）。

**隐私文案（改这块之前先读）**

- 两句公开承诺 `strings.xml` 的 `about_to_uncle`（「…也不会收集任何用户隐私信息。」）与 `text_setup_introduction`（「…不会收集你的任何账号及隐私信息，请放心使用！」）**已同步改写**。
- 新增 `desc_setting_telemetry` / `desc_setting_crash_auto` / `desc_setting_feedback_attach_mid`。**以后往上报载荷里加任何字段，必须同时改这三处文案**，否则承诺又是假的。
- 默认值：`TELEMETRY_ENABLE = true`、`CRASH_REPORT_AUTO = true`、`FEEDBACK_ATTACH_MID = false`（账号 ID **默认不带**；关掉时传 `0`，服务端那条记录里根本不会有这个值）。

**§41.8（26.10.09 补丁）：隐私「再同意一遍」启动闸门**

- 取舍（用户拍的板）：弹窗点「不同意」= **App 照常使用，只是不上报**（不是拒绝进入应用），两个开关直接关掉，以后可在设置里手动打开；只有隐私说明改版才会再问一次。
- **两个 key 分开，是这一节核心**：`SettingsKeys.PRIVACY_CONSENT_VERSION`（Int，0 = 从未同意，决定**能不能上报**）与 `SettingsKeys.PRIVACY_PROMPTED_VERSION`（Int，0 = 从未问过，同意/不同意都算，决定**还要不要弹**）。`TerminalApi.PRIVACY_VERSION = 1`，**改隐私文案必须 +1**。
- **闸门收在 `TerminalApi` 里，不靠调用点自觉**：`isTelemetryEnabled() = hasPrivacyConsent() && getBoolean(TELEMETRY_ENABLE, true)`；`isCrashReportAutoEnabled() = hasPrivacyConsent() && getBoolean(CRASH_REPORT_AUTO, true)`。
- 另有 `hasPrivacyConsent()` / `needsPrivacyConsent()` / `acceptPrivacyConsent()` / `declinePrivacyConsent()` / `recordPrivacyConsentFromSettings()`；`declinePrivacyConsent()` 只记 prompted 版本 + 两开关置 false，`recordPrivacyConsentFromSettings()` **只记版本号、不碰任何开关**。
- 落地点：`SplashActivity.proceedSplashFlowWithPrivacyGate()`（**两个调用点都改走闸门**：`onCreate` 的 UETool 权限之后、`onActivityResult` 的 UETool 分支；两条路都继续启动流程，不拦人）。
- 设置页两个 `switch(...)` 补 onChange，**只在 `on == true` 时** `recordPrivacyConsentFromSettings()`（`SettingsAdapter.kt:201-207` 是**先写 SharedPreferences、再回调 onChange**）。
- `MsgUtil.showMsgLong(...)`：默认路径是 sticky `SnackEvent`，闪屏（`class SplashActivity : Activity()`，**不是 BaseActivity**）不消费，下一个 `BaseActivity` 在 `onResume`（`BaseActivity.kt:355-361`）取出显示并 `removeStickyEvent`（`MsgUtil.java:121/125`）→ 刚好弹一次、弹在新页面上。**是有意为之，别改成 Toast。**
- **老用户不需要迁移**：`getInt(key, 0)` 键不存在时返回 0，正好等价于「从未同意」。
- 验证：`TerminalPrivacyConsentTest.kt` **7 例全绿**；当时 `:app:testDebugUnitTest --rerun-tasks` 全量 **48 个测试类 / 450 例全绿**（含 `TerminalApiTest` 14 例）。
- 同一补丁新增 `TerminalDialog.choice(...)` 与 `layout_dialog_terminal.xml` 的固定底栏 `@id/terminal_dialog_buttons`。
  - 原来 `Sheet.addButton` 把按钮加进 `terminal_dialog_list`（**在 ScrollView 内部**），而 `capScrollHeight()` 把滚动区高度**钉死在屏高 45%**、滚动条又是 `scrollbars="none"`；长正文会把按钮顶到看不见，配上必要的 `setCancelable(false)` 就是**用户彻底卡在闪屏上**。
  - `Sheet` 加构造参数 `bottomButtonBar: Boolean = false`（默认 false = 原行为，既有 12 处 `confirm/alert/menu` 零影响），只有 `choice()` 用 `true`。
  - **为什么必须新增 `choice()` 而不是复用 `confirm()`**：`confirm()` 的取消按钮写死 `dismiss()`（无回调），且内部是**先 `dismiss()` 再 `onConfirm()`**，`setOnDismissListener` 触发时还拿不到「用户点的其实是确定」，两条路都会走一遍。

### 3.2 管理控制台 WebUI + 服务端并发加固（§四十二）

- 需求：域名 `console.zsapp.asia`（泛解析 `*.zsapp.asia` 已指向本机，**不需另加 DNS 记录**）；登录用**登录页 + HttpOnly 会话 Cookie**，旧 `GET /admin?token=…` 保留作兜底。
- 容量结论：`telemetry/ping` 每安装每天最多 1 次，公告每次启动 1 次差量 → 日均请求 ≈ 2~3 × 日活。
  - 1 万日活 ≈ 0.3 req/s 均值、约 1 req/s 高峰；10 万日活 ≈ 3 req/s 均值、25~30 req/s 高峰。
  - **瓶颈不是 Python 而是每次 commit 的 fsync**。
- **并发加固两条（真实隐患）**：
  - ① 阻塞 I/O 跑在事件循环上 —— `telemetry_ping`/`feedback_submit`/`upload_stack` 是 `async def` 却直接 `conn.execute(...)`；一旦撞锁走 `busy_timeout=15000`，**整个事件循环卡最多 15 秒**，连 `/terminal/health` 都不响应。修法：库访问全抽成同步 `_db_*` 函数，由 `from starlette.concurrency import run_in_threadpool` 调度，**api 层不再出现 `connect()`**。
  - ② `connect()` 没设 `synchronous` → WAL 下默认仍 `FULL` → 每次 commit 都 fsync；改 `PRAGMA synchronous=NORMAL`。
- 会话：`_session_value(exp) = <exp>.<hmac_sha256(ADMIN_TOKEN, exp)>`，`exp = now + 7 天`，**不存服务端状态**。
- `_check_token(request, token)` 三种凭据任一通过（URL `token` / `X-Admin-Token` 头 / 会话 Cookie）→ **12 处既有调用点一次都没动**；Cookie `HttpOnly; Secure; SameSite=strict; Max-Age=604800; Path=/`。
- **CSRF 两道独立防线**：`SameSite=strict` + 状态变更接口额外要求 `Content-Type: application/json`（跨站表单只能发 urlencoded/plain，必须先过 CORS 预检），不满足直接 415；登录接口 `rate_limit(ip,"login",20,3600)`。
- WebUI：`server/console.html` 单文件、**零外部依赖**（不引 CDN / 图表库，国内访问 jsdelivr/unpkg 不稳），折线图手写内联 SVG。
- `GET /` 原样吐出，**带 mtime 缓存**（改前端只覆盖文件、不用重启）。四个页签：公告/反馈/崩溃 + 顶部遥测卡片与图表；60s 只刷遥测，**不覆盖公告输入框内容**。
- 新接口：`GET /admin/api/announcements`（返回**内部 id**，客户端看到的仍是 `ANN_ID_OFFSET + 内部 id`）、`POST /admin/api/announcement`、`/announcement/update`（`{id, pinned?, active?}`）、`/announcement/delete`。
- `GET /admin/api/stats` 扩了 `week_active`/`today_new`/`feedback_pending`/`crash_pending`/`brands`，`daily` 每条多了 `new_installs`（**纯增量，旧字段一个没改**）。
- nginx：`server/nginx/console.zsapp.asia.conf` 80+443、HTTP 跳 HTTPS、`/terminal/` 直接 `return 404`（控制台域名不开放客户端接口）、HSTS + `X-Content-Type-Options` + `X-Frame-Options DENY` + `Referrer-Policy: no-referrer` + 紧 CSP。
- 备份：`server/backup.sh` + cron `7 4 * * *`，用 **`sqlite3 .backup` 而不是 `cp`**（WAL 下最近写入还在 `-wal` 里），存 `/opt/rebiliterminal-api/backups/data-<YYYYMMDD>.db`，保留 14 天，校验表数量（少于 5 张非零退出）。探活 cron `*/10` 失败只追加 `health.log` 一行。
- 验证：`server/selftest.py`（临时 sqlite + 临时口令，**不碰生产数据**）**39 项断言全通过**。
- 公网端到端全部按契约返回（`console.zsapp.asia/` 200 + 证书 + 5 个安全头；发布公告 `public_id=1000000002` → 立刻能从 `rebiliterminal.zsapp.asia/terminal/announcement/get_list` 拉到）。测试数据已清理，`sqlite_sequence` 复位。

### 3.3 紧急撤回「自动跳过片头片尾」（§四十三）

- **用户原话**：「将跳过片头片尾的功能紧急撤回，直接关闭且隐藏此设置，立刻发新版本26.10.10 2610102 tag打新的」，追加「更新公告追加一条说明即可」。要求是**撤回而非删除**：代码与设置项保留，只让它不生效、入口不可见。
- **为什么必须新加开关，而不是把默认值改成 false**：`SharedPreferences.getBoolean(key, def)` 里的 `def` 只在**键不存在**时生效，而老用户从 26.10.09 起就已被写入 `true`（首次读取即落盘），改默认值对这些人一点作用都没有。
- **撤回否决位（逐字，恢复时只改这一行）** —— `app/src/main/java/com/RobinNotBad/BiliClient/player/SkipOpEdPrefs.kt`：
  ```kotlin
  const val DEFAULT_ENABLED = true   // 保持 true，恢复后行为与本补丁前完全一致
  const val FEATURE_ENABLED = false  // 26.10.10 撤回；恢复时只改这一行
  fun isEnabled(prefValue: Boolean): Boolean = FEATURE_ENABLED && prefValue
  ```
- `DEFAULT_ENABLED` **必须保持 `true`**：`app/src/test/java/com/RobinNotBad/BiliClient/player/SkipOpEdPrefsTest.kt:41` 的 `默认值是开启` 断言钉着它（那是用户上一轮明确要求的「默认必须是开启」）。改它既撤回不了老用户，还会弄红测试。
- **撤回四处落点**（`isEnabled` 组合是运行期闸门，另三处是可见性 / 白拉接口的收口）：

  | 位置 | 改动 |
  |---|---|
  | `player/SkipOpEdPrefs.kt` | 新增 `FEATURE_ENABLED` + `isEnabled(prefValue)` 纯函数；object 头注释加「26.10.10 已紧急撤回」 |
  | `activity/player/PlayerActivity.kt` `skipOpEdEnabled()` | 改为 `SkipOpEdPrefs.isEnabled(getBoolean(PLAYER_SKIP_OP_ED, DEFAULT_ENABLED))`，即运行期恒 `false` |
  | 同上 `maybeShowSkipGuide()` | 开头 `if (!SkipOpEdPrefs.FEATURE_ENABLED) return`（引导里「开启」按钮会写回存档） |
  | 同上 `needViewPoints()` | **显式删掉 `\|\| PLAYER_SKIP_OP_ED`**，只留 `PLAYER_SHOW_VIEWPOINTS`（光有运行期闸门不够：老用户存档还是 `true`，不改判断仍会为「跳过」白拉一次 `view_points`） |
  | `activity/settings/SettingTerminalPlayerActivity.kt:132-133` | `add(SettingSection("switch", "自动跳过片头片尾", …))` 两行注释掉，`:10` 的 `import ...player.SkipOpEdPrefs` 一并注释，两处写明恢复方法 |
  | `activity/settings/SettingsIndex.kt` | 搜索关键词表删掉 `"自动跳过片头片尾"`，否则留一个点进去定位不到的幽灵条目 |

- `player/ViewPointSkip.kt`（纯判定）与其单测**一行未动** —— 撤回是关闸门，不是拆功能。
- **测试钉子**：`SkipOpEdPrefsTest.kt` 新增 3 例 —— `功能已撤回时总开关必须是关闭`、`撤回期间存档里写着开启也不能跳`（先断言存档确实读出 `true`，再断言 `isEnabled` 为 `false`）、`撤回期间两种存档取值都判定为不启用`；类注释新增第 4 条「撤回必须与存档无关」。
- **公告与版本**：`app/src/main/res/values/strings.xml` 的 `update_log_current` 追加第 8 条「暂时下线「自动跳过片头片尾」：设置入口已隐藏，之前开启过的也不再自动跳过，播放时不会再有相关提示。相关代码与设置项保留，后续视情况恢复。」
- `app/build.gradle` versionCode `2610101` → **`2610102`**，versionName 仍为 `26.10.10`；tag 用 **`26.10.10.1`**（`26.10.10` 已被 2610101 的不可变 Release 用掉，release immutability 下被 release 用过的 tag 永久不可复用）。
- **版本序列（26.10.09 → 26.10.10）**：写 §四十一 时版本仍是 `2610090` / `26.10.09`，随后按发版技能正常发版，落成 **`2610101` / `26.10.10`**（`update_log_current` 补本次 7 条 + `update_history_log` 顶部新增 `## 2026-10-09` 分组）；同日又因紧急撤回追加 **`2610102` / `26.10.10`**。
- 本地 `verifyVersionConsistency` + `testDebugUnitTest`（457 例）+ `assembleRelease` 全绿，四个 ABI 的 release APK 已产出，然后推 tag `26.10.10` 走 `.github/workflows/build-release.yml`。
- **恢复清单（6 步，未执行）**：
  - ① `player/SkipOpEdPrefs.kt` 的 `FEATURE_ENABLED` 改回 `true`；
  - ② `activity/settings/SettingTerminalPlayerActivity.kt:132-133` 两行与 `:10` 的 import 取消注释；
  - ③ `activity/settings/SettingsIndex.kt` 搜索关键词加回 `"自动跳过片头片尾"`；
  - ④ `PlayerActivity.needViewPoints()` 的 `|| PLAYER_SKIP_OP_ED` 判断按需恢复；
  - ⑤ `strings.xml` 的 `update_log_current` 追加一条恢复说明；
  - ⑥ 递增 versionCode / versionName 并按发版技能出包（tag 只能用未被 Release 占用过的新号）。

## 4. 历史章节索引

> 完整正文在 `docs/archive/review/fix-progress-history.md`。**编号在历史上有重复**（`## 二十二` / `## 二十九` 各出现两次、`## 30.` 是阿拉伯数字写法，原为不打断既有交叉引用而保持不动），下表**以归档文件的物理行序为准**。
> §一~§三十 的「收尾状态」按归档正文与 §五 / §十 的登记推断：批次类记「已闭环」，正文明确登记遗留的记「部分遗留」，总览 / 明细 / 建议 / 验证记录类记「纯历史」。

| §编号 | 标题 | 一句话内容 | 收尾状态 |
|---|---|---|---|
| 一 | 修复总览 | 286 条基线（Critical 23 / High 52 / Medium 105 / Low 106）+ 2026-09-24 深度审查新增 严重 10 / 中等 13 组 / 轻微 11 | 纯历史 |
| 二 | 已修复问题明细 | 逐轮修复记录，逐条 file:line（第一~四轮表格为主） | 纯历史 |
| 三 | 审查前已修复（本次核查确认，无需改动） | 审查前已完成、本次核查确认无需改动的项 | 纯历史 |
| 四 | 构建验证状态 | 当时的构建 / 单测记录与结论 | 纯历史 |
| 五 | 待处理问题（按优先级） | 绝大多数已 `[x]`；仍未关闭的 A7 WebView 输入校验 / A8 教程键污染 / A9 硬编码专栏 id / E1 拆 PlayerActivity / 发布侧 APK 校验等见 §2.2 | 部分遗留 |
| 六 | 下一步建议 | 4 条早期建议，已过时 | 纯历史 |
| 七 | GitHub issue 修复轮次（2026-10-03） | issue #1 手表右滑返回与滑动调进度冲突；根因 `android:windowSwipeToDismiss` 在窗口层直接 finish + `setTheme(ColorScheme.themeResId(theme))` 覆盖 `Theme.NoSwipe*` | 已闭环 |
| 八 | 发版链路改造：Gitee 发行版同步 + 更新检查改读发行版（2026-10-03） | 发行版同步到 Gitee，客户端更新检查改读发行版而非 tag | 已闭环 |
| 九 | 高能进度条接口变更适配（2026-10-04） | pbp 接口变更后的适配 | 已闭环 |
| 十 | 26.10.04 逐条拍板后的待做队列 | A1~A12 / C6b / E1~E6 结论映射表 + 已作废项（D2 跑马灯、D3 圆屏、D5 候选词）+ 数字勘误 + 8 批落地顺序；仍开放的即 A7 / A8 / A9 / E1 | 部分遗留 |
| 十一 | 26.10.04 批次 1：A 组快修落地记录 | A 组快修逐条落地 | 已闭环 |
| 十二 | 26.10.04 批次 2：A1 csrf 实时化 + A10 评论点踩 | csrf 实时化与评论点踩 | 已闭环 |
| 十三 | 26.10.04 批次 3：性能参数接线 + 列表增量刷新越界 + 崩溃页独立进程 | 性能参数接线、增量刷新越界排查（判据进 `architecture-map.md` §7.9）、崩溃页独立进程 | 已闭环 |
| 十四 | 26.10.04 批次 4：SettingsKeys 收敛收尾 + 删假批量 API + 更新包校验 | E4 键名收敛（`SettingsKeysTest` 钉键名）+ 删假批量 API + 更新包校验（`ApkVerifier`） | 已闭环 |
| 十五 | 26.10.04 批次 5（1/4）：私信会话管理（C13） | 私信会话管理（置顶 / 删除 / 查看主页） | 已闭环 |
| 十六 | 26.10.04 批次 5（2/4）：私信发图（C12） | 私信发图；`size` 小 1024 倍缺陷（见 `architecture-map.md` §7.14） | 已闭环 |
| 十七 | 26.10.04 批次 5（3/4）：新消息通知栏通知（C14） | 新消息通知栏通知 | 已闭环 |
| 十八 | 26.10.04 批次 5（4/4）：追番更新提醒（C16） | 追番更新提醒 | 已闭环 |
| 十九 | 26.10.04 批次 6（1/8）：评论置顶 / 取消置顶（C3） | 评论置顶与取消置顶 | 已闭环 |
| 二十 | 26.10.04 批次 6（2/8）：评论楼中楼排序（C4） | 楼中楼排序开关（纯客户端 `Reply.sortReplies`）；后续由 §30. 重做成真双向排序 | 已闭环 |
| 二十一 | 26.10.04 批次 6（3/8）：带图评论的三个静默出错（C6b） | 带图评论三个静默出错；12066 未复现 | 部分遗留 |
| 二十二 | 26.10.04 批次 6（4/8）：动态编辑与管理菜单（C7） | 动态编辑与管理菜单（点击与长按弹同一个菜单） | 已闭环 |
| 二十三 | 26.10.04 批次 6（5/8）：动态置顶与取消置顶（C8） | 动态置顶与取消置顶 | 已闭环 |
| 二十四 | 26.10.04 批次 6（6/8）：动态定时发布（C9） | 动态定时发布 | 已闭环 |
| 二十五 | 26.10.04 批次 6（7/8）：话题广场与话题动态列表（C10） | 话题广场与话题下动态列表；广场仍单页不翻页 | 部分遗留 |
| 二十六 | 26.10.04 批次 6（8/8）：视频笔记查看（C27） | 视频笔记查看；「无法正常加载」根因未确证 | 部分遗留 |
| 二十七 | 26.10.04 批次 7（1/4）：稍后再看「未看完」分类（C18） | 稍后再看「未看完」分类；服务端 `viewed=2` 语义待真机 | 部分遗留 |
| 二十八 | 26.10.04 批次 7（2/4）：收藏夹排序与复制 / 移动（C19） | 收藏夹排序行与复制 / 移动 | 已闭环 |
| 二十九 | 26.10.04 批次 7（3/4）：收藏夹多选删除（C20） | 收藏夹多选删除 | 已闭环 |
| 三十 | 26.10.04 批次 7（4/4）：关注分组增删改（C21） | 关注分组增删改（系统分组不可编辑） | 已闭环 |
| 三十一 | 26.10.04 批次 8（1/2）：拆分 DownloadService（E2） | `service/download/` 分层（`DownloadPathSpec`/`DownloadProgressMath`/`DownloadProgressStore`/`DownloadRepository`/`DownloadNotifier`），对外 `@JvmStatic` API 一字未改；F4 漫画裁为不做；测试数 38 类 / 327 例 | 已闭环 |
| 三十二 | 26.10.05：弹窗（选择框）主题接入 + 终端列表收口 | 三个缺失主题属性、7 份 `ThemeOverlay.<X>.Dialog`、`dialog_background.xml`、`util/TerminalDialog.kt`（369 行四方法）、17 处调用点全迁 | 已闭环（当时的编译验证缺口由 §36.3 关闭；对比度未重测属遗留） |
| 二十二（第二处） | 26.10.04 批次 7（5/5）：置顶会话排序 + 收藏夹排序行 + 关注分组移动成员（C22） | `util/SessionSorter.kt`、`FavoriteVideoListActivity` 链路排查（代码无缺陷 + 诊断埋点）、`FollowApi.moveFollowTagUsers` + 长按移动成员 | 部分遗留（真机未验证） |
| 三十三 | 26.10.05：片头片尾跳过默认开启 + 新增「通知设置」大类 | `player/SkipOpEdPrefs.kt`、`GROUP_NOTIFY` 分组、`SettingsIndex` 条目 | 纯历史（后由 §四十三 整体撤回；「通知设置」部分仍生效） |
| 三十四 | 26.10.05：话题广场加载不出来 + `#话题#` 不解析（C23） | `TopicApi` 换 `pub/search`、三处富文本 switch 补 `RICH_TEXT_NODE_TYPE_TOPIC`、`StringUtil.TopicClickableSpan`、`LinkUrlUtil.parseTopicId` | 已闭环（广场单页不翻页属遗留） |
| 二十九（第二处） | 26.10.04 批次 6 复查（C27 视频笔记 + C6b 带图评论） | `NoteApi.failureText` 分级 + `BiliNote` 埋点、`img_size` 改 double / `buildPictures`、私信 `buildImageContentOfKb` | 部分遗留（笔记根因未确证、12066 未复现） |
| 30. | 长按冲突与楼中楼排序二次切换失效（task-4） | `Reply.sortReplies` 真双向排序 + `ctime`、`DynamicHolder.showManage()` 去重、`ReplyAdapter.canManage`、`cell_reply_list.xml` 兄弟节点分析 | 部分遗留（§30.5b 未决项、真机未验证） |
| 三十五 | 26.10.05：稍后再看「未看完」为空（C18 回归）+ 新增「清除所有已看完」（task-2） | 服务端 `viewed` 参数、`isUnfinished`/`filterUnfinished` 判据翻转、`history/toview/clear` + `clearWatched` | 部分遗留（服务端 `viewed=2` 语义待抓包确认） |
| 三十六 | 26.10.05 Lead 终验：全队冻结后的串行全量验证 + 本轮问题闭环对照 | 13 条用户报告逐条对照表、43 类 / 400 例全绿、`AlertDialog` 自检 0 命中、方法论 5 条 | 已闭环（关闭 §29.4/§30.4/§35.6 的构建缺口） |
| 三十七 | 26.10.05 批次 8：长按操作面板（复制收进弹窗）+ 顶部工具条滚动收回 | `util/LongPressPrefs.kt` + `long_press_panel_enable`、「复制文字」进面板、`setupAutoHideBars` / `expandAutoHideBars` / `util/view/ScrollRetractDecider.kt` | 部分遗留（真机手感未验证） |
| 三十八 | 26.10.05 线上 release 崩溃：动态详情页 NPE（用户提供混淆栈） | mapping retrace 定位 lambda、`Dynamic.ensureDetailFields()`、`diFragment.view!!` 改 `view?.let`、6 例新单测 | 部分遗留（未真机复现） |
| 三十九 | 26.10.05 第三批真机反馈：收藏夹顶部工具条三处 | `supportsChangeAnimations = false`、`manageBar` 只在多选态可见 + 长按菜单「多选」入口、`ScrollRetractDecider.action()` 展开条件收紧为「仅 `!canScrollUp`」、`suppressClickAfterLongPress` | 部分遗留（三处均未真机复现） |
| 四十 | Release 说明漏掉更新日志：根因与兜底（26.10.05 发版后） | `.github/scripts/extract_update_log.py`、`build-release.yml` fail-closed、AGENTS/SKILL 硬要求 | 部分遗留（26.10.05 Release 正文未人工补） |
| 四十一 | 26.10.09：自建反馈界面 + 自建公告源 + 匿名统计 / 崩溃自动上报（新功能） | `server/` 全栈 + `api/TerminalApi.java` + `util/TelemetryReporter.kt` / `CrashReporter.kt` / `CrashTrail.kt` + `activity/FeedbackActivity.kt` + 隐私文案改写 + §41.8 隐私再同意闸门（`PRIVACY_CONSENT_VERSION` / `PRIVACY_PROMPTED_VERSION` / `TerminalDialog.choice`） | 已闭环（明细回归清单待补） |
| 四十二 | 26.10.09：管理控制台 WebUI（遥测 / 反馈 / 崩溃 / 公告四合一）+ 服务端并发加固 | `server/console.html` 零依赖单文件、`run_in_threadpool` + `synchronous=NORMAL`、无状态签名 Cookie + 双 CSRF 防线、`console.zsapp.asia` vhost / 证书、`backup.sh` + 探活、selftest 39 项 | 部分遗留（无告警渠道、无 TOTP/IP 白名单、无浏览器回归） |
| 四十三 | 26.10.10 补丁：紧急撤回「自动跳过片头片尾」（2610102） | `SkipOpEdPrefs.FEATURE_ENABLED = false` + `isEnabled()`、四处落点、`SkipOpEdPrefsTest` +3 例、tag `26.10.10.1` | 部分遗留（处于撤回状态，恢复清单 6 步未执行） |

## 5. 仍待回迁 / 仅见于归档的长期结论

> 下列结论目前**只存在于归档正文**，`docs/architecture-map.md` §7.29~§7.36 与 AGENTS.md 都还没有；要么回迁到 `architecture-map.md` §7，要么保持「归档可查」。已被 §7 / AGENTS / §6.5 覆盖的结论不再重复。

- **默认值必须收敛到唯一常量**：片头片尾跳过被用户误判为「压根没实现」，实际接线完整，唯一原因是**四处调用点各写一遍字面量 `false`**。漏掉一处就会出现「设置页显示开着、播放器却不跳」这种**半生效状态**（范式：`player/SkipOpEdPrefs.kt` 的 `DEFAULT_ENABLED`、`util/LongPressPrefs.kt`）。§三十三 / §37.1
- **改默认值天然满足「新装默认开 + 老用户尊重显式选择」，不需要任何迁移 / 重置代码**：`SharedPreferences.getBoolean(key, def)` 的语义就是「键不存在时才用 def」。既有同款范式：`util/SharedPreferencesUtil.java:132-144` 的 `loadMenuEnabled()` 用 `sharedPreferences.contains(key)` 区分。§三十三 / §37.1
- **改默认值 / 改键名是两种效果**：改键名等于把老用户设置读成「没设过」，默认值会把他关掉的开关重新打开 → 单测必须有「键名必须稳定」的护栏。§37.1
- **撤回一个已发布的特性，不能只改默认值**（见 §3.3 的 `FEATURE_ENABLED` 否决位）。§四十三
- **废弃接口的经典陷阱：`code=0` + `data=null` 造成静默空列表**：话题广场原走的 `app.bilibili.com/x/topic/web/dynamic/rcmd` **恒返回 `{"code":0,…, "data":null}`**（带 / 不带参数、游客 Cookie、伪装浏览器 headers、`page_size` 9/25/26 全部一样）。因为 `code` 是 0，`if (code != 0) throw` 永不触发 → 静默 `data == null → 返回空列表` → `showEmptyView()`。**按「网络异常 / 抛异常」方向排查全是死路**。替代源：`api.bilibili.com/x/topic/pub/search`（`keywords` 留空即按热度返回整页话题，稳定 20 条，匿名可用）。§三十四
- **单向排序缺陷的形态**：`model/Reply.java` 的 `sortReplies` 首行 `if (replies == null || sort != SORT_LIKE) return;` → `SORT_TIME`（= 0）直接 return、从不重排 → 「时间→热度→时间」往返后顺序**永久停在热度序**。这是**推翻一个旧的设计取舍**（归档正文 `fix-progress.md:1758`「时间序 = 不做任何本地重排」），不是修疏忽。修法用 `ctime`（服务端**恒返回**）显式升序，两个比较器 `LIKE_ORDER`/`TIME_ORDER` 都是 `private static final` 且各自 null 兜底，**两个分支都幂等**。**`pubTime` 是展示文案、不单调，不能做排序键。**§30.1
- **「排序前备份原列表、切回时还原」被否决**：`Reply` 实现 `Serializable` 且跨页传递，模型里藏 mutable 备份字段经翻页 / 点赞 / 删除后极易变脏。§30.1
- **`Reply` 只实现 `Serializable`、无任何 `Parcelable`**（`model/Reply.java:31`），评论相关 `putExtra` 只传标量 → 新增 `ctime` 无需同步 Parcelable（属**理由修正、结论不变**）。§30.1
- **同一手势承载两种语义 = 用户嘴里的「冲突」**：`util/StringUtil.java:170` 的 `setCopy(TextView, String)` 是业务代码自己注册的长按监听（`SharedPreferencesUtil.getBoolean("copy_enable", true)` + `startActivity(CopyTextActivity)`），不是 `LinkMovementMethod`/`textIsSelectable`。**同一个 TextView 只能有一个长按结果** → 修复方向是「按需让位」，不是去改复制本体。§30.2 / §37.1
- **`performLongClick()` 转发 click 会弹两次菜单**：长按手势结束后系统还会补发一次 click。动态侧原代码 `item_dynamic_delete!!.setOnClickListener { item_dynamic_delete!!.performLongClick() }` 就是真实缺陷。修法：**存普通 lambda 而不是 `View.OnLongClickListener`**，收敛到唯一真相源 `DynamicHolder.showManage()` + `MANAGE_DEDUP_MS = 400L` 去重；`showManage()` 在 `manageAction == null` 时返回 `false` → 不消费长按 → 自动回落到 `setCopy`。§30.2 / §37.1
- **`setOnTouchListener` + `ACTION_DOWN` 返回 `false` 也会有补发 click**（`VideoCardHolder.bindClick()`）：多选态下「长按先选上、抬手立刻取消 = 什么也选不中」，非多选态下「长按弹菜单、抬手又跳详情页」。修法：`suppressClickAfterLongPress` 标记，长按回调真执行就立起、随后那次点击自己吞掉，`bindClick()` 里随新绑定清零。§39.2
- **RecyclerView 的 item animator 会吃掉你的 alpha**：`DefaultItemAnimator` 对 `notifyItemChanged` 是另建 ViewHolder 交叉淡入（`supportsChangeAnimations = true` 时 `canReuseUpdatedViewHolder()` 返回 `false`），动画收尾把新布局 alpha 设成 `1f` → 「勾选（目标 1f）看不出问题、取消勾选（目标 0.45f）被静默改回 1f」。修法：`VideoCardAdapter.onAttachedToRecyclerView()` 里 `(recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false`。§39.1
- **`ScrollRetractDecider` 一条反直觉返回值必须保留**：已收着时**继续向下滚仍返回 `COLLAPSE`**，唯一作用是让调用方把累加值清零；返回 `NONE` 会让累加值一直涨，表现为「条收起来以后怎么滚都不回来」。§37.2 / §39.1（工具条的兄弟节点结构与「展开只允许在列表到顶」的口径已在 §7.28 / AGENTS）
- **「自然高度」必须缓存**：条本是 `wrap_content`，收回被压成 0，而 0 高度量不出「原本多高」→ 否则「收得回去、展不回来」；且「收回」只动当前真正可见的条。§37.2
- **`danger` 不要写死下标**：`setOf(2)` 在菜单插入新项后会漂移，改为 `setOf(actions.indexOfFirst { it.first == "取消收藏" })`。§39.1
- **`local` 判据的朝向决定用户看到什么**：稍后再看旧 `isUnfinished` 首行 `if (progress <= 0) return false;` 把「从未播放」当成「不是未看完」→ 整表都从未播放时「未看完」档**就是空的**（与用户现象逐字吻合）；且本工程**从未给 `history/toview/web` 带过 `viewed` 参数**，服务端能力从未被使用。正确：**主判据 = 服务端 `viewed`（0 全部 / 2 未看完）**，本地 `filterUnfinished` 退化为兜底（`duration <= 0` → 一律算「未看完」；只有 `progress >= duration && duration > 0` 才算「已看完」）。§三十五（§7.23 只覆盖了 `isUnfinished` 的判据本身）
- **`content` 长按点击的 span 陷阱**：`StringUtil.LinkClickableSpan` 的 `case TYPE_WEB_URL:` 取的是 `text`（span 显示文本）而**不是**第三个构造参数 `val`（`StringUtil.java:205` 能工作是因为那里 `text` 就是 URL）；且 `updateDrawState` **硬编码 `ds.setColor(Color.rgb(0x66,0xcc,0xff))`**，执行时会**覆盖 `ForegroundColorSpan`** → 话题必须新写 `StringUtil.TopicClickableSpan`（颜色取 `ColorScheme.INSTANCE.getPRIMARY()`，**不硬编码色值**），解析不出 `topic_id` 时退化为「只上色、不可点」。§三十四
- **R8 合并类的 mapping 不是一对一**：`E0.j` 表头写 `DynamicInfoActivity$$ExternalSyntheticLambda1`，但它同时有 `(CollectionInfoActivity, long)` 构造来源；`C0.c` 底下合并了 `VideoRcmdFragment`/`VideoInfoFragment`/`VideoInfoActivity`/`BangumiInfoFragment` 一串同类 SAM 包装。**单看表头类名定归属会错**，必须结合 `residualsignature` 注释与实际方法签名。§38.1
- **崩的是 release 包就必须单独验 release 链路**：`assembleRelease`（含 `minifyReleaseWithR8`）不能只跑 debug。§38.4
- **`app/build/outputs/mapping/release/mapping.txt` 不进版本库**，换机或 `clean` 后即失 → 线上混淆栈要趁 mapping 还在先 retrace。ProGuard retrace 能读 R8 写进 mapping 的 `# {"id":"sourceFile"}` 与 `residualsignature` 注释，**不需要额外装 R8 retrace**。§38.1
- **`Result.onSuccess` 自己已判空**（`if (isSuccess()) { T value = getOrNull(); if (value != null) accept(value); }`）→ **NPE 只可能在消费者 lambda 体内**，不在 `Result` 里，这是比读 mapping 表头更快的定位路径。§38.1
- **并发构建必须串行**：`app/build/` 共享，Gradle 对 `tmp/kotlin-classes`、`intermediates/javac` 先删再写，多人同跑必互踩并产生**假失败**。判据三选：① 复跑同一命令 ② `git status --short` 前后对比确认工作树静止 ③ 报错文件 `git diff` 与 HEAD 有实际差异；不全只能报「疑似在途中间态」。**类型不匹配 = 真错误；引用不存在的类 = 多为中间态。** §36.7
- **用例数一律以机器产出为准**：读 `app/build/test-results/testDebugUnitTest/*.xml` 的 `<testcase>` 计数；队友口述「23 例」实际 25 例（场景数口径与 `@Test` 数口径混用）。口述数字不进入正式文档。§36.7
- **正式文档只写正确结论**；唯一例外是「以错误理由做出的正确决定」，写成「**理由修正、结论不变**」并保留原错误理由，否则后人会重新推演出同一个错误理由。§36.7
- **JVM 单测里的 Android 桩陷阱（`§7` 只覆盖了「判据抽纯函数」这一半）**：`android.util.Pair` 这类**直接 `new` 出来的框架类**在单测下字段恒 null，只断言列表长度完全测不出。判据不是「类型名里有没有 `android`」，而是「**这个值是真实实现算出来的，还是桩给的默认值**」（自实现 Android 接口的 fake 如 `FakeSharedPreferences`、以及 `app/build.gradle` 单独补了真实实现的 `org.json` 都不踩坑）；**「测试文件里没有 `import android.*`」≠「该测试不依赖 Android 桩」**。修法：新建 `model/TopicNode.java` 取代 `Pair` + 回归守卫 `parseTopicNodes_returnsRealObjectsNotAndroidStubs`。§36.3 / §36.7
- **异常堆栈里没有被测方法的帧 → 失败在构造参数转型那一层**：`NoteApiTest.kt:198` 原写 `as String`，Kotlin 在构造器调用当成就抛 NPE，异常根本没进 `failureText`；改 `as String?` 后 null 才传进去（`NoteApi.failureText` 的 null 兜底 `NoteApi.java:237` 失败时就已存在、一行未改）。⚠️ 不要读成「产品代码曾有 null 返回缺陷」。§36.3
- **断言「某属性全工程缺失」必须同时 grep 相邻属性做对照组**：最初结论「弹窗层从未接入主题系统」被「7 族主题都声明了 `colorSurface`」与「XML 侧 71 处 `?attr/` 引用」直接推翻。§三十二（`§8.4` 只覆盖了主题层根因）
- **`Sheet.init` 必须清 window 背景**（`setBackgroundDrawable(TRANSPARENT)`），否则圆角被方角底衬盖掉。§三十二
- **`colorError` 的对比度未重测**：`docs/archive/visual-experience-report.md` §2.25（`#EBE0E2` 叠 `#FF6699` 约 2.1:1）仍待处理。§三十二
- **Python 服务的瓶颈不是 Python 而是 fsync**：单 uvicorn worker 处理「小 JSON + 一次小写入」天花板在几百 req/s；加固两件事：阻塞 sqlite 调用必须 `run_in_threadpool`（否则撞锁时 `busy_timeout=15000` 卡死整个事件循环、连 `/health` 都不响应）、`PRAGMA synchronous=NORMAL`（WAL 下默认 `FULL` = 每次 commit 都 fsync）。§42.2
- **带 `Secure` 的 Cookie 在 http 下不会被回发**：selftest 一开始用 `TestClient(api.app)` 默认 `http://testserver` → 「登录成功但后续全 403」；改成 `TestClient(api.app, base_url="https://console.test")` 才对。这个假失败恰好证明 `Secure` 确实生效。§42.3
- **nginx 签证书的坑**：证书还没签出来时 vhost 不能引用证书文件，得先用只 `listen 80` 的引导配置；引导配置若写了 `location / { return 404; }` 而**没给 `/.well-known/` 单独开口**，acme 的 http-01 会拿到 404（`Invalid response from …/.well-known/acme-challenge/…: 404`）。补 `location ^~ /.well-known/ { root /www/wwwroot/<域名>; allow all; }` 后签发成功。§42.6
- **WAL 模式下备份必须用 `sqlite3 .backup`，不能 `cp`**（最近写入还在 `-wal` 里，直接拷会丢数据）。§42.7
- **PowerShell 传 JSON 的坑**：`curl.exe --data-raw '{"title":"中文…"}'` 含中文时会被 Windows 控制台代码页搞坏 → 服务端 `json.loads` 失败 → `read_json` 返回 `{}` → 报「标题和正文都不能为空」。正确姿势：**把请求体写成无 BOM 的 UTF-8 文件，再用 `--data-binary @file`**。§42.8
- **`update_log_current` 是当版日志的单一真相**（关于页读它、`:app:verifyVersionConsistency` 拿它当锚点），不引入第二份需要维护的文案。§40.3
