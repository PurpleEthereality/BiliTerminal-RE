package com.RobinNotBad.BiliClient.model;

/**
 * 话题（推荐话题广场与话题详情共用）。
 *
 * 字段来自 {@code api.bilibili.com/x/topic/pub/search} 的 {@code data.topic_items[]}
 * （26.10.05 起广场改用它；此前是已废弃的 {@code app.bilibili.com/x/topic/web/dynamic/rcmd}），
 * 解析见 {@link com.RobinNotBad.BiliClient.api.TopicApi#parseTopic(org.json.JSONObject)}。
 *
 * 注意 {@code pub/search} 不下发 dynamics 字段，所以广场列表里该字段恒为 0；
 * 展示文案请用 {@code discuss}，见
 * {@link com.RobinNotBad.BiliClient.adapter.dynamic.TopicAdapter}。
 */
public class Topic {
    public long id;
    public String name = "";
    /** 讨论数（{@code pub/search} 会下发） */
    public long discuss;
    /** 动态数（{@code pub/search} **不下发**，广场场景恒为 0） */
    public long dynamics;
    /** 浏览数 */
    public long view;
}
