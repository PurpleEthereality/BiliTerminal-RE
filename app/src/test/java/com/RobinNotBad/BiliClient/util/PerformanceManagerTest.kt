package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PerformanceManager` 的纯函数分支（26.10.04 批次 3）。
 *
 * 只测不碰 Android 的部分：硬件探测（[PerformanceManager.getHardwareScore]）要读
 * sysfs / `/proc/cpuinfo`，JVM 单测里没有；档位换算与图片/分页参数则全是纯函数或纯状态读。
 */
class PerformanceManagerTest {

    // ===== levelFromScore：档位分界（>=65 高 / >=35 中 / else 低）=====

    @Test
    fun levelFromScore_boundaries() {
        assertEquals(PerformanceManager.PERF_LEVEL_LOW, PerformanceManager.levelFromScore(0))
        assertEquals(PerformanceManager.PERF_LEVEL_LOW, PerformanceManager.levelFromScore(34))
        assertEquals(PerformanceManager.PERF_LEVEL_MEDIUM, PerformanceManager.levelFromScore(35))
        assertEquals(PerformanceManager.PERF_LEVEL_MEDIUM, PerformanceManager.levelFromScore(64))
        assertEquals(PerformanceManager.PERF_LEVEL_HIGH, PerformanceManager.levelFromScore(65))
        assertEquals(PerformanceManager.PERF_LEVEL_HIGH, PerformanceManager.levelFromScore(100))
    }

    // ===== isLowPerfLevel：低端设备手动开高性能后不再算低端 =====

    @Test
    fun isLowPerfLevel_lowWithoutHighPerfMode() {
        assertTrue(PerformanceManager.isLowPerfLevel(PerformanceManager.PERF_LEVEL_LOW, false))
    }

    @Test
    fun isLowPerfLevel_lowWithHighPerfMode_isNotLow() {
        assertFalse(PerformanceManager.isLowPerfLevel(PerformanceManager.PERF_LEVEL_LOW, true))
    }

    @Test
    fun isLowPerfLevel_nonLowLevels() {
        assertFalse(PerformanceManager.isLowPerfLevel(PerformanceManager.PERF_LEVEL_MEDIUM, false))
        assertFalse(PerformanceManager.isLowPerfLevel(PerformanceManager.PERF_LEVEL_HIGH, false))
    }

    // ===== 列表图档位：低端 320w/50q，其余 512w/60q =====

    @Test
    fun listImageParams_lowTier() {
        assertEquals(50, PerformanceManager.listImageQuality(PerformanceManager.PERF_LEVEL_LOW, false))
        assertEquals(320, PerformanceManager.listImageMaxWidth(PerformanceManager.PERF_LEVEL_LOW, false))
    }

    @Test
    fun listImageParams_mediumAndHighTier_keep512w60q() {
        for (level in listOf(PerformanceManager.PERF_LEVEL_LOW, PerformanceManager.PERF_LEVEL_MEDIUM, PerformanceManager.PERF_LEVEL_HIGH)) {
            for (highPerf in listOf(false, true)) {
                // 只有"低端且没开高性能"才降到 320w/50q，其余一律 512w/60q
                val expectLow = PerformanceManager.isLowPerfLevel(level, highPerf)
                assertEquals(if (expectLow) 50 else 60, PerformanceManager.listImageQuality(level, highPerf))
                assertEquals(if (expectLow) 320 else 512, PerformanceManager.listImageMaxWidth(level, highPerf))
            }
        }
    }

    @Test
    fun listImageParams_mediumTier_isNotUpscaledToQ80() {
        // 台账原本写"中/高档一律 80q/1024w"，那会让列表图从 512 变 1024（像素 ×4）；
        // 这里锁死中档仍是 512w/60q，防止以后又被改回去。
        assertEquals(60, PerformanceManager.listImageQuality(PerformanceManager.PERF_LEVEL_MEDIUM, false))
        assertEquals(512, PerformanceManager.listImageMaxWidth(PerformanceManager.PERF_LEVEL_MEDIUM, false))
    }

    // ===== 大图档位：低端 512w/60q，其余 1024w/80q =====

    @Test
    fun hqImageParams_lowTier() {
        assertEquals(60, PerformanceManager.hqImageQuality(PerformanceManager.PERF_LEVEL_LOW, false))
        assertEquals(512, PerformanceManager.hqImageMaxWidth(PerformanceManager.PERF_LEVEL_LOW, false))
    }

    @Test
    fun hqImageParams_mediumAndHighTier_keep1024w80q() {
        assertEquals(80, PerformanceManager.hqImageQuality(PerformanceManager.PERF_LEVEL_MEDIUM, false))
        assertEquals(1024, PerformanceManager.hqImageMaxWidth(PerformanceManager.PERF_LEVEL_MEDIUM, false))
        assertEquals(80, PerformanceManager.hqImageQuality(PerformanceManager.PERF_LEVEL_HIGH, false))
        assertEquals(1024, PerformanceManager.hqImageMaxWidth(PerformanceManager.PERF_LEVEL_HIGH, false))
    }

    // ===== 默认档位（没有 init、没有缓存时）=====
    // 批次 3 把首次检测挪到后台，所以冷启动最初就是"中档"，这些值就是用户第一眼看到的参数。

    @Test
    fun defaultsBeforeInit_areMedium() {
        assertEquals(PerformanceManager.PERF_LEVEL_MEDIUM, PerformanceManager.getCurrentPerfLevel())
        assertFalse(PerformanceManager.isLowPerfDevice())

        assertEquals(20, PerformanceManager.getPageSize())
        assertEquals(60, PerformanceManager.getImageQuality())
        assertEquals(512, PerformanceManager.getImageMaxWidth())
        assertEquals(80, PerformanceManager.getHqImageQuality())
        assertEquals(1024, PerformanceManager.getHqImageMaxWidth())
    }
}
