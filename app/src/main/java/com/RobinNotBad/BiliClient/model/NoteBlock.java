package com.RobinNotBad.BiliClient.model;

/**
 * 笔记正文的一个块（Quill delta 元素，26.10.04 批次 6 的 C27）。
 *
 * <p>接口依据：{@code bilibili-API/docs/note/readme.md:19-75}。根数组元素形如
 * {@code {"insert": "文本", "attributes": {...}}}；{@code insert} 换成对象时是跳转标签或图片。
 * {@code imageUpload.width} 是**图片宽度 - 2**（服务端就是这么存的），渲染时别当真实宽度用。
 */
public class NoteBlock {

    /** 普通文本（可能带 {@code attributes} 样式）。 */
    public static final String TYPE_TEXT = "text";
    /** 图片，只有 {@link #imageUrl}/{@link #imageWidth} 有意义。 */
    public static final String TYPE_IMAGE = "image";
    /** 跳转标签，指向稿件的某个分P与进度。 */
    public static final String TYPE_TAG = "tag";

    public String type = TYPE_TEXT;

    public String text = "";

    public boolean bold;
    public boolean underline;
    public boolean strike;

    /** 文字颜色，形如 {@code #ff6699}；空串表示没设。 */
    public String color = "";
    /** 背景色（高亮），形如 {@code #fff359}；空串表示没设。 */
    public String background = "";
    /** 列表属性：{@code ordered} 有序列表 / {@code bullet} 无序列表 / 空串 普通段落。 */
    public String list = "";

    public String imageUrl = "";
    /** 服务端给的是「图片宽度 - 2」。 */
    public int imageWidth;

    public long tagCid;
    /** 跳转到的分P索引（0 起）。 */
    public int tagIndex = -1;
    /** 跳转到的视频进度（秒）。 */
    public long tagSeconds;
    public String tagTitle = "";
}
