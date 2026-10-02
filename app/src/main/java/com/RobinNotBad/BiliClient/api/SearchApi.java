package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.ArticleCard;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

//搜索API 自己写的
//逐渐感觉拆json是个很爽的事（
//2023-07-14

public class SearchApi {

    /**
     * seid 是 B 站为一次搜索会话下发的分页标记，翻页时要原样带回。
     * <p>
     * 早先它是两个全局静态字段（seid + search_keyword）：同时打开两个搜索页时，
     * 后发起的那一页会把关键词和 seid 顶掉，前一页再翻页就会带着别人的关键词/seid 去请求（结果串页，
     * 而且 seid 与关键词不匹配时 B 站可能直接返回空列表）。现在按关键词隔离，
     * 并用访问序 LRU 限制条目数，避免用户搜过多少词就常驻多少条。
     */
    private static final int MAX_SEID_ENTRIES = 16;
    private static final LinkedHashMap<String, String> SEID_BY_KEYWORD =
            new LinkedHashMap<String, String>(MAX_SEID_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_SEID_ENTRIES;
                }
            };

    private static synchronized String getSeid(String keyword) {
        String seid = SEID_BY_KEYWORD.get(keyword);
        return seid == null ? "" : seid;
    }

    private static synchronized void putSeid(String keyword, String seid) {
        if (seid != null && !seid.isEmpty()) SEID_BY_KEYWORD.put(keyword, seid);
    }

    public static JSONArray search(String keyword, int page) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/wbi/search/all/v2?";
        url += "page=" + page +
                "&keyword=" + URLEncoder.encode(keyword, "UTF-8") + "&seid=" + getSeid(keyword);

        JSONObject all = NetWorkUtil.getJson(ConfInfoApi.signWBI(url)); // 得到一整个json

        if (all.isNull("data"))
            return null;
        JSONObject data = all.getJSONObject("data"); // 搜索列表中的data项又是一个json，把它提出来

        // optString 而非 getString：个别风控/降级响应里没有 seid 字段，getString 会抛 JSONException
        // 把整页搜索结果一起带走
        putSeid(keyword, data.optString("seid", ""));

        if (data.has("result") && !data.isNull("result"))
            return data.getJSONArray("result"); // 其实这还不是我们要的结果，下面的函数对它进行再次拆解 这里做了判空
        else
            return null;
    }

    public static Object searchType(String keyword, int page, String type) throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/wbi/search/type?";
        url += "page=" + page +
                "&keyword=" + URLEncoder.encode(keyword, "UTF-8") + "&search_type=" + type + "&seid=" + getSeid(keyword);

        JSONObject all = NetWorkUtil.getJson(ConfInfoApi.signWBI(url)); // 得到一整个json

        if (all.isNull("data"))
            return null;
        JSONObject data = all.getJSONObject("data"); // 搜索列表中的data项又是一个json，把它提出来

        putSeid(keyword, data.optString("seid", ""));

        if (data.has("result") && !data.isNull("result"))
            return data.get("result"); // 其实这还不是我们要的结果，下面的函数对它进行再次拆解 这里做了判空
        else
            return null;
    }

    public static void getVideosFromSearchResult(JSONArray input, ArrayList<VideoCard> videoCardList, boolean first)
            throws JSONException {
        for (int i = 0; i < input.length(); i++) { // 遍历所有的分类，找到视频那一项
            JSONObject typecard = input.getJSONObject(i);
            String type = typecard.getString("result_type");
            if (type.equals("video")) {
                JSONArray data = typecard.getJSONArray("data"); // 把这个列表提出来，接着拆
                for (int j = 0; j < data.length(); j++) {
                    JSONObject card = data.getJSONObject(j); // 获得视频卡片
                    if (!card.getString("type").equals("video"))
                        continue; // 警惕虚假视频卡片（阿B为什么要把直播间放进视频结果里）

                    String title = card.getString("title");
                    title = title.replace("<em class=\"keyword\">", "").replace("</em>", "");
                    // 标题里的红字，知道怎么显示但因为懒所以直接删（//显示方式可以用imagespan，参照表情包那部分程序（EmoteUtil），期待后人补齐（
                    title = StringUtil.htmlToString(title);

                    String bvid = card.getString("bvid");
                    long aid = card.getLong("aid");
                    String cover = "http:" + card.getString("pic"); // 离谱了嗷，前面甚至不肯加个http:
                    String upName = card.getString("author");

                    long play = card.getLong("play");
                    String playTimesStr = StringUtil.toWan(play) + "观看";

                    videoCardList.add(new VideoCard(title, upName, playTimesStr, cover, aid, bvid, type));
                }
            } else if (type.equals("media_bangumi") && first) {
                JSONArray data = typecard.getJSONArray("data");
                for (int j = 0; j < data.length(); j++) {
                    JSONObject card = data.getJSONObject(j); // 获得番剧卡片

                    String title = card.getString("title");
                    title = title.replace("<em class=\"keyword\">", "").replace("</em>", "");
                    // 标题里的红字,直接上面复制粘贴
                    title = StringUtil.htmlToString(title);
                    String cover = card.getString("cover");
                    String upName = card.getString("areas");
                    long aid = card.getLong("media_id");
                    String bvid = card.getString("season_id");
                    String playTimesStr = card.getString("index_show");
                    videoCardList.add(new VideoCard(title, upName, playTimesStr, cover, aid, bvid, type));
                }
            }
        }
    }

    /**
     * 单独搜索番剧（search_type=media_bangumi）的结果拆解。
     * 与 {@link #getVideosFromSearchResult} 里的 media_bangumi 分支不同：这里的 input 就是
     * data.result 本身（一个番剧条目数组），外面没有再套一层 result_type，所以直接遍历即可。
     * <p>
     * 番剧条目里没有 aid/bvid，借用 VideoCard 的已有字段承载：
     * aid=media_id（剧集 mdid，番剧详情页正是用它拉取信息）、bvid=season_id（ssid，仅作稳定 id 用）、
     * type 固定 "media_bangumi"（VideoCardHolder 已按该 type 跳番剧详情页）。
     *
     * @param input           search_type=media_bangumi 返回的 data.result
     * @param videoCardList   结果追加到这里
     */
    public static void getBangumiFromSearchResult(JSONArray input, ArrayList<VideoCard> videoCardList)
            throws JSONException {
        if (input == null) return;

        for (int i = 0; i < input.length(); i++) {
            JSONObject card = input.optJSONObject(i);
            if (card == null) continue;

            String title = card.optString("title", "");
            title = title.replace("<em class=\"keyword\">", "").replace("</em>", "");
            title = StringUtil.htmlToString(title);

            // 搜索结果里的封面是协议相对地址（//i0.hdslb.com/...），而 GlideUtil 对不以 http 开头的
            // url 会原样返回、不加尺寸后缀，Glide 本身也不认无 scheme 的地址 —— 不补 scheme 封面必然失败
            String cover = card.optString("cover", "");
            if (cover.startsWith("//")) cover = "https:" + cover;

            String areas = card.optString("areas", "");          // 地区，卡片第二行
            String indexShow = card.optString("index_show", ""); // 更新进度（如"全14话"），卡片第三行
            // index_show 是聚合搜索（all/v2）才稳定给出的字段，单独的 search_type=media_bangumi
            // 接口文档里没有它，实测可能为空；为空时退用 styles（风格，如"原创/科幻/推理"），
            // 免得卡片第三行整行消失（VideoCardHolder 对空 view 是直接隐藏）
            if (indexShow.isEmpty()) indexShow = card.optString("styles", "");
            long mediaId = card.optLong("media_id", 0);
            String seasonId = card.optString("season_id", "");

            videoCardList.add(new VideoCard(title, areas, indexShow, cover, mediaId, seasonId, "media_bangumi"));
        }
    }

    public static void getUsersFromSearchResult(JSONArray input, List<UserInfo> userInfoList) throws JSONException {
        for (int i = 0; i < input.length(); i++) {
            JSONObject card = input.getJSONObject(i); // 获得用户卡片

            long mid = card.getLong("mid");
            String name = card.getString("uname");
            String avatar = "http:" + card.getString("upic");
            String sign = card.getString("usign");
            int fans = card.getInt("fans");
            int level = card.getInt("level");

            userInfoList.add(new UserInfo(mid, name, avatar, sign, fans, 0, level, false, "", 0, "", 0));
        }
    }

    public static void getArticlesFromSearchResult(JSONArray input, ArrayList<ArticleCard> articleCardList)
            throws JSONException {
        for (int i = 0; i < input.length(); i++) {
            ArticleCard articleCard = new ArticleCard();
            JSONObject card = input.getJSONObject(i); // 获得专栏卡片

            articleCard.id = card.getLong("id");
            if (card.getJSONArray("image_urls").length() > 0)
                articleCard.cover = "http:" + card.getJSONArray("image_urls").getString(0);
            else
                articleCard.cover = "";
            articleCard.upName = card.getString("category_name");
            articleCard.title = StringUtil.htmlReString(card.getString("title"));
            articleCard.view = StringUtil.toWan(card.getInt("view")) + "阅读";

            articleCardList.add(articleCard);
        }
    }

    /**
     * 获取搜索建议
     *
     * @param keyword 搜索关键词
     * @return 返回搜索建议列表，最多10条
     * @throws IOException   网络请求异常
     * @throws JSONException JSON解析异常
     */
    public static ArrayList<String> getSearchSuggestions(String keyword) throws IOException, JSONException {
        ArrayList<String> suggestions = new ArrayList<>();

        if (keyword == null || keyword.trim().isEmpty()) {
            return suggestions;
        }

        String url = "https://s.search.bilibili.com/main/suggest?term=" + URLEncoder.encode(keyword, "UTF-8");

        JSONObject response = NetWorkUtil.getJson(url);

        if (response.getInt("code") == 0 && response.has("result")) {
            JSONObject result = response.getJSONObject("result");
            if (result.has("tag") && !result.isNull("tag")) {
                JSONArray tagArray = result.getJSONArray("tag");
                for (int i = 0; i < tagArray.length(); i++) {
                    JSONObject tagObj = tagArray.getJSONObject(i);
                    String value = tagObj.getString("value");
                    suggestions.add(value);
                }
            }
        }

        return suggestions;
    }

    public static String getDefaultSearchContent() throws IOException, JSONException {
        String url = "https://api.bilibili.com/x/web-interface/wbi/search/default";
        JSONObject response = NetWorkUtil.getJson(ConfInfoApi.signWBI(url));
        if (response.getInt("code") == 0 && response.has("data")) {
            JSONObject data = response.getJSONObject("data");
            if (data.has("show_name") && !data.isNull("show_name")) {
                return data.getString("show_name");
            }
        }
        return null;
    }
}
