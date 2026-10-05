package com.RobinNotBad.BiliClient.util.view

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「滚动收回工具条」的触发判据。
 *
 * 这些断言守的是**用户能直接感觉到的错**：手一抖条就闪、滚回顶部条还是不回来、
 * 收着的时候往上滚却怎么也展不开。动画本身在单测里跑不了（View 全是 Android 桩），
 * 所以把判断抽成纯函数在这里钉死。
 */
class ScrollRetractDeciderTest {

    private fun action(accumulated: Int, collapsed: Boolean, canScrollUp: Boolean = true) =
        ScrollRetractDecider.action(accumulated, collapsed, canScrollUp)

    // ---------- 阈值防抖 ----------

    @Test
    fun 阈值以内的滚动不动作() {
        assertEquals(ScrollRetractDecider.NONE, action(5, collapsed = false))
        assertEquals(ScrollRetractDecider.NONE, action(-5, collapsed = false))
        assertEquals(ScrollRetractDecider.NONE, action(0, collapsed = false))
    }

    @Test
    fun 阈值边界上不动作超过才动作() {
        // THRESHOLD 是「超过才算」，正好等于时不动，免得在边界上反复横跳
        assertEquals(ScrollRetractDecider.NONE, action(ScrollRetractDecider.THRESHOLD, collapsed = false))
        assertEquals(
            ScrollRetractDecider.COLLAPSE,
            action(ScrollRetractDecider.THRESHOLD + 1, collapsed = false)
        )
    }

    // ---------- 正常的收与展 ----------

    @Test
    fun 向下滚超过阈值就收回() {
        assertEquals(ScrollRetractDecider.COLLAPSE, action(20, collapsed = false))
    }

    @Test
    fun 向上滚超过阈值就展开() {
        assertEquals(ScrollRetractDecider.EXPAND, action(-20, collapsed = true))
    }

    @Test
    fun 已收回时继续向下滚仍然要求收回() {
        // 看起来是废话，但这条返回值的真正作用是让调用方把累加值清零。
        // 若在这里返回 NONE，累加值会一直涨；等用户往上滚时先要抵消掉这些历史正值，
        // 阈值早就被吃掉了，表现为「条收起来以后怎么滚都不回来」。
        assertEquals(ScrollRetractDecider.COLLAPSE, action(20, collapsed = true))
    }

    // ---------- 回到顶部 ----------

    @Test
    fun 已经到顶且收着时必须展开() {
        // 列表顶部没有更多内容可滚，RecyclerView 不会再产生负的 dy，
        // 靠滚动事件永远等不到展开——只能在这里兜住
        assertEquals(ScrollRetractDecider.EXPAND, action(0, collapsed = true, canScrollUp = false))
        assertEquals(ScrollRetractDecider.EXPAND, action(-30, collapsed = true, canScrollUp = false))
    }

    @Test
    fun 到顶时正在向下滚就不展开() {
        // 刚到顶用户又往下滑，那是在要求继续收起，不要跟他抢
        assertEquals(ScrollRetractDecider.NONE, action(5, collapsed = true, canScrollUp = false))
    }

    @Test
    fun 到顶但本来就没收时不动作() {
        assertEquals(ScrollRetractDecider.NONE, action(0, collapsed = false, canScrollUp = false))
    }
}
