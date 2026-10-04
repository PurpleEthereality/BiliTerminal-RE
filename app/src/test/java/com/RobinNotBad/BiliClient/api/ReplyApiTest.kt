package com.RobinNotBad.BiliClient.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评论点赞/点踩的纯逻辑单测。
 *
 * 这两块逻辑不碰网络，但错了的后果是"用户看不见的错"：
 * 评论的 action 字段判错 → 已踩的评论重进页面显示成没操作过；
 * 错误码不翻译 → 用户点了没反应却不知道原因（没登录 / csrf 失效 / 限流被当成同一种失败）。
 */
class ReplyApiTest {

    // ---- action 字段：0=无 1=已赞 2=已踩（bilibili-API/docs/comment/readme.md） ----

    @Test
    fun isLikedAction_onlyOneCountsAsLiked() {
        assertTrue("action==1 才是已点赞", ReplyApi.isLikedAction(1))
        assertFalse("action==0 是无操作，不能当成已赞", ReplyApi.isLikedAction(0))
        assertFalse("action==2 是已点踩，绝不能当成已赞", ReplyApi.isLikedAction(2))
    }

    @Test
    fun isDislikedAction_onlyTwoCountsAsDisliked() {
        assertTrue("action==2 才是已点踩", ReplyApi.isDislikedAction(2))
        assertFalse("action==0 是无操作", ReplyApi.isDislikedAction(0))
        assertFalse("action==1 是已点赞，不能当成已踩", ReplyApi.isDislikedAction(1))
    }

    @Test
    fun likedAndDislikedAreMutuallyExclusive() {
        // 服务端保证同一时刻只会是 0/1/2 中的一个；
        // 这条测试防止将来有人"顺手"把两个判定都改成 >= 之类而破坏互斥性
        for (action in 0..2) {
            assertFalse(
                "action=$action 不能同时是已赞和已踩",
                ReplyApi.isLikedAction(action) && ReplyApi.isDislikedAction(action)
            )
        }
    }

    @Test
    fun unknownActionIsTreatedAsNoOperation() {
        // 服务端新增状态时不能崩，也不能误判成已赞/已踩
        assertFalse(ReplyApi.isLikedAction(99))
        assertFalse(ReplyApi.isDislikedAction(-1))
    }

    // ---- 错误码翻译：这些接口失败只回 code，不抛异常 ----

    @Test
    fun actionErrorMsg_successIsSilent() {
        assertEquals("code==0 表示成功，不该弹任何提示", "", ReplyApi.actionErrorMsg(0))
    }

    @Test
    fun actionErrorMsg_distinguishesNotLoggedInFromCsrfExpired() {
        // 这两条原来都只显示"失败"，但用户要做的事完全不同：一个去登录，一个重新登录刷新凭证
        assertTrue("未登录要说清是没登录", ReplyApi.actionErrorMsg(-101).contains("登录"))
        assertTrue("csrf 失效要说是凭证失效", ReplyApi.actionErrorMsg(-111).contains("失效"))
        assertFalse(
            "-101 与 -111 不能给出同一句提示，否则用户无从区分",
            ReplyApi.actionErrorMsg(-101) == ReplyApi.actionErrorMsg(-111)
        )
    }

    @Test
    fun actionErrorMsg_rateLimitedIsExplicit() {
        assertTrue("被限流要提示稍后再试，而不是让用户反复点", ReplyApi.actionErrorMsg(-509).contains("频繁"))
    }

    @Test
    fun actionErrorMsg_knownCommentErrorsAreNotGeneric() {
        val known = listOf(-102, -400, -404, 12002, 12004, 12006, 12009, 12011, 65004, 65005, 65006, 65007)
        for (code in known) {
            val msg = ReplyApi.actionErrorMsg(code)
            assertTrue("code=$code 应有专属中文提示", msg.isNotEmpty())
            assertFalse("code=$code 不应落到兜底文案", msg.startsWith("操作失败（错误码"))
        }
    }

    @Test
    fun actionErrorMsg_unknownCodeKeepsTheNumber() {
        // 遇到没见过的码，提示里必须带上原始数字，否则用户/开发者无法据此定位
        val msg = ReplyApi.actionErrorMsg(99999)
        assertTrue("兜底文案要带上错误码本身", msg.contains("99999"))
    }
}
