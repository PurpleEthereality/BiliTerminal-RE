package com.RobinNotBad.BiliClient.adapter.dynamic

import com.RobinNotBad.BiliClient.model.Topic
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TopicAdapter.buildStats] 的 JVM 单测（26.10.05）。
 *
 * 这个话题的背景：广场数据源从已废弃的 `x/topic/web/dynamic/rcmd` 换成
 * `x/topic/pub/search` 后，**新端点不下发 `dynamics` 字段**（恒为 0），
 * 原实现固定展示 `"N 动态"` 会永远显示 `"0 动态"` —— 这是「看起来像坏了」的假数据。
 *
 * 所以这里重点锁住两件事：
 * 1. **绝不显示 `0 讨论` / `0 动态`**：只有计数 `> 0` 才展示该计数；
 * 2. **降级要有兜底**：字段缺失时依次退化（discuss → dynamics → view → `-`），
 *    任何情况下都不返回空串（空白会让用户以为布局坏了）。
 */
class TopicAdapterTest {

    private fun topic(
        name: String = "测试话题",
        discuss: Long = 0L,
        dynamics: Long = 0L,
        view: Long = 0L
    ) = Topic().apply {
        this.id = 1L
        this.name = name
        this.discuss = discuss
        this.dynamics = dynamics
        this.view = view
    }

    // region 主路径：pub/search 的真实形态（有 discuss、没有 dynamics）

    @Test
    fun buildStats_withDiscuss_prefersDiscussOverDynamics() {
        // pub/search 的真实形态：discuss 有值、dynamics 恒 0
        assertEquals(
            "220.5万 讨论 · 22.5亿 浏览",
            TopicAdapter.buildStats(topic(discuss = 2204644L, view = 2247738435L))
        )
    }

    @Test
    fun buildStats_withBothCounts_discussWins() {
        // discuss 与 dynamics 都有值时，以 discuss 为准（新端点的语义）
        assertEquals(
            "1.0万 讨论 · 2.0万 浏览",
            TopicAdapter.buildStats(topic(discuss = 10000L, dynamics = 20000L, view = 20000L))
        )
    }

    // endregion

    // region 降级路径：字段缺失时的四级兜底

    @Test
    fun buildStats_onlyDynamics_showsDynamicsNotZero() {
        // 关键回归用例（Lead 追问的场景）：只有 dynamics 没有 discuss 时，
        // 动态数不能被静默丢掉，更不能显示成 "0 动态"
        assertEquals(
            "3 动态 · 500 浏览",
            TopicAdapter.buildStats(topic(discuss = 0L, dynamics = 3L, view = 500L))
        )
    }

    @Test
    fun buildStats_onlyView_showsViewAlone() {
        // 两个计数都缺，只有浏览数
        assertEquals("500 浏览", TopicAdapter.buildStats(topic(view = 500L)))
    }

    @Test
    fun buildStats_allZero_returnsDashNotBlank() {
        // 全 0 时绝不返回空串 —— 空白会让人以为布局坏了
        val stats = TopicAdapter.buildStats(topic())
        assertEquals("-", stats)
        assertEquals(true, stats.isNotEmpty())
    }

    @Test
    fun buildStats_neverRendersZeroCounts() {
        // 归纳断言：任何输入都不该出现 "0 讨论" / "0 动态" 这种假数据
        val cases = listOf(
            topic(),
            topic(discuss = 0L, dynamics = 0L, view = 0L),
            topic(discuss = 0L, dynamics = 0L, view = 1L),
            topic(discuss = 1L),
            topic(dynamics = 1L)
        )
        for (t in cases) {
            val stats = TopicAdapter.buildStats(t)
            assertEquals(false, stats.contains("0 讨论"))
            assertEquals(false, stats.contains("0 动态"))
        }
    }

    // endregion

    // region 数字格式化边界（复用 StringUtil.toWan 的 万/亿 分档）

    @Test
    fun buildStats_formatsWanAndYiBoundaries() {
        assertEquals("9999 浏览", TopicAdapter.buildStats(topic(view = 9999L)))
        assertEquals("1.0万 浏览", TopicAdapter.buildStats(topic(view = 10000L)))
        assertEquals("1.0亿 浏览", TopicAdapter.buildStats(topic(view = 100000000L)))
    }

    @Test
    fun buildStats_negativeCounts_fallThroughToDash() {
        // 服务端理论上不会给负数，但万一给了也不该渲染 "-5 讨论"；
        // 所有分支都用 `> 0` 判断，负数会一路落到 "-"
        assertEquals("-", TopicAdapter.buildStats(topic(discuss = -1L, dynamics = -1L, view = -1L)))
    }

    // endregion
}
