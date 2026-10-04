package com.RobinNotBad.BiliClient.model

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
}
