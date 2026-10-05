package com.RobinNotBad.BiliClient.model;

/**
 * 动态正文里的一个话题节点（{@code RICH_TEXT_NODE_TYPE_TOPIC}）。
 *
 * 为什么不复用 {@link android.util.Pair}：`android.util.Pair` 属于**没有测试实现的 Android 桩**，
 * 在 JVM 单测里 `new Pair<>(a, b)` 返回的对象其 `first`/`second` 恒为 {@code null}
 * （app/build.gradle 只给 `org.json` 补了真实实现，没给 android.util 补）。
 * 直接返回它会得到「列表长度对、内容全 null」这种极难排查的假通过。
 * 本类是**真实类**，单测里字段有真值，也用不上泛型解包，可读性更好。
 *
 * 解析见 {@link com.RobinNotBad.BiliClient.api.DynamicApi#parseTopicNodes(org.json.JSONArray)}。
 */
public class TopicNode {
    /** 话题显示文本，形如 {@code #话题名#} */
    public String text = "";
    /** 话题跳转链接，形如 {@code https://m.bilibili.com/topic-detail?topic_id=1305890}；服务端可能不下发，此时为空串 */
    public String jumpUrl = "";

    public TopicNode() {
    }

    public TopicNode(String text, String jumpUrl) {
        this.text = text == null ? "" : text;
        this.jumpUrl = jumpUrl == null ? "" : jumpUrl;
    }

    /** 从 jumpUrl 解析出的 topic_id；解析不出返回 0（见 LinkUrlUtil.parseTopicId）。 */
    public long getTopicId() {
        return com.RobinNotBad.BiliClient.util.LinkUrlUtil.parseTopicId(jumpUrl);
    }

    /** 无 jump_url 的话题仍是话题（染主色展示），只是不可点击。 */
    public boolean isClickable() {
        return getTopicId() > 0;
    }
}
