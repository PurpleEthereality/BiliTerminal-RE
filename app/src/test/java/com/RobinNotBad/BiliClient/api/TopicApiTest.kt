package com.RobinNotBad.BiliClient.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 话题解析的单测（26.10.04 批次 6 的 C10）。
 *
 * 只覆盖 [TopicApi] 的纯解析部分——网络请求部分不测。
 */
class TopicApiTest {

    @Test
    fun parseTopic_readsEveryField() {
        val json = JSONObject(
            """
            {"id":1305890,"name":"燕云河西凉州篇预测","discuss":147,"dynamics":20,"view":261060}
            """.trimIndent()
        )

        val topic = TopicApi.parseTopic(json)

        assertEquals(1305890L, topic.id)
        assertEquals("燕云河西凉州篇预测", topic.name)
        assertEquals(147L, topic.discuss)
        assertEquals(20L, topic.dynamics)
        assertEquals(261060L, topic.view)
    }

    @Test
    fun parseTopic_toleratesMissingFieldsAndNull() {
        val empty = TopicApi.parseTopic(JSONObject())

        assertEquals(0L, empty.id)
        assertEquals("", empty.name)
        assertEquals(0L, empty.discuss)
        assertEquals(0L, empty.dynamics)
        assertEquals(0L, empty.view)

        // null 也要能过（服务端偶尔给 null 字段，别抛 NPE）
        assertEquals(0L, TopicApi.parseTopic(null).id)
    }

    @Test
    fun parseTopics_skipsNullEntriesAndItemsWithoutId() {
        val items = JSONArray(
            """
            [null,{"name":"没有 id"},{"id":1,"name":"有名有姓"},{"id":2}]
            """.trimIndent()
        )

        val topics = TopicApi.parseTopics(items)

        assertEquals(2, topics.size)
        assertEquals("有名有姓", topics[0].name)
        assertEquals(2L, topics[1].id)
    }

    @Test
    fun parseTopics_toleratesNullOrEmptyArray() {
        assertTrue(TopicApi.parseTopics(null).isEmpty())
        assertTrue(TopicApi.parseTopics(JSONArray()).isEmpty())
    }
}
