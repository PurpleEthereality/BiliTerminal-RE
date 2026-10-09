# 弹窗（选择框）重做进度

> 起于 26.10.05。设计稿见 `docs/design/dialog-redesign-v2.html`（方案 B 终端列表）。
> 约定见 `AGENTS.md`「硬约定」里的弹窗禁令。

## 用户提的问题

表盘上弹出的选择框「灰底白字 + 粉色按钮」，没有圆角，像安卓原生的一样。

## 根因（已核实，不是猜的）

**弹窗不是「没接入主题系统」，而是只接入了一半。**

7 族主题（`res/values/themes.xml`）都声明了 `colorSurface`，所以弹窗底色**是**跟随主题的
（这是前序修复 `themes.xml:681-682` 注释里提过、已补上 `colorSurface`/`colorBackground` 的那半）。
真正缺的是下面三个属性，全工程 `app/src/main/res` 全域 **0 命中**：

| 缺失属性 | 谁在用 | 回退到什么 | 用户看到的现象 |
|---|---|---|---|
| `alertDialogTheme` | AlertDialog 的颜色 overlay 选择 | `Theme.MaterialComponents` 自带的 alert overlay | 弹窗**不读**本主题的定制 overlay |
| `colorOnSurface` | Material 正文/标题文字色 | Material 默认近白 | **「白字」**（主题里的 `android:colorForeground` 救不了，Material 正文不读它） |
| `colorError` | 破坏性按钮色 | Material 默认红 | 删除类操作没有危险语义 |

**「粉按钮」的确切来源**：`setPositiveButton`/`setNegativeButton` 的文字色由 `colorAccent` 决定，
而这是 7 族主题里**唯一都设了**的属性，B站粉与经典终端恰好都是 `#FF6699`。所以按钮是弹窗里唯一
跟随主题的部分，且恰好是那个刺眼的荧光粉。

**「没有圆角、像安卓原生」的来源**：AppCompat AlertDialog 的背景是方角无描边，dialog window
自己还带一层背景底衬。

> 教训：判断「某属性全工程缺失」时**必须同时 grep 相邻属性做对照组**。本次最初的错误结论是
> 「弹窗层从未接入主题系统」——被 `colorSurface` 的 71 处引用和 7 族主题的声明直接推翻。

## 已完成

### 一、主题层（让既有裸 Builder 立刻正常，调用点零改动）

- `res/values/colors.xml` 新增 14 个弹窗语义色（`on_surface_*` / `error_*` × 7 族），
  与 `ui/appearance/ColorScheme.kt` 的 `ON_SURFACE` / `ERROR` 字段对齐。
- `res/values/dimens.xml` 新增弹窗 token：`dialog_item_padding_h`(15dp) / `_v`(11dp) /
  `dialog_item_min_height`(44dp) / `dialog_title_padding_top`(13dp) / `_h`(15dp) / `_bottom`(9dp) /
  `dialog_glyph_width`(11dp)。
  **不要在弹窗样式里写 `?attr/` 维度间接层**——真机实测解析失败（见 `docs/architecture-map.md` §8.7.1），
  圆角直接引 `@dimen/card_round`。
- 新建 `res/drawable/dialog_background.xml`：`solid=?attr/colorSurface` +
  `stroke 1dp ?attr/colorPrimary` + `corners @dimen/card_round`。
  不用 MaterialCardView 包一层：AlertDialog 的 window 背景直接吃 drawable，套 CardView 多一层裁剪与测量。
- 新建 `res/layout/item_dialog_terminal.xml`（条目：引导符 + 文字 + 状态位，44dp 最小高度）
  与 `res/layout/layout_dialog_terminal.xml`（标题 + ScrollView 列表 + 提示行）。
- `themes.xml` 新增 **7 份 overlay**（`ThemeOverlay.<X>.Dialog`，parent 都是
  `ThemeOverlay.MaterialComponents.Dialog.Alert`），各含 `colorSurface` / `colorOnSurface` /
  `colorError` / `android:background=@drawable/dialog_background`；
  并给 7 族主题各加一行 `<item name="alertDialogTheme">`。

### 二、统一构造入口 `util/TerminalDialog.kt`（约 369 行）

`object TerminalDialog`，沿用静态方法风格（项目无 DI）。四个公开入口：

- `menu(context, title, items, danger, onPick)` —— 菜单型，对应原 7 处 `setItems`。
  点击**先 dismiss 再回调**，因为回调里常会再弹一个框（「删除评论」→ 二次确认），不关会叠在一起。
- `singleChoice(context, title, items, checked, onPick)` —— 单选型，对应原 2 处 `setSingleChoiceItems`。
  **不自动关闭**：选季场景要先做越界校验再由调用方决定关不关。`checked` 越界会被夹到合法范围。
- `confirm(context, title, message, confirmText, cancelText, confirmIsDanger, onConfirm)` —— 确认型，
  对应原 8 处 `setMessage`。**保留按钮行**（菜单/单选去掉了按钮行，但确认框没按钮就没有明确语义）；
  取消走次级灰、确认走 `colorError`；`cancelText` 传空串则不显示取消按钮。
- `alert(context, title, message, buttonText)` —— 纯提示型，内部转调 `confirm(cancelText = "")`。

实现要点：

- 引导符双态：未选 `›`（次级灰）、选中 `▸`（主色）。常量 `GLYPH_IDLE` / `GLYPH_ACTIVE`。
- `Sheet.init` 里 **`dialog.window?.setBackgroundDrawable(ColorDrawable(TRANSPARENT))` 必须做**，
  否则 AppCompat 会垫一层方角底衬，圆角白做——这正是「像安卓原生」的直接原因。
- 颜色**只读 `?attr/`**（`colorPrimary` / `colorOnSurface` / `colorError`），文件内无任何色值常量；
  `attrColor` 带默认值兜底（日后新主题漏了 overlay 时退回可读色，而不是崩）。
- `Sheet.capScrollHeight()` 把条目区高度封顶到屏幕 45%：`ScrollView` **没有** `android:maxHeight`
  属性，光靠 `wrap_content` 会被内容撑出表盘（300×300 表盘上一条动态最多 10 个菜单项）。
- 内存：重绘回调由 `Sheet` 自己持有，**不用 `<AlertDialog, Lambda>` 全局 map**（Dialog 回收后仍被持有会泄漏）。

### 三、17 处调用点全部迁移

| 文件 | 处数 | 类型 |
|---|---|---|
| `adapter/ReplyAdapter.kt` | 2 | 菜单（危险项「删除评论」）+ 确认 |
| `adapter/dynamic/DynamicHolder.kt` | 2 | 菜单（危险项「删除动态」）+ 确认 |
| `adapter/message/PrivateMsgSessionsAdapter.kt` | 2 | 菜单（危险项「删除会话」）+ 确认 |
| `activity/vote/VoteInfoActivity.kt` | 1 | 确认（删除投票） |
| `activity/dynamic/send/SendDynamicActivity.kt` | 1 | 菜单（定时发布时间） |
| `activity/settings/login/AccountSwitchActivity.kt` | 1 | 确认（删除账号） |
| `activity/user/FollowUsersActivity.kt` | 2 | 菜单（危险项「删除分组」）+ 确认 |
| `activity/user/favorite/FavoriteVideoListActivity.kt` | 4 | 2 菜单（危险项「取消收藏」/ 目标收藏夹）+ 2 确认 |
| `activity/video/info/BangumiInfoFragment.kt` | 2 + 1 | 2 单选（选季/选集）+ 1 纯提示（该季暂无剧集） |

**自检命令**：`grep -r "AlertDialog" app/src/main/java` —— 除 `util/TerminalDialog.kt` 自身外应 0 命中。

### 四、与设计稿逐值对齐（后续修正轮）

首版实现交付后用户反馈「你这做出来和前端完全不一样啊」。逐条比对了设计稿 `.d2` 的 CSS 与
`TerminalDialog.kt` + 两个 layout，修掉 4 处偏差：

| 项 | 设计稿 | 首版实现 | 处理 |
|---|---|---|---|
| 外圈描边 | 单层 `rgba(254,254,254,.32)` | **两层**，且描边色是 `colorPrimary`（终端下 `#FF6699` 荧光粉） | 去掉 content 那层，只留窗口背景；描边改 `colorOnSurface @32%` |
| 标题分割线 | `border-bottom` | 没有 | 新增 `terminal_dialog_divider`（`colorOnSurface @18%`） |
| 单选右侧 ✓ | 有 `.ck` | 控件在但从未赋值 | `paintSelection` 填 `GLYPH_CHECK` |
| 等宽字体 | `var(--mono)` | 系统默认字体 | 按用户决定**不做** |

**外圈描边的成因**（用户直接指出）—— 主题 overlay 的 `android:background=@drawable/dialog_background`
给窗口画一层，`Sheet.init` 又给 content 画一层，两层之间隔着 dialog 默认 padding，
看起来就是「弹窗外面又套一圈线」。`Sheet.init` 现只保留
`dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))` +
`dialog.setView(content, 0, 0, 0, 0)`，**不要再给 content 设 background**。

**圆角 6dp vs 设计稿 12dp 未改**：`card_round`(6dp) 是「方角」档、`card_round_large`(12dp) 是
「圆角」档，即用户在「外观设置」里切换的那一项。弹窗若钉死 12dp，用户选「方角」仍会看到
圆角弹窗，与设置项语义打架。故跟随 `card_round`。对比图见 `docs/design/dialog-implemented-v3.html` §6。

### 五、「只剩复制」时不弹面板

别人的评论/动态只有「复制文字」一个可操作项，此前长按会弹一个只有单选项的菜单。
现 `ReplyAdapter.showManageMenu` 与 `DynamicHolder.showManageMenu` 在列出 actions 后判一次：

```kotlin
if (actions.size == 1 && actions[0].first == "复制文字") {
    actions[0].second()
    return
}
```

位置在 `actions.isEmpty()` 守卫**之后**（否则 `actions[0]` 会越界）。语义说明写进
`util/LongPressPrefs.kt` 的文件头注释。

## 未完成 / 待验证

- **编译与单测已通过**：`:app:assembleDebug` → `BUILD SUCCESSFUL`；
  `:app:testDebugUnitTest --rerun-tasks` → 46 个 suite / **422 项全过**（failures=0 errors=0）。
  过程中修掉 3 个真实编译错误：`R.attr.colorPrimary` / `colorOnSurface` / `colorError` 在
  `app/src/main/res/values/` 里**从未声明过**（它们来自依赖库），必须写全限定库 R ——
  `androidx.appcompat.R.attr.colorPrimary`（appcompat 声明了 `colorPrimary`/`colorError`，
  但**没有** `colorOnSurface`）与 `com.google.android.material.R.attr.colorOnSurface`/`colorError`。
- **真机目视确认未完成**：`adb install` 到已连接的 `V2229A` 始终被拒
  （`INSTALL_FAILED_ABORTED: User rejected permissions`，关闭安装校验与唤醒屏幕后仍被拒），
  疑为 vivo 系统侧限制。`docs/design/dialog-implemented-v3.html` 是按代码取值在 HTML 里
  等价还原的预览，不是真机截图。
- `:app:clean` 在本机**永久失败**：`app/build/outputs/apk/release` 空目录被某进程占着句柄，
  `rmdir` / `Remove-Item -Force` / `Rename-Item` 全失败，`--stop` 停 daemon 后仍锁。
  绕过方式：跳过 clean 直接 `assembleDebug --no-build-cache --no-configuration-cache`。
- `docs/visual-experience-report.md` §2.25 记录的对比度问题（`#EBE0E2` 叠 `#FF6699` 约 2.1:1，
  AA 要求 4.5:1）—— 本次新增的 `colorError` 语义色（如 `#FF6A6A`）**没有**重新做对比度测量。

## 遗留的文档漂移（本次未改）

- `res/values/styles.xml:10` 注释指向 `ui/appearance/CornerRadius.kt`，实际文件是 `CornerStyle.kt`。
