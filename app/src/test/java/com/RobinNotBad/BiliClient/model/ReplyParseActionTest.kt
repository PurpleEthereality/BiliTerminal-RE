package com.RobinNotBad.BiliClient.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Reply.parseAction] 的单测。
 *
 * 回归点：原实现写成 `liked = action == 1`，把"已点踩(2)"和"无操作(0)"当成同一回事，
 * 于是用户踩过的评论重新进页面后显示成没操作过，还能再踩一次（服务端回"已经点过踩了"）。
 */
class ReplyParseActionTest {

    @Test
    fun actionTwo_marksDislikedOnly() {
        val reply = Reply()
        reply.parseAction(2)

        assertTrue("action==2 必须置为已点踩", reply.disliked)
        assertFalse("已点踩时不能同时是已点赞", reply.liked)
    }

    @Test
    fun actionOne_marksLikedOnly() {
        val reply = Reply()
        reply.parseAction(1)

        assertTrue("action==1 必须置为已点赞", reply.liked)
        assertFalse("已点赞时不能同时是已点踩", reply.disliked)
    }

    @Test
    fun actionZero_clearsBoth() {
        val reply = Reply()
        reply.parseAction(2)
        reply.parseAction(0)

        assertFalse("action==0 应清掉已点踩", reply.disliked)
        assertFalse("action==0 应清掉已点赞", reply.liked)
    }

    @Test
    fun repeatedParse_doesNotAccumulateState() {
        // 同一对象被反复解析（例如刷新后重新构造前的兜底路径）时，状态必须被"覆盖"而不是"叠加"
        val reply = Reply()
        reply.parseAction(1)
        reply.parseAction(2)
        reply.parseAction(1)

        assertTrue(reply.liked)
        assertFalse(reply.disliked)
    }

    // ---- 置顶标记的唯一性（服务端一个评论区只有一个置顶位） ----

    @Test
    fun clearTopFlags_removesEveryTopMark() {
        // 场景：已有置顶评论 A，用户改置顶评论 B。
        // 服务端仍只有一个置顶位，但本地若不清理，列表里会同时出现两条「[置顶]」。
        val oldTop = Reply().apply { isTop = true }
        val other = Reply().apply { isTop = false }
        val another = Reply().apply { isTop = true }

        val cleared = Reply.clearTopFlags(listOf(oldTop, other, another))

        assertEquals("应清掉 2 条置顶标记", 2, cleared)
        assertFalse(oldTop.isTop)
        assertFalse(other.isTop)
        assertFalse(another.isTop)
    }

    @Test
    fun clearTopFlags_withoutAnyTopMarkReturnsZero() {
        // 返回 0 用于判断"要不要刷新列表"，所以没有置顶时必须如实返回 0
        val list = listOf(Reply(), Reply())
        assertEquals("没有置顶标记时应返回 0", 0, Reply.clearTopFlags(list))
    }

    @Test
    fun clearTopFlags_toleratesNullListAndNullItems() {
        // 列表在加载完成前可能为 null，构造过程中也可能出现 null 项，不能因此崩在 UI 线程
        assertEquals("null 列表应返回 0", 0, Reply.clearTopFlags(null))
        val list = mutableListOf(Reply().apply { isTop = true }, null, Reply())
        assertEquals("null 项应被跳过", 1, Reply.clearTopFlags(list))
        assertFalse(list[0]!!.isTop)
    }
}
