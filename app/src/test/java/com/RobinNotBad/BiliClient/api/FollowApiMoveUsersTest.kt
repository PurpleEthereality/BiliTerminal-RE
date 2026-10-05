package com.RobinNotBad.BiliClient.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FollowApi.joinMids] 与 [FollowApi.tagErrorMsg] 的单测（C22 移动成员到其它分组）。
 *
 * 这两个函数是「组装请求参数」和「错误码 → 用户能看懂的中文」的纯逻辑部分，
 * 抽出来单独测，避免动辄需要网络的集成测试。
 */
class FollowApiMoveUsersTest {

    // ==================== joinMids：拼 fids 参数 ====================

    @Test
    fun `单个 mid 不加分隔符`() {
        assertEquals("321173469", FollowApi.joinMids(listOf(321173469L)))
    }

    @Test
    fun `多个 mid 用逗号连接`() {
        assertEquals(
            "321173469,327086920",
            FollowApi.joinMids(listOf(321173469L, 327086920L))
        )
    }

    @Test
    fun `空列表返回空串`() {
        assertEquals("", FollowApi.joinMids(emptyList()))
    }

    @Test
    fun `null 返回空串而不是崩`() {
        assertEquals("", FollowApi.joinMids(null))
    }

    // ==================== moveUsersFields：请求字段名与取值 ====================
    // 字段名写错是这个接口最容易犯又最难发现的错：服务端对错字段名一律回 -400，
    // 真机上看起来只是「请求出错了，请稍后再试」，根本猜不到是拼写问题。

    @Test
    fun `字段名必须是接口规定的四个`() {
        val fields = FollowApi.moveUsersFields(207542, 23130, listOf(321173469L, 327086920L), "csrf-token")
        assertEquals(
            setOf("beforeTagids", "afterTagids", "fids", "csrf"),
            fields.keys
        )
    }

    @Test
    fun `原分组与新分组用复数形式且是单值`() {
        // 接口字段是 beforeTagids / afterTagids（复数），即使只移动一个分组也只传单值
        val fields = FollowApi.moveUsersFields(207542, 23130, listOf(1L), "t")
        assertEquals("207542", fields["beforeTagids"])
        assertEquals("23130", fields["afterTagids"])
    }

    @Test
    fun `fids 是逗号分隔的 mid 串`() {
        val fields = FollowApi.moveUsersFields(1, 2, listOf(321173469L, 327086920L), "t")
        assertEquals("321173469,327086920", fields["fids"])
    }

    @Test
    fun `移动到默认分组时 afterTagids 为 0`() {
        // 0 = 默认分组，这是「移出分组但不取关」的唯一途径
        val fields = FollowApi.moveUsersFields(207542, 0, listOf(1L), "t")
        assertEquals("0", fields["afterTagids"])
    }

    @Test
    fun `csrf 为 null 时退化成空串而不是 null 字面量`() {
        val fields = FollowApi.moveUsersFields(1, 2, listOf(3L), null)
        assertEquals("", fields["csrf"])
    }

    // ==================== tagErrorMsg：moveUsers 的错误码文案 ====================

    @Test
    fun `成功码没有提示文案`() {
        assertEquals("", FollowApi.tagErrorMsg(0))
    }

    @Test
    fun `未登录与凭证失效有专门文案`() {
        assertEquals("还没有登录喵~", FollowApi.tagErrorMsg(-101))
        assertEquals("登录凭证已失效，请重新登录", FollowApi.tagErrorMsg(-111))
    }

    @Test
    fun `分组不存在有可读提示而不是裸码`() {
        assertEquals("这个分组不存在，可能已经被删了", FollowApi.tagErrorMsg(22104))
    }

    @Test
    fun `未关注有可读提示`() {
        assertEquals("你还没有关注这个人", FollowApi.tagErrorMsg(22105))
    }

    @Test
    fun `请求错误有兜底文案`() {
        assertEquals("请求出错了，请稍后再试", FollowApi.tagErrorMsg(-400))
    }

    @Test
    fun `未知码兜底带上错误码本身`() {
        val msg = FollowApi.tagErrorMsg(999999)
        assertEquals(true, msg.contains("999999"))
    }
}
