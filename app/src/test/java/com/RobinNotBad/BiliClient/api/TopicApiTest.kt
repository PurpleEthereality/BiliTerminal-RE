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

    // ==================== 广场数据源修正（26.10.05） ====================

    /**
     * 广场已改用 `/x/topic/pub/search`（`keywords` 留空 = 按热度取一页）。
     *
     * 原 `/x/topic/web/dynamic/rcmd` 实测恒返回 `{code:0, data:null}` —— 注意 code 是 0，
     * 所以旧代码的 `if (code != 0) throw` 从不触发，而是静默走到 `data == null → 空列表`。
     * 本用例钉住「data 为 null 时返回空列表且不抛」，即用户看到空态而非崩溃。
     */
    @Test
    fun parseSearchResponse_nullDataReturnsEmptyWithoutThrowing() {
        val rcmdStyle = JSONObject("""{"code":0,"message":"OK","ttl":1,"data":null}""")
        assertTrue(
            "data=null（废弃端点 rcmd 的返回）必须是空列表，不能抛异常",
            TopicApi.parseSearchResponse(rcmdStyle).isEmpty()
        )
    }

    @Test
    fun parseSearchResponse_parsesPubSearchShape() {
        // 真实抓包结构：pub/search 只有 view/discuss，**没有 dynamics**，另有 stat_desc/description
        val json = JSONObject(
            """
            {"code":0,"message":"OK","ttl":1,"data":{"topic_items":[
              {"id":1269670,"name":"小剧场过大年","view":2247738435,"discuss":2204644,
               "stat_desc":"22.5亿浏览·220.5万讨论","description":"2025蛇来运转！","show_interact_data":false},
              {"id":61457,"name":"原神原神原神","view":296659346,"discuss":100}
            ],"page_info":{"offset":20,"has_more":true}}}
            """.trimIndent()
        )

        val topics = TopicApi.parseSearchResponse(json)

        assertEquals(2, topics.size)
        assertEquals(1269670L, topics[0].id)
        assertEquals("小剧场过大年", topics[0].name)
        assertEquals(2247738435L, topics[0].view)
        assertEquals(2204644L, topics[0].discuss)
        // 该端点不返回 dynamics —— 解析必须容忍缺失并取默认值 0，
        // 否则 TopicAdapter 会显示 "0 动态"（已改为展示讨论数）
        assertEquals(0L, topics[0].dynamics)
    }

    @Test
    fun parseSearchResponse_nonZeroCodeReturnsEmpty() {
        // -101 账号未登录：走空态即可，不抛异常（loadPlaza 依赖「返回空列表 = 没数据」语义）
        assertTrue(TopicApi.parseSearchResponse(JSONObject("""{"code":-101,"message":"账号未登录"}""")).isEmpty())
        assertTrue(TopicApi.parseSearchResponse(null).isEmpty())
    }

    @Test
    fun parseSearchResponse_skipsItemsWithoutId() {
        val json = JSONObject(
            """{"code":0,"data":{"topic_items":[{"name":"没有id"},{"id":9,"name":"有id"}]}}"""
        )
        val topics = TopicApi.parseSearchResponse(json)
        assertEquals(1, topics.size)
        assertEquals(9L, topics[0].id)
    }
}
