package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.Note;
import com.RobinNotBad.BiliClient.model.NoteBlock;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频笔记（26.10.04 批次 6 的 C27，仅查看）。
 *
 * <p>接口依据（仓库自带快照）：
 * <ul>
 *   <li>稿件私有笔记 id 列表：{@code GET https://api.bilibili.com/x/note/list/archive}
 *       —— {@code bilibili-API/docs/note/list.md:3-71}。一个稿件只有一篇私有笔记，
 *       返回 {@code data.noteIds[]}（字符串数组，无笔记则没有这个字段）。</li>
 *   <li>私有笔记内容：{@code GET https://api.bilibili.com/x/note/info}
 *       —— {@code bilibili-API/docs/note/info.md:57-172}，需要 SESSDATA，
 *       错误码 79502 笔记详情未找到 / 79503 笔记正文未找到。</li>
 * </ul>
 *
 * <p>正文不是 HTML，是 Quill delta 序列的 JSON 字符串（{@code data.content}），
 * 结构见 {@code bilibili-API/docs/note/readme.md:19-75}，故本类自带
 * {@link #parseBlocks(String)}，**不能**复用 opus 的段落解析（opus 是嵌套结构）。
 */
public class NoteApi {

    /** oid_type：0 = 视频，此时 oid 就是 avid（快照只支持这一种）。 */
    private static final int OID_TYPE_VIDEO = 0;

    private NoteApi() {
    }

    // ---------------------------------------------------------------- 纯解析

    /**
     * 从 id 列表响应里取笔记 id。纯函数，便于单测；无笔记（没有 {@code noteIds}）返回空表。
     */
    public static List<String> parseNoteIds(JSONObject data) {
        List<String> ids = new ArrayList<>();
        if (data == null) return ids;
        JSONArray array = data.optJSONArray("noteIds");
        if (array == null) return ids;
        for (int i = 0; i < array.length(); i++) {
            String id = array.optString(i, "");
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    /**
     * 取笔记 id：{@code note_id_str} 优先。
     *
     * <p>快照示例里 {@code "note_id": 24508729145690110} 与 {@code "note_id_str": "24508729145690112"}
     * 最后几位不一致——17 位 id 超过 2^53，JSON number 已经丢精度，只有字符串可信。
     * 纯函数，便于单测。
     */
    public static String pickNoteId(JSONObject json) {
        if (json == null) return "";
        String str = json.optString("note_id_str", "");
        if (!str.isEmpty()) return str;
        return json.optString("note_id", "");
    }

    /**
     * 解析笔记正文（Quill delta 序列的 JSON 字符串）。纯函数，便于单测。
     *
     * <p>空串、坏 JSON、非数组一律返回空表，不抛异常：正文解析失败不该让整页崩掉。
     */
    public static List<NoteBlock> parseBlocks(String contentJson) {
        List<NoteBlock> blocks = new ArrayList<>();
        if (contentJson == null || contentJson.isEmpty()) return blocks;

        JSONArray array;
        try {
            array = new JSONArray(contentJson);
        } catch (JSONException e) {
            return blocks;
        }

        for (int i = 0; i < array.length(); i++) {
            JSONObject node = array.optJSONObject(i);
            if (node == null) continue;

            Object insert = node.opt("insert");
            if (insert instanceof JSONObject) {
                NoteBlock block = parseInsertObject((JSONObject) insert);
                if (block != null) blocks.add(block);
                continue;
            }
            if (!(insert instanceof String)) continue;

            NoteBlock block = new NoteBlock();
            block.text = (String) insert;
            JSONObject attributes = node.optJSONObject("attributes");
            if (attributes != null) {
                block.bold = attributes.optBoolean("bold", false);
                block.underline = attributes.optBoolean("underline", false);
                block.strike = attributes.optBoolean("strike", false);
                block.color = attributes.optString("color", "");
                block.background = attributes.optString("background", "");
                block.list = attributes.optString("list", "");
            }
            // 空文本块只在带列表属性时有意义（有序/无序列表的换行项）
            if (block.text.isEmpty() && block.list.isEmpty()) continue;
            blocks.add(block);
        }
        return blocks;
    }

    /**
     * {@code insert} 为对象时只可能是跳转标签或图片（快照 readme.md:49-54）。
     * 都不是就返回 null（这种元素没法渲染）。
     */
    private static NoteBlock parseInsertObject(JSONObject insert) {
        JSONObject image = insert.optJSONObject("imageUpload");
        if (image != null) {
            NoteBlock block = new NoteBlock();
            block.type = NoteBlock.TYPE_IMAGE;
            block.imageUrl = image.optString("url", "");
            block.imageWidth = image.optInt("width", 0);
            return block.imageUrl.isEmpty() ? null : block;
        }

        JSONObject tag = insert.optJSONObject("tag");
        if (tag != null) {
            NoteBlock block = new NoteBlock();
            block.type = NoteBlock.TYPE_TAG;
            block.tagCid = tag.optLong("cid", 0);
            block.tagIndex = tag.optInt("index", -1);
            block.tagSeconds = tag.optLong("seconds", 0);
            block.tagTitle = tag.optString("title", "");
            return block;
        }
        return null;
    }

    /**
     * 解析笔记详情。纯函数，便于单测；{@code null} 返回空对象，缺字段取默认值。
     */
    public static Note parseNoteDetail(JSONObject data) {
        Note note = new Note();
        if (data == null) return note;
        note.noteId = pickNoteId(data);
        note.title = data.optString("title", "");
        note.summary = data.optString("summary", "");
        note.blocks = parseBlocks(data.optString("content", ""));

        JSONObject arc = data.optJSONObject("arc");
        if (arc != null) {
            note.aid = arc.optLong("oid", 0);
            note.bvid = arc.optString("bvid", "");
            note.videoTitle = arc.optString("title", "");
            note.videoDesc = arc.optString("desc", "");
        }
        return note;
    }

    /**
     * 把跳转标签的秒数格式化成 {@code mm:ss}（超过一小时是 {@code h:mm:ss}）。
     * 纯函数，便于单测。
     */
    public static String formatTagSeconds(long seconds) {
        long safe = Math.max(seconds, 0);
        long hours = safe / 3600;
        long minutes = (safe % 3600) / 60;
        long secs = safe % 60;
        if (hours > 0) return String.format("%d:%02d:%02d", hours, minutes, secs);
        return String.format("%02d:%02d", minutes, secs);
    }

    /**
     * 笔记接口错误码 → 中文提示。纯函数，便于单测；0（成功）返回空串。
     */
    public static String noteErrorMsg(int code) {
        switch (code) {
            case 0:
                return "";
            case -101:
                return "还没有登录喵~";
            case -400:
                return "请求出错了，请稍后再试";
            case 79502:
                return "没有找到这篇笔记";
            case 79503:
                return "这篇笔记还没有正文";
            case 79514:
                return "没有找到这篇公开笔记";
            default:
                return "获取笔记失败（错误码 " + code + "）";
        }
    }

    // ---------------------------------------------------------------- 网络请求

    /** 取稿件私有笔记的 id 列表；没有笔记返回空表。 */
    public static List<String> getNoteIdsOfVideo(long aid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/note/list/archive"
                + "?oid=" + aid
                + "&oid_type=" + OID_TYPE_VIDEO
                + "&csrf=" + NetWorkUtil.currentCsrf();
        JSONObject all = NetWorkUtil.getJson(url);
        int code = all.optInt("code", -1);
        if (code != 0) throw new JSONException(errorText(all, code));
        return parseNoteIds(all.optJSONObject("data"));
    }

    /**
     * 取私有笔记内容。{@code noteId} 用字符串传：见 {@link #pickNoteId(JSONObject)} 的精度说明。
     */
    public static Note getNoteInfo(long aid, String noteId) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/note/info"
                + "?oid=" + aid
                + "&oid_type=" + OID_TYPE_VIDEO
                + "&note_id=" + noteId;
        JSONObject all = NetWorkUtil.getJson(url);
        int code = all.optInt("code", -1);
        if (code != 0) throw new JSONException(errorText(all, code));
        return parseNoteDetail(all.optJSONObject("data"));
    }

    /** 错误提示：服务端 message 优先，没有就用错误码文案。 */
    private static String errorText(JSONObject all, int code) {
        String message = all.optString("message", "");
        if (message.isEmpty() || "0".equals(message)) return noteErrorMsg(code);
        return message;
    }
}
