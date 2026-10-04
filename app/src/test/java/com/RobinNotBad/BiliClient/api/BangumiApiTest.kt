package com.RobinNotBad.BiliClient.api

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 追番列表解析测试（26.10.04 批次 5 的 C16）。
 *
 * 解析结果直接决定"要不要弹追番更新通知"：字段取错会让更新永远检测不到（静默失效），
 * 或者把没开播的番当成更新（每次打开应用都弹）。只测纯解析，不发网络请求。
 */
class BangumiApiTest {

    private fun item(
        mediaId: Long,
        title: String = "某番",
        newEpId: Long = 0,
        indexShow: String = ""
    ): String = buildString {
        append("""{"media_id":$mediaId,"title":"$title"""")
        if (newEpId > 0 || indexShow.isNotEmpty()) {
            append(""","new_ep":{"id":$newEpId,"index_show":"$indexShow"}""")
        }
        append("}")
    }

    private fun rootWith(vararg items: String): JSONObject =
        JSONObject("""{"code":0,"data":{"list":[${items.joinToString(",")}],"pn":1,"ps":30,"total":${items.size}}}""")

    @Test
    fun parseFollowingList_取出mediaId与最新集() {
        val list = BangumiApi.parseFollowingList(
            rootWith(
                item(12345L, "葬送的芙莉莲", 307774L, "更新至第13话"),
                item(67890L, "孤独摇滚", 290001L, "全12话")
            )
        )

        assertEquals(2, list.size)
        assertEquals(12345L, list[0].mediaId)
        assertEquals("葬送的芙莉莲", list[0].title)
        assertEquals(307774L, list[0].newEpId)
        assertEquals("更新至第13话", list[0].newEpIndexShow)
        assertEquals(67890L, list[1].mediaId)
        assertEquals(290001L, list[1].newEpId)
    }

    @Test
    fun parseFollowingList_没有new_ep时最新集为0() {
        val list = BangumiApi.parseFollowingList(rootWith(item(111L, "还没开播的番")))

        assertEquals(1, list.size)
        assertEquals(0L, list[0].newEpId)
        assertEquals("", list[0].newEpIndexShow)
    }

    @Test
    fun parseFollowingList_跳过没有media_id的项() {
        val list = BangumiApi.parseFollowingList(
            rootWith(item(0L, "脏数据"), item(222L, "正常番", 5L, "更新至第5话"))
        )

        assertEquals(1, list.size)
        assertEquals(222L, list[0].mediaId)
    }

    @Test
    fun parseFollowingList_缺list或data_返回空表() {
        assertEquals(0, BangumiApi.parseFollowingList(JSONObject("""{"code":0}""")).size)
        assertEquals(0, BangumiApi.parseFollowingList(JSONObject("""{"code":0,"data":{}}""")).size)
        assertEquals(
            0,
            BangumiApi.parseFollowingList(JSONObject("""{"code":0,"data":{"list":null}}""")).size
        )
    }

    @Test
    fun parseFollowingList_错误码_抛出可读异常() {
        var thrown: JSONException? = null
        try {
            BangumiApi.parseFollowingList(JSONObject("""{"code":53013,"message":"用户隐私设置未公开"}"""))
        } catch (e: JSONException) {
            thrown = e
        }

        assertTrue("错误码必须抛出 JSONException", thrown != null)
        assertEquals("用户隐私设置未公开", thrown!!.message)
    }

    @Test
    fun parseFollowingList_错误码但没有message_用错误码兜底() {
        var thrown: JSONException? = null
        try {
            BangumiApi.parseFollowingList(JSONObject("""{"code":-400}"""))
        } catch (e: JSONException) {
            thrown = e
        }

        assertTrue("错误码必须抛出 JSONException", thrown != null)
        assertEquals("错误码：-400", thrown!!.message)
    }
}
