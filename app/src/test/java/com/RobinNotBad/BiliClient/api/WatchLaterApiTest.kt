package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.VideoCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 稍后再看「未看完」判据与过滤（C18）。
 *
 * 只测纯函数，不碰网络。VideoCard 是 Parcelable，但这里只 new + 读字段，不调用任何 android 方法。
 */
class WatchLaterApiTest {

    private fun card(aid: Long, progress: Int, duration: Long): VideoCard {
        val card = VideoCard("标题$aid", "up", "1万观看", "cover", aid, "BV$aid")
        card.progress = progress
        card.duration = duration
        return card
    }

    @Test
    fun isUnfinished_requiresPositiveProgress() {
        assertFalse(WatchLaterApi.isUnfinished(0, 100))
        assertFalse(WatchLaterApi.isUnfinished(-5, 100))
    }

    @Test
    fun isUnfinished_treatsUnknownDurationAsUnfinished() {
        // 总时长未知（0）时只看进度，避免整表被判成「已看完」
        assertTrue(WatchLaterApi.isUnfinished(1, 0))
        assertTrue(WatchLaterApi.isUnfinished(50, -1))
    }

    @Test
    fun isUnfinished_comparesProgressWithDuration() {
        assertTrue(WatchLaterApi.isUnfinished(50, 100))
        assertFalse(WatchLaterApi.isUnfinished(100, 100))
        assertFalse(WatchLaterApi.isUnfinished(120, 100))
    }

    @Test
    fun filterUnfinished_toleratesNullAndEmpty() {
        assertTrue(WatchLaterApi.filterUnfinished(null, true).isEmpty())
        assertTrue(WatchLaterApi.filterUnfinished(emptyList(), true).isEmpty())
        assertTrue(WatchLaterApi.filterUnfinished(emptyList(), false).isEmpty())
    }

    @Test
    fun filterUnfinished_skipsNullElements() {
        // 接口理论上不会给 null 元素，但过滤逻辑要防脏数据（Java 侧可空，Kotlin 需要强转）
        @Suppress("UNCHECKED_CAST")
        val list = listOf(card(1, 10, 100), null, card(2, 100, 100)) as List<VideoCard>
        val result = WatchLaterApi.filterUnfinished(list, true)
        assertEquals(1, result.size)
        assertEquals(1L, result[0].aid)
    }

    @Test
    fun filterUnfinished_returnsOnlyUnfinishedInOriginalOrder() {
        val first = card(1, 30, 100)
        val done = card(2, 100, 100)
        val untouched = card(3, 0, 100)
        val unknownDuration = card(4, 5, 0)
        val result = WatchLaterApi.filterUnfinished(listOf(first, done, untouched, unknownDuration), true)
        assertEquals(listOf(1L, 4L), result.map { it.aid })
        assertSame(first, result[0])
    }

    @Test
    fun filterUnfinished_falseReturnsCopyOfWholeList() {
        val a = card(1, 30, 100)
        val b = card(2, 100, 100)
        val source = listOf(a, b)
        val result = WatchLaterApi.filterUnfinished(source, false)
        assertEquals(2, result.size)
        assertSame(a, result[0])
        // 改副本不影响原表
        result.removeAt(0)
        assertEquals(2, source.size)
    }
}
