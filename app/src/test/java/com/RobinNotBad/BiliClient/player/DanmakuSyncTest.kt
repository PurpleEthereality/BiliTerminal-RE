package com.RobinNotBad.BiliClient.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DanmakuSync] 的纯 JVM 单测。
 *
 * 钉住的是一条真实回归：播放位置每 250ms 采样一次、**天然滞后 0~250ms**。
 * 若判定逻辑对这个量级的偏差也返回 true，就会每次采样都把 DFM 平滑自走的弹幕时钟
 * 往回拽一次，滚动弹幕表现为"每 0.25 秒一跳"。
 */
class DanmakuSyncTest {

    private val tolerance = DanmakuSync.TOLERANCE_MS

    @Test
    fun steadyStateStaleness_doesNotResync() {
        // 采样间隔 250ms ⇒ 任意时刻位置最多滞后 250ms，稳态下必须一次都不校正
        for (lag in 0L..250L) {
            assertFalse(
                "滞后 ${lag}ms 不应触发校正，否则就是每 0.25 秒把时钟往回拽一次（滚动弹幕一跳一跳）",
                DanmakuSync.shouldResync(1000L - lag, 1000L)
            )
        }
    }

    @Test
    fun withinTolerance_doesNotResync() {
        assertFalse("位置与时钟一致时不需要校正", DanmakuSync.shouldResync(1000L, 1000L))
        assertFalse("正向刚好等于容差时不校正", DanmakuSync.shouldResync(1000L + tolerance, 1000L))
        assertFalse("负向刚好等于容差时不校正", DanmakuSync.shouldResync(1000L - tolerance, 1000L))
    }

    @Test
    fun beyondTolerance_resyncs() {
        assertTrue("时钟落后于视频超过容差，应拉回", DanmakuSync.shouldResync(1000L + tolerance + 1, 1000L))
        assertTrue("时钟跑在视频前面超过容差，应拉回", DanmakuSync.shouldResync(1000L - tolerance - 1, 1000L))
    }

    @Test
    fun untrustedPosition_neverResyncs() {
        // 播放器未就绪/重建窗口期返回负数：此时灌进 timer 会让整批弹幕被判定为"已过期"而不显示
        assertFalse(DanmakuSync.shouldResync(-1L, 0L))
        assertFalse("位置不可信时，偏差再大也不能校正", DanmakuSync.shouldResync(-1L, 10_000L))
        assertFalse(DanmakuSync.shouldResync(Long.MIN_VALUE, 0L))
    }

    @Test
    fun tolerance_isLargerThanSamplingInterval() {
        assertTrue(
            "容差必须大于 250ms 的采样间隔，否则每次采样都会触发校正，等于没修",
            tolerance > 250L
        )
    }
}
