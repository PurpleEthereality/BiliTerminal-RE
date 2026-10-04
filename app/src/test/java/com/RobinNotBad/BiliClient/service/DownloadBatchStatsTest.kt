package com.RobinNotBad.BiliClient.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 下载批次统计与速度格式化的纯逻辑测试（E2 拆分 DownloadService 第 1 步补测）。
 *
 * 这批逻辑原本就没在 DownloadService 里，但没有单测覆盖：
 * 总进度算错只会让进度条停在半路（不报错、不影响文件），速度算错只会显示奇怪数字，
 * 都属于"没人报 bug 但用户看得见"的问题，所以在这里钉住语义。
 */
class DownloadBatchStatsTest {

    // ---------- DownloadBatchStats ----------

    @Test
    fun overallProgress_没有任何任务时为0() {
        val stats = DownloadBatchStats()
        assertEquals(0f, stats.overallProgress(0f, 0, 0), 0f)
        assertEquals("没有任务但传了进度和，除以 0 前必须先返回", 0f, stats.overallProgress(1f, 0, 0), 0f)
    }

    @Test
    fun overallProgress_失败项目视作终态不卡进度条() {
        val stats = DownloadBatchStats()
        stats.recordSuccess()
        stats.recordFailure()
        assertEquals("完成 1 + 失败 1，两项都算做完", 1f, stats.overallProgress(0f, 0, 0), 1e-6f)
        assertEquals(1, stats.completed)
        assertEquals(1, stats.failed)
    }

    @Test
    fun overallProgress_进行中与等待中一起进分母() {
        val stats = DownloadBatchStats()
        stats.recordSuccess()
        stats.recordFailure()
        // 总工作量 = 1 完成 + 1 失败 + 2 进行中 + 1 等待 = 5
        // 已完成单位 = 1 + 1 + 1.5 = 3.5 → 0.7
        assertEquals(0.7f, stats.overallProgress(1.5f, 2, 1), 1e-6f)
    }

    @Test
    fun overallProgress_进行中进度和会被夹到合法范围() {
        val stats = DownloadBatchStats()
        assertEquals("进度和超过进行中项目数（每项最多 1）要夹回 2", 1f,
            stats.overallProgress(10f, 2, 0), 1e-6f)
        assertEquals("负数进度夹回 0，不能把总量算成负的", 0f,
            stats.overallProgress(-5f, 2, 0), 1e-6f)
    }

    @Test
    fun overallProgress_结果恒在0到1之间() {
        val stats = DownloadBatchStats()
        stats.recordSuccess()
        assertEquals(1f, stats.overallProgress(0f, 0, 0), 0f)
        assertEquals(1f, stats.overallProgress(99f, 0, 0), 1e-6f)
    }

    @Test
    fun reset_清空完成与失败计数() {
        val stats = DownloadBatchStats()
        stats.recordSuccess()
        stats.recordSuccess()
        stats.recordFailure()
        stats.reset()
        assertEquals(0, stats.completed)
        assertEquals(0, stats.failed)
        assertEquals("清空后没有任务", 0f, stats.overallProgress(0f, 0, 0), 0f)
    }

    // ---------- SpeedSampler ----------

    @Test
    fun speedSampler_前两次采样数据不足返回null() {
        val sampler = SpeedSampler()
        sampler.reset(0L)
        // DownloadService 里 resetSpeedSampling 紧跟 resetDownloadedBytes，
        // 所以第一次采样传进来的一定是 0 字节
        assertNull("首次采样必须返回 null（由调用方回退到上一次显示值）", sampler.sample(0L, 1000L))
        // 第二次采样：上一次记下的基准是 0，仍然没有可用的分母
        assertNull("基准为 0 时不能拿它当分母", sampler.sample(1000L, 2000L))
        // 第三次采样：基准 1000、间隔 1s
        assertEquals(1000L, sampler.sample(2000L, 3000L))
    }

    @Test
    fun speedSampler_间隔过短返回null() {
        val sampler = SpeedSampler()
        sampler.reset(0L)
        sampler.sample(1000L, 1000L)
        assertNull("0.2s 间隔太短，速度会剧烈抖动", sampler.sample(2000L, 1200L))
        assertEquals("0.8s 间隔可用：(3000-2000)/0.8 = 1250", 1250L, sampler.sample(3000L, 2000L))
    }

    @Test
    fun speedSampler_字节数没增长时返回0而不是负数() {
        val sampler = SpeedSampler()
        sampler.reset(0L)
        sampler.sample(5000L, 0L)
        sampler.sample(5000L, 1000L)
        assertEquals("字节数不变时速度为 0", 0L, sampler.sample(5000L, 2000L))
        assertEquals("已下载字节总数被清零（如服务重启）时不能算出负数", 0L, sampler.sample(100L, 3000L))
    }

    @Test
    fun speedSampler_reset后重新进入数据不足状态() {
        val sampler = SpeedSampler()
        sampler.reset(0L)
        sampler.sample(1000L, 0L)
        sampler.sample(2000L, 1000L)
        assertEquals(1000L, sampler.sample(3000L, 2000L))
        sampler.reset(3000L)
        assertNull("reset 后基准归零，要重新攒够两次采样", sampler.sample(4000L, 4000L))
    }

    // ---------- formatDownloadSpeed ----------

    @Test
    fun formatDownloadSpeed_按量级切换单位() {
        assertEquals("0 B/s", formatDownloadSpeed(0L))
        assertEquals("1 B/s", formatDownloadSpeed(1L))
        assertEquals("1023 B/s", formatDownloadSpeed(1023L))
        assertEquals("1.0 KB/s", formatDownloadSpeed(1024L))
        assertEquals("1024.0 KB/s", formatDownloadSpeed(1048575L))
        assertEquals("1.0 MB/s", formatDownloadSpeed(1048576L))
        assertEquals("2.5 MB/s", formatDownloadSpeed(2621440L))
    }

    @Test
    fun formatDownloadSpeed_百万级速度不出现科学计数法() {
        assertEquals("1000.0 MB/s", formatDownloadSpeed(1048576000L))
    }
}
