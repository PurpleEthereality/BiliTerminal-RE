package com.RobinNotBad.BiliClient.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高能进度条（pbp）响应的解析测试。
 *
 * 背景：接口在 2026-10 前后把数据从"摊在根上"改成了包在
 * `modules[].params.data` 里。原来只认扁平结构的实现不会报错，
 * 只是 events 解析成空数组、进度条静默变成一条直线——最难查的那种回归。
 * 所以这两代结构、以及"新外壳但里面是空的、数据仍留在根上"的中间态，
 * 都得有断言钉住。
 */
class PlayerApiPbpTest {

    @Test
    fun parse_legacyFlatRoot_parsesEvents() {
        val json = """{"step_sec":3,"tagstr":"pbphide_0","debug":"{}",
            "events":{"default":[0,8853,8011,8043.5]}}"""

        val data = PlayerApi.parseHighEnergyData(JSONObject(json))

        assertNotNull("扁平旧结构必须仍能解析", data)
        assertTrue("有 events 即视为有效数据", data!!.hasValidData())
        assertEquals("采样间隔", 3, data.stepSec)
        assertEquals("tagstr", "pbphide_0", data.tagStr)
        assertEquals("顶点个数", 4, data.events.size)
        assertEquals("第1个顶点", 0f, data.events[0], 0.001f)
        assertEquals("第2个顶点", 8853f, data.events[1], 0.001f)
        assertEquals("顶点含小数", 8043.5f, data.events[3], 0.001f)
    }

    @Test
    fun parse_modulesEnvelope_parsesEvents() {
        val json = """{"modules":[{"params":{"data":{
            "step_sec":5,"tagstr":"t","debug":"d","events":{"default":[1,2,3]}}}}]}"""

        val data = PlayerApi.parseHighEnergyData(JSONObject(json))

        assertNotNull("新外壳必须能解析", data)
        assertEquals("采样间隔应取外壳里的", 5, data!!.stepSec)
        assertEquals("tagstr 应取外壳里的", "t", data.tagStr)
        assertEquals("debug 应取外壳里的", "d", data.debug)
        assertEquals("顶点个数", 3, data.events.size)
        assertEquals("第3个顶点", 3f, data.events[2], 0.001f)
    }

    @Test
    fun parse_dataWrappedModules_parsesEvents() {
        // 有些链路外面还套着 {code,message,data}，modules 在 data 里
        val json = """{"code":0,"message":"0","data":{"modules":[
            {"params":{"data":{"step_sec":2,"events":{"default":[7,8]}}}}]}}"""

        val data = PlayerApi.parseHighEnergyData(JSONObject(json))

        assertNotNull("data 里套 modules 也要能解析", data)
        assertEquals(2, data!!.stepSec)
        assertEquals(2, data.events.size)
        assertEquals(7f, data.events[0], 0.001f)
    }

    @Test
    fun parse_modulesPresentButEmpty_keepsLookingOnRoot() {
        // "结构猜错就画空线"的中间态：外壳在、但数据仍留在根上
        val json = """{"modules":[],"step_sec":9,"events":{"default":[4,5,6]}}"""

        val data = PlayerApi.parseHighEnergyData(JSONObject(json))

        assertNotNull(data)
        assertTrue("空的 modules 不能把根上的数据挡掉", data!!.hasValidData())
        assertEquals("采样间隔应取根上的", 9, data.stepSec)
        assertEquals(3, data.events.size)
    }

    @Test
    fun parse_noEventsAtAll_returnsEmptyButKeepsMeta() {
        val json = """{"step_sec":12,"tagstr":"only","debug":"x","events":{}}"""

        val data = PlayerApi.parseHighEnergyData(JSONObject(json))

        assertNotNull("有响应就不该返回 null，交给调用方按 hasValidData 判断", data)
        assertFalse("没有 default 数组即无有效数据", data!!.hasValidData())
        assertEquals("空数组而不是 null，避免调用方 NPE", 0, data.events.size)
        assertEquals("元信息仍应保留，便于排查", 12, data.stepSec)
        assertEquals("only", data.tagStr)
    }

    @Test
    fun parse_stepSecMissing_defaultsToTen() {
        val json = """{"events":{"default":[1]}}"""

        val data = PlayerApi.parseHighEnergyData(JSONObject(json))

        assertEquals("采样间隔缺失时兜底10秒", 10, data!!.stepSec)
    }

    @Test
    fun parse_errorCode_returnsNull() {
        val json = """{"code":-10403,"message":"风控校验失败"}"""

        assertNull("错误码必须返回 null，而不是一条空曲线", PlayerApi.parseHighEnergyData(JSONObject(json)))
    }

    @Test
    fun parse_nullResponse_returnsNull() {
        assertNull("空响应返回 null", PlayerApi.parseHighEnergyData(null))
    }

    @Test
    fun buildReferer_bvidWinsThenAvThenSiteRoot() {
        assertEquals(
            "有 bvid 时用视频页",
            "https://www.bilibili.com/video/BV1xx411c7mD",
            PlayerApi.buildPbpReferer("BV1xx411c7mD", 123L)
        )
        assertEquals(
            "没 bvid 时退回 av 号视频页，仍不是站点根",
            "https://www.bilibili.com/video/av123",
            PlayerApi.buildPbpReferer("", 123L)
        )
        assertEquals(
            "两者都没有才退回站点根",
            "https://www.bilibili.com/",
            PlayerApi.buildPbpReferer("", 0L)
        )
        assertEquals(
            "空白 bvid 视同没有",
            "https://www.bilibili.com/video/av9",
            PlayerApi.buildPbpReferer("", 9L)
        )
    }
}
