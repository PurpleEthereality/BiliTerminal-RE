package com.RobinNotBad.BiliClient.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 关注分组的纯函数单测（26.10.04 批次 7 的 C21）。
 *
 * 只测不碰 android 的部分：分组名预校验与错误码文案。网络请求与既有 api 测试一致，不测。
 */
class FollowApiTest {

    @Test
    fun checkTagName_rejectsNullAndBlank() {
        assertEquals("分组名不能为空", FollowApi.checkTagName(null))
        assertEquals("分组名不能为空", FollowApi.checkTagName(""))
        assertEquals("分组名不能为空", FollowApi.checkTagName("   "))
        assertEquals("分组名不能为空", FollowApi.checkTagName("\t\n"))
    }

    @Test
    fun checkTagName_acceptsNormalNames() {
        assertEquals("", FollowApi.checkTagName("测试"))
        assertEquals("", FollowApi.checkTagName("  膜法师  "))
        assertEquals("", FollowApi.checkTagName("a"))
    }

    @Test
    fun checkTagName_lengthBoundaryIs16() {
        val exactly16 = "一".repeat(16)
        val seventeen = "一".repeat(17)
        assertEquals("", FollowApi.checkTagName(exactly16))
        assertEquals("分组名最多 16 个字", FollowApi.checkTagName(seventeen))
        // trim 之后算长度：17 个字外面包空格仍然是超长
        assertEquals("分组名最多 16 个字", FollowApi.checkTagName("  $seventeen  "))
    }

    @Test
    fun tagErrorMsg_mapsKnownCodes() {
        assertEquals("", FollowApi.tagErrorMsg(0))
        assertEquals("还没有登录喵~", FollowApi.tagErrorMsg(-101))
        assertEquals("登录凭证已失效，请重新登录", FollowApi.tagErrorMsg(-111))
        assertEquals("请求出错了，请稍后再试", FollowApi.tagErrorMsg(-400))
        assertEquals("分组名里有不允许的字符", FollowApi.tagErrorMsg(22101))
        assertEquals("分组数量已达上限", FollowApi.tagErrorMsg(22102))
        assertEquals("分组名太长了，最多 16 个字", FollowApi.tagErrorMsg(22103))
        assertEquals("这个分组不存在，可能已经被删了", FollowApi.tagErrorMsg(22104))
        assertEquals("已经有同名的分组了", FollowApi.tagErrorMsg(22106))
    }

    @Test
    fun tagErrorMsg_unknownCodeKeepsTheCode() {
        assertEquals("操作失败（错误码 99999）", FollowApi.tagErrorMsg(99999))
        assertEquals("操作失败（错误码 -1）", FollowApi.tagErrorMsg(-1))
    }
}
