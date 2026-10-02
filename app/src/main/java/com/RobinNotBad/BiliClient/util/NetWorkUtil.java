package com.RobinNotBad.BiliClient.util;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Inflater;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 被 luern0313 创建于 2019/10/13.
 * #以下代码来源于腕上哔哩的开源项目，感谢开源者做出的贡献！
 */

public class NetWorkUtil {
    private static final AtomicReference<OkHttpClient> INSTANCE = new AtomicReference<>();
    private static volatile String cachedCookies = null;

    /**
     * 返回内存缓存的 cookie 字符串，避免每次请求都读 SharedPreferences。
     * 在 putCookie / setCookies / saveCookiesFromResponse 中更新。
     */
    public static String getCachedCookies() {
        String cookies = cachedCookies;
        if (cookies == null) {
            cookies = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "");
            // 只有 SharedPreferences 已就绪时才允许写缓存：webHeaders 的静态初始化可能
            // 发生在 Application.onCreate 赋值 sharedPreferences 之前，此时读到的必然是
            // 默认空串。若把它当成真实 Cookie 缓存下来，整个进程会一直误判为"未登录"，
            // 直到下一次 Cookie 写入才恢复。
            if (SharedPreferencesUtil.sharedPreferences != null) {
                cachedCookies = cookies;
            }
        }
        return cookies;
    }

    /**
     * 直接写入 cookie 字符串并同步内存缓存（供登录/刷新等场景使用）
     */
    public static void setCookiesString(String cookies) {
        SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies);
        cachedCookies = cookies;
        refreshHeaders();
    }

    public static class Inet4Selector implements Dns {
        @NonNull
        @Override
        public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
            List<InetAddress> hosts = Dns.SYSTEM.lookup(hostname);
            List<InetAddress> inet4Hosts = new ArrayList<>();
            for (InetAddress host : hosts) {
                if (host.getAddress().length == 4) inet4Hosts.add(host);
            }
            return inet4Hosts;    //筛选IPV4地址，IPV6请求有异常
        }
    }

    public static OkHttpClient getOkHttpInstance() {
        while (INSTANCE.get() == null) {
            INSTANCE.compareAndSet(null, setOkHttpSsl(new OkHttpClient.Builder())
                    .followRedirects(false)
                    .addInterceptor(chain -> {
                        Request request = chain.request();
                        Response response = chain.proceed(request);
                        RedirectHandler handler;
                        String location = response.header("Location");
                        boolean isSslRedirect = false;
                        try {
                            if (location != null && !request.isHttps()) {
                                URI locationUri = new URI(location);
                                String scheme = locationUri.getScheme();
                                String host = locationUri.getHost();
                                // 相对路径 Location 时 scheme/host 为 null，必须先判空避免 NPE
                                if (scheme != null && host != null) {
                                    isSslRedirect = scheme.equalsIgnoreCase("https") && request.url().host().equalsIgnoreCase(host);
                                }
                            }
                        } catch (URISyntaxException ignored) {
                        }

                        if (response.isRedirect() && location != null) {
                            if (request.url().host().equals("b23.tv") && !isSslRedirect && (handler = request.tag(RedirectHandler.class)) != null) {
                                handler.handleRedirect(location);
                            } else {
                                // 安全边界（审计 S1）：手动跟跳会把完整请求头（含登录 Cookie）带到新地址，
                                // 因此 Location 必须解析成绝对地址并落在 B 站域名白名单内才允许跟随。
                                // 被劫持/伪造的 302 再也无法把凭据引到任意主机：命中不了就原样返回这枚 3xx。
                                HttpUrl target = request.url().resolve(location);
                                if (target == null || !isBilibiliHost(target.host())) {
                                    return response;
                                }
                                // 用 request tag 累计跳数，封死"白名单内互相跳转"构成的无限重定向环
                                Integer hopsTag = request.tag(Integer.class);
                                int hops = hopsTag != null ? hopsTag : 0;
                                if (hops >= 5) {
                                    return response;
                                }
                                // 手动跟进重定向前必须先关闭原响应，否则连接泄漏
                                response.close();
                                Request newRequest = request.newBuilder()
                                        .url(target)
                                        .tag(Integer.class, hops + 1)
                                        .build();
                                return chain.proceed(newRequest);
                            }
                        }
                        return response;
                    })
                    .addInterceptor(new CookieSaveInterceptor())
                    .dns(new Inet4Selector())
                    .pingInterval(8, TimeUnit.SECONDS)
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(16, TimeUnit.SECONDS)
                    .writeTimeout(16, TimeUnit.SECONDS).build());
            // 有意不设 callTimeout：本实例是全 App 唯一的 OkHttpClient，既跑普通 API，也跑
            // DownloadActivity / UpdateManager 的大文件流式下载；callTimeout 覆盖"整个调用含读完 body"，
            // 一旦设置就会把耗时正常的长下载一并掐断。单次调用的时长上限交给
            // connectTimeout/readTimeout，以及下载侧自己的进度/取消逻辑。
        }
        return INSTANCE.get();
    }

    /**
     * 本方法历史上在 Android 5.1 及以下会启用 trust-all 证书校验（信任任意自签证书）。
     * 本项目 minSdk 24，`Build.VERSION.SDK_INT > 22` 恒成立，该分支永不执行；
     * 但把它留在源码里等于给未来预留了一个"静默降级 TLS"的开关——一旦有人下调 minSdk
     * 或在别处复制这段代码，登录 Cookie 就会立刻可被中间人截获。
     * 所以收敛为无条件返回调用方传入的 builder（使用系统默认证书校验）。
     * 方法签名保留：CustomGlideModule 与本类仍按此入口构造 OkHttpClient。
     */
    public synchronized static OkHttpClient.Builder setOkHttpSsl(OkHttpClient.Builder okhttpBuilder) {
        return okhttpBuilder;
    }

    /**
     * akamai 镜像白名单（B 站 PCDN/直链会跳到这里）。
     * 必须用精确主机名而不是 `endsWith(".akamaized.net")`：akamaized.net 是 Akamai 的共享域，
     * 后缀放行等于允许任意第三方 akamaized.net 主机接到携带登录 Cookie 的跟跳请求。
     */
    private static final List<String> BILIBILI_AKAMAI_MIRROR_HOSTS = Arrays.asList(
            "upos-sz-mirrorakam.akamaized.net",
            "upos-hz-mirrorakam.akamaized.net");

    /**
     * 判断主机是否属于 B 站自有/可信域名。
     * 供手动跟随重定向（本类拦截器）与 OpusApi 手动跟跳复用：
     * 只有命中白名单才允许把带 Cookie 的请求发过去，否则一律停止跟随（fail-closed）。
     * 注意 bilibili.cn 是本项目在对方白名单之外额外放行的 B 站自有域名。
     */
    public static boolean isBilibiliHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("b23.tv")
                || h.equals("bilibili.com") || h.endsWith(".bilibili.com")
                || h.equals("bilibili.cn") || h.endsWith(".bilibili.cn")
                || h.endsWith(".bilivideo.com")
                || h.endsWith(".hdslb.com")
                || BILIBILI_AKAMAI_MIRROR_HOSTS.contains(h);
    }

    public static JSONObject getJson(String url) throws IOException, JSONException {
        return executeJsonWithRiskRetry(url, webHeaders);
    }

    public static JSONObject getJson(String url, ArrayList<String> headers) throws IOException, JSONException {
        return executeJsonWithRiskRetry(url, headers);
    }

    /**
     * 隐私模式下的JSON请求，使用游客Cookie（剔除登录态Cookie），避免详情请求暴露个人状态
     */
    public static JSONObject getJsonPrivacy(String url) throws IOException, JSONException {
        ArrayList<String> headers = new ArrayList<>(webHeaders);
        headers.set(1, buildGuestCookieString(getCachedCookies()));
        return executeJsonWithRiskRetry(url, headers);
    }

    /**
     * 从完整Cookie字符串构建游客Cookie：剔除登录相关项，保留 buvid3/buvid4/bili_ticket/_uuid 等游客身份Cookie
     *
     * @param cookieString 完整Cookie字符串（形如 "a=1; b=2"）
     * @return 游客Cookie字符串
     */
    public static String buildGuestCookieString(String cookieString) {
        if (cookieString == null || cookieString.isEmpty()) return "";
        String[] excluded = {"SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid"};
        StringBuilder sb = new StringBuilder();
        String[] cookies = cookieString.split("; ");
        for (String cookie : cookies) {
            int eq = cookie.indexOf('=');
            if (eq <= 0) continue;
            String name = cookie.substring(0, eq);
            boolean skip = false;
            for (String ex : excluded) {
                if (ex.equals(name)) {
                    skip = true;
                    break;
                }
            }
            if (skip) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(cookie);
        }
        return sb.toString();
    }

    private static JSONObject executeJsonWithRiskRetry(String url, ArrayList<String> headers) throws IOException, JSONException {
        int maxRetries = Math.max(1, SharedPreferencesUtil.getInt("api_retry_max_times", 5));
        long retryIntervalMs = Math.max(0L, (long) (SharedPreferencesUtil.getFloat("api_retry_interval_seconds", 0.1f) * 1000));

        JSONObject json = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (ResponseBody body = get(url, headers).body()) {
                if (body != null) json = new JSONObject(body.string());
                else throw new JSONException("在访问" + url + "时返回数据为空");
            }
            int code = json.optInt("code", 0);
            if (code != -352 && code != -412) {
                return json;
            }
            Logu.d("RiskRetry", "检测到风控错误码 " + code + "，第" + attempt + "次重试...");
            if (attempt < maxRetries && retryIntervalMs > 0) {
                try {
                    Thread.sleep(retryIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return json;
    }

    public static Response get(String url) throws IOException {
        return get(url, webHeaders);
    }

    /**
     * 抓取 HTML 页面（如 Opus/专栏详情页）。
     * HTML 响应本就以 {@code <!doctype html>} 开头，不能走 {@link #executeWithDoctypeRetry}
     * （它会把合法的 DOCTYPE 页面误判为风控拦截并丢弃，导致整篇文章加载失败），
     * 因此这里直接执行请求，不做 doctype 重试。
     */
    public static Response getHtml(String url) throws IOException {
        Logu.d("get-url", url);
        OkHttpClient client = getOkHttpInstance();
        Request.Builder requestBuilder = new Request.Builder().url(url).get();
        for (int i = 0; i < webHeaders.size(); i += 2)
            requestBuilder.addHeader(webHeaders.get(i), webHeaders.get(i + 1));
        Response response = client.newCall(requestBuilder.build()).execute();
        saveCookiesFromResponse(response);
        return response;
    }

    public static Response get(String url, ArrayList<String> headers) throws IOException {
        return get(url, headers, null);
    }

    public static Response get(String url, ArrayList<String> headers, RedirectHandler redirectHandler) throws IOException {
        Logu.d("get-url", url);
        OkHttpClient client = getOkHttpInstance();
        Request.Builder requestBuilder = new Request.Builder().url(url).get();
        for (int i = 0; i < headers.size(); i += 2)
            requestBuilder.addHeader(headers.get(i), headers.get(i + 1));
        if (redirectHandler != null) requestBuilder.tag(RedirectHandler.class, redirectHandler);
        Request request = requestBuilder.build();
        return executeWithDoctypeRetry(client, request);
    }

    private static Response executeWithDoctypeRetry(OkHttpClient client, Request request) throws IOException {
        int maxRetries = Math.max(1, SharedPreferencesUtil.getInt("api_retry_max_times", 5));
        long retryIntervalMs = Math.max(0L, (long) (SharedPreferencesUtil.getFloat("api_retry_interval_seconds", 0.1f) * 1000));
        
        IOException lastException = null;
        Response response = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (response != null) {
                response.close();
                response = null;
            }
            
            try {
                response = client.newCall(request).execute();
                
                if (!isDoctypeResponse(response)) {
                    return response;
                }
                
                Logu.d("DoctypeRetry", "检测到DOCTYPE响应（风控拦截），第" + attempt + "次重试...");
                response.close();
                response = null;
                
            } catch (IOException e) {
                lastException = e;
                // 调用方主动取消时（OkHttp 抛 IOException("Canceled")）重试没有意义：
                // 结果已经没人要了，重试只会再发一次请求、再占一条连接，而且新 call 未必继承取消状态。
                if ("Canceled".equals(e.getMessage())) {
                    throw e;
                }
                Logu.d("DoctypeRetry", "网络异常，第" + attempt + "次重试: " + e.getMessage());
            }
            
            if (attempt < maxRetries && retryIntervalMs > 0) {
                try {
                    Thread.sleep(retryIntervalMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        if (response != null) {
            return response;
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new IOException("请求失败");
    }

    private static boolean isDoctypeResponse(Response response) {
        ResponseBody body = response.body();
        if (body == null) return false;
        
        try {
            okio.BufferedSource source = body.source();
            source.request(128);
            okio.Buffer buffer = source.buffer();
            String peek = buffer.clone().readUtf8(128).trim().toLowerCase();
            return peek.startsWith("<!doctype");
        } catch (Exception e) {
            return false;
        }
    }

    public static Response post(String url, String data, List<String> headers, String contentType) throws IOException {
        Logu.d("post-url", url);
        Logu.d("post-data", data);
        OkHttpClient client = getOkHttpInstance();
        RequestBody body = RequestBody.create(MediaType.parse(contentType + "; charset=utf-8"), data);
        Request.Builder requestBuilder = new Request.Builder().url(url).post(body);
        for (int i = 0; i < headers.size(); i += 2) {
            String key = headers.get(i);
            String val = headers.get(i + 1);
            if (key.equalsIgnoreCase("Content-Type")) val = contentType;
            requestBuilder.addHeader(key, val);
        }
        Request request = requestBuilder.build();
        return client.newCall(request).execute();
    }

    public static Response post(String url, String data, List<String> headers) throws IOException {
        return post(url, data, headers, "application/x-www-form-urlencoded");
    }

    public static Response postJson(String url, String data, List<String> headers) throws IOException {
        return post(url, data, headers, "application/json");
    }

    public static Response postJson(String url, String data) throws IOException {
        return post(url, data, webHeaders, "application/json");
    }

    public static Response post(String url, String data) throws IOException {
        return post(url, data, webHeaders);
    }


    /**
     * 从 Cookie 串里取指定键对应的值，取不到返回空串。
     *
     * 审计 M1：原实现是
     * <pre>if (i.contains(name + "=")) return i.substring(name.length() + 1);</pre>
     * 匹配用子串、取值用前缀裁剪，两者并不一致：
     * 键名出现在条目中间时（例如查 SESSDATA 而条目是 {@code x_SESSDATA=abc}）也会命中，
     * 但 substring 是按 name 的长度从头切的，切回来的是错位的垃圾串；
     * 条目带前导空格时同样错位。这里改为严格按 "key=" 前缀比对。
     */
    public static String getInfoFromCookie(String name, String cookie) {
        if (name == null || name.isEmpty() || cookie == null || cookie.isEmpty()) return "";
        String prefix = name + "=";
        for (String i : cookie.split(";")) {
            String item = i.trim();
            if (item.startsWith(prefix))
                return item.substring(prefix.length());
        }
        return "";
    }

    private static void saveCookiesFromResponse(Response response) {
        List<String> newCookies = response.headers("Set-Cookie");

        //如果没有新cookies，直接返回
        if (newCookies.isEmpty()) return;
        // 本方法由 CookieSaveInterceptor 在任意线程调用，必须与 putCookie/setCookies/getCookies
        // 共用同一把锁，否则 read-modify-write 互相覆盖会导致偶发登录态丢失。
        synchronized (NetWorkUtil.class) {
            saveCookiesLocked(newCookies);
        }
    }

    private static void saveCookiesLocked(List<String> newCookies) {
        String cookiesStr = getCachedCookies();
        ArrayList<String> oldCookies = (cookiesStr.equals("") ? new ArrayList<>() : new ArrayList<>(Arrays.asList(cookiesStr.split("; "))));  //转list

        for (String newCookie : newCookies) {  //对每一条新cookie遍历

            Cookies cookies = new Cookies(newCookie);
            if (cookies.containsKey("Domain") && !cookies.get("Domain").endsWith("bilibili.com"))
                continue;

            int index = newCookie.indexOf("; ");
            if (index != -1) newCookie = newCookie.substring(0, index);  //如果没有分号不做处理

            index = newCookie.indexOf("=") + 1;
            if (index == 0) continue;   //如果没有等号，跳过

            String key = newCookie.substring(0, index);    //key=
            // 只记录 Cookie 键名，绝不记录值：轮换后的 SESSDATA/bili_jct 等本身就是登录凭证，
            // 写进日志等于把会话明文留在了设备与 logcat 里（审计 S2）
            Logu.d("newCookie", newCookie.substring(0, Math.max(key.length() - 1, 0)));

            boolean added = false;
            for (int i = 0; i < oldCookies.size(); i++) {  //查找旧cookie表有没有
                String oldCookie = oldCookies.get(i);
                if (oldCookie.contains(key)) {
                    oldCookies.set(i, newCookie);    //有的话直接换掉
                    added = true;
                    break;
                }
            }
            if (!added) {
                oldCookies.add(newCookie);  //没有就加项
            }
        }

        StringBuilder setCookies = new StringBuilder();
        for (String setCookie : oldCookies) {
            setCookies.append(setCookie).append("; ");
        }
        //如果一次setCookies都没有，就不要存了， 因为是个空字符串
        if (setCookies.length() >= 2) {
            String result = setCookies.substring(0, setCookies.length() - 2);
            if (!result.equals(cookiesStr)) {
                SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, result);
                cachedCookies = result;
                refreshHeaders();
            }
        }
    }

    /**
     * 存储单个Cookie
     *
     * @param key 键
     * @param val 值
     */
    public static void putCookie(String key, String val) {
        synchronized (NetWorkUtil.class) {
            Cookies cookies = new Cookies(getCachedCookies());
            cookies.set(key, val);
            String result = cookies.toString();
            SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, result);
            cachedCookies = result;
            refreshHeaders();
        }
    }

    /**
     * 获取存储的Cookies
     *
     * @return 存储的Cookies
     */
    public static Cookies getCookies() {
        synchronized (NetWorkUtil.class) {
            return new Cookies(getCachedCookies());
        }
    }

    public static final String USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.95 Safari/537.36";

    /**
     * 全局请求头快照。volatile + 每次整体替换（copy-on-write）：
     * 原实现是 `static final ArrayList` 被 refreshHeaders() 原地 set(1, ...)，
     * 而所有 API 都在多线程里按索引遍历它——读线程可能看到"改了一半"的表，
     * 非 volatile 还会让它长期读到旧值（可见性问题）。改成 volatile 引用后，
     * 读线程要么拿到完整旧快照、要么拿到完整新快照，且不需要加锁。
     * 语义不变：全项目没有任何调用点原地修改该列表（唯一原地 set 就在 refreshHeaders）。
     */
    public static volatile ArrayList<String> webHeaders = buildWebHeaders();

    /** 构造一份全新的请求头列表；每次调用返回独立对象，避免共享可变状态。 */
    private static ArrayList<String> buildWebHeaders() {
        ArrayList<String> headers = new ArrayList<>();
        headers.add("Cookie");
        headers.add(getCachedCookies());

        headers.add("Origin");
        headers.add("https://www.bilibili.com");

        headers.add("Referer");
        headers.add("https://www.bilibili.com/");

        headers.add("User-Agent");
        headers.add(USER_AGENT_WEB);

        headers.add("Sec-Ch-Ua");
        headers.add("\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"");

        headers.add("Sec-Ch-Ua-Platform");
        headers.add("\"Windows\"");

        headers.add("Sec-Ch-Ua-Mobile");
        headers.add("?0");
        return headers;
    }

    public static void refreshHeaders() {
        // 整体替换而非原地 set：见 webHeaders 字段注释（并发读线程不能看到半成品表）
        webHeaders = buildWebHeaders();
    }

    public static class FormData {
        private final Map<String, String> data;
        private boolean isUrlParam;
        // 默认不自动加 access_key（B 站 web 接口用 Cookie 鉴权，带 access_key 会触发风控返回 HTML）
        // 参考 BiliTerminal-Revival：FormData 不自动加 access_key
        private boolean autoAddAccessKey = false;

        public FormData() {
            data = new HashMap<>();
        }

        public FormData remove(String key) {
            data.remove(key);
            return this;
        }

        public FormData put(String key, Object value) {
            data.put(key, String.valueOf(value));
            return this;
        }

        public FormData setUrlParam(boolean isUrlParam) {
            this.isUrlParam = isUrlParam;
            return this;
        }

        public FormData setAutoAddAccessKey(boolean autoAddAccessKey) {
            this.autoAddAccessKey = autoAddAccessKey;
            return this;
        }

        @NonNull
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();

            if (isUrlParam) sb.append("?");

            try {
                if (autoAddAccessKey && !data.containsKey("access_key")) {
                    String accessKey = SharedPreferencesUtil.getString(SharedPreferencesUtil.access_key, "");
                    if (!accessKey.isEmpty()) {
                        if (sb.length() > (isUrlParam ? 1 : 0)) {
                            sb.append("&");
                        }
                        sb.append("access_key=");
                        sb.append(URLEncoder.encode(accessKey, "UTF-8"));
                    }
                }

                for (String key : data.keySet()) {
                    if (sb.length() > (isUrlParam ? 1 : 0)) {
                        sb.append("&");
                    }
                    sb.append(URLEncoder.encode(key, "UTF-8"));
                    sb.append("=");
                    sb.append(URLEncoder.encode(data.get(key), "UTF-8"));
                }
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }

            return sb.toString();
        }
    }

    /**
     * 解压 CDN 返回的裸 deflate 数据（raw deflate，无 zlib 头）。
     *
     * 合并自原先三份逐字相同的副本（DownloadService / DownloadActivity / PlayerActivity），
     * 权威实现放在这里，调用方不要再各写一份。
     * 解压失败时回退返回原始数据；Inflater 持有 native 资源，必须在 finally 里 end()，
     * 否则一次异常就会泄漏一份 native 内存。
     */
    public static byte[] decompress(byte[] data) {
        byte[] output;
        Inflater decompresser = new Inflater(true);
        decompresser.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        try {
            byte[] buf = new byte[2048];
            while (!decompresser.finished()) {
                // 数据被截断/损坏时，inflate 会恒返回 0、finished() 恒为 false 且不抛异常
                // （外层 catch 兜不住），原写法就是 100% CPU 的死循环（审计 P15）。
                // needsInput/needsDictionary 表示流已无法继续推进，直接跳出（此时返回的是已解出的部分数据）。
                if (decompresser.needsInput() || decompresser.needsDictionary()) break;
                int i = decompresser.inflate(buf);
                if (i == 0) break;
                out.write(buf, 0, i);
            }
            output = out.toByteArray();
        } catch (Exception e) {
            output = data;
            e.printStackTrace();
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            decompresser.end();
        }
        return output;
    }

    public interface RedirectHandler {
        void handleRedirect(String location);
    }

    private static class CookieSaveInterceptor implements Interceptor {
        @NonNull
        @Override
        public Response intercept(Chain chain) throws IOException {
            Response response = chain.proceed(chain.request());
            saveCookiesFromResponse(response);
            return response;
        }
    }

}
