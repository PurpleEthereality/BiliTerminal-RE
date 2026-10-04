package com.RobinNotBad.BiliClient.model;

/**
 * 话题（推荐话题广场与话题详情共用）。
 *
 * 字段来自 {@code app.bilibili.com/x/topic/web/dynamic/rcmd} 的 {@code data.topic_items[]}，
 * 解析见 {@link com.RobinNotBad.BiliClient.api.TopicApi#parseTopic(org.json.JSONObject)}。
 */
public class Topic {
    public long id;
    public String name = "";
    /** 讨论数 */
    public long discuss;
    /** 动态数 */
    public long dynamics;
    /** 浏览数 */
    public long view;
}
