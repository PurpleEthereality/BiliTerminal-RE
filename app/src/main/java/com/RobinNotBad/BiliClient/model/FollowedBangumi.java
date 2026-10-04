package com.RobinNotBad.BiliClient.model;

/**
 * C16：追番列表里的一项，只保留"判断是否有更新"需要的字段。
 *
 * 更新判据是 `newEpId`（服务端 `new_ep.id`，最新一集的剧集 id）在两次检查之间发生变化——
 * 比"总集数"可靠（总集数对电影/特别篇不敏感），也比服务端的 `is_new` 可靠（那个标记
 * 会被用户在别的客户端看过之后清掉，与本地的"我看到哪了"无关）。
 */
public class FollowedBangumi {
    /** 番剧 media_id，作为快照的键。 */
    public long mediaId = 0;
    public String title = "";
    /** `new_ep.id`，最新一集的剧集 id；未开播/无新集时为 0。 */
    public long newEpId = 0;
    /** `new_ep.index_show`，如"更新至第3话"，仅用于提示文案。 */
    public String newEpIndexShow = "";
}
