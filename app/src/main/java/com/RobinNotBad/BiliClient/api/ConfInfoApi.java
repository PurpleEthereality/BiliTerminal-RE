package com.RobinNotBad.BiliClient.api;

import android.net.Uri;

import com.RobinNotBad.BiliClient.util.FileUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.ToolsUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import okhttp3.HttpUrl;

/**
 * 被 luern0313 创建于 2019/8/25.
 * (人尽皆知的)绝 · 密 · 档 · 案
 * #以下代码修改自腕上哔哩的开源项目，感谢开源者做出的贡献！
 */

public class ConfInfoApi {


    /*
    这里是WBI签名校验
    https://socialsisteryi.github.io/bilibili-API-collect/docs/misc/sign/wbi.html#wbi-%E7%AD%BE%E5%90%8D%E7%AE%97%E6%B3%95
     */
    private static final int[] MIXIN_KEY_ENC_TAB = {46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52};

    /** WBI 签名结果缓存。整体替换，避免多个字段分开赋值时读到"query 已换、签名未换"的组合。 */
    private static final class WbiCache {
        final String query;
        final long wts;
        final String signedUrl;

        WbiCache(String query, long wts, String signedUrl) {
            this.query = query;
            this.wts = wts;
            this.signedUrl = signedUrl;
        }
    }

    private static volatile WbiCache lastWbiCache = null;

    /**
     * WBI 密钥的有效期（30 分钟）。
     * 服务端会不定时轮换 img_key / sub_key，老代码"一天只取一次密钥"在轮换后当天剩余时间全部签名失败；
     * 但每个请求都重取又太浪费，所以折中用 TTL：过期才重取。
     */
    private static final long WBI_KEY_TTL_MS = 30L * 60 * 1000;

    public static String getWBIRawKey() throws IOException, JSONException {
        JSONObject getJson = NetWorkUtil.getJson("https://api.bilibili.com/x/web-interface/nav");
        JSONObject wbi_img = getJson.getJSONObject("data").getJSONObject("wbi_img");  //不要被名称骗了，这玩意是签名用的
        String img_key = FileUtil.getFileFirstName(FileUtil.getFileNameFromLink(wbi_img.getString("img_url")));  //得到文件名
        String sub_key = FileUtil.getFileFirstName(FileUtil.getFileNameFromLink(wbi_img.getString("sub_url")));

        return img_key + sub_key;  //相连
    }

    public static String getWBIMixinKey(String raw_key) {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            key.append(raw_key.charAt(MIXIN_KEY_ENC_TAB[i]));
        }

        return key.toString();
    }

    public static String signWBI(String url_query) throws JSONException, IOException {
        // 取密钥要走网络，多线程并发时只放一个进去取，其余线程在这里等结果复用，避免重复请求
        synchronized (ConfInfoApi.class) {
            String mixin_key = SharedPreferencesUtil.getString("wbi_mixin_key", "");
            long now = System.currentTimeMillis();
            // 只有"没有密钥"或"密钥已过期"才重取。
            // 关键：时间戳必须在 getWBIRawKey() 成功之后才写。
            // 老代码先写 last_wbi 再取密钥，一旦取密钥抛异常（网络抖动等），
            // 失败状态会被当成"今天已经取过了"缓存一整天，之后所有 WBI 接口签名全错且无法自愈。
            if (mixin_key.isEmpty() || now - SharedPreferencesUtil.getLong("last_wbi_time", 0L) > WBI_KEY_TTL_MS) {
                Logu.d("检查WBI");
                String rawKey = ConfInfoApi.getWBIRawKey();
                mixin_key = ConfInfoApi.getWBIMixinKey(rawKey);
                SharedPreferencesUtil.putString("wbi_mixin_key", mixin_key);
                SharedPreferencesUtil.putLong("last_wbi_time", now); // 取到密钥才算成功，此时才落时间戳
            }

            long wts = System.currentTimeMillis() / 1000;
            WbiCache cached = lastWbiCache;
            if (cached != null && url_query.equals(cached.query) && wts == cached.wts) {
                return cached.signedUrl;
            }

            String wtsStr = String.valueOf(wts);
            String calc_str = sortUrlParams(Uri.encode(url_query, "@#&=*+-_.,:!?()/~'%") + "&wts=" + wtsStr) + mixin_key;
            Logu.d(calc_str);

            String w_rid = ToolsUtil.md5(calc_str);

            String signedUrl = Objects.requireNonNull(HttpUrl.parse(url_query)).newBuilder()
                    .addQueryParameter("w_rid", w_rid).addQueryParameter("wts", wtsStr).build().toString();
            lastWbiCache = new WbiCache(url_query, wts, signedUrl);
            return signedUrl;
        }
    }

    public static String sortUrlParams(String url) {
        String encodedParam = Objects.requireNonNull(HttpUrl.parse(url)).encodedQuery();
        if (encodedParam == null) encodedParam = "";
        // 解析URL参数
        Map<String, String> paramMap = new HashMap<>();
        String[] params = encodedParam.split("&");
        for (String param : params) {
            if (param.isEmpty()) continue; // 尾随 '&' 会切出空串，跳过，避免塞进一个空 key 干扰排序结果
            // 只按「第一个 =」切分：参数值本身可能含 '='（base64、url 等）。
            // 老代码 split("=") 遇这种参数会得到 3 段以上、length != 2 就把整个参数丢掉，导致签名算错。
            int eq = param.indexOf('=');
            if (eq < 0) {
                paramMap.put(param, "");
            } else {
                paramMap.put(param.substring(0, eq), param.substring(eq + 1));
            }
        }

        // 使用TreeMap对参数进行排序
        Map<String, String> sortedMap = new TreeMap<>(paramMap);

        // 构建排序后的URL
        StringBuilder sortedUrl = new StringBuilder();
        boolean isFirst = true;
        for (Map.Entry<String, String> entry : sortedMap.entrySet()) {
            if (!isFirst) {
                sortedUrl.append("&");
            } else {
                isFirst = false;
            }
            sortedUrl.append(entry.getKey()).append("=").append(entry.getValue());
        }

        return sortedUrl.toString();
    }


    public static int getDateCurr() {
        Calendar calendar = Calendar.getInstance();
        // Calendar.MONTH 从 0 开始（0=一月），必须 +1，否则算出的"日期编号"错位、比较逻辑跟着错
        return calendar.get(Calendar.YEAR) * 10000 + (calendar.get(Calendar.MONTH) + 1) * 100 + calendar.get(Calendar.DATE);
    }
}
