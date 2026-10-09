package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ApkVerifier] 纯逻辑的 JVM 单测（26.10.04 批次 4 / E6）。
 *
 * 这里只钉住判定规则本身（Android 取签名那步要靠真机，见 `docs/review/real-device-regression-checklist.md` 第 24~26 条）。
 * 重点是把「读不到签名时必须失败关闭」钉死：一旦某天有人把它改成"读不到就放过"，
 * 校验就退化成永远通过的摆设。
 */
class ApkVerifierTest {

    // ==================== 包名 ====================

    @Test
    fun samePackage_matches() {
        assertTrue(ApkVerifier.isSamePackage("com.RobinNotBad.BiliClient", "com.RobinNotBad.BiliClient"))
    }

    @Test
    fun samePackage_differentOrMissing_rejects() {
        assertFalse(ApkVerifier.isSamePackage("com.RobinNotBad.BiliClient", "com.evil.clone"))
        assertFalse(ApkVerifier.isSamePackage("com.RobinNotBad.BiliClient", null))
        assertFalse(ApkVerifier.isSamePackage("com.RobinNotBad.BiliClient", ""))
    }

    // ==================== 签名 ====================

    @Test
    fun sameSignature_orderDoesNotMatter() {
        assertTrue(ApkVerifier.isSameSignature(listOf("aa", "bb"), listOf("bb", "aa")))
    }

    @Test
    fun sameSignature_caseDoesNotMatter() {
        assertTrue(ApkVerifier.isSameSignature(listOf("AABB"), listOf("aabb")))
    }

    @Test
    fun sameSignature_different_rejects() {
        assertFalse(ApkVerifier.isSameSignature(listOf("aa"), listOf("bb")))
        // 多一个签名也算不一致：不要用 contains 之类的宽松比较
        assertFalse(ApkVerifier.isSameSignature(listOf("aa"), listOf("aa", "bb")))
    }

    @Test
    fun sameSignature_emptyOnEitherSide_failsClosed() {
        assertFalse(ApkVerifier.isSameSignature(emptyList(), listOf("aa")))
        assertFalse(ApkVerifier.isSameSignature(listOf("aa"), emptyList()))
        assertFalse(ApkVerifier.isSameSignature(emptyList(), emptyList()))
    }

    // ==================== 十六进制 ====================

    @Test
    fun signatureHex_padsEveryByteToTwoDigits() {
        // 0x0A → "0a"（不能变成 "a"）、0x00 → "00"、0xFF → "ff"（必须补码成无符号）
        assertEquals("0a00ff7f", ApkVerifier.signatureHex(byteArrayOf(0x0A, 0x00, 0xFF.toByte(), 0x7F)))
        assertEquals("00", ApkVerifier.signatureHex(byteArrayOf(0)))
        assertEquals("", ApkVerifier.signatureHex(ByteArray(0)))
    }

    @Test
    fun signatureHex_isLowerCase() {
        val hex = ApkVerifier.signatureHex(byteArrayOf(0xAB.toByte(), 0xCD.toByte()))
        assertEquals("abcd", hex)
        assertEquals(hex, hex.lowercase())
    }
}
