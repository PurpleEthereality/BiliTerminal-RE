# 文档总索引

> 整理时间：2026-10-10（基线 `versionName 26.10.10` / `versionCode 2610102`）。
> **规则**：本目录只放**活文档**（描述现状 / 承载未完成工作）。一次性审查、已执行完毕的方案、过程流水一律进 `docs/archive/`（索引见 [`archive/README.md`](archive/README.md)）。
> 引用文档前先确认它是不是归档的——**归档文档只代表当时**。

## 按需求找入口

| 你要做的事 | 读这份 | 说明 |
| --- | --- | --- |
| 改任何非平凡代码 | [`../AGENTS.md`](../AGENTS.md) → [`architecture-map.md`](architecture-map.md) | AGENTS 是每次都要遵守的约定；架构地图是真实分层 + 逐条坑 |
| 找「现在的状态 / 还没做的事」 | [`review/fix-progress.md`](review/fix-progress.md) | **唯一活台账**：当前版本状态、未完成待办（§2，含 §2.4 视觉技术债）、最新两版快照、历史章节索引 |
| 改网络 / 接口 / 风控相关 | [`bilibili-api-notes.md`](bilibili-api-notes.md) | 接口常识与风控硬约束（错误码语义、取流有效期、弹幕协议取舍） |
| 发版前上真机 | [`review/real-device-regression-checklist.md`](review/real-device-regression-checklist.md) | 与发行版同维护的回归清单（247 条），每条的来源小节都标在标题里 |
| 补真机 / 联网联调 | [`review/device-test-plan.md`](review/device-test-plan.md) | T0–T15 + D0–D8，**88 条全部未执行**，是待执行计划 |
| 写新功能 | [`superpowers/plans/2026-08-27-new-features-roadmap.md`](superpowers/plans/2026-08-27-new-features-roadmap.md) | 功能 backlog（含实测过的接口地址、判据与仍未落地项） |
| 改播放器 | [`superpowers/specs/2026-09-10-player-core-merge.md`](superpowers/specs/2026-09-10-player-core-merge.md) | 播放器核合并 S1–S3 已完成，**S4/S5/S6 未做** |
| 改教程系统 | [`tutorial-system-redesign.md`](tutorial-system-redesign.md) | 重做进行中，旧链路未删完——**动之前先读它** |
| 看用户能用到什么 | [`FEATURES.md`](FEATURES.md) | 用户视角功能总表（对应用户文档，不含实现细节） |
| 查 B 站接口字段 | [`../bilibili-API/`](../bilibili-API/) | 上游接口文档快照（**注意上游已关停，见 `bilibili-api-notes.md` §1 的合规风险**） |
| 部署 / 维护自建服务端 | [`../server/README.md`](../server/README.md) | 反馈 / 公告 / 匿名统计 / 崩溃上报的后端（FastAPI + SQLite + nginx） |

## 文档清单（活）

| 文件 | 内容 | 维护时机 |
| --- | --- | --- |
| [`architecture-map.md`](architecture-map.md) | 架构通读：§6 网络层、§7 改前必查的坑（7.1–7.36）、§8 UI 基建与外观、§9 API 类映射（43 个类）、§10 检查清单 | 改架构 / 基建 / 新增基类时 |
| [`FEATURES.md`](FEATURES.md) | 用户可见功能总表（19 章） | 新增用户可见功能时 |
| [`bilibili-api-notes.md`](bilibili-api-notes.md) | 接口与风控常识（来源与可信度、速查表、11 条硬约束、值得做/不做） | 核实出新接口结论时 |
| [`review/fix-progress.md`](review/fix-progress.md) | 活台账：当前状态 + 未完成待办 + 最新快照 + §一~§四十三 索引 | 修完 bug / 关掉待办 / 每轮真机验证后 |
| [`review/real-device-regression-checklist.md`](review/real-device-regression-checklist.md) | 发版前真机回归清单（247 条，含第十三节视觉面；条目只增不改，失效就地标注作废） | 每个发行版 |
| [`review/device-test-plan.md`](review/device-test-plan.md) | 待执行的真机/联网测试计划（T/D 两组） | 执行时勾选 |
| [`superpowers/plans/2026-08-27-new-features-roadmap.md`](superpowers/plans/2026-08-27-new-features-roadmap.md) | 功能路线图 + 从各报告回迁的仍未落地项 | 排期 / 落地后 |
| [`superpowers/specs/2026-09-10-player-core-merge.md`](superpowers/specs/2026-09-10-player-core-merge.md) | 播放器核合并设计与 S4/S5/S6 待做项 | 动播放器时 |
| [`tutorial-system-redesign.md`](tutorial-system-redesign.md) | 教程系统重做（含 §五 待办 4 项） | 动教程时 |
| [`archive/README.md`](archive/README.md) | 归档索引：每份归档文档的内容与「为什么归档」 | 归档新文档时 |

## 维护纪律（避免又长出一堆旧文档）

1. **只写现状，不写流水**。修 bug 的经过超过一屏就并进 [`review/fix-progress.md`](review/fix-progress.md) 的「历史章节索引」一行，正文留在归档里。
2. **一份知识只有一个落点**：架构/坑 → `architecture-map.md`；用户可见 → `FEATURES.md`；接口/风控 → `bilibili-api-notes.md`；状态与待办 → `review/fix-progress.md`。别在别处复述。
3. **归档不是删除**：`git mv` 进 `docs/archive/` 并在 `archive/README.md` 加一行「内容 + 为什么归档」，正文一字不改（只在开头加存档说明）。
4. **归档前先回迁**：文档里唯一还活着的知识（不要回退的取舍、未落地的待办、实测数字）要先搬进活文档，再归档。
5. 文档间引用一律写**仓库相对路径**（`docs/...`），归档后同步改引用；源码注释里的文档路径同样要改。
