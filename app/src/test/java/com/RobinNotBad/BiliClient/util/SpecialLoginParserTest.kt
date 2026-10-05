package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialLoginParserTest {

    private val fullInfo = """
        {"cookies":"SESSDATA=abc; bili_jct=csrf; DedeUserID=12345; buvid3=x","refresh_token":"rt","access_key":"ak"}
    """.trimIndent()

    @Test
    fun parse_fullInfo_success() {
        val result = SpecialLoginParser.parse(fullInfo)
        assertTrue("完整登录信息应解析成功：$result", result is SpecialLoginParser.Result.Success)
        result as SpecialLoginParser.Result.Success
        assertEquals("SESSDATA=abc; bili_jct=csrf; DedeUserID=12345; buvid3=x", result.cookies)
        assertEquals("rt", result.refreshToken)
        assertEquals("ak", result.accessKey)
        assertEquals(12345L, result.mid)
    }

    @Test
    fun parse_withoutAccessKey_successAndAccessKeyNull() {
        val result = SpecialLoginParser.parse("{\"cookies\":\"DedeUserID=1\",\"refresh_token\":\"rt\"}")
        assertTrue(result is SpecialLoginParser.Result.Success)
        assertEquals(null, (result as SpecialLoginParser.Result.Success).accessKey)
    }

    @Test
    fun parse_surroundingWhitespace_trimmed() {
        val result = SpecialLoginParser.parse("  \n$fullInfo \n ")
        assertTrue("前后空白应被容忍：$result", result is SpecialLoginParser.Result.Success)
    }

    @Test
    fun parse_emptyOrNull_failure() {
        assertTrue(SpecialLoginParser.parse(null) is SpecialLoginParser.Result.Failure)
        assertTrue(SpecialLoginParser.parse("") is SpecialLoginParser.Result.Failure)
        assertTrue(SpecialLoginParser.parse("   ") is SpecialLoginParser.Result.Failure)
    }

    @Test
    fun parse_notJson_failure() {
        assertTrue(SpecialLoginParser.parse("随便一段文字") is SpecialLoginParser.Result.Failure)
        assertTrue(SpecialLoginParser.parse("[1,2,3]") is SpecialLoginParser.Result.Failure)
        assertTrue(SpecialLoginParser.parse("cookies=abc;DedeUserID=1") is SpecialLoginParser.Result.Failure)
    }

    @Test
    fun parse_missingCookiesField_failure() {
        val result = SpecialLoginParser.parse("{\"refresh_token\":\"rt\"}")
        assertTrue(result is SpecialLoginParser.Result.Failure)
    }

    @Test
    fun parse_emptyCookies_failure() {
        assertTrue(
            SpecialLoginParser.parse("{\"cookies\":\"  \",\"refresh_token\":\"rt\"}")
                    is SpecialLoginParser.Result.Failure
        )
    }

    @Test
    fun parse_missingRefreshToken_failure() {
        val result = SpecialLoginParser.parse("{\"cookies\":\"DedeUserID=1\"}")
        assertTrue(result is SpecialLoginParser.Result.Failure)
    }

    @Test
    fun parse_cookiesWithoutDedeUserId_failure() {
        val result = SpecialLoginParser.parse("{\"cookies\":\"SESSDATA=abc\",\"refresh_token\":\"rt\"}")
        assertTrue("cookies 里没有 DedeUserID 应失败", result is SpecialLoginParser.Result.Failure)
    }

    @Test
    fun parse_dedeUserIdNotANumber_failure() {
        val result = SpecialLoginParser.parse("{\"cookies\":\"DedeUserID=abc\",\"refresh_token\":\"rt\"}")
        assertTrue("DedeUserID 不是数字应失败而不是崩溃", result is SpecialLoginParser.Result.Failure)
    }

    @Test
    fun parse_failureMessagesAreNotEmpty() {
        val cases = listOf(
            "",
            "不是json",
            "{\"refresh_token\":\"rt\"}",
            "{\"cookies\":\"SESSDATA=abc\",\"refresh_token\":\"rt\"}",
        )
        for (case in cases) {
            val result = SpecialLoginParser.parse(case)
            assertTrue("应失败：$case", result is SpecialLoginParser.Result.Failure)
            assertTrue("失败原因不能为空", (result as SpecialLoginParser.Result.Failure).message.isNotBlank())
        }
    }
}
