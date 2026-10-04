package com.RobinNotBad.BiliClient.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 私信会话列表解析测试。
 *
 * 重点覆盖 account_info 过滤：该字段仅在系统会话中出现，
 * 旧实现 `!has && isNull` 恒等于 `!has`，会把 account_info 显式为 null 的普通会话一起丢弃。
 */
class PrivateMsgApiTest {

    private fun sessionJson(
        talkerId: Long,
        unread: Int = 0,
        accountInfo: String = "",
        lastMsg: String = "",
        topTs: Long = -1
    ): String = buildString {
        append("""{"talker_id":$talkerId,"unread_count":$unread""")
        if (accountInfo.isNotEmpty()) append(""","account_info":$accountInfo""")
        if (lastMsg.isNotEmpty()) append(""","last_msg":$lastMsg""")
        if (topTs >= 0) append(""","top_ts":$topTs""")
        append("}")
    }

    private fun rootWith(vararg sessions: String): JSONObject =
        JSONObject("""{"code":0,"data":{"session_list":[${sessions.joinToString(",")}]}}""")

    @Test
    fun parseSessionsList_普通会话_全部保留() {
        val root = rootWith(
            sessionJson(1001L, unread = 2),
            sessionJson(1002L, unread = 0)
        )

        val list = PrivateMsgApi.parseSessionsList(root)

        assertEquals("两个普通会话都应保留", 2, list.size)
        assertEquals(1001L, list[0].talkerUid)
        assertEquals(2, list[0].unread)
    }

    @Test
    fun parseSessionsList_accountInfo为null_应保留() {
        // 回归用例：字段存在但值为 null 的是普通会话，不能被过滤掉
        val root = rootWith(
            sessionJson(2001L, accountInfo = "null"),
            sessionJson(2002L)
        )

        val list = PrivateMsgApi.parseSessionsList(root)

        assertEquals("account_info 为 null 的会话应保留", 2, list.size)
    }

    @Test
    fun parseSessionsList_系统会话_应被过滤() {
        val root = rootWith(
            sessionJson(3001L, accountInfo = """{"name":"系统消息"}"""),
            sessionJson(3002L)
        )

        val list = PrivateMsgApi.parseSessionsList(root)

        assertEquals("系统会话应被过滤", 1, list.size)
        assertEquals(3002L, list[0].talkerUid)
    }

    @Test
    fun parseSessionsList_解析lastMsg与未读数() {
        val root = rootWith(
            sessionJson(4001L, unread = 5, lastMsg = """{"msg_type":1,"content":"你好"}"""),
            sessionJson(4002L, unread = 0, lastMsg = """{"msg_type":7,"content":"{\"text\":\"卡片\"}"}""")
        )

        val list = PrivateMsgApi.parseSessionsList(root)

        assertEquals(1, list[0].contentType)
        assertEquals(5, list[0].unread)
        assertNull("纯文本内容不应解析成 JSONObject", list[0].content)
        assertEquals(7, list[1].contentType)
        assertEquals("卡片", list[1].content?.optString("text"))
    }

    @Test
    fun parseSessionsList_data为null_返回空列表() {
        val root = JSONObject("""{"code":0,"data":null}""")

        assertTrue("data 为 null 时应返回空列表", PrivateMsgApi.parseSessionsList(root).isEmpty())
    }

    @Test
    fun parseSessionsList_null输入_返回空列表() {
        assertTrue("null 输入应返回空列表", PrivateMsgApi.parseSessionsList(null).isEmpty())
    }

    @Test
    fun parseSessionsList_解析置顶时间() {
        val root = rootWith(
            sessionJson(5001L, topTs = 1780000000000000L),
            sessionJson(5002L, topTs = 0L),
            sessionJson(5003L)
        )

        val list = PrivateMsgApi.parseSessionsList(root)

        assertEquals(1780000000000000L, list[0].topTs)
        assertTrue("top_ts 非零 = 已置顶", list[0].isTop)
        assertEquals(0L, list[1].topTs)
        assertTrue("top_ts 为 0 = 未置顶", !list[1].isTop)
        assertEquals("缺字段按未置顶处理", 0L, list[2].topTs)
        assertTrue(!list[2].isTop)
    }

    @Test
    fun opTypeForTop_0是置顶_1是取消置顶() {
        // 接口的 op_type 与直觉相反，写反的后果是"点置顶实际取消置顶"，不报错、极难发现
        assertEquals("置顶必须传 0", 0, PrivateMsgApi.opTypeForTop(true))
        assertEquals("取消置顶必须传 1", 1, PrivateMsgApi.opTypeForTop(false))
    }

    @Test
    fun sessionErrorMsg_成功为空串_其余给出可读提示() {
        assertEquals("", PrivateMsgApi.sessionErrorMsg(0))
        assertTrue(PrivateMsgApi.sessionErrorMsg(-101).contains("登录"))
        assertTrue(PrivateMsgApi.sessionErrorMsg(-400).contains("请求错误"))
        assertTrue(PrivateMsgApi.sessionErrorMsg(12345).contains("12345"))
    }

    // ---------- C12 图片消息 ----------

    @Test
    fun sizeToKb_按千字节保留三位小数() {
        assertEquals(0.0, PrivateMsgApi.sizeToKb(0L), 0.0)
        assertEquals("负数按 0 处理，不能发出 NaN/负数", 0.0, PrivateMsgApi.sizeToKb(-1L), 0.0)
        assertEquals(1.0, PrivateMsgApi.sizeToKb(1024L), 0.0)
        // 接口文档示例值就是 55.443 这种三位小数
        assertEquals(55.443, PrivateMsgApi.sizeToKb(56774L), 0.001)
        // 小于 1KB 也要保留小数，不能被截成 0
        assertEquals(0.5, PrivateMsgApi.sizeToKb(512L), 0.0)
    }

    @Test
    fun imageTypeOf_去掉mime前缀并把jpg归一成jpeg() {
        assertEquals("jpeg", PrivateMsgApi.imageTypeOf("image/jpeg"))
        assertEquals("jpeg", PrivateMsgApi.imageTypeOf("image/jpg"))
        assertEquals("png", PrivateMsgApi.imageTypeOf("image/png"))
        assertEquals("gif", PrivateMsgApi.imageTypeOf("image/gif"))
        assertEquals("大小写不敏感，统一小写输出", "webp", PrivateMsgApi.imageTypeOf("IMAGE/WEBP"))
    }

    @Test
    fun imageTypeOf_空值兜底为jpeg() {
        assertEquals("jpeg", PrivateMsgApi.imageTypeOf(null))
        assertEquals("jpeg", PrivateMsgApi.imageTypeOf(""))
    }

    @Test
    fun buildImageContent_字段齐全且是图片消息规格() {
        val content = PrivateMsgApi.buildImageContent(
            "https://message.biliimg.com/bfs/im_new/xxx.jpg", 300, 400, 56774L, "jpeg"
        )

        assertEquals("https://message.biliimg.com/bfs/im_new/xxx.jpg", content.getString("url"))
        assertEquals(300, content.getInt("width"))
        assertEquals(400, content.getInt("height"))
        assertEquals("jpeg", content.getString("imageType"))
        assertEquals("1 表示 APP 显示「下载原图」", 1, content.getInt("original"))
        assertEquals(55.443, content.getDouble("size"), 0.001)
    }
}
