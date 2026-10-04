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
    public static ArrayList<VideoCard> getWatchLaterList() throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/v2/history/toview/web";

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
     * <p>规则：进度必须 &gt; 0（完全没播过的不算「看了一半」，那是「未看」），
     * 且已知总时长时进度要小于总时长。总时长为 0 视为未知，只按进度 &gt; 0 判定。
     * 这样「已看完」（progress &gt;= duration）与「从未播放」都不会混进来。
     */
    public static boolean isUnfinished(long progress, long duration) {
        if (progress <= 0) return false;
        return duration <= 0 || progress < duration;
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
}
