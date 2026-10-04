package com.RobinNotBad.BiliClient.player

import com.RobinNotBad.BiliClient.player.ViewPointSkip.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ViewPointSkip] 的纯 JVM 单测。
 *
 * 钉住的是三条真实会翻车的边界：
 * 1. 区间必须**左闭右开**——跳过之后位置正好落在 `toSec`，右闭会让定时器每轮都再跳一次；
 * 2. 每个片段**只跳一次**——否则用户点了「撤回」往回拖，下一轮又会被弹走；
 * 3. 用户**手动**拖进片段内要算「已处理」——他主动看片头，不该被自动逻辑打断。
 */
class ViewPointSkipTest {

    private val op = Segment(ViewPointSkip.TYPE_OP, 15.0, 95.0)
    private val ed = Segment(ViewPointSkip.TYPE_ED, 1380.0, 1440.0)

    @Test
    fun onlyOpeningAndEndingAreSkippable() {
        assertTrue("片头(type=1)必须可跳", ViewPointSkip.isSkippable(ViewPointSkip.TYPE_OP))
        assertTrue("片尾(type=2)必须可跳", ViewPointSkip.isSkippable(ViewPointSkip.TYPE_ED))
        // type=0 是普通章节看点（用户点着看的），type=3 是预留值，都不能自动跳
        assertFalse("普通看点不是片头片尾，不能自动跳", ViewPointSkip.isSkippable(0))
        assertFalse(ViewPointSkip.isSkippable(3))
        assertFalse(ViewPointSkip.isSkippable(-1))
    }

    @Test
    fun buildSegments_keepsOnlySkippableValidOnes() {
        val raw = listOf(
            Segment(0, 100.0, 200.0),   // 普通看点
            Segment(ViewPointSkip.TYPE_OP, 15.0, 95.0),
            Segment(ViewPointSkip.TYPE_ED, 95.0, 95.0),   // 空区间
            Segment(ViewPointSkip.TYPE_ED, 200.0, 100.0), // 反向区间
            Segment(ViewPointSkip.TYPE_ED, 1380.0, 1440.0)
        )
        assertEquals(
            "只剩两条合法片头片尾，脏区间必须被丢掉",
            listOf(op, ed),
            ViewPointSkip.buildSegments(raw)
        )
    }

    @Test
    fun buildSegments_sortsByStart() {
        val late = Segment(ViewPointSkip.TYPE_ED, 1380.0, 1440.0)
        val early = Segment(ViewPointSkip.TYPE_OP, 15.0, 95.0)
        assertEquals(
            "必须按起点排序，segmentAt 依赖「第一个命中」的语义",
            listOf(early, late),
            ViewPointSkip.buildSegments(listOf(late, early))
        )
    }

    @Test
    fun buildSegments_dropsAbsurdlyLongSegment() {
        // 上游一旦下发「0 秒 → 整集」，自动跳过会把用户直接甩到片尾，这是兜底
        val wholeEpisode = Segment(ViewPointSkip.TYPE_OP, 0.0, 1440.0)
        assertTrue(
            "整集长度的片段必须被当成脏数据丢掉",
            ViewPointSkip.buildSegments(listOf(wholeEpisode)).isEmpty()
        )
        // 边界：刚好等于上限仍保留
        val atLimit = Segment(ViewPointSkip.TYPE_OP, 0.0, ViewPointSkip.MAX_SEGMENT_SECONDS)
        assertEquals(1, ViewPointSkip.buildSegments(listOf(atLimit)).size)
    }

    @Test
    fun segmentAt_isLeftClosedRightOpen() {
        val segments = ViewPointSkip.buildSegments(listOf(op))
        assertNull("还没到片头时不能命中", ViewPointSkip.segmentAt(14.9, segments))
        assertEquals("起点必须算在片段内", op, ViewPointSkip.segmentAt(15.0, segments))
        assertEquals("区间中间命中", op, ViewPointSkip.segmentAt(50.0, segments))
        assertNull("终点必须不算在片段内", ViewPointSkip.segmentAt(95.0, segments))
        assertNull("越过终点后不再命中", ViewPointSkip.segmentAt(95.1, segments))
    }

    @Test
    fun segmentAt_ignoresUntrustedPosition() {
        val segments = ViewPointSkip.buildSegments(listOf(op))
        // 播放器未就绪/重建窗口期可能给出 -1；NaN 来自 0/0 的时长换算
        assertNull(ViewPointSkip.segmentAt(-1.0, segments))
        assertNull(ViewPointSkip.segmentAt(Double.NaN, segments))
    }

    @Test
    fun shouldSkip_returnsSegmentOnceThenStaysQuiet() {
        val segments = ViewPointSkip.buildSegments(listOf(op))
        val first = ViewPointSkip.shouldSkip(20.0, segments, emptySet())
        assertEquals("首次进入片头应当触发跳过", op, first)

        val handled = setOf(ViewPointSkip.keyOf(first!!))
        assertNull("已经跳过一次后不能再跳，否则撤回就失效了", ViewPointSkip.shouldSkip(30.0, segments, handled))
    }

    @Test
    fun keyForManualSeek_marksSegmentUserDraggedInto() {
        val segments = ViewPointSkip.buildSegments(listOf(op))
        assertEquals(
            "用户手动拖进片头，必须标记为已处理，否则下一轮定时器又把他弹走",
            ViewPointSkip.keyOf(op),
            ViewPointSkip.keyForManualSeek(30.0, segments, emptySet())
        )
        assertNull("没拖进任何片段时不需要标记", ViewPointSkip.keyForManualSeek(300.0, segments, emptySet()))
        assertNull(
            "已经处理过的片段不必重复标记",
            ViewPointSkip.keyForManualSeek(30.0, segments, setOf(ViewPointSkip.keyOf(op)))
        )
    }

    @Test
    fun keyOf_distinguishesTypesAndRanges() {
        val opAgain = Segment(ViewPointSkip.TYPE_OP, 15.0, 95.0)
        val edSameRange = Segment(ViewPointSkip.TYPE_ED, 15.0, 95.0)
        assertEquals("同一片段必须得到同一个 key", ViewPointSkip.keyOf(op), ViewPointSkip.keyOf(opAgain))
        assertFalse(
            "片头和片尾哪怕区间相同也不能共用 key",
            ViewPointSkip.keyOf(op) == ViewPointSkip.keyOf(edSameRange)
        )
    }
}
