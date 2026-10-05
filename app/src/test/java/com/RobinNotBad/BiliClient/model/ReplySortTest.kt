package com.RobinNotBad.BiliClient.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [Reply.sortReplies] 的纯逻辑单测。
 *
 * 楼中楼排序只能在客户端做（服务端 /x/v2/reply/reply 没有 sort 参数），
 * 这里钉死四件事：
 *  1. 按热度是点赞数降序、同热度保持原顺序（稳定）；
 *  2. 按时间是 ctime 升序——**这是本次修复的核心**，旧实现直接 return，
 *     导致「切回时间序」什么都不做；
 *  3. 反复切换（时间→热度→时间）能回到最初的顺序，即两个分支都是幂等的；
 *  4. 根评论（下标 0）在任何排序方式下都不参与重排。
 */
class ReplySortTest {

    private fun reply(rpid: Long, likeCount: Int, ctime: Long = rpid): Reply {
        val reply = Reply()
        reply.rpid = rpid
        reply.likeCount = likeCount
        reply.ctime = ctime
        return reply
    }

    private fun rpids(list: List<Reply>): List<Long> = list.map { it.rpid }

    // ---------- 热度序 ----------

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

        // 点赞数相同的三条必须保持原顺序，只有 4 冒到最前
        assertEquals(listOf(4L, 1L, 2L, 3L), rpids(list))
    }

    // ---------- 时间序（本次修复的核心） ----------

    @Test
    fun sortReplies_timeMode_ordersByCtimeAscending() {
        // 故意打乱成热度序：时间是 1,2,3，热度却是 1<9>5，两者顺序不同
        val list = arrayListOf(reply(2, 9, ctime = 20), reply(3, 5, ctime = 30), reply(1, 1, ctime = 10))

        Reply.sortReplies(list, Reply.SORT_TIME, 0)

        assertEquals("按时间应升序：早的在前", listOf(1L, 2L, 3L), rpids(list))
    }

    @Test
    fun sortReplies_timeMode_restoresOrderAfterLikeMode() {
        // 这就是用户报的 bug：时间→热度→时间，顺序回不来
        val list = arrayListOf(reply(1, 1, ctime = 10), reply(2, 9, ctime = 20), reply(3, 5, ctime = 30))
        val original = rpids(list)

        Reply.sortReplies(list, Reply.SORT_LIKE, 0)
        assertEquals("切到热度序应生效", listOf(2L, 3L, 1L), rpids(list))

        Reply.sortReplies(list, Reply.SORT_TIME, 0)
        assertEquals("切回时间序必须还原", original, rpids(list))
    }

    @Test
    fun sortReplies_repeatedSwitching_isIdempotent() {
        val list = arrayListOf(reply(1, 1, ctime = 10), reply(2, 9, ctime = 20), reply(3, 5, ctime = 30))
        val original = rpids(list)

        // 时间→热度→时间→热度→时间：三轮往返后必须还在最初的时间序
        repeat(3) {
            Reply.sortReplies(list, Reply.SORT_LIKE, 0)
            assertEquals(listOf(2L, 3L, 1L), rpids(list))
            Reply.sortReplies(list, Reply.SORT_TIME, 0)
            assertEquals("第 ${it + 1} 轮往返后应回到时间序", original, rpids(list))
        }
    }

    @Test
    fun sortReplies_timeMode_isStableForMissingCtime() {
        // 接口没给 ctime 时键相等（都是 0），稳定排序必须保住原有相对顺序
        val list = arrayListOf(reply(1, 1, ctime = 0), reply(2, 1, ctime = 0), reply(3, 1, ctime = 0))

        Reply.sortReplies(list, Reply.SORT_TIME, 0)

        assertEquals(listOf(1L, 2L, 3L), rpids(list))
    }

    // ---------- 根评论保护 ----------

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
    fun sortReplies_timeMode_doesNotTouchItemsBeforeFromIndex() {
        // 根评论 ctime 最小，若被排进去会跑到最前——它本来就在最前，所以用「不该动它」来验证
        val root = reply(1, 100, ctime = 999)
        val list = arrayListOf(root, reply(2, 1, ctime = 10), reply(3, 9, ctime = 20))

        Reply.sortReplies(list, Reply.SORT_TIME, 1)

        assertSame("根评论必须留在下标 0", root, list[0])
        assertEquals(listOf(1L, 2L, 3L), rpids(list))
    }

    // ---------- 边界 ----------

    @Test
    fun sortReplies_toleratesNullEmptyAndShortLists() {
        Reply.sortReplies(null, Reply.SORT_LIKE, 0)
        Reply.sortReplies(null, Reply.SORT_TIME, 0)

        val empty = ArrayList<Reply>()
        Reply.sortReplies(empty, Reply.SORT_LIKE, 0)
        Reply.sortReplies(empty, Reply.SORT_TIME, 0)
        assertEquals(0, empty.size)

        val single = arrayListOf(reply(1, 5))
        Reply.sortReplies(single, Reply.SORT_LIKE, 0)
        Reply.sortReplies(single, Reply.SORT_TIME, 0)
        assertEquals(listOf(1L), rpids(single))

        // fromIndex 越界或为负都不应该抛异常
        val two = arrayListOf(reply(1, 1), reply(2, 2))
        Reply.sortReplies(two, Reply.SORT_LIKE, 5)
        Reply.sortReplies(two, Reply.SORT_LIKE, -1)
        assertEquals(listOf(2L, 1L), rpids(two))
    }

    @Test
    fun sortReplies_toleratesNullItems() {
        // 列表里混入 null 不应崩（实际数据不会出现，但排序函数要经得起）
        val list = arrayListOf(reply(1, 1, ctime = 10), null, reply(3, 3, ctime = 30))

        Reply.sortReplies(list, Reply.SORT_TIME, 0)
        Reply.sortReplies(list, Reply.SORT_LIKE, 0)

        assertEquals(3, list.size)
    }

    @Test
    fun sortReplies_unknownMode_fallsBackToTimeOrder() {
        // 未知排序值不能当成「不打乱」——那会让下一次切换留下脏顺序，统一按时间序处理
        val list = arrayListOf(reply(3, 9, ctime = 30), reply(1, 1, ctime = 10), reply(2, 5, ctime = 20))

        Reply.sortReplies(list, 2, 0)
        Reply.sortReplies(list, 3, 0)

        assertEquals("未知排序应回落到时间序", listOf(1L, 2L, 3L), rpids(list))
    }
}
