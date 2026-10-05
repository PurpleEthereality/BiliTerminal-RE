package com.RobinNotBad.BiliClient.api

import org.json.JSONArray
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

    // ==================== 话题节点解析（26.10.05，#话题# 不解析的修复） ====================

    /**
     * 用户报告：动态正文里的 `#话题#` 没有按话题解析（应为主色且可跳转）。
     *
     * 修复点在于 `analyzeTextContent` 的 switch 补上了 `RICH_TEXT_NODE_TYPE_TOPIC` 分支；
     * 这里用纯函数 [DynamicApi.parseTopicNodes] 钉住「话题节点能被稳定挑出来」，
     * 防止将来重构 switch 时又把它漏掉。
     */
    @Test
    fun parseTopicNodes_picksEveryTopicNodeInOrder() {
        val nodes = JSONArray(
            """
            [
              {"type":"RICH_TEXT_NODE_TYPE_TEXT","text":"今天看了"},
              {"type":"RICH_TEXT_NODE_TYPE_TOPIC","text":"#A#","jump_url":"https://m.bilibili.com/topic-detail?topic_id=1"},
              {"type":"RICH_TEXT_NODE_TYPE_AT","text":"@某人","rid":123},
              {"type":"RICH_TEXT_NODE_TYPE_TOPIC","text":"#B#","jump_url":"https://m.bilibili.com/topic-detail?topic_id=2"}
            ]
            """.trimIndent()
        )

        val topics = DynamicApi.parseTopicNodes(nodes)

        assertEquals(2, topics.size)
        assertEquals("#A#", topics[0].text)
        assertEquals("https://m.bilibili.com/topic-detail?topic_id=1", topics[0].jumpUrl)
        assertEquals(1L, topics[0].topicId)
        assertEquals("#B#", topics[1].text)
        assertEquals("https://m.bilibili.com/topic-detail?topic_id=2", topics[1].jumpUrl)
    }

    @Test
    fun parseTopicNodes_ignoresOtherNodeTypes() {
        val nodes = JSONArray(
            """
            [
              {"type":"RICH_TEXT_NODE_TYPE_TEXT","text":"纯文本"},
              {"type":"RICH_TEXT_NODE_TYPE_AT","text":"@某人"},
              {"type":"RICH_TEXT_NODE_TYPE_EMOJI","text":"[妙]"},
              {"type":"RICH_TEXT_NODE_TYPE_WEB","text":"网页链接"}
            ]
            """.trimIndent()
        )

        assertTrue(
            "只有 TOPIC 节点算话题；把 AT/EMOJI 也当话题会让纯文本被误染主色",
            DynamicApi.parseTopicNodes(nodes).isEmpty()
        )
    }

    @Test
    fun parseTopicNodes_skipsEmptyTextAndToleratesNull() {
        val nodes = JSONArray(
            """
            [
              {"type":"RICH_TEXT_NODE_TYPE_TOPIC","text":"","jump_url":"https://x"},
              {"type":"RICH_TEXT_NODE_TYPE_TOPIC","jump_url":"https://y"},
              null,
              {"text":"没有 type"}
            ]
            """.trimIndent()
        )

        // 空 text 挑出来会生成一个长度为 0 的 span，且会让 setSingleTopic 的起点落在错误位置
        assertTrue(DynamicApi.parseTopicNodes(nodes).isEmpty())
        assertTrue(DynamicApi.parseTopicNodes(null).isEmpty())
    }

    @Test
    fun parseTopicNodes_topicWithoutJumpUrlIsStillCollected() {
        // 服务端偶尔不下发 jump_url。此时仍应识别为话题（染主色），只是不可跳转——
        // 不能因为缺 jump_url 就整个丢掉，那样用户会看到话题是普通黑字。
        val nodes = JSONArray("""[{"type":"RICH_TEXT_NODE_TYPE_TOPIC","text":"#无链接#"}]""")

        val topics = DynamicApi.parseTopicNodes(nodes)

        assertEquals(1, topics.size)
        assertEquals("#无链接#", topics[0].text)
        assertEquals("", topics[0].jumpUrl)
        // 无 jump_url ⇒ 解析不出 topic_id ⇒ 不可点，但**仍是话题**（调用方仍会上主色）
        assertEquals(0L, topics[0].topicId)
        assertFalse("缺 jump_url 的话题不该被当成可点击", topics[0].isClickable)
    }

    /**
     * 回归守卫：**不要**把 `parseTopicNodes` 的返回类型改回 `android.util.Pair`。
     *
     * **失败时的根因速查**：`android.util.Pair` 在 JVM 单测下是**没有实现的 Android 桩**，
     * `new Pair<>(a, b)` 造出的对象其 `first`/`second` 恒为 `null`（本项目的 `app/build.gradle:262`
     * 只用 `testImplementation 'org.json:json:20231013'` 给 `org.json` 补了真实实现，
     * **没给 `android.util` 补**）。所以本函数改用真实类 `TopicNode`。
     *
     * 若本用例失败，症状会是「列表长度对（ArrayList 是真的）、但字段全是 null」——
     * 看起来像解析逻辑错，实则是返回类型退回了 Android 桩。**照上面两句定位即可，不用重新查。**
     *
     * 对照先例：`service/download/DownloadProgressMath.kt` 返回 `LongRange`（Kotlin 标准库真实类），
     * 所以 `DownloadProgressMathTest.kt:66/93` 断言 `.first` 能过 —— 用真实类型就没这问题。
     */
    @Test
    fun parseTopicNodes_returnsRealObjectsNotAndroidStubs() {
        val nodes = JSONArray(
            """[{"type":"RICH_TEXT_NODE_TYPE_TOPIC","text":"#人话#","jump_url":"https://m.bilibili.com/topic-detail?topic_id=42"}]"""
        )

        val node = DynamicApi.parseTopicNodes(nodes).single()

        assertEquals("字段必须是真值，不能是 Android 桩的 null", "#人话#", node.text)
        assertEquals(42L, node.topicId)
        assertTrue(node.isClickable)
    }
}
