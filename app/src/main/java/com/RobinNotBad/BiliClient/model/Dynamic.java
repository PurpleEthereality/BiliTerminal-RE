package com.RobinNotBad.BiliClient.model;

import java.io.Serializable;

public class Dynamic implements Serializable {
    public final static String DYNAMIC_TYPE_UGC_SEASON = "DYNAMIC_TYPE_UGC_SEASON";
    public long dynamicId;
    public String type;
    public long comment_id;
    public int comment_type;

    public String title;
    public UserInfo userInfo;
    public CharSequence content;
    public String pubTime;

    public Stats stats;

    public String major_type;
    public Object major_object;
    public Dynamic dynamic_forward;
    public boolean canDelete;
    public boolean canEdit;  // 是否可编辑（服务端三点菜单里有 THREE_POINT_EDIT 才行）
    public boolean isTop;  // 是否置顶

    // 投票相关
    public String additional_type;   // 附加卡片类型，如 "ADDITIONAL_TYPE_VOTE"
    public VoteInfo vote;            // 投票卡片信息

    public Dynamic() {
    }

    /**
     * 补全「任何消费者都会直接取用」的字段，把 null 换成空对象。
     *
     * <p>为什么需要：{@code DynamicApi.analyzeDynamic} 在动态类型为 {@code DYNAMIC_TYPE_NONE}
     * （动态已被删除 / 被屏蔽）时会在填充 {@code modules.module_stat} 之前提前 return，此时
     * {@code stats} 仍是 null；另外部分动态类型本身就没有 {@code module_stat}。渲染卡片时
     * {@code DynamicHolder} 会自己判空，但详情页直接取 {@code dynamic.stats.reply} 就会 NPE
     * （26.10.05 线上崩溃：{@code DynamicInfoActivity.onCreate$lambda$3$lambda$1}）。
     *
     * <p>{@code userInfo} 目前由 {@code analyzeDynamic} 无条件赋值，一并纳入是为了让详情页不必
     * 关心「哪个字段会被提前 return 跳过」这件事。
     *
     * <p>已有值不会被覆盖，只补 null，因此可以重复调用。
     */
    public void ensureDetailFields() {
        if (stats == null) stats = new Stats();
        if (userInfo == null) userInfo = new UserInfo();
    }

}
