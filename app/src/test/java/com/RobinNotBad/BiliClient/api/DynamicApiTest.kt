package com.RobinNotBad.BiliClient.api

import org.junit.Assert.assertEquals
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
}
