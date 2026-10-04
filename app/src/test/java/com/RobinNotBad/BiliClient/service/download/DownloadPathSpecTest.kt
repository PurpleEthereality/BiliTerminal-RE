package com.RobinNotBad.BiliClient.service.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载路径规格与结果码的常量测试（E2 拆分 DownloadService 第 1 步）。
 *
 * 这些字面量是**磁盘与数据库的事实协议**：文件名一改，旧的半成品目录就找不回续传标记、
 * 画质标记对不上、成品替换失败；结果码一改，`runDownloadSection` 会把暂停误判成失败
 * （进而清理半成品）或把失败误判成成功。它们在 Kotlin 侧只是常量，改错了编译器不会说话，
 * 所以用测试把取值钉死。
 */
class DownloadPathSpecTest {

    @Test
    fun 文件名与目录名保持磁盘协议不变() {
        assertEquals(".DOWNLOADING", DownloadPathSpec.FILE_DOWNLOADING)
        assertEquals(".quality", DownloadPathSpec.FILE_QUALITY)
        assertEquals("cover.png", DownloadPathSpec.FILE_COVER)
        assertEquals("danmaku.xml", DownloadPathSpec.FILE_DANMAKU)
        assertEquals("subtitles", DownloadPathSpec.DIR_SUBTITLES)
        assertEquals("video.mp4", DownloadPathSpec.FILE_VIDEO)
        assertEquals("audio.m4a", DownloadPathSpec.FILE_AUDIO)
        assertEquals("video_new.mp4", DownloadPathSpec.FILE_VIDEO_TMP)
        assertEquals("audio_new.m4a", DownloadPathSpec.FILE_AUDIO_TMP)
    }

    @Test
    fun 临时文件与成品文件不能同名() {
        // 同名会让"下载完整才替换成品"的保护彻底失效：半成品会直接盖掉能播的旧文件
        assertTrue(DownloadPathSpec.FILE_VIDEO != DownloadPathSpec.FILE_VIDEO_TMP)
        assertTrue(DownloadPathSpec.FILE_AUDIO != DownloadPathSpec.FILE_AUDIO_TMP)
        assertTrue(DownloadPathSpec.FILE_VIDEO != DownloadPathSpec.FILE_AUDIO)
    }

    @Test
    fun 数据库类型字面量不变() {
        // download 表 type 列与 download_type 列直接存这些字面量，改了就认不出老记录
        assertEquals("video_single", DownloadPathSpec.TASK_VIDEO_SINGLE)
        assertEquals("video_multi", DownloadPathSpec.TASK_VIDEO_MULTI)
        assertEquals("audio_only", DownloadPathSpec.TYPE_AUDIO_ONLY)
    }

    @Test
    fun 结果码取值不变() {
        assertEquals(0, DownloadPathSpec.NORMAL)
        assertEquals(-1, DownloadPathSpec.ERR_NETWORK)
        assertEquals(-2, DownloadPathSpec.ERR_JSON)
        assertEquals(-3, DownloadPathSpec.ERR_FILE)
        assertEquals(-4, DownloadPathSpec.ERR_DATABASE)
        assertEquals(-7, DownloadPathSpec.ERR_UNKNOWN)
        assertEquals(-8, DownloadPathSpec.ERR_PAUSED)
    }

    @Test
    fun 结果码互不相同且只有NORMAL表示成功() {
        val codes = listOf(
            DownloadPathSpec.NORMAL,
            DownloadPathSpec.ERR_NETWORK,
            DownloadPathSpec.ERR_JSON,
            DownloadPathSpec.ERR_FILE,
            DownloadPathSpec.ERR_DATABASE,
            DownloadPathSpec.ERR_UNKNOWN,
            DownloadPathSpec.ERR_PAUSED
        )
        assertEquals("结果码不能重号，否则失败会被误判", codes.size, codes.toSet().size)
        assertEquals("只有 0 表示成功", 1, codes.count { it == DownloadPathSpec.NORMAL })
        assertEquals("其余全是负数", codes.size - 1, codes.count { it < 0 })
    }
}
