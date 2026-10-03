package com.RobinNotBad.BiliClient.player

import com.RobinNotBad.BiliClient.util.SettingsKeys

/**
 * 「播放默认值」的纯解析逻辑（无 Android 依赖，可直接 JVM 单测）。
 *
 * 每个三态项在设置里存两处：
 * - **模式**：`SettingsKeys.PLAYER_DEFAULT_MODE_ON / _OFF / _LAST`；
 * - **上次实际值**：对应的 `PLAYER_LAST_*`（播放中用户切换时写入，跨重启保存）。
 *
 * 播放开始时由 [resolveTriState] 等函数算出本次真正要用的值。
 */
object PlayerDefaults {

    /**
     * 字幕候选项。
     *
     * 刻意不直接用 `model/SubtitleLink`（它实现了 `Parcelable`，会把 Android 依赖带进来），
     * 由调用方做一次映射，纯逻辑这层才能跑 JVM 单测。
     */
    data class SubtitleCandidate(val lang: String?, val isAI: Boolean)

    /**
     * 三态解析：把「模式 + 上次值」解析成本次播放实际要用的布尔值。
     *
     * [lastUsed] 由调用方用 `getBoolean(PLAYER_LAST_*, 该功能的原默认值)` 读出，
     * 于是「选了沿用上次但还没有历史」会自然回落到各功能的原默认值（行为与改造前一致）。
     * 未知模式值也按「沿用上次」处理，避免脏数据把功能锁死成开或关。
     */
    fun resolveTriState(mode: String, lastUsed: Boolean): Boolean = when (mode) {
        SettingsKeys.PLAYER_DEFAULT_MODE_ON -> true
        SettingsKeys.PLAYER_DEFAULT_MODE_OFF -> false
        else -> lastUsed
    }

    /**
     * 倍速解析：存的可能是固定值（"0.75"）或「沿用上次」（"last"）。
     * 解析不出数字时一律用 [lastUsed]。
     */
    fun resolveSpeed(value: String, lastUsed: Float): Float = value.toFloatOrNull() ?: lastUsed

    /**
     * 屏幕方向解析：只有「按视频分辨率」需要宽高，宽 > 高判为横屏。
     *
     * 宽高未知（<= 0，例如还没 prepare）时按竖屏处理；拿到分辨率后（`changeVideoSize()`）
     * 再调用一次即可切过去。
     */
    fun resolveLandscape(mode: String, videoWidth: Int, videoHeight: Int): Boolean = when (mode) {
        SettingsKeys.PLAYER_DEFAULT_ORIENTATION_LANDSCAPE -> true
        SettingsKeys.PLAYER_DEFAULT_ORIENTATION_AUTO ->
            videoWidth > 0 && videoHeight > 0 && videoWidth > videoHeight
        else -> false
    }

    /**
     * 语言标签是否为中文。
     *
     * B 站字幕列表给的是 `lan_doc`（形如「中文（中国）」「中文（自动生成）」「英语（美国）」），
     * 不是 BCP-47 标签，所以既要认 "中文" 也要认 "zh"（兼容将来换成标准标签）。
     */
    fun isChineseLang(lang: String?): Boolean {
        if (lang.isNullOrBlank()) return false
        return lang.lowercase().contains("zh") || lang.contains("中文")
    }

    /**
     * 在字幕候选项里挑中文：**优先人工中文，其次 AI 中文**，都没有则返回 -1。
     *
     * 列表末尾的「不显示字幕」项语言不含中文，会被天然跳过。
     */
    fun pickChineseSubtitleIndex(candidates: List<SubtitleCandidate>): Int {
        var aiIndex = -1
        for ((i, c) in candidates.withIndex()) {
            if (!isChineseLang(c.lang)) continue
            if (!c.isAI) return i
            if (aiIndex < 0) aiIndex = i
        }
        return aiIndex
    }
}
