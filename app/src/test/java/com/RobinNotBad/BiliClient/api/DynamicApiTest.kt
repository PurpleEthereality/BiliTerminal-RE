package com.RobinNotBad.BiliClient.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态编辑相关的纯函数单测（动态编辑对齐 PiliPlus，26.10.04 批次 6 的 C7）。
 */
class DynamicApiTest {

    @Test
    fun buildUploadId_joinsMidSecondsAndRandomWithUnderline() {
        assertEquals("12345_1700000000_6789", DynamicApi.buildUploadId(12345L, 1700000000L, 6789))
    }

    @Test
    fun buildUploadId_isDeterministicForSameInput() {
        assertEquals(
            DynamicApi.buildUploadId(1L, 2L, 3),
            DynamicApi.buildUploadId(1L, 2L, 3)
        )
    }

    @Test
    fun editErrorMsg_successIsEmpty() {
        assertTrue(DynamicApi.editErrorMsg(0).isEmpty())
    }

    @Test
    fun editErrorMsg_mapsEveryAuthFailureToRelogin() {
        for (code in intArrayOf(-101, -102, -111)) {
            assertTrue(
                "错误码 $code 应提示重新登录",
                DynamicApi.editErrorMsg(code).contains("重新登录")
            )
        }
    }

    @Test
    fun editErrorMsg_explainsKnownBusinessErrors() {
        assertTrue(DynamicApi.editErrorMsg(-400).contains("规范"))
        assertTrue(DynamicApi.editErrorMsg(-403).contains("权限"))
        assertTrue(DynamicApi.editErrorMsg(-404).contains("已经"))
        assertTrue(DynamicApi.editErrorMsg(-509).contains("频繁"))
    }

    @Test
    fun editErrorMsg_unknownCodeStillShowsTheCode() {
        val msg = DynamicApi.editErrorMsg(12345)
        assertTrue(msg.contains("12345"))
        assertTrue(msg.contains("失败"))
    }

    @Test
    fun topPath_switchesBetweenSetAndRemoveTop() {
        assertEquals("space/set_top", DynamicApi.topPath(true))
        assertEquals("space/rm_top", DynamicApi.topPath(false))
    }

    @Test
    fun topSuccessMsg_matchesTheDirection() {
        assertEquals("置顶成功~", DynamicApi.topSuccessMsg(true))
        assertEquals("已取消置顶~", DynamicApi.topSuccessMsg(false))
    }

    @Test
    fun topErrorMsg_successIsEmpty() {
        assertTrue(DynamicApi.topErrorMsg(0).isEmpty())
    }

    @Test
    fun topErrorMsg_explainsKnownCodes() {
        assertTrue(DynamicApi.topErrorMsg(-101).contains("登录"))
        for (code in intArrayOf(-102, -111)) {
            assertTrue(DynamicApi.topErrorMsg(code).contains("重新登录"))
        }
        assertTrue(DynamicApi.topErrorMsg(4100001).contains("id"))
        assertTrue(DynamicApi.topErrorMsg(-404).contains("已经"))
    }

    @Test
    fun topErrorMsg_unknownCodeStillShowsTheCode() {
        val msg = DynamicApi.topErrorMsg(999)
        assertTrue(msg.contains("999"))
        assertTrue(msg.contains("失败"))
    }

    @Test
    fun buildPublishOption_writesTimerAsIntegerSeconds() {
        // 定时发布要的是秒级时间戳（int），不是 "yyyy-MM-dd HH:mm" 字符串——写死以防被改回字符串
        val option = DynamicApi.buildPublishOption(false, null, null, 1893456000)
        assertTrue(option.get("timer_pub_time") is Int)
        assertEquals(1893456000, option.getInt("timer_pub_time"))
    }

    @Test
    fun buildPublishOption_withoutTimerHasNoTimerKey() {
        val option = DynamicApi.buildPublishOption(false, null, null, null)
        assertFalse(option.has("timer_pub_time"))
        assertFalse(option.has("private_pub"))
    }

    @Test
    fun buildPublishOption_keepsOtherFlags() {
        val option = DynamicApi.buildPublishOption(true, 1, 0, null)
        assertTrue(option.getBoolean("private_pub"))
        assertEquals(1, option.getInt("close_comment"))
        assertEquals(0, option.getInt("up_choose_comment"))
    }

    @Test
    fun timerSecondsAt_addsMinutes() {
        assertEquals(1000L + 10 * 60L, DynamicApi.timerSecondsAt(1000L, 10))
        assertEquals(1000L + 120 * 60L, DynamicApi.timerSecondsAt(1000L, 120))
    }

    @Test
    fun timerSecondsAt_ignoresNonPositiveMinutes() {
        assertEquals(1000L, DynamicApi.timerSecondsAt(1000L, 0))
        assertEquals(1000L, DynamicApi.timerSecondsAt(1000L, -5))
    }
}
