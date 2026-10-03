package com.RobinNotBad.BiliClient.player

/**
 * 弹幕时钟校正的纯判定逻辑（无 Android 依赖，可直接 JVM 单测）。
 *
 * 背景：DFM 在 `DrawHandler.syncTimer` 里每帧 `timer.add(d)`（`DrawHandler.java:466`）平滑推进
 * 弹幕时钟；而播放位置由主线程定时器每 250ms 采样一次喂进来。若每次采样都 `timer.update(pos)`
 * （把时钟强行拨到该值），DFM 自走的时钟会被周期性往回拽，滚动弹幕表现为"每 0.25 秒一跳"。
 */
object DanmakuSync {

    /**
     * 弹幕时钟与播放位置的最大容许偏差（毫秒），**超过**才把 DFM 自走的时钟拉回。
     *
     * 位置每 250ms 采样一次，天然滞后 0~250ms；容差必须大于该间隔，否则每次采样都会触发校正。
     * 取 400ms 再留约 150ms 给主线程卡顿余量，保证稳态下不校正。
     */
    const val TOLERANCE_MS = 400L

    /**
     * 是否需要把弹幕时钟拉回到 [posMs]。
     *
     * @param posMs 当前播放位置（毫秒）。**负数表示"位置不可信"**（播放器未就绪或正在重建），
     *   此时必须跳过校正 —— 把窗口期的脏位置灌进 timer 会让整批弹幕被判定为"已过期"而一条都不显示。
     * @param timerMs DFM 当前自走的时钟（`DanmakuTimer.currMillisecond`）。
     * @return true 表示偏差已超出 [TOLERANCE_MS]，应当 `timer.update(posMs)`。
     */
    fun shouldResync(posMs: Long, timerMs: Long): Boolean {
        if (posMs < 0) return false
        val drift = posMs - timerMs
        return drift > TOLERANCE_MS || drift < -TOLERANCE_MS
    }
}
