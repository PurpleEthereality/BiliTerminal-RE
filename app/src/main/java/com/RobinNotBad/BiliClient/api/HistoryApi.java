package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ApiResult;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

import okhttp3.Response;

public class HistoryApi {

    //观看记录里可能混有直播/专栏等其它业务，这两类之外的一律不展示，保持与旧实现(type=archive)一致的可见范围
    private static final String BUSINESS_ARCHIVE = "archive";
    private static final String BUSINESS_PGC = "pgc";
    //观看记录翻页定位时最多翻几页，避免脏游标导致死循环
    private static final int LOCATE_MAX_PAGES = 5;

    /**
     * 上传历史记录
     *
     * @param aid      视频aid
     * @param cid      分集cid
     * @param progress 观看进度，单位为s
     * @throws IOException
     */
    public static void reportHistory(long aid, long cid, long progress) throws IOException {
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PRIVACY_MODE, false)) return;
        String url = "https://api.bilibili.com/x/v2/history/report";
        //csrf 必须取"当前"值：见 currentCsrf() 的说明，读本地快照会因 bili_jct 轮换而静默失败
        String csrf = currentCsrf();
        if (csrf.isEmpty()) Logu.e("history-report", "csrf 为空，上报必被服务端拒绝(-111)，请确认已登录");
        String per = "aid=" + aid + "&cid=" + cid
                + "&progress=" + (progress >= 0 ? progress : "")
                + "&platform=pc"
                + "&csrf=" + csrf;
        Response response = NetWorkUtil.post(url, per, NetWorkUtil.webHeaders);
        logReportResult("reportHistory", response, "aid=" + aid + " cid=" + cid + " progress=" + progress);
    }

    //番剧(PGC)的观看进度必须走心跳接口：x/v2/history/report 只有 aid/cid 维度，
    //没有 epid/sid/type/sub_type，拿它上报不会被记成番剧记录——
    //表现就是"在终端里看的番剧，观看记录与进度都不更新，续播和详情页定位都拿不到数据"。
    private static final String HEARTBEAT_URL = "https://api.bilibili.com/x/click-interface/web/heartbeat";
    //type：3 投稿视频 / 4 剧集 / 10 课程
    private static final int HEARTBEAT_TYPE_SEASON = 4;
    //sub_type 取值与 season_type 一致；不在集合内就不发该字段，交给服务端按 epid/sid 判定
    private static final int[] HEARTBEAT_SUB_TYPES = {1, 2, 3, 4, 5, 7};

    /**
     * 上报番剧剧集的观看进度（番剧专用，投稿视频用 {@link #reportHistory}）。
     *
     * @param epid       剧集 epid（即 Bangumi.Episode#id）
     * @param seasonId   番剧 season_id
     * @param seasonType 剧集副类型（Bangumi.Info#type）
     * @param progress   已观看秒数
     */
    public static void reportHistoryPgc(long aid, long cid, long epid, long seasonId, int seasonType, long progress) throws IOException {
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PRIVACY_MODE, false)) return;
        //progress<=0 的上报零信息量且有害：服务端对本季"最近观看"按季粒度维护，
        //报 0 会把本季记录覆盖成"该集 + 0 进度"，详情页"定位上次观看分集"会因此失效
        if (progress <= 0) {
            Logu.d("history-report", "跳过 0 进度上报 epid=" + epid);
            return;
        }
        String csrf = currentCsrf();
        if (csrf.isEmpty()) Logu.e("history-report", "csrf 为空，上报必被服务端拒绝(-111)，请确认已登录");

        long nowSec = System.currentTimeMillis() / 1000;
        NetWorkUtil.FormData form = new NetWorkUtil.FormData()
                .put("aid", aid)
                .put("cid", cid)
                .put("epid", epid)
                .put("sid", seasonId)
                .put("mid", currentMid())
                .put("type", HEARTBEAT_TYPE_SEASON)
                .put("played_time", progress)
                //接口文档明确说明这几个"持续时间"字段算不准时可以都填同一个值
                .put("realtime", progress)
                .put("real_played_time", progress)
                .put("last_play_progress_time", progress)
                .put("max_play_progress_time", progress)
                //start_ts 是"开始播放时刻"：已知看了 progress 秒，倒推大致起点比直接填当前时间更接近语义。
                //下界必须夹到 0：设备时钟偏慢时倒推结果会变负数，服务端直接判参数错误(-400)
                .put("start_ts", Math.max(0, nowSec - progress))
                .put("play_type", 0)   //0 播放中
                .put("dt", 2)
                .put("outer", 0)
                .put("csrf", csrf);
        if (isKnownSeasonType(seasonType)) form.put("sub_type", seasonType);

        Response response = NetWorkUtil.post(HEARTBEAT_URL, form.toString(), NetWorkUtil.webHeaders);
        logReportResult("reportHistoryPgc", response,
                "aid=" + aid + " cid=" + cid + " epid=" + epid + " sid=" + seasonId
                        + " sub_type=" + seasonType + " progress=" + progress);
    }

    /**
     * 取当前有效的 csrf。
     *
     * 不能只读 {@code SharedPreferencesUtil.csrf}：该字段只在"登录成功 / 刷新 Cookie 成功"那一刻写入，
     * 而 bilibili 会在任意响应里通过 Set-Cookie 轮换 bili_jct（{@link NetWorkUtil} 会把新 Cookie 落进
     * cookies 字段，却不同步 csrf）。两者一旦错位，所有 POST 都会拿到 -111 而 GET 一切正常——
     * 表现出来就只是"观看记录上报静默不生效"，且同一份代码在不同设备/登录时机表现不同。
     * 这里与 MessageApi / CookieRefreshApi 保持同一口径：优先从实时 Cookie 派生，取不到再退回旧字段。
     */
    private static String currentCsrf() {
        String csrf = NetWorkUtil.getInfoFromCookie("bili_jct",
                SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
        if (csrf != null && !csrf.isEmpty()) return csrf;
        return SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "");
    }

    /** 与 {@link #currentCsrf()} 同理：mid 也可能因换设备/刷新 Cookie 而与实时 Cookie 不一致。 */
    private static long currentMid() {
        String midStr = NetWorkUtil.getInfoFromCookie("DedeUserID",
                SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, ""));
        if (midStr != null && !midStr.isEmpty()) {
            try {
                return Long.parseLong(midStr);
            } catch (NumberFormatException ignored) {
                //Cookie 形态异常时退回本地记录，不能让上报直接失败
            }
        }
        return SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0);
    }

    private static boolean isKnownSeasonType(int seasonType) {
        for (int t : HEARTBEAT_SUB_TYPES) {
            if (t == seasonType) return true;
        }
        return false;
    }

    /**
     * 历史/进度上报是"静默失败"成本最高的调用之一，返回码必须落日志，
     * 否则 -101(未登录)/-111(csrf 失效)/-400(请求错误) 之类的失败无从排查。
     */
    private static void logReportResult(String tag, Response response, String detail) throws IOException {
        try {
            if (response == null || response.body() == null) {
                Logu.e(tag, "无返回体 " + detail);
                return;
            }
            //peekBody 只读取一份副本，不会消费原 body，调用方后续仍可读
            String body = response.peekBody(1024 * 1024).string();
            Logu.d(tag, body);
            int code = new JSONObject(body).optInt("code", -1);
            if (code != 0) Logu.e(tag, "上报失败 code=" + code + " " + detail);
        } catch (JSONException e) {
            Logu.e(tag, "上报返回解析失败 " + detail);
        }
    }

    /**
     * 获取视频历史记录
     *
     * @param lastResult 上一次获取返回的ApiResult，如果是第一次就传入新对象
     * @param videoList  已有的视频列表
     * @return 新的ApiResult，包含了返回码、文本信息以及翻页所需的offset
     * @throws IOException
     * @throws JSONException
     */
    public static ApiResult getHistory(ApiResult lastResult, List<VideoCard> videoList) throws IOException, JSONException {
        //type=archive 只会返回投稿视频，番剧(business=pgc)必须用 type=all 才会出现在 list 中
        String url = "https://api.bilibili.com/x/web-interface/history/cursor?type=all&view_at=" + lastResult.timestamp + "&business=" + lastResult.business + "&max=" + lastResult.offset;
        JSONObject result = NetWorkUtil.getJson(url);
        ApiResult apiResult = new ApiResult(result);
        if (!result.isNull("data")) {
            JSONObject data = result.getJSONObject("data");
            JSONArray list = data.getJSONArray("list");
            for (int i = 0; i < list.length(); i++) {
                JSONObject videoCard = list.getJSONObject(i);
                String cover = videoCard.optString("cover", "");
                String upName = videoCard.optString("author_name", "");
                int progress = videoCard.optInt("progress", 0);

                JSONObject history = videoCard.optJSONObject("history");
                //business 优先取 history.business，其次顶层，都没有时按投稿视频处理
                String business = (history != null && !history.isNull("business"))
                        ? history.optString("business", BUSINESS_ARCHIVE)
                        : videoCard.optString("business", BUSINESS_ARCHIVE);
                //改用 type=all 后列表里会混入直播/专栏等业务，这类不展示，保持与旧实现一样的可见范围
                if (!BUSINESS_ARCHIVE.equals(business) && !BUSINESS_PGC.equals(business)) continue;

                long aid = history != null ? history.optLong("oid", 0) : videoCard.optLong("oid", 0);
                String bvid = history != null ? history.optString("bvid", "") : "";
                String title = videoCard.optString("title", "");
                if (BUSINESS_PGC.equals(business)) {
                    String longTitle = videoCard.optString("long_title", "");
                    //番剧标题在历史里被拆成 title + long_title(第几话)，不拼起来就分不清看的是哪一集
                    if (!longTitle.isEmpty()) title = title + " " + longTitle;
                }
                long viewAt = videoCard.optLong("view_at", 0);

                String viewStr;
                if (progress == 0) viewStr = "还没看过";
                else viewStr = "看到" + StringUtil.toTime(progress);

                VideoCard card = new VideoCard(title, upName, viewStr, cover, aid, bvid);
                card.viewAt = viewAt;
                //VideoCard 目前没有 progress/epid 字段（本组无权修改该文件）：
                //进度信息已由 viewStr 承载；这里置 type 是为了让番剧卡片点击时走番剧详情页(只需 aid)。
                //遗留：epid/progress 无法随卡片传出，详情页"定位上次观看分集"仍需另一组给 VideoCard 补字段。
                if (BUSINESS_PGC.equals(business)) card.type = "media_bangumi";
                videoList.add(card);
            }
            if (list.length() == 0) apiResult.isBottom = true;

            JSONObject cursor = data.getJSONObject("cursor");
            apiResult.business = cursor.optString("business");
            apiResult.offset = cursor.optLong("max");
            apiResult.timestamp = cursor.optLong("view_at");
        }
        return apiResult;
    }

    public static int deleteHistory(long aid, String bvid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v2/history/delete";
        String per = "kid=archive_" + aid + "&csrf=" + currentCsrf();
        JSONObject result = new JSONObject(NetWorkUtil.post(url, per, NetWorkUtil.webHeaders).body().string());
        return result.getInt("code");
    }

    /**
     * 从观看记录里取某个稿件/剧集最近一次的播放进度（毫秒），作为续播进度的兜底来源。
     *
     * 为什么需要兜底：番剧续播进度走 x/player/wbi/v2，该接口需要 WBI 签名和登录态，
     * 一旦密钥异常/被风控/未登录就静默返回 0，表现为"续播永远从 0 开始"；
     * 而观看记录列表接口不需要 WBI，只要登录过就能拿到 progress（秒），可靠性更高。
     *
     * @param aid 稿件/剧集 aid（即观看记录里的 history.oid）
     * @return 毫秒；未登录、无记录或进度为 0/-1（已看完）时返回 0
     */
    public static long findProgressMsByAid(long aid) {
        if (aid == 0) return 0;
        //未登录时没有观看记录，直接跳过，避免发无谓请求
        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) return 0;

        long viewAt = 0, max = 0;
        String business = "";
        try {
            for (int page = 1; page <= LOCATE_MAX_PAGES; page++) {
                String url = "https://api.bilibili.com/x/web-interface/history/cursor?type=all&view_at=" + viewAt
                        + "&business=" + business + "&max=" + max;
                JSONObject result = NetWorkUtil.getJson(url);
                if (result.isNull("data")) break;
                JSONObject data = result.getJSONObject("data");
                JSONArray list = data.optJSONArray("list");
                if (list == null) break;

                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item == null) continue;
                    JSONObject history = item.optJSONObject("history");
                    if (history == null) continue;
                    if (history.optLong("oid", 0) != aid) continue;
                    //progress 单位是秒；0/-1 都没有可续播的位置
                    int progress = item.optInt("progress", 0);
                    if (progress > 0) return progress * 1000L;
                }

                JSONObject cursor = data.optJSONObject("cursor");
                if (list.length() == 0 || cursor == null) break;
                long nextViewAt = cursor.optLong("view_at", 0);
                long nextMax = cursor.optLong("max", 0);
                String nextBusiness = cursor.optString("business", "");
                //游标没有前进说明已经没有更多数据，必须跳出，否则会无限翻页
                if (nextViewAt == viewAt && nextMax == max && nextBusiness.equals(business)) break;
                viewAt = nextViewAt;
                max = nextMax;
                business = nextBusiness;
            }
        } catch (Exception e) {
            Logu.e("history-locate", "兜底查询观看记录失败: " + e.getMessage());
        }
        return 0;
    }

}
