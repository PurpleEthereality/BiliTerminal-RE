# B 站接口接入常识与风控要点（长期备忘）

> 来源：`docs/archive/watch-optimization-research.md` §10（26.10.04 一手核对），2026-10-10 抽出为独立活文档。
> 用途：改 `api/` 下任何接口前查这里，尤其**错误码语义、风控处置、取流有效期、弹幕协议取舍**。
> 接口字段级快照在 `bilibili-API/`；网络层实现与坑见 `docs/architecture-map.md` §6 与 §7.35。

## 1. 来源与可信度

### 1.1 上游接口字典已永久关停 ⚠️

- `SocialSisterYi/bilibili-API-collect`（20,191★）README 已改为 `# Deprecated` / "本仓库停止维护并永久关停"，并附**律师函**措辞（指控"对非公开 API 及其调用逻辑、参数结构、访问控制及安全认证机制进行系统性收集并传播"），落款 **2026-01-28**；官方文档站 `socialsisteryi.github.io/bilibili-API-collect/` 现返回 404。
  来源：<https://github.com/SocialSisterYi/bilibili-API-collect>、<https://raw.githubusercontent.com/SocialSisterYi/bilibili-API-collect/master/README.md>
- **本项目已经在仓库根目录自带快照 `bilibili-API/`（197 个文件，`docs/` 下 195 篇 md）**，它是关停前的版本。另有贡献者复刻仓库 <https://github.com/pskdje/bilibili-API-collect>（master 同步至 2026-01-25），本文接口路径以这两份快照为准。
- **战略含义**：不要再把"抄现成端点清单"当长期模式。应把本项目**实际依赖的端点、参数、错误码固化为仓库内自有契约文档**，并建立"上游变更 → 快速自检"的机制。另外注意：该文档集为 **CC BY-NC 4.0**，且上游已收到律师函，仓库内自带快照的**合规风险**需要在发布前评估。

### 1.2 来源清单

**接口字典（本文结论的直接依据）**
- bilibili-API-collect（**已永久关停，2026-01-28**）— https://github.com/SocialSisterYi/bilibili-API-collect ；关停说明 https://raw.githubusercontent.com/SocialSisterYi/bilibili-API-collect/master/README.md
- bilibili-API-collect 贡献者复刻（快照 2026-01-25，用于本次接口核对）— https://github.com/pskdje/bilibili-API-collect
- 本仓库自带快照 `bilibili-API/` （关停前版本，197 文件 / 195 篇 md）

**纠偏 / 旁证来源**
- SponsorBlock 官方 README（用于纠偏"是否支持 B 站"）— https://raw.githubusercontent.com/ajayyy/SponsorBlock/master/README.md
- BiliRoaming（已 archived，收到律师函）— https://github.com/yujincheng08/BiliRoaming ；https://m.ithome.com/html/973202.htm
- B 站官方口径（正文可读的少数来源）：官方下载中心 https://app.bilibili.com/ ；腾讯应用宝 https://sj.qq.com/appdetail/tv.danmaku.bili ；小米应用商店 https://app.mi.com/details?id=tv.danmaku.bili
- 说明：`openhome.bilibili.com/doc`、`open-live.bilibili.com/document/`、`www.bilibili.com/blackboard/help.html` 等官方页均为 JS 渲染空壳，**只能读到标题，读不到正文**。
- 本仓库实测：其余结论均标注了 `文件:行号`，可直接核对。（归档文档另收有 Wear OS 官方文档与竞品仓库清单，与接口无关，不在此列出。）

### 1.3 可信度分级：哪些是一手实测、哪些是二手/待核实

**一手实测（可直接依赖）**
- §3 第 1~4、8、9 条：全部标注了 `bilibili-API/...md:行号` 原文，可直接核对。
- §3 第 5 条：搜索风控原文（快照 `docs/search/search_request.md:3`）**另加本仓库实测**——`search.bilibili.com/all?keyword=...` 直接返回 `<title>验证码_哔哩哔哩</title>`，确认 Web 页面模拟已不可用。
- §3 第 6 条：快照 `docs/misc/errcode.md` 有更长列表，本文**只逐字核对了 §3 提到的这几条**。
- §3 第 11 条：SponsorBlock 官方 README 全文抓取成功，确认其未提及 B 站。
- §2 速查表三列（登录 / wbi / csrf）：按快照文档逐端点核对。

**二手 / 待核实（用到时须再验）**
- GitHub `RobinNotBad/BiliClient` 返回 404（上游主仓只在 Gitee）；Gitee 无公开 API，`71★ / 1322 commits / 状态「暂停」` 取自网页。
- `Darock-Studio/Darock-Bili`（Apple Watch 版）因匿名 API 限流（HTTP 403）**未能核实**。
- `qingyiwebt/Biliw`、`nonomal/bilibili-for-AppleWatch` 仅见搜索结果中的个位数 star，**未核实**。
- Wear OS 是否存在其他仍在维护的 B 站客户端：**未查到**。
- **「Android 9+ 默认禁止明文 HTTP」未取到可引用的官方原文**：`developer.android.com/privacy-and-security/security-config` 与 `/about/versions/pie/android-9.0-changes-all` 均只读到导航或被截断（入口存在）。本仓库相关的依据只有 `bilibili-API` 快照 README 的"强制使用 https 协议"。
- **SponsorBlock API 正文与"B 站是否被其服务端收录"**：`wiki.sponsor.ajay.app` 抓取失败；其 README 全文未提 B 站 → 只能确认"官方目标平台是 YouTube"，不能确认"B 站被收录"。
- **官方 App 底部 Tab 的具体数量与顺序**：官方页面全是 JS 空壳，商店页仅能确认存在"我的"Tab；归档文档 §7.4 的模块划分是按官方子站与接口域反推的**模块划分**，不是 Tab 顺序断言。
- **动态发布的完整参数取值表**（定时 / 可见范围的字段枚举）：只读到接口与部分字段名，未逐字段核对 `bilibili-API/docs/dynamic/publish.md` 全文。
- **`-352` / `-412` / `-111` 之外的错误码语义**：见上，未逐条核对。

## 2. 接口速查表（登录 / wbi / csrf 三列）

三列含义：**登录 = 需 SESSDATA；wbi = 需 `w_rid`+`wts`；csrf = 需 `bili_jct`**。

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

## 3. 必须知道的硬约束

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

## 4. 按接口可行性收敛的「值得做 / 不值得做」

> 下表是 **26.10.04 的接口可行性判断**，不是最终产品裁决。用户裁决与此表有三处相反，以归档文档 `docs/archive/watch-optimization-research.md` §12 台账为准：① 「关注主播开播提醒」裁为**无计划**；② 漫画裁为**不做**（追漫列表无接口）；③ 「收藏夹批量整理」「发布动态/评论/弹幕」裁为**想要实现**（"需要输入"不构成否决理由），其中多项已落地。

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
