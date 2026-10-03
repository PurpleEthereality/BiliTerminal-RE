---
name: rebili-version-release
description: >-
  在 RE:哔哩终端（ReBiliClient）仓库发版：递增版本号、把旧“本次更新”移入历史更新日志、
  写入新版本日志、用 Gradle 构建签名发行 APK，并由 CI 把发行版同步到 Gitee、产出下载直链。
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

### 4. 发版（GitHub Release + 同步 Gitee）
**优先走 CI**：推一个 tag（或手工触发 `构建发行版并创建 Release` 工作流并填 tag）即可，
工作流会依次完成：

1. 跑单测 → `:app:assembleRelease` → 生成各 APK 的 MD5；
2. 从 `app/build.gradle` 读出 `versionCode`/`versionName`，连同「是否强制更新」一起写进
   Release 说明末尾的机器可读元数据 `<!-- update: versionCode=… versionName=… forceUpdate=… -->`
   （手工触发时用 `force_update` 输入控制，默认 `false`）——**客户端更新检查就靠这段**；
3. 把**发行版**（只同步发行版，不推代码）同步到 Gitee 仓库 `zisekongling/bili-terminal-re`：
   建 Release + 上传 APK/`md5sums.txt`，用 Secrets 里的 `GITEE_TOKEN` 调 Gitee OpenAPI；
   同步后只保留**最近 3 个** Gitee 发行版。该步失败**不阻断** GitHub Release（GitHub 是兜底）；
4. 生成 `release-links.txt`（Gitee 与 GitHub 两侧直链）作为附件挂到 Release 上。

- 客户端更新源：Gitee 发行版优先，失败回落 GitHub 发行版；两处都是公开仓库，读 release 不需要 token。
- Gitee 直链格式：`https://gitee.com/zisekongling/bili-terminal-re/releases/download/<tag>/<文件名>`。
- 若需本地出包（不开 CI）：按 §3 构建，然后手工触发工作流上传，或本地跑
  `.github/scripts/sync_gitee_release.py`（用环境变量传 `GITEE_TOKEN`/`TAG`/`ASSET_DIR` 等，见脚本头部注释）。

### 5. 校验
- strings.xml 保持 XML 合法（本次只改数组文本）。
- 版本号改过就必须让 `:app:verifyVersionConsistency` 通过（校验 build.gradle 与 strings.xml 更新日志锚点）。
- 向用户汇报：改了哪些文件、发行 APK 的路径/大小、CI 里 Gitee 同步是否成功、`release-links.txt` 里的直链。

## 约定与坑
- 一律**中文**文案与注释；遗留页文案硬编码、不改 `strings.xml`（设置页为字符串驱动例外，用 `desc_*`）。
- `update_log_items`、`versionCode` 尾码规律（`YYMMDD0`；同一天发第二个包时尾位递增为 `YYMMDD1`）、Gitee 直链这类与既有约定/外部资源强相关的内容，不确定就先确认再改。
- **新客户端不再读 config.json**：更新检查已改为读发行版本身，版本元数据由发版工作流写进 Release 说明，少一处人工同步的远端文件。
- 例外：**渠道切换那一次**（把 26.10.02 及更早、只认 123pan config.json 的老客户端带过来）需要在触发发版时把 `emit_config_json` 打开，工作流会额外产出一份 `config.json`（`downloadUrl`=Gitee 直链、`forceUpdate` 同本次）挂到 Release —— 还需**人工把它传到 123pan**。之后不再产出。
- `:app:verifyVersionConsistency` 只校验 build.gradle 与 strings.xml 更新日志锚点两处；改了版本号就要同步那个锚点，否则 CI 会红。
- Gitee 会在发行版里**自动附带** `{tag}.zip` / `{tag}.tar.gz` 两个源码归档（tag 落在 `master` 上产生），
  客户端选包必须按精确文件名匹配（`app-<abi>-release.apk`），不能用"第一个附件"。
