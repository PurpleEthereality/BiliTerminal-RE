package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.Announcement
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TerminalApi] 里所有**不碰 Android 运行时**的纯函数。
 *
 * 为什么值得写：这几段都直接决定发到自建服务器上的内容——
 * install_id 是否带上、分类是不是白名单里的值、公告 id 是否参与已读差量。
 * 一旦写错，App 编译、安装、界面全都正常，只有服务器侧的数据是脏的（或者干脆没数据），
 * 而那是从用户手机上删不掉的。
 */
class TerminalApiTest {

    // ---------- 分类白名单 ----------

    @Test
    fun normalizeCategory_keepsWhitelistedValues() {
        assertEquals("bug", TerminalApi.normalizeCategory("bug"))
        assertEquals("suggestion", TerminalApi.normalizeCategory("suggestion"))
        assertEquals("content", TerminalApi.normalizeCategory("content"))
        assertEquals("performance", TerminalApi.normalizeCategory("performance"))
        assertEquals("other", TerminalApi.normalizeCategory("other"))
    }

    @Test
    fun normalizeCategory_fallsBackToOther() {
        assertEquals("未知分类应归为 other", "other", TerminalApi.normalizeCategory("BUG"))
        assertEquals("空串应归为 other", "other", TerminalApi.normalizeCategory(""))
        assertEquals("null 应归为 other", "other", TerminalApi.normalizeCategory(null))
        assertEquals("任意脏数据应归为 other", "other", TerminalApi.normalizeCategory("drop table"))
    }

    // ---------- 遥测载荷 ----------

    @Test
    fun buildPingPayload_carriesInstallIdAndDevice() {
        val payload = TerminalApi.buildPingPayload(
            "abc123", 2610090, "26.10.09", false, 34, "Xiaomi", "diting", "arm64-v8a"
        )
        assertEquals("abc123", payload.getString("install_id"))
        assertEquals(2610090, payload.getInt("version_code"))
        assertEquals("26.10.09", payload.getString("version_name"))
        assertFalse("非 beta 应为 false", payload.getBoolean("is_beta"))
        assertEquals(34, payload.getInt("sdk"))
        assertEquals("Xiaomi", payload.getString("brand"))
        assertEquals("diting", payload.getString("device"))
        assertEquals("arm64-v8a", payload.getString("abi"))
    }

    @Test
    fun buildPingPayload_nullStringsBecomeEmpty() {
        val payload = TerminalApi.buildPingPayload(null, 0, null, true, 0, null, null, null)
        assertEquals("install_id 为 null 时应落成空串", "", payload.getString("install_id"))
        assertEquals("", payload.getString("version_name"))
        assertEquals("", payload.getString("brand"))
        assertEquals("", payload.getString("device"))
        assertEquals("", payload.getString("abi"))
        assertTrue("beta 应为 true", payload.getBoolean("is_beta"))
    }

    // ---------- 反馈载荷 ----------

    @Test
    fun buildFeedbackPayload_normalizesCategoryAndDefaults() {
        val payload = TerminalApi.buildFeedbackPayload(
            "abc123", "BUG", "播放器闪退", null, 0, 2610090, "26.10.09", 34, "Xiaomi", "diting"
        )
        assertEquals("非法分类应被收敛", "other", payload.getString("category"))
        assertEquals("content 不可以被裁剪或改写", "播放器闪退", payload.getString("content"))
        assertEquals("未填联系方式应为空串", "", payload.getString("contact"))
        assertEquals("未勾选附带账号时 mid 应为 0", 0L, payload.getLong("mid"))
    }

    @Test
    fun buildFeedbackPayload_keepsSelectedMid() {
        val payload = TerminalApi.buildFeedbackPayload(
            "abc123", "bug", "内容", "qq:123", 123456L, 1, "v", 34, "b", "d"
        )
        assertEquals(123456L, payload.getLong("mid"))
        assertEquals("qq:123", payload.getString("contact"))
    }

    // ---------- 崩溃载荷 ----------

    @Test
    fun buildCrashPayload_hasRicherFieldsThanUpstream() {
        val payload = TerminalApi.buildCrashPayload(
            /* installId    = */ "abc123",
            /* mid          = */ 0L,
            /* exception    = */ "java.lang.NullPointerException",
            /* message      = */ "Attempt to invoke virtual method",
            /* thread       = */ "main",
            /* stack        = */ "at com.example.A.b(A.java:1)",
            /* log          = */ "最近页面：视频详情 -> 播放器",
            /* extra        = */ "{\"page\":\"player\"}",
            /* versionCode  = */ 2610090,
            /* versionName  = */ "26.10.09",
            /* sdk          = */ 34,
            /* release      = */ "14",
            /* brand        = */ "Xiaomi",
            /* device       = */ "diting",
            /* product      = */ "diting",
            /* model        = */ "Redmi Note 12T Pro",
            /* abi          = */ "arm64-v8a",
            /* ramTotal     = */ 8192L,
            /* ramAvail     = */ 2048L,
            /* uptimeSec    = */ 3600L
        )
        assertEquals("abc123", payload.getString("install_id"))
        assertEquals("java.lang.NullPointerException", payload.getString("exception"))
        assertEquals("Attempt to invoke virtual method", payload.getString("message"))
        assertEquals("出错线程必须带上", "main", payload.getString("thread"))
        assertTrue("堆栈必须原样带上", payload.getString("stack").contains("com.example.A.b"))
        assertEquals("最近页面：视频详情 -> 播放器", payload.getString("log"))
        assertEquals(8192L, payload.getLong("ram_total"))
        assertEquals(2048L, payload.getLong("ram_avail"))
        assertEquals(3600L, payload.getLong("uptime_sec"))
        assertEquals("Redmi Note 12T Pro", payload.getString("model"))
        assertEquals("14", payload.getString("release"))
    }

    // ---------- 公告解析 ----------

    @Test
    fun parseAnnouncements_parsesSecondsAndSkipsIdlessItems() {
        val json = """
            [
              {"id":1000000002,"ctime":1700000000,"title":"第二条","content":"内容二"},
              {"id":1000000001,"ctime":1690000000,"title":"第一条","content":"内容一"},
              {"ctime":1600000000,"title":"没有 id","content":"应被跳过"}
            ]
        """
        val list = TerminalApi.parseAnnouncements(JSONArray(json))
        assertEquals("缺 id 的条目应被跳过", 2, list.size)
        assertEquals(1000000002, list[0].id)
        assertEquals("第二条", list[0].title)
        assertEquals("内容一", list[1].content)
        assertFalse("ctime 秒应被换算成可读日期", list[0].ctime.isEmpty())
        assertTrue("应格式化为 yyyy-MM-dd", list[0].ctime.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun parseAnnouncements_nullArrayYieldsEmptyList() {
        assertTrue(TerminalApi.parseAnnouncements(null).isEmpty())
        assertTrue(TerminalApi.parseAnnouncements(JSONArray()).isEmpty())
    }

    // ---------- 两路合并 ----------

    private fun announcement(id: Int): Announcement {
        val item = Announcement()
        item.id = id
        item.title = "t$id"
        item.content = "c$id"
        item.ctime = "2024-01-01"
        return item
    }

    @Test
    fun mergeAnnouncements_sortsByIdDescending() {
        val upstream = arrayListOf(announcement(3), announcement(1))
        val own = arrayListOf(announcement(1000000002), announcement(2))
        val merged = TerminalApi.mergeAnnouncements(upstream, own)
        assertEquals(4, merged.size)
        assertEquals("自建源 id 带 10 亿偏移，应排在最前", 1000000002, merged[0].id)
        assertEquals(3, merged[1].id)
        assertEquals(2, merged[2].id)
        assertEquals(1, merged[3].id)
    }

    @Test
    fun mergeAnnouncements_deduplicatesById() {
        val merged = TerminalApi.mergeAnnouncements(
            arrayListOf(announcement(1), announcement(2)),
            arrayListOf(announcement(2), announcement(3))
        )
        assertEquals("重复 id 只应保留一条", 3, merged.size)
    }

    @Test
    fun mergeAnnouncements_toleratesOneSourceFailing() {
        assertEquals("上游挂掉时应仍能展示自建源", 1, TerminalApi.mergeAnnouncements(null, arrayListOf(announcement(1))).size)
        assertEquals("自建源挂掉时应仍能展示上游", 1, TerminalApi.mergeAnnouncements(arrayListOf(announcement(1)), null).size)
        assertTrue("两路都挂掉时应为空而不是崩溃", TerminalApi.mergeAnnouncements(null, null).isEmpty())
    }

    // ---------- 日期键 ----------

    @Test
    fun dayKey_isIsoDate() {
        assertTrue(TerminalApi.dayKey(1700000000000L).matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    // ---------- 安全：载荷里不得出现账号凭据 ----------

    @Test
    fun feedbackPayload_containsNoCookieOrToken() {
        val payload = TerminalApi.buildFeedbackPayload(
            "abc123", "bug", "内容", "", 0, 1, "v", 34, "b", "d"
        )
        val text = payload.toString().lowercase()
        for (forbidden in listOf("sessdata", "bili_jct", "dedeuserid", "cookie", "csrf")) {
            assertFalse("载荷里不应出现 $forbidden", text.contains(forbidden))
        }
    }
}
