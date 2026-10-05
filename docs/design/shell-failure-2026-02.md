# DSH 子进程启动失败现场记录

> 记录时间：本次会话期间
> 状态：**未修复**（本会话内无任何可用命令执行路径）
> 影响：**无法执行 Gradle 构建与单测**，本次弹窗重设计的编译验证被迫推迟

## 1. 现象

`pwsh` 工具的任何调用都返回空输出 + `[exit code: 3221225794]`。

`3221225794` = `0xC0000142` = `STATUS_DLL_INIT_FAILED`（Windows 进程初始化阶段 DLL 加载失败）。

## 2. 复现矩阵

| 探测 | 结果 |
|---|---|
| `pwsh`（隐式调用） | `3221225794`，无输出 |
| `echo hello` / `Write-Output "test-ok"` / `'test'` | 同上 |
| `Get-ChildItem env:` / `$env:PATH` | 同上 |
| 更换 workdir（含 `C:\`） | 同上 |
| 延长 timeout 至 10s | 同上 |
| `powershell.exe` 5.1（绝对路径） | 同上 |
| `C:\Windows\System32\cmd.exe`（绝对路径） | 同上 |
| 裸 `cmd.exe /c "echo hello-from-cmd"` | 同上 |

8/8 探测失败，从未捕获到任何 stdout/stderr。

## 3. 根因判断

**故障点在 DSH → 子进程的 spawn 环节，不在 PowerShell 本身。**

判定依据：

- 三个不同位置、互不相关的可执行文件（`pwsh`、Windows PowerShell 5.1、`cmd.exe`）返回**同一个** DLL 初始化失败码。单一二进制损坏、`PATH` 劫持、ConstrainedLanguage 模式都无法解释 `cmd.exe` 一并失败（后者会报语言层错误，而非进程启动失败）。
- **ACL / 沙箱原因被排除**：`read` / `glob` 在**同一个文件系统**上工作正常（glob 枚举到 10,696 个路径，read 成功打开 `C:\Users\ASUS\AppData\Local\Temp` 与 `D:\...` 下的文件）。没有任何访问被拒绝，只有进程创建失败。
- **属于新出现的回归**：DSH 遗留的 subprocess 日志证明该机器上 pwsh 曾经正常工作 —— `C:\Users\ASUS\AppData\Local\Temp\dsh-subprocess-9120-2-15e95b11881a-stderr.log` 含 1047+ 行真实 PowerShell 解析错误（`CategoryInfo : OperationStopped`、`FullyQualifiedErrorId : System.ArgumentException`），`...-stdout.log` 含真实的文件清单输出。
- `C:\Program Files\PowerShell` **不存在**（`Program Files`、`AppData\Local\Programs`、`WindowsApps` 下均无 `pwsh.exe`），安装状态本身可疑。全盘搜索 `pwsh.exe` 因 30s 超时未完成。

## 4. 已排除

- `diagnose-windows-sandbox-acl` skill **不适用**：其目标是工作区路径的 ACL/属主拒绝，而此处文件访问正常，无拒绝可修；且其自带脚本本身就是 PowerShell 脚本（需 `pwsh -File` 启动），走的是同一条死路。曾调用一次，得到相同的 `3221225794`，未生成报告文件，此后未重试、未手工改 ACL。
- 未做任何变更：没有创建/修改/删除文件，没有改 ACL，没有安装软件。

## 5. 需要人在主机上执行（普通终端，非 DSH）

```bat
cmd /c echo ok
powershell -NoProfile -Command "Write-Output ok"
winget list --id Microsoft.PowerShell
winget install --id Microsoft.PowerShell --source winget
```

若上面在纯净终端里能跑通，则故障确定在 DSH 的 spawn 层，而非 Windows 本身。随后按 `STATUS_DLL_INIT_FAILED` 的经典成因逐项排查：

- 导出 `PATH`，查找混入的 `api-ms-win-*`、`ucrtbase.dll`、`msvcp140.dll`，或排在更前面的流氓 `pwsh.exe` 垫片。
- 重装 **Microsoft Visual C++ Redistributable 2015-2022 x64**（CRT/MSVC 依赖损坏是该错误码的典型成因）。
- 检查注入型 DLL：`HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Windows` 下的 `AppInit_DLLs`，以及杀软/EDR 的 shell hooking，改完重启再测。
- 检查调试器劫持：`HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Image File Execution Options`。
- 必要时重启 DSH。

## 6. 构建恢复后的注意事项

- `gradle.properties:9` 钉了 `org.gradle.java.home=C:\Program Files\Java\jdk-17`，该 JDK **确实存在**（已确认 `C:\Program Files\Java\jdk-17\jmods\jdk.compiler.jmod`；机器上另有 jdk-21.0.10 与 jdk-24）。
- 但**在真实命令打印出输出之前，不得假定构建可用**。
- AGENTS.md 已记录的坑在此处会生效：`gradle.properties:24,27-29` 开着 configuration-cache 与 build cache，**改动 `res/` 文件集合会回放陈旧的 `mergeDebugResources`**。本次弹窗重设计必然要动 `res/`，恢复后须按文档化的两步走：

```
gradlew.bat :app:clean --offline --no-configuration-cache
gradlew.bat :app:assembleDebug --offline --no-build-cache --no-configuration-cache
```

（必须**分两次调用**。）
