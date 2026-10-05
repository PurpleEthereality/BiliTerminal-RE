package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import okhttp3.Response;

//稍后再看API
//2023-08-17

public class WatchLaterApi {
    /** 列表筛选档位：全部。对应网页端 {@code viewed=0}。 */
    public static final int VIEWED_ALL = 0;
    /** 列表筛选档位：未看完（网页端/PiliPlus 的 {@code viewed=2}，服务端过滤）。 */
    public static final int VIEWED_UNFINISHED = 2;

    /** {@code /toview/clear} 的 clean_type：清除「已看完」。另有 1=已失效、缺省=清空全部。 */
    public static final int CLEAN_TYPE_WATCHED = 2;

    /**
     * 取稍后再看完整列表（不筛选）。
     *
     * @deprecated 用 {@link #getWatchLaterList(int)} 显式传档位，避免调用点看不出筛没筛。
     */
    @Deprecated
    public static ArrayList<VideoCard> getWatchLaterList() throws IOException, JSONException {
        return getWatchLaterList(VIEWED_ALL);
    }

    /**
     * 取稍后再看列表。
     *
     * <p>{@code viewed} 是<b>服务端</b>筛选参数（对照 PiliPlus 的 {@code LaterViewType}：
     * all=0 / unfinished=2，见 {@code lib/models/common/later_view_type.dart} 与
     * {@code lib/http/user.dart} 的 {@code seeYouLater}）——「未看完」不必也不该在本地重算，
     * 服务端返回的就是权威结果（它还知道本客户端拿不到的观看进度）。
     *
     * @param viewed {@link #VIEWED_ALL} 或 {@link #VIEWED_UNFINISHED}
     */
    public static ArrayList<VideoCard> getWatchLaterList(int viewed) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v2/history/toview/web?viewed=" + viewed;

        JSONObject result = NetWorkUtil.getJson(url);
        JSONObject data = result.getJSONObject("data");


        ArrayList<VideoCard> videoCardList = new ArrayList<>();
        if (!data.isNull("list")) {
            JSONArray list = data.getJSONArray("list");
            for (int i = 0; i < list.length(); i++) {
                JSONObject videoCard = list.getJSONObject(i);
                long aid = videoCard.getLong("aid");
                String bvid = videoCard.getString("bvid");
                String title = videoCard.getString("title");
                String cover = videoCard.getString("pic");
                String upName = videoCard.getJSONObject("owner").getString("name");
                long view = videoCard.getJSONObject("stat").getLong("view");
                String viewStr = StringUtil.toWan(view) + "观看";
                VideoCard card = new VideoCard(title, upName, viewStr, cover, aid, bvid);
                // 这两个字段只有稍后再看接口会给，用来区分「已看完 / 没看完」
                card.progress = videoCard.optInt("progress", 0);
                card.duration = videoCard.optLong("duration", 0);
                videoCardList.add(card);
            }
        }
        return videoCardList;
    }

    /**
     * 判断一条稍后再看是否「没看完」。
     *
     * <p>语义是<b>排除已看完的，其余（含从未播放）都算未看完</b>：
     * <ul>
     *   <li>从未播放（progress &lt;= 0）→ <b>算未看完</b>。旧实现要求 progress &gt; 0，把
     *       「一直没点开过」的稿件排除在外，用户看到的就是「未看完」空列表——这正是被修的 bug。</li>
     *   <li>总时长未知（duration &lt;= 0）→ 无法判断是否看完，一律算未看完（宁可多留不可错杀）。</li>
     *   <li>只有 progress &gt;= duration 且 duration &gt; 0 才算已看完。</li>
     * </ul>
     *
     * <p>注：网页端/PiliPlus 的「未看完」实际是走服务端 {@code viewed} 参数过滤的
     * （见 {@link #getWatchLaterList(int)}），本方法用于本地兜底与单测。
     */
    public static boolean isUnfinished(long progress, long duration) {
        if (duration <= 0) return true;
        return progress < duration;
    }

    /**
     * 按「未看完」过滤。
     *
     * @param list            原始列表（可为 null）
     * @param onlyUnfinished  true 只留没看完的；false 原样拷贝一份
     * @return 新列表，调用方可以安全地增删而不影响入参
     */
    public static ArrayList<VideoCard> filterUnfinished(List<VideoCard> list, boolean onlyUnfinished) {
        ArrayList<VideoCard> result = new ArrayList<>();
        if (list == null) return result;
        for (VideoCard card : list) {
            if (card == null) continue;
            if (!onlyUnfinished || isUnfinished(card.progress, card.duration)) result.add(card);
        }
        return result;
    }

    public static int delete(long aid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v2/history/toview/del";
        String per = "aid=" + aid + "&csrf=" + NetWorkUtil.currentCsrf();

        Response response = NetWorkUtil.post(url, per, NetWorkUtil.webHeaders);

        JSONObject result = new JSONObject(Objects.requireNonNull(response.body()).string());

        return result.getInt("code");
    }

    public static int add(long aid) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v2/history/toview/add";
        String per = "aid=" + aid + "&csrf=" + NetWorkUtil.currentCsrf();

        Response response = NetWorkUtil.post(url, per, NetWorkUtil.webHeaders);

        JSONObject result = new JSONObject(Objects.requireNonNull(response.body()).string());

        return result.getInt("code");
    }

    /**
     * 清除所有「已看完」的稍后再看稿件（对应网页端「清除所有已看完」按钮，仅稍后再看有）。
     *
     * <p>契约来自用户抓的 HAR（{@code D:\Users\ASUS\Downloads\清除所有已看完.har}）
     * 与 {@code bilibili-API/docs/historytoview/toview.md}：
     * <pre>
     * POST https://api.bilibili.com/x/v2/history/toview/clear
     * multipart/form-data : clean_type=2, csrf=&lt;当前 csrf&gt;
     * 响应 {"code":0,"message":"OK","ttl":1}
     * </pre>
     * {@code clean_type} 取值（对照 PiliPlus {@code UserHttp.toViewClear}）：
     * {@code null/缺省}=清空全部、{@code 1}=已失效、{@code 2}=已看完。
     *
     * <p>这里用 multipart 而非 urlencoded：HAR 里网页端发的就是 multipart，且工程内已有
     * 同款写法（{@code UserInfoApi} / {@code ReplyApi} 的 {@code MultipartBody}）。
     *
     * @return 接口 code，0 表示成功；-101 未登录、-111 csrf 校验失败
     */
    public static int clearWatched() throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v2/history/toview/clear";

        okhttp3.MultipartBody body = new okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("clean_type", String.valueOf(CLEAN_TYPE_WATCHED))
                .addFormDataPart("csrf", NetWorkUtil.currentCsrf())
                .build();

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .post(body)
                .build();
        // 复用全局 webHeaders（Cookie / Origin / Referer / UA），与其它 POST 一致
        for (int i = 0; i < NetWorkUtil.webHeaders.size(); i += 2) {
            request = request.newBuilder()
                    .addHeader(NetWorkUtil.webHeaders.get(i), NetWorkUtil.webHeaders.get(i + 1))
                    .build();
        }

        Response response = NetWorkUtil.getOkHttpInstance().newCall(request).execute();
        JSONObject result = new JSONObject(Objects.requireNonNull(response.body()).string());

        return result.getInt("code");
    }
}
