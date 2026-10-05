package com.RobinNotBad.BiliClient.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 钉死 [Dynamic.ensureDetailFields] 的契约。
 *
 * 背景：26.10.05 线上 release 崩溃
 * `NullPointerException at DynamicInfoActivity.onCreate$lambda$3$lambda$1`
 * —— 详情页直接取 `dynamic.stats.reply`，而 `DynamicApi.analyzeDynamic` 对
 * `DYNAMIC_TYPE_NONE`（动态已删除/被屏蔽）会在填充 `module_stat` 之前提前 return，
 * `stats` 保持 null。这里用纯 POJO 复现该状态并验证补全。
 */
class DynamicEnsureDetailFieldsTest {

    @Test
    fun `stats 为 null 时补成空对象`() {
        val dynamic = Dynamic()
        dynamic.stats = null

        dynamic.ensureDetailFields()

        assertNotNull(dynamic.stats)
        // 崩溃现场取的三个值都必须可读且为 0，而不是抛 NPE
        assertEquals(0, dynamic.stats.reply)
        assertEquals(0, dynamic.stats.like)
    }

    @Test
    fun `userInfo 为 null 时补成空对象`() {
        val dynamic = Dynamic()
        dynamic.userInfo = null

        dynamic.ensureDetailFields()

        assertNotNull(dynamic.userInfo)
        assertEquals(0L, dynamic.userInfo.mid)
    }

    @Test
    fun `已有的 stats 不会被覆盖`() {
        val dynamic = Dynamic()
        val existing = Stats()
        existing.reply = 7
        existing.like = 99
        dynamic.stats = existing

        dynamic.ensureDetailFields()

        // 同一个对象，且计数原样保留——补全只补 null，不做重置
        assertSame(existing, dynamic.stats)
        assertEquals(7, dynamic.stats.reply)
        assertEquals(99, dynamic.stats.like)
    }

    @Test
    fun `已有的 userInfo 不会被覆盖`() {
        val dynamic = Dynamic()
        val existing = UserInfo()
        existing.mid = 12345L
        existing.name = "测试用户"
        dynamic.userInfo = existing

        dynamic.ensureDetailFields()

        assertSame(existing, dynamic.userInfo)
        assertEquals(12345L, dynamic.userInfo.mid)
        assertEquals("测试用户", dynamic.userInfo.name)
    }

    @Test
    fun `重复调用是幂等的`() {
        val dynamic = Dynamic()
        dynamic.ensureDetailFields()
        val statsAfterFirst = dynamic.stats
        val userAfterFirst = dynamic.userInfo

        dynamic.ensureDetailFields()

        assertSame(statsAfterFirst, dynamic.stats)
        assertSame(userAfterFirst, dynamic.userInfo)
    }

    @Test
    fun `两个字段同时为 null 时一次补全`() {
        val dynamic = Dynamic()
        dynamic.stats = null
        dynamic.userInfo = null

        dynamic.ensureDetailFields()

        assertNotNull(dynamic.stats)
        assertNotNull(dynamic.userInfo)
    }
}
