package com.RobinNotBad.BiliClient.util

import com.RobinNotBad.BiliClient.model.FollowedBangumi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 追番更新检查的纯逻辑测试（26.10.04 批次 5 的 C16）。
 *
 * 判错的后果一头是"更新了却不提醒"（静默失效），另一头是"每次打开应用都弹一次"
 * （用户会直接关掉开关），两种都不报错，只能靠单测锁死语义。
 */
class BangumiUpdateCheckerTest {

    private fun bangumi(mediaId: Long, newEpId: Long, title: String = "番$mediaId"): FollowedBangumi =
        FollowedBangumi().apply {
            this.mediaId = mediaId
            this.title = title
            this.newEpId = newEpId
        }

    @Test
    fun snapshotJson_序列化再解析_原样还原() {
        val json = BangumiUpdateChecker.snapshotJson(
            listOf(bangumi(111L, 5L), bangumi(222L, 0L))
        )
        val parsed = BangumiUpdateChecker.parseSnapshot(json)

        assertEquals(2, parsed.size)
        assertEquals(5L, parsed[111L])
        assertEquals(0L, parsed[222L])
    }

    @Test
    fun parseSnapshot_空值与坏JSON_都当没有快照() {
        assertTrue(BangumiUpdateChecker.parseSnapshot(null).isEmpty())
        assertTrue(BangumiUpdateChecker.parseSnapshot("").isEmpty())
        assertTrue(BangumiUpdateChecker.parseSnapshot("不是JSON").isEmpty())
        assertTrue(BangumiUpdateChecker.parseSnapshot("[1,2,3]").isEmpty())
    }

    @Test
    fun parseSnapshot_非数字键被跳过() {
        val parsed = BangumiUpdateChecker.parseSnapshot("""{"111":5,"title":"脏数据"}""")

        assertEquals(1, parsed.size)
        assertEquals(5L, parsed[111L])
    }

    @Test
    fun findUpdated_最新集变了才算更新() {
        val stored = mapOf(111L to 5L, 222L to 9L)
        val updated = BangumiUpdateChecker.findUpdated(
            stored,
            listOf(bangumi(111L, 6L, "更新了的番"), bangumi(222L, 9L, "没更新"))
        )

        assertEquals(1, updated.size)
        assertEquals("更新了的番", updated[0].title)
    }

    @Test
    fun findUpdated_这次新追的番不算更新() {
        val stored = mapOf(111L to 5L)
        val updated = BangumiUpdateChecker.findUpdated(
            stored,
            listOf(bangumi(111L, 5L), bangumi(999L, 3L, "刚追的番"))
        )

        assertTrue("快照里没有的番只应记录、不该提醒", updated.isEmpty())
    }

    @Test
    fun findUpdated_新集变成0不算更新() {
        val stored = mapOf(111L to 5L)
        val updated = BangumiUpdateChecker.findUpdated(stored, listOf(bangumi(111L, 0L)))

        assertTrue("接口没给 new_ep 时不能当成更新", updated.isEmpty())
    }

    @Test
    fun findUpdated_多部更新_保持列表顺序() {
        val stored = mapOf(111L to 1L, 222L to 2L, 333L to 3L)
        val updated = BangumiUpdateChecker.findUpdated(
            stored,
            listOf(bangumi(333L, 4L, "C"), bangumi(111L, 2L, "A"), bangumi(222L, 2L, "B"))
        )

        assertEquals(2, updated.size)
        assertEquals("C", updated[0].title)
        assertEquals("A", updated[1].title)
    }

    @Test
    fun findUpdated_快照为空时一律不报更新() {
        val updated = BangumiUpdateChecker.findUpdated(
            emptyMap(),
            listOf(bangumi(111L, 5L), bangumi(222L, 9L))
        )

        assertTrue("首次检查应只写快照", updated.isEmpty())
    }
}
