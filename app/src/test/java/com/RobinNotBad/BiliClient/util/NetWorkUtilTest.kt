package com.RobinNotBad.BiliClient.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetWorkUtilTest {

    private val fakePrefs = FakeSharedPreferences()

    @Before
    fun setUp() {
        SharedPreferencesUtil.sharedPreferences = fakePrefs
    }

    @After
    fun tearDown() {
        SharedPreferencesUtil.sharedPreferences = null
    }

    @Test
    fun buildGuestCookieString_removesLoginCookies_keepsGuestCookies() {
        val full = "SESSDATA=abc; bili_jct=def; DedeUserID=123; DedeUserID__ckMd5=xyz; " +
                "sid=9; buvid3=aaa; buvid4=bbb; bili_ticket=ccc; _uuid=ddd; CURRENT_FNVAL=4048"
        val guest = NetWorkUtil.buildGuestCookieString(full)

        assertFalse("应剔除SESSDATA", guest.contains("SESSDATA"))
        assertFalse("应剔除bili_jct", guest.contains("bili_jct"))
        assertFalse("应剔除DedeUserID", guest.contains("DedeUserID"))
        assertFalse("应剔除DedeUserID__ckMd5", guest.contains("DedeUserID__ckMd5"))
        assertFalse("应剔除sid", guest.contains("sid"))
        assertTrue("应保留buvid3", guest.contains("buvid3=aaa"))
        assertTrue("应保留buvid4", guest.contains("buvid4=bbb"))
        assertTrue("应保留bili_ticket", guest.contains("bili_ticket=ccc"))
        assertTrue("应保留_uuid", guest.contains("_uuid=ddd"))
    }

    @Test
    fun buildGuestCookieString_nullOrEmpty_returnsEmpty() {
        assertEquals("", NetWorkUtil.buildGuestCookieString(null))
        assertEquals("", NetWorkUtil.buildGuestCookieString(""))
    }

    // ---- pickCsrf：csrf 必须是「实时值」而不是登录那一刻的快照 ----
    // bili_jct 会随 Cookie 刷新轮换，用旧快照发 POST 会被服务端回 -111，而报错看起来像"没登录"。
    // 这几条测试锁住的就是"优先用 Cookie 里的实时值，取不到才退回快照"这条判定。

    @Test
    fun pickCsrf_liveCookieWins_overStoredSnapshot() {
        val cookies = "SESSDATA=abc; bili_jct=LIVE_TOKEN; DedeUserID=123"
        assertEquals("应优先取 Cookie 里的 bili_jct", "LIVE_TOKEN", NetWorkUtil.pickCsrf(cookies, "OLD_TOKEN"))
    }

    @Test
    fun pickCsrf_missingLiveValue_fallsBackToStoredSnapshot() {
        val cookies = "SESSDATA=abc; DedeUserID=123"
        assertEquals("Cookie 里没有 bili_jct 时应退回快照", "OLD_TOKEN", NetWorkUtil.pickCsrf(cookies, "OLD_TOKEN"))
    }

    @Test
    fun pickCsrf_nullCookieString_fallsBackToStoredSnapshot() {
        assertEquals("Cookie 串为 null 时不能抛异常", "OLD_TOKEN", NetWorkUtil.pickCsrf(null, "OLD_TOKEN"))
    }

    @Test
    fun pickCsrf_emptyLiveValue_treatedAsMissing() {
        val cookies = "SESSDATA=abc; bili_jct=; DedeUserID=123"
        assertEquals("bili_jct 为空值时同样算没有，退回快照", "OLD_TOKEN", NetWorkUtil.pickCsrf(cookies, "OLD_TOKEN"))
    }

    @Test
    fun pickCsrf_bothEmpty_returnsEmptyStringNotFallbackGarbage() {
        assertEquals("", NetWorkUtil.pickCsrf("", ""))
        assertEquals("", NetWorkUtil.pickCsrf(null, null))
        assertEquals("", NetWorkUtil.pickCsrf("SESSDATA=abc", null))
    }

    @Test
    fun pickCsrf_liveValueFoundWhenNotFirstCookie() {
        // Cookie 顺序不固定，bili_jct 可能排在很后面；这里确认不是只看第一段
        val cookies = "buvid3=a; SESSDATA=b; DedeUserID=123; DedeUserID__ckMd5=z; sid=s; bili_jct=TAIL_TOKEN"
        assertEquals("TAIL_TOKEN", NetWorkUtil.pickCsrf(cookies, "OLD_TOKEN"))
    }
}
