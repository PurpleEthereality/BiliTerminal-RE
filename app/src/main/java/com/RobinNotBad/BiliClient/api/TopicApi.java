package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.Dynamic;
import com.RobinNotBad.BiliClient.model.Topic;
import com.RobinNotBad.BiliClient.util.DmImgParamUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 话题相关接口（26.10.04 批次 6 的 C10）。
 *
 * 接口依据（仓库自带快照）：
 * - 推荐话题（广场）：{@code GET https://api.bilibili.com/x/topic/pub/search}
 *   —— **{@code keywords} 留空即按热度返回整页话题**，{@code data.topic_items[]}。
 *   26.10.05 由已废弃的 {@code app.bilibili.com/x/topic/web/dynamic/rcmd} 换过来
 *   （后者恒返回 {@code data:null}，见 {@link #getRecommendedTopics()} 的注释）。
 * - 话题下动态列表：{@code GET https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/topic}
 *   —— {@code bilibili-API/docs/dynamic/topic.md:3-54}，每项是
 *   {@code {dynamic_card_item, topic_type}} 套壳，真正的动态在 {@code dynamic_card_item} 里，
 *   结构与全站动态列表完全一致，故直接复用 {@link DynamicApi#analyzeDynamic(JSONObject)}。
 *
 * 话题 id 无法从动态正文反推（RICH_TEXT_NODE_TYPE_TOPIC 只带跳转搜索页的 jump_url），
 * 所以「话题广场」入口是必需的，不能只做“从动态点进话题”。
 */
public class TopicApi {

    /** 动态列表需要的功能模块（与全站动态列表同源，缺了会没有大图/投票等模块）。 */
    private static final String DYNAMIC_FEATURES =
            "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote,decorationCard";

    private TopicApi() {
    }

    /**
     * 解析推荐话题列表里的单个话题。纯函数，便于单测；缺字段一律取默认值。
     */
    public static Topic parseTopic(JSONObject json) {
        Topic topic = new Topic();
        if (json == null) return topic;
        topic.id = json.optLong("id", 0);
        topic.name = json.optString("name", "");
        topic.discuss = json.optLong("discuss", 0);
        topic.dynamics = json.optLong("dynamics", 0);
        topic.view = json.optLong("view", 0);
        return topic;
    }

    /**
     * 解析推荐话题数组。纯函数，便于单测；{@code null}、非对象项都跳过。
     */
    public static List<Topic> parseTopics(JSONArray items) {
        List<Topic> topics = new ArrayList<>();
        if (items == null) return topics;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            Topic topic = parseTopic(item);
            if (topic.id == 0) continue;
            topics.add(topic);
        }
        return topics;
    }

    /** 推荐话题（广场）。空列表代表服务端暂时没给，不抛异常。 */
    public static List<Topic> getRecommendedTopics() throws IOException, JSONException {
        // 26.10.05 修正：原实现打的是 /x/topic/web/dynamic/rcmd，实测该端点
        // **恒返回 {"code":0,"message":"OK","data":null}**（带不带参数、带不带游客 Cookie 都一样），
        // 即接口已废弃、不再下发数据。由于 code 仍是 0，旧的 `if (code != 0) throw` 从不触发，
        // 代码会走到 `data == null → 返回空列表`，页面只 showEmptyView()——
        // 用户看到的就是「话题广场加载不出来」（静默空，既不报错也无内容）。
        //
        // 换成实测可用的 /x/topic/pub/search：**keywords 留空**时服务端按热度返回整页话题
        // （实测稳定 20 条，首条为 22 亿浏览的大话题），且支持 page_info.offset 翻页。
        // 这样广场不依赖任何硬编码词表，内容随服务端热度自然更新。
        // PiliPlus 亦已改用同名端点（lib/http/api.dart:866 topicPubSearch）。
        return searchTopics("", 20);
    }

    /**
     * 话题搜索（同时充当广场数据源）。{@code keywords} 留空即"按热度取一页话题"。
     *
     * 纯解析拆到 {@link #parseSearchResponse(JSONObject)} 以便 JVM 单测。
     *
     * @param keywords 关键词，空串表示不筛（广场模式）
     * @param pageSize 每页数量，服务端上限 20
     */
    public static List<Topic> searchTopics(String keywords, int pageSize) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/topic/pub/search"
                + "?keywords=" + URLEncoder.encode(keywords == null ? "" : keywords, "UTF-8")
                + "&page_size=" + pageSize
                + "&page_num=1"
                + "&web_location=333.1365";
        JSONObject all = NetWorkUtil.getJson(url);
        return parseSearchResponse(all);
    }

    /**
     * 解析 {@code /x/topic/pub/search} 的响应。纯函数，便于单测。
     *
     * 与 {@code rcmd} 的差别：这里**没有 {@code dynamics} 字段**（只有 view/discuss），
     * 另有 {@code stat_desc} 现成文案。缺字段一律取默认值，不抛异常。
     * {@code code != 0}（如 -101 未登录）同样返回空列表，由调用方走空态。
     */
    public static List<Topic> parseSearchResponse(JSONObject all) {
        if (all == null || all.optInt("code", -1) != 0) return new ArrayList<>();
        JSONObject data = all.optJSONObject("data");
        if (data == null) return new ArrayList<>();
        return parseTopics(data.optJSONArray("topic_items"));
    }

    /**
     * 拉取话题下的动态列表，追加进 [dynamicList]。
     *
     * @param offset 上一页返回的偏移量；传空串表示第一页
     * @return 下一页的偏移量，空串表示没有更多了
     */
    public static String getTopicDynamicList(List<Dynamic> dynamicList, long topicId, String offset)
            throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/topic"
                + "?topic_id=" + topicId
                + "&page_size=20"
                + "&source=Web"
                + "&features=" + DYNAMIC_FEATURES
                + ((offset == null || offset.isEmpty()) ? "" : ("&offset=" + offset));

        // 风控对抗：与全站动态列表一致，补 dm_img 参数后做 WBI 签名
        String signedUrl = ConfInfoApi.signWBI(DmImgParamUtil.getDmImgParamsUrl(url));
        JSONObject all = NetWorkUtil.getJson(signedUrl);
        if (all.getInt("code") != 0) throw new JSONException(all.optString("message"));

        JSONObject cardList = all.optJSONObject("data") == null
                ? null : all.getJSONObject("data").optJSONObject("topic_card_list");
        if (cardList == null) return "";

        JSONArray items = cardList.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject card = items.optJSONObject(i);
                if (card == null) continue;
                JSONObject item = card.optJSONObject("dynamic_card_item");
                if (item == null) continue;
                dynamicList.add(DynamicApi.analyzeDynamic(item));
            }
        }

        boolean hasMore = cardList.optBoolean("has_more", false);
        if (!hasMore) return "";
        return cardList.optString("offset", "");
    }
}
