package com.RobinNotBad.BiliClient.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [Reply.sortReplies] 的纯逻辑单测。
 *
 * 楼中楼排序只能在客户端做（服务端 /x/v2/reply/reply 没有 sort 参数），
 * 这里钉死三件事：按热度是降序、同热度保持原顺序（稳定）、根评论不能被排进去。
 */
class ReplySortTest {

    private fun reply(rpid: Long, likeCount: Int): Reply {
        val reply = Reply()
        reply.rpid = rpid
        reply.likeCount = likeCount
        return reply
    }

    private fun rpids(list: List<Reply>): List<Long> = list.map { it.rpid }

    @Test
    fun sortReplies_likeMode_ordersByLikeCountDesc() {
        val list = arrayListOf(reply(1, 1), reply(2, 9), reply(3, 5))

        Reply.sortReplies(list, Reply.SORT_LIKE, 0)

        assertEquals(listOf(2L, 3L, 1L), rpids(list))
    }

    @Test
    fun sortReplies_likeMode_isStableForEqualLikeCounts() {
        val list = arrayListOf(reply(1, 7), reply(2, 7), reply(3, 7), reply(4, 9))

        Reply.sortReplies(list, Reply.SORT_LIKE, 0)

        // 点赞数相同的三条必须保持服务端给的时间顺序，只有 4 冒到最前
        assertEquals(listOf(4L, 1L, 2L, 3L), rpids(list))
    }

    @Test
    fun sortReplies_timeMode_keepsServerOrder() {
        val list = arrayListOf(reply(1, 1), reply(2, 9), reply(3, 5))

        Reply.sortReplies(list, Reply.SORT_TIME, 0)

        assertEquals(listOf(1L, 2L, 3L), rpids(list))
    }

    @Test
    fun sortReplies_likeMode_doesNotTouchItemsBeforeFromIndex() {
        // 第 0 位是评论详情页的根评论：它点赞最多，也必须留在最前面，不能排进子评论里
        val root = reply(1, 100)
        val list = arrayListOf(root, reply(2, 1), reply(3, 9))

        Reply.sortReplies(list, Reply.SORT_LIKE, 1)

        assertSame(root, list[0])
        assertEquals(listOf(1L, 3L, 2L), rpids(list))
    }

    @Test
    fun sortReplies_toleratesNullEmptyAndShortLists() {
        Reply.sortReplies(null, Reply.SORT_LIKE, 0)

        val empty = ArrayList<Reply>()
        Reply.sortReplies(empty, Reply.SORT_LIKE, 0)
        assertEquals(0, empty.size)

        val single = arrayListOf(reply(1, 5))
        Reply.sortReplies(single, Reply.SORT_LIKE, 0)
        assertEquals(listOf(1L), rpids(single))

        // fromIndex 越界或为负都不应该抛异常
        val two = arrayListOf(reply(1, 1), reply(2, 2))
        Reply.sortReplies(two, Reply.SORT_LIKE, 5)
        Reply.sortReplies(two, Reply.SORT_LIKE, -1)
        assertEquals(listOf(2L, 1L), rpids(two))
    }

    @Test
    fun sortReplies_unknownMode_keepsOrder() {
        val list = arrayListOf(reply(1, 1), reply(2, 9))

        // 主评论列表用的是服务端排序（sort 2/3），不该被这个客户端排序函数动到
        Reply.sortReplies(list, 2, 0)
        Reply.sortReplies(list, 3, 0)

        assertEquals(listOf(1L, 2L), rpids(list))
    }
}
