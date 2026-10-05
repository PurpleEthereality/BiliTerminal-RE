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
 */
object SkipOpEdPrefs {

    /** `player_skip_op_ed` 的默认值：开启。 */
    const val DEFAULT_ENABLED = true

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
