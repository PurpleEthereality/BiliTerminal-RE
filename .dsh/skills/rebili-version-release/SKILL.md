---
name: rebili-version-release
description: >-
  在 RE:哔哩终端（ReBiliClient）仓库发版：递增版本号、把旧“本次更新”移入历史更新日志、
  写入新版本日志、用 Gradle 构建签名发行 APK，并由 CI 通知中转服务把发行版同步到 Gitee、产出下载直链。
  当用户要求“发版/出包/更新日志/构建发行版/把更新日志写入历史”等属于本仓库的操作时使用本技能。
---

# RE:哔哩终端（ReBiliClient）发版工作流

把一次“写更新日志 + 构建发行版”的完整流程固化为可复用步骤。改动前先读仓库根
`AGENTS.md`（语言/架构/构建约定），不要盲信现有实现。

## 术语与关键位置

| 对象 | 位置 | 说明 |
| --- | --- | --- |
| 版本号 | `app/build.gradle` → `defaultConfig` | `versionName "YY.MM.DD"`，`versionCode` 为其数字编码 |
| 本次更新日志 | `app/src/main/res/values/strings.xml` → `update_log_current` | 关于页“本次更新 (版本名)”展示的内容 |
| 历史更新日志 | 同上 → `update_history_log` | 按 `## YYYY-MM-DD` 日期分组，新日期放数组最前（最上方） |
| 遗留完整日志 | 同上 → `update_log_items` | 老入口用，非必要时**不要**改动 |
| 发行包 | `app/build/outputs/apk/release/*-release.apk` | assembleRelease 产物（ABI 分包 + universal） |
| 发行渠道 | GitHub Release（`.github/workflows/build-release.yml`）+ Gitee 发行版 | 客户端更新检查按「Gitee → GitHub」顺序读这两个仓库的 `releases/latest` |
| 版本元数据 | Release 说明末尾的 `<!-- update: versionCode=… versionName=… forceUpdate=… -->` | 发版工作流自动从 `app/build.gradle` 读出并写入，**不再有 config.json** |

## 标准流程

### 0. 盘点自上个发布版以来的改动
先确定“上个已发布版本”对应的提交，用它作为日志内容来源：
`git log --oneline <上个发版commit>..HEAD`（可用上次发布的版本名哈希/说明定位）。
据此判断本版本日志该写哪些功能/安全/修复条目，并按实际代码改动撰写，不要凭空编造。

### 1. 处理历史更新日志（把旧的本次更新归档）
`update_log_current` 记录的是**上一个已发布版本**的内容。发新版本前把它移入历史：

- 在 `update_history_log` 数组**最前面**插入一组：
  - `<item>## YYYY-MM-DD</item>`（日期取旧版本发布日）
  - 之后接旧 `update_log_current` 里的条目（去掉原来的 `【xx 本次更新】` 首行，日期已由 `##` 头表达；空行可省略，历史页解析会跳过空行）
- 保持数组内其它旧组不动，新组放最前以便历史页优先展示最新日期。

### 2. 写入新的“本次更新”日志
- **打包之前的强制步骤：先把版本号设为当天日期**（确认“今天”后再填，再进入第 3 步打包）：
  - `versionName` = 今天的 `YY.MM.DD`；
  - `versionCode` = 日期数字后补一个 `0`（即 `YYMMDD0`）。
  - 示例：`2026-09-07` → `versionName "26.09.07"`、`versionCode 2609070`。
  - 用 `Get-Date` / 系统日期确认“今天”，不要沿用旧日期；改完确认 `app/build.gradle` 已落到当天值，**再开始打包**。
- 把 `update_log_current` 整体替换为新版内容：
  - 首行 `<item>【YY.MM.DD 本次更新】</item>`
  - 之后按条写功能/修复，用**中文**、加编号、条目语气与既有日志一致。
- 若仓库里 `description` 有 `strings.xml` 注释提示排序规则（新功能在上/修复在下），按提示组织。

### 3. 构建发行版
```bash
./gradlew.bat :app:assembleRelease   # Windows；Linux/WSL 需覆盖 gradle.properties 里的 java.home
```
- 成功标记：`BUILD SUCCESSFUL`；产物在 `app/build/outputs/apk/release/`。
- 注意（在受限沙箱/agent 环境下）：Gradle 要写 `C:\Users\<user>\.gradle` 与 SDK 等**工作区之外**的路径，首次可能因写锁/权限失败。若失败需以不受限文件权限重跑同一命令，不要改路径绕过。
- `app/build.gradle` 里 `copyApkToDesktop`（含 `adb install`）已注释，**不会**随 assembleRelease 自动复制/安装；需要时单独跑 `./gradlew.bat copyApkToDesktop`。

### 4. 发版（GitHub Release + 通知中转同步 Gitee）

> **⚠️ 硬要求：Release 说明里必须有完整的本次更新说明，不许省略。**
> 发布说明要包含 `strings.xml` 中 `update_log_current` 的**全部条目**（去掉 `<item>` 标签，
> 保留「【YY.MM.DD 本次更新】」标题与编号）。**只留 `### APK 校验值（MD5）` 与
> `<!-- update: … -->` 机器元数据不算合格**；拿 GitHub 自动生成的「Full Changelog」commits
> 摘要顶替也不算——用户点进 Release 是要看这版改了什么。
>
> **成因**：`push` tag 这条路径下 `inputs.*` 恒为空，`release_body` 是空的。
> （26.10.05 就是这么漏掉的。）
>
> **已由工作流保证（26.10.05 后）**：`build-release.yml` 的「计算发行 tag 与发布说明」步骤里，
> 手工触发用输入的 `release_body`；**推 tag 时由 `.github/scripts/extract_update_log.py`
> 自动从 `update_log_current` 抽取**（每行一条、反转义 XML 实体、跳过空条目，并强制 UTF-8 输出）。
> 抽不到任何条目就打印 `::error::` 并以退出码 1 结束——**宁可让发版红掉，也不发出没有更新说明的
> Release**。抽到时 `has_body=true`，GitHub 不再自动补 commits 摘要。
>
> ⚠️ **别把这段逻辑改回「body 只取 `inputs.release_body`」**：那样推 tag 又会退化成只有 MD5 表。
> 手工触发想覆盖文案时，填 `release_body` 即可（它优先于自动抽取）。
>
> 无论走哪条路，发布完都要回读 Release 正文确认（见 §5）。

**优先走 CI**：推一个 tag（或手工触发 `构建发行版并创建 Release` 工作流并填 tag）即可，
工作流会依次完成：

1. 跑单测 → `:app:assembleRelease` → 生成各 APK 的 MD5；
2. 组装 **Release 说明正文**：更新日志在前（手工触发取 `release_body`，推 tag 由
   `.github/scripts/extract_update_log.py` 从 `strings.xml` 的 `update_log_current` 自动抽，
   见上方硬要求），接着是 MD5 表，末尾是从 `app/build.gradle` 读出的
   `versionCode`/`versionName` 与「是否强制更新」构成的机器可读元数据
   `<!-- update: versionCode=… versionName=… forceUpdate=… -->`
   （手工触发时用 `force_update` 输入控制，默认 `false`）——**客户端更新检查就靠这段**；
3. 生成 `release-links.txt`（Gitee 与 GitHub 两侧直链）与（按需）老客户端用的 `config.json`，
   随 APK 一起上传到 **GitHub Release**；
4. Release 发完后**通知中转服务**（末尾的 Notify relay 步骤）：地址与密钥取仓库 Secrets
   `RELAY_URL` / `RELAY_SECRET`，请求体用 HMAC-SHA256 签名；由中转服务自己去 GitHub
   拉附件并同步到 Gitee `zisekongling/bili-terminal-re`（幂等，同步后 Gitee 只留最近若干个发行版）。
   **Action 侧绝不直接访问 Gitee**（网络不通），也不要把地址/密钥写进代码或日志。
   ⚠️ **2026-10-08 换主后这两个 Secret 没配**（值随旧仓库一起丢失）：工作流已改成「缺 Secret
   就 `::warning::` 跳过」而不再 `exit 1`，所以**现在发版不会自动同步到 Gitee**，
   Gitee 侧需手工补，或等 relay 恢复后重发通知。

- 客户端更新源：Gitee 发行版优先，失败回落 GitHub 发行版；两处都是公开仓库，读 release 不需要 token。
- Gitee 直链格式：`https://gitee.com/zisekongling/bili-terminal-re/releases/download/<tag>/<文件名>`。
- 漏同步 / 想重跑同步：触发 `.github/workflows/relay-notify.yml` 并填 tag（只重发通知，不重新构建）。
- ⚠️ **本仓库开着 release immutability**：一个 tag 只要被不可变 release 用过，**就永久不能再建**，
  即使把 release 和 tag 都删掉也一样（报 `tag_name was used by an immutable release` +
  `Cannot create ref due to creations being restricted`）。所以**重发同一个版本必须换 tag**
  （例如 `26.10.03` 用掉了就用 `26.10.03.1`）；另注意不可变 release 的**附件也不能增删改**，
  发完就没法补传，只能靠 `relay-notify.yml` 重发通知让中转去补。
- 若需本地出包（不开 CI）：按 §3 构建后手工触发工作流上传；**不要**在本地直接调 Gitee API。

### 5. 校验
- strings.xml 保持 XML 合法（本次只改数组文本）。
- 版本号改过就必须让 `:app:verifyVersionConsistency` 通过（校验 build.gradle 与 strings.xml 更新日志锚点）。
- **Release 建好后必须回读它的说明正文**（`https://api.github.com/repos/PurpleEthereality/BiliTerminal-RE/releases/latest` 的 `body`），确认 `update_log_current` 的全部条目都在里面。若正文只有 `### APK 校验值（MD5）` + `<!-- update: … -->` + `**Full Changelog**`，就是省略了更新说明 —— 见 §4 的硬要求，必须补上。**别只看 `conclusion=success` 就收工**：工作流全绿也不代表说明写了。
- 向用户汇报：改了哪些文件、发行 APK 的路径/大小、**Release 说明是否含完整更新日志**、CI 里 Gitee 同步是否成功、`release-links.txt` 里的直链。

## 约定与坑
- 一律**中文**文案与注释；遗留页文案硬编码、不改 `strings.xml`（设置页为字符串驱动例外，用 `desc_*`）。
- `update_log_items`、`versionCode` 尾码规律（`YYMMDD0`；同一天发第二个包时尾位递增为 `YYMMDD1`）、Gitee 直链这类与既有约定/外部资源强相关的内容，不确定就先确认再改。
- **新客户端不再读 config.json**：更新检查已改为读发行版本身，版本元数据由发版工作流写进 Release 说明，少一处人工同步的远端文件。
- 例外：**渠道切换那一次**（把 26.10.02 及更早、只认 123pan config.json 的老客户端带过来）需要在触发发版时把 `emit_config_json` 打开，工作流会额外产出一份 `config.json`（`downloadUrl` = Gitee 上 **32 位包**直链、`forceUpdate` 同本次）挂到 Release —— 还需**人工把它传到 123pan**。之后不再产出。
- `:app:verifyVersionConsistency` 只校验 build.gradle 与 strings.xml 更新日志锚点两处；改了版本号就要同步那个锚点，否则 CI 会红。
- Gitee 会在发行版里**自动附带** `{tag}.zip` / `{tag}.tar.gz` 两个源码归档（tag 落在 `master` 上产生），
  客户端选包必须按精确文件名匹配（`app-<abi>-release.apk`），不能用"第一个附件"。
