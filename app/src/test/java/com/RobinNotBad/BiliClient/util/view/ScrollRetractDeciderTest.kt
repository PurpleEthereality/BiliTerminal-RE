package com.RobinNotBad.BiliClient.util.view

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「滚动收回工具条」的触发判据。
 *
 * 这些断言守的是**用户能直接感觉到的错**：手一抖条就闪、滚回顶部条还是不回来、
 * 列表中间一展开就把内容顶走和滑动手势打架。动画本身在单测里跑不了（View 全是 Android 桩），
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
        // 26.10.05 真机反馈后改了口径：列表**中间**向上滚不再展开。
        // 展开会把条的高度从 0 动画回自然高度，条是列表的兄弟节点，高度一变列表内容就整体位移；
        // 用户正按着屏幕拖动时手指底下的条目被推走，就是「工具条和滑动手势打架」。
        // 展开现在只有「回到顶部」这一条路，见下面那组用例。
        assertEquals(ScrollRetractDecider.NONE, action(-20, collapsed = true))
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
    fun 回顶前一路向上滚都不展开回到顶部才展开() {
        // 用户 bug 的回归用例：在列表中间往上滑，条要一动不动地收着；
        // 一路滑到顶（canScrollUp=false）的那一次滚动才允许展开。
        // 累加值在中间会变得很负，这不影响判断——到顶看的是 canScrollUp，不是累加了多少。
        assertEquals(ScrollRetractDecider.NONE, action(-500, collapsed = true, canScrollUp = true))
        assertEquals(ScrollRetractDecider.EXPAND, action(-500, collapsed = true, canScrollUp = false))
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

    @Test
    fun 没收回时向上滚也不动作() {
        // 没收回就没有「展开」这回事，返回 EXPAND 只会让调用方白白重置累加值
        assertEquals(ScrollRetractDecider.NONE, action(-100, collapsed = false))
    }
}
