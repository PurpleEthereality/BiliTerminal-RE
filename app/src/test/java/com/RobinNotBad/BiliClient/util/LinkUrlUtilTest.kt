package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LinkUrlUtil.parseTopicId] 的纯函数单测（26.10.05，#话题# 可跳转的修复）。
 *
 * 话题节点只带一个 `jump_url`，站内话题页需要的是其中的 `topic_id`。
 * 这个解析是"话题能不能点得动"的唯一入口，解析不出来就退化成只上色不可点，
 * 所以把它钉在测试里。
 */
class LinkUrlUtilTest {

    @Test
    fun parseTopicId_readsIdFromRealJumpUrl() {
        assertEquals(
            1305890L,
            LinkUrlUtil.parseTopicId("https://m.bilibili.com/topic-detail?topic_id=1305890")
        )
    }

    @Test
    fun parseTopicId_ignoresTopicNameAfterId() {
        // 真实抓包里 topic_name 是 URL 编码的中文，且排在 topic_id 之后
        assertEquals(
            1314000L,
            LinkUrlUtil.parseTopicId(
                "https://m.bilibili.com/topic-detail?topic_id=1314000&topic_name=%E6%B4%9B%E5%A4%A9"
            )
        )
    }

    @Test
    fun parseTopicId_handlesIdNotBeingFirstQueryParam() {
        assertEquals(
            1061343L,
            LinkUrlUtil.parseTopicId(
                "https://m.bilibili.com/topic-detail?from=dynamic&topic_id=1061343&topic_name=x"
            )
        )
    }

    @Test
    fun parseTopicId_returnsZeroWhenAbsentOrUnparsable() {
        // 都返回 0（而不是抛异常），调用方据此走"只上色不可点"的退化分支
        assertEquals(0L, LinkUrlUtil.parseTopicId(null))
        assertEquals(0L, LinkUrlUtil.parseTopicId(""))
        assertEquals(0L, LinkUrlUtil.parseTopicId("https://m.bilibili.com/topic-detail"))
        assertEquals(0L, LinkUrlUtil.parseTopicId("https://m.bilibili.com/topic-detail?topic_name=x"))
        assertEquals(0L, LinkUrlUtil.parseTopicId("#A#"))
    }

    @Test
    fun parseTopicId_doesNotMatchTopicNameContainingId() {
        // 防"匹配到 topic_name 里的 topic_id=" 这类串味；锚定在 ? 或 & 之后
        assertEquals(
            123L,
            LinkUrlUtil.parseTopicId("https://m.bilibili.com/topic-detail?topic_name=a&topic_id=123")
        )
    }
}
