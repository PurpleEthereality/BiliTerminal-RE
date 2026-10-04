package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新消息通知的纯逻辑测试（C14）。
 *
 * 提醒时机只有"未读变多"这一个判据，错判的两种后果（该提醒不提醒 / 每次打开都骚扰）
 * 都不报错，所以必须在这里锁死。
 */
class MsgNotifierTest {

    @Test
    fun shouldNotify_仅在未读变多时才提醒() {
        assertTrue("从 0 变成 3 应提醒", MsgNotifier.shouldNotify(0, 3, true))
        assertTrue("从 2 变成 3 应提醒", MsgNotifier.shouldNotify(2, 3, true))
        assertFalse("未读数没变不该重复提醒", MsgNotifier.shouldNotify(3, 3, true))
        assertFalse("未读变少（用户读过了）不该提醒", MsgNotifier.shouldNotify(5, 2, true))
    }

    @Test
    fun shouldNotify_当前没有未读时不提醒() {
        assertFalse(MsgNotifier.shouldNotify(-1, 0, true))
        assertFalse(MsgNotifier.shouldNotify(0, 0, true))
    }

    @Test
    fun shouldNotify_开关关闭一律不提醒() {
        assertFalse(MsgNotifier.shouldNotify(0, 5, false))
        assertFalse(MsgNotifier.shouldNotify(5, 6, false))
    }

    @Test
    fun summaryText_私信与其它未读分开报() {
        assertEquals("3 条新私信", MsgNotifier.summaryText(3, 0))
        assertEquals("2 条新消息", MsgNotifier.summaryText(0, 2))
        assertEquals("3 条新私信、2 条新消息", MsgNotifier.summaryText(3, 2))
    }

    @Test
    fun summaryText_都没有未读时给出兜底文案() {
        assertEquals("有新消息", MsgNotifier.summaryText(0, 0))
    }

    @Test
    fun bangumiSummaryText_一部报名字_多部报第一部与总数() {
        assertEquals("《葬送的芙莉莲》更新了", MsgNotifier.bangumiSummaryText(listOf("葬送的芙莉莲")))
        assertEquals(
            "《葬送的芙莉莲》等 3 部追番更新了",
            MsgNotifier.bangumiSummaryText(listOf("葬送的芙莉莲", "孤独摇滚", "别当欧尼酱了"))
        )
    }

    @Test
    fun bangumiSummaryText_没有更新时给出兜底文案() {
        assertEquals("有追番更新了", MsgNotifier.bangumiSummaryText(emptyList()))
    }
}
