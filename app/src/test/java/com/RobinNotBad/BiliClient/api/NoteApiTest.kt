package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.NoteBlock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 笔记解析的纯函数单测（26.10.04 批次 6 的 C27）。
 *
 * 网络请求不在这里测：`getNoteIdsOfVideo` / `getNoteInfo` 需要真 Cookie，属于真机清单。
 */
class NoteApiTest {

    @Test
    fun parseNoteIds_readsStringIdsAndSkipsBlanks() {
        val data = JSONObject().put("noteIds", JSONArray().put("3809605586518023").put("").put("2"))
        assertEquals(listOf("3809605586518023", "2"), NoteApi.parseNoteIds(data))
    }

    @Test
    fun parseNoteIds_toleratesMissingFieldAndNullData() {
        assertTrue(NoteApi.parseNoteIds(null).isEmpty())
        assertTrue(NoteApi.parseNoteIds(JSONObject()).isEmpty())
    }

    @Test
    fun pickNoteId_prefersTheStringForm() {
        // 快照示例：note_id 与 note_id_str 最后几位不一样，17 位 id 走 JSON number 已经丢精度
        val json = JSONObject()
            .put("note_id", 24508729145690110L)
            .put("note_id_str", "24508729145690112")
        assertEquals("24508729145690112", NoteApi.pickNoteId(json))
    }

    @Test
    fun pickNoteId_fallsBackToTheNumberField() {
        assertEquals("123", NoteApi.pickNoteId(JSONObject().put("note_id", 123)))
        assertEquals("", NoteApi.pickNoteId(null))
        assertEquals("", NoteApi.pickNoteId(JSONObject()))
    }

    @Test
    fun parseBlocks_readsTextAndAttributes() {
        val content = "[" +
                "{\"attributes\":{\"size\":\"24px\",\"bold\":true},\"insert\":\"关掉\"}," +
                "{\"insert\":\"，\"}," +
                "{\"attributes\":{\"background\":\"#fff359\"},\"insert\":\"一定要\"}," +
                "{\"insert\":\"\\n再不关掉那些\"}," +
                "{\"attributes\":{\"underline\":true,\"strike\":true,\"color\":\"#ff6699\",\"list\":\"bullet\"},\"insert\":\"网络游戏\"}" +
                "]"
        val blocks = NoteApi.parseBlocks(content)
        assertEquals(5, blocks.size)

        assertEquals("关掉", blocks[0].text)
        assertTrue(blocks[0].bold)
        assertEquals(NoteBlock.TYPE_TEXT, blocks[0].type)

        assertEquals("，", blocks[1].text)
        assertFalse(blocks[1].bold)
        assertEquals("", blocks[1].background)

        assertEquals("#fff359", blocks[2].background)
        assertEquals("\n再不关掉那些", blocks[3].text)
        assertTrue(blocks[4].underline)
        assertTrue(blocks[4].strike)
        assertEquals("#ff6699", blocks[4].color)
        assertEquals("bullet", blocks[4].list)
    }

    @Test
    fun parseBlocks_readsTagAndImageInserts() {
        val content = "[" +
                "{\"insert\":{\"tag\":{\"cid\":11,\"status\":0,\"index\":2,\"seconds\":125,\"cidCount\":3,\"key\":\"k\",\"title\":\"output\"}}}," +
                "{\"insert\":{\"imageUpload\":{\"url\":\"https://i0.hdslb.com/a.jpg\",\"status\":\"done\",\"width\":600}}}," +
                "{\"insert\":{\"unknown\":{}}}" +
                "]"
        val blocks = NoteApi.parseBlocks(content)
        assertEquals(2, blocks.size)

        assertEquals(NoteBlock.TYPE_TAG, blocks[0].type)
        assertEquals(11L, blocks[0].tagCid)
        assertEquals(2, blocks[0].tagIndex)
        assertEquals(125L, blocks[0].tagSeconds)

        assertEquals(NoteBlock.TYPE_IMAGE, blocks[1].type)
        assertEquals("https://i0.hdslb.com/a.jpg", blocks[1].imageUrl)
        // 服务端给的是「图片宽度 - 2」，这里原样保留
        assertEquals(600, blocks[1].imageWidth)
    }

    @Test
    fun parseBlocks_keepsEmptyTextOnlyWhenItIsAListItem() {
        val content = "[{\"insert\":\"\"},{\"insert\":\"\",\"attributes\":{\"list\":\"bullet\"}}]"
        val blocks = NoteApi.parseBlocks(content)
        assertEquals(1, blocks.size)
        assertEquals("bullet", blocks[0].list)
    }

    @Test
    fun parseBlocks_toleratesBrokenInput() {
        assertTrue(NoteApi.parseBlocks(null).isEmpty())
        assertTrue(NoteApi.parseBlocks("").isEmpty())
        assertTrue(NoteApi.parseBlocks("不是 json").isEmpty())
        // 根是对象不是数组，也当空
        assertTrue(NoteApi.parseBlocks("{\"insert\":\"x\"}").isEmpty())
    }

    @Test
    fun parseNoteDetail_readsArcAndContent() {
        val data = JSONObject()
            .put("title", "标题")
            .put("summary", "预览")
            .put("content", "[{\"insert\":\"正文\"}]")
            .put(
                "arc",
                JSONObject()
                    .put("oid", 970322090L)
                    .put("bvid", "BV1xx")
                    .put("title", "视频标题")
                    .put("desc", "视频简介")
            )
        val note = NoteApi.parseNoteDetail(data)
        assertEquals("标题", note.title)
        assertEquals("预览", note.summary)
        assertEquals(970322090L, note.aid)
        assertEquals("BV1xx", note.bvid)
        assertEquals("视频标题", note.videoTitle)
        assertEquals("视频简介", note.videoDesc)
        assertEquals(1, note.blocks.size)
        assertEquals("正文", note.blocks[0].text)
    }

    @Test
    fun parseNoteDetail_toleratesNull() {
        val note = NoteApi.parseNoteDetail(null)
        assertEquals("", note.title)
        assertEquals(0L, note.aid)
        assertTrue(note.blocks.isEmpty())
    }

    @Test
    fun formatTagSeconds_padsMinutesAndHours() {
        assertEquals("00:00", NoteApi.formatTagSeconds(0))
        assertEquals("00:05", NoteApi.formatTagSeconds(5))
        assertEquals("01:05", NoteApi.formatTagSeconds(65))
        assertEquals("59:59", NoteApi.formatTagSeconds(3599))
        assertEquals("1:00:00", NoteApi.formatTagSeconds(3600))
        assertEquals("1:02:05", NoteApi.formatTagSeconds(3725))
        // 负数按 0 处理，别拼出 "-1:59"
        assertEquals("00:00", NoteApi.formatTagSeconds(-1))
    }

    @Test
    fun noteErrorMsg_mapsKnownCodes() {
        assertEquals("", NoteApi.noteErrorMsg(0))
        assertTrue(NoteApi.noteErrorMsg(-101).contains("登录"))
        assertTrue(NoteApi.noteErrorMsg(79502).contains("笔记"))
        assertTrue(NoteApi.noteErrorMsg(79503).contains("正文"))
        assertTrue(NoteApi.noteErrorMsg(12345).contains("12345"))
    }
}
