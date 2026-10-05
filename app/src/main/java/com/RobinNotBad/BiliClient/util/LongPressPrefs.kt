package com.RobinNotBad.BiliClient.util

/**
 * 「长按打开操作面板」的默认值与判据。
 *
 * <h3>这个开关解决什么</h3>
 * 评论正文、动态正文上的长按原先只做一件事——打开复制界面。等到自己的稿件/动态有了
 * 「置顶」「删除」之后，同一个长按手势开始承载两种结果，于是变成用户报的
 * 「和长按复制冲突」。现在的方案是把**复制收进面板**，长按只负责弹面板：
 *
 * - 开关**开启**（默认）：长按正文 → 弹面板。面板第一项是「复制」（受 [SettingsKeys.COPY_ENABLE]
 *   约束，且正文为空时不列出），其余是置顶/删除等管理项。
 * - 开关**关闭**：长按正文 → 直接打开复制界面，即改版前的旧行为。
 *
 * **管理按钮不受本开关影响**：评论末列的操作按钮、动态的「管理」按钮点击时一律弹面板。
 * 本开关只管「长按正文」这一个入口。
 *
 * <h3>别人的内容不弹面板</h3>
 * 一条内容如果**除「复制」之外没有别的可操作项**（典型就是别人的评论/动态：非管理员、
 * 也不是自己发的），面板就只剩一个孤零零的「复制文字」，多一次点击才拿到本来就该直接
 * 给的东西。所以调用点在列出 actions 之后会判一次「是否只剩复制」——是则**直接打开复制
 * 界面**，不弹这个没有选择意义的面板。判据写在各自的 `showManageMenu` 里（需要知道
 * 有哪些管理项），本文件只负责「复制这项该不该列出来」。
 *
 * <h3>为什么默认值收在这里</h3>
 * 与 `player/SkipOpEdPrefs` 同样的理由：默认值一旦散落在多个调用点，改漏一处的症状是
 * 「设置页显示开着、实际却是关的」——这种错不报错、只能靠人肉比对发现。
 */
object LongPressPrefs {

    /** `SettingsKeys.LONG_PRESS_PANEL_ENABLE` 的默认值：开启。 */
    const val DEFAULT_ENABLED = true

    /**
     * 当前是否开启。
     *
     * 语义同 `SharedPreferencesUtil.getBoolean(key, def)`：**键不存在才用默认值**，
     * 所以老用户显式关过之后不会被默认值重新打开。
     */
    @JvmStatic
    fun isEnabled(): Boolean =
        SharedPreferencesUtil.getBoolean(SettingsKeys.LONG_PRESS_PANEL_ENABLE, DEFAULT_ENABLED)

    /**
     * 面板里要不要出现「复制」这一项。
     *
     * 两个条件缺一不可：用户没关掉复制功能（`copy_enable`），且这次的内容确实有文字。
     * 抽成纯函数是因为「空正文还列出复制」不会崩、也不会报错，只是点了没反应——
     * 这种缺陷没法靠编译器和异常发现，只能靠断言钉住。
     */
    @JvmStatic
    fun shouldOfferCopy(copyEnabled: Boolean, text: CharSequence?): Boolean =
        copyEnabled && !text.isNullOrEmpty()
}
