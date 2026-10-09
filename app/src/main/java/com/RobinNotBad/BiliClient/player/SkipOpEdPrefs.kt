package com.RobinNotBad.BiliClient.player

/**
 * 「自动跳过片头片尾」两个设置位的**默认值与判据**（纯逻辑，无 Android 依赖，可直接 JVM 单测）。
 *
 * 为什么单独抽出来：这两个默认值原先散在四处 `getBoolean(KEY, false)` / `"false"`
 * （`PlayerActivity` 的 `needViewPoints()` / `maybeAutoSkipOpEd()` / `maybeShowSkipGuide()`
 * 三处，以及 `SettingTerminalPlayerActivity` 的设置项默认值），
 * 默认值一改就要四处同步，漏一处的症状是「设置页显示开着、播放器却不跳」这种不报错的错位。
 * 所以改默认值时**只改本文件的 [DEFAULT_ENABLED]**，四个调用点全部引用它。
 *
 * **默认开启**（26.10.04 后续）：PiliPlus 的同类开关 `pgcSkipType` 默认就是 `SkipType.skipOnce`
 * （`lib/utils/storage_pref.dart:977-979`），B 站官方播放器同样默认跳过；且跳过之后有「撤回」兜底，
 * 误跳的代价远小于「用户不知道有这功能」。
 *
 * 注意 [DEFAULT_ENABLED] 只用于**读取**：`SharedPreferences.getBoolean(key, def)` 的语义就是
 * 「键不存在才用 def」，所以老用户若显式关过，升级后仍然是关的——这正是我们要的行为。
 *
 * ⚠️ **26.10.10 起本功能已紧急撤回**：整个功能由 [FEATURE_ENABLED] 关掉并在设置页隐藏，
 * 与用户存档无关。下面关于「默认开启」的历史说明保留，仅作为恢复后的依据。
 */
object SkipOpEdPrefs {

    /** `player_skip_op_ed` 的默认值：开启。 */
    const val DEFAULT_ENABLED = true

    /**
     * **功能总开关：26.10.10 紧急撤回，现在置为 `false`。**
     *
     * 为什么不直接把 [DEFAULT_ENABLED] 改成 `false`：默认值只决定「键不存在时读什么」，
     * 老用户存档里早就是 `true` 了，改默认值对他们**一点作用都没有**，自动跳过照旧生效。
     * 撤回必须与存档无关，所以这里是独立的否决位。
     *
     * 撤回期间的实际表现：
     * - 自动跳过完全失效（[isEnabled] 恒为 `false`，与 [DEFAULT_ENABLED]、存档都无关）；
     * - 播放器不再为了跳过而拉取 `view_points`（见 `PlayerActivity.needViewPoints()`）；
     * - 首次引导不再弹出（引导里带「开启」按钮，会写回存档）；
     * - 设置页入口已隐藏（`SettingTerminalPlayerActivity`）与搜索索引里已摘掉
     *   （`SettingsIndex`），用户没有任何途径把它打开。
     *
     * 恢复时只把这里改回 `true` 并把设置项加回去即可——[ViewPointSkip] 的判定逻辑
     * 与它的单测**一行未动**，不是靠删代码做到的撤回。
     */
    const val FEATURE_ENABLED = false

    /**
     * 自动跳过当前是否生效：功能总开关 **与** 用户存档，两者都为真才算开。
     *
     * 抽成纯函数是为了能直接单测「撤回期间存档里写着 `true` 也不能跳」这条断言——
     * 它恰恰是这次撤回最容易被改错的地方（只改默认值是最常见的错法）。
     */
    fun isEnabled(prefValue: Boolean): Boolean = FEATURE_ENABLED && prefValue

    /**
     * 首次引导是否该弹。
     *
     * **判据只看「引导过没有」，不看开关当前值**。原实现是「开关开着就 return」，
     * 一旦默认变成开启，这条引导（真机清单第 200 条）就永远不会出现，
     * 新用户也就没有机会知道「刚才那段被跳过了、还能撤回」。
     * 改成与开关解耦后：没引导过就引导一次，见过一次就永不再弹。
     *
     * @param hasSegments 本视频是否真的有片头/片尾可跳；没有就不引导（否则是骚扰）
     * @param alreadyGuided `player_skip_op_ed_guided` 记账位
     */
    fun shouldShowGuide(hasSegments: Boolean, alreadyGuided: Boolean): Boolean =
        hasSegments && !alreadyGuided

    /**
     * 引导文案：开关已开时不该再劝用户「开启」，只提示「已自动跳过、可撤回」。
     *
     * 做成纯函数而不是在 `PlayerActivity` 里写 if，是为了让两句话都能被单测覆盖——
     * 文案错位（开着还说"可以开启"）不会报错，只能靠测试发现。
     */
    fun guideText(enabled: Boolean): String =
        if (enabled) "已自动跳过片头/片尾，可在下方撤回" else "这个视频有片头片尾，可以自动跳过"

    /** 引导上的按钮文案：已经开着就没有「开启」可点，只留一个「知道了」。 */
    fun guideActionText(enabled: Boolean): String = if (enabled) "知道了" else "开启"
}
