package com.RobinNotBad.BiliClient.api;

import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import com.RobinNotBad.BiliClient.BuildConfig;
import com.RobinNotBad.BiliClient.model.Announcement;
import com.RobinNotBad.BiliClient.model.ApiResult;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.RobinNotBad.BiliClient.util.SettingsKeys;
import com.RobinNotBad.BiliClient.util.TimeUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

import okhttp3.Response;

/**
 * 自建终端服务（RE:哔哩终端自己的后端）的客户端入口。
 *
 * <p><b>与 {@link AppInfoApi} 的关系</b>：{@code AppInfoApi} 指向的是上游开发者
 * （RobinNotBad）的 {@code api.biliterminal.cn}，那是原作者的服务器。本 fork 的
 * 新主人另有自己的服务器，所以公告、反馈、遥测、崩溃报告走这里；上游那套接口
 * <b>原样保留、不动</b>——两路公告在客户端合并展示，见
 * {@link #getAnnouncementList()}。
 *
 * <p><b>安全红线（务必保持）</b>：
 * <ul>
 *   <li>本类所有请求都必须显式带 {@link #headers()}，它是从
 *       {@link AppInfoApi#customHeaders} 复制的、<b>不含 Cookie</b> 的头。</li>
 *   <li>绝不可使用 {@link NetWorkUtil#getJson(String)} 或
 *       {@link NetWorkUtil#postJson(String, String)}：这两个单参重载用 {@code webHeaders}，
 *       而 {@code webHeaders} 的第一项就是 {@code Cookie}，会把 SESSDATA 发到自建服务器。</li>
 *   <li>自建接口只接受匿名 install_id，不携带任何账号凭据；UID 仅在用户于反馈页
 *       <b>显式勾选</b>后才附带（{@code mid}）。</li>
 * </ul>
 *
 * <p>服务端返回约定：成功 {@code code=0}，失败 {@code code!=0} 且带 {@code msg}，
 * HTTP 状态码一律 2xx（便于客户端统一按 body 判断）。
 */
public class TerminalApi {

    /** 自建服务基址。 */
    public static final String BASE_URL = "https://rebiliterminal.zsapp.asia/terminal";

    /** 反馈分类白名单，与服务端 `FEEDBACK_CATEGORIES` 保持一致。 */
    public static final String CATEGORY_BUG = "bug";
    public static final String CATEGORY_SUGGESTION = "suggestion";
    public static final String CATEGORY_CONTENT = "content";
    public static final String CATEGORY_PERFORMANCE = "performance";
    public static final String CATEGORY_OTHER = "other";

    public static final String[] CATEGORIES = {
            CATEGORY_BUG, CATEGORY_SUGGESTION, CATEGORY_CONTENT, CATEGORY_PERFORMANCE, CATEGORY_OTHER
    };

    /**
     * 当前隐私说明版本号。
     *
     * <p>只要用户可见的隐私文案（收集什么、不收集什么、怎么关）有实质变化，就必须 +1：
     * 老用户升级后会**重新看到一次**确认弹窗（见 {@link #needsPrivacyConsent()}）。
     * 改这个号的时候，`strings.xml` 里的 {@code privacy_consent_message}、
     * {@code about_to_uncle}、{@code text_setup_introduction} 三处要一起改。
     */
    public static final int PRIVACY_VERSION = 1;

    private TerminalApi() {
    }

    // ==================== 纯函数：可在 JVM 单测里直接验证 ====================

    /** null 安全：服务端字段一律以字符串落库，null 会变成 JSON 的 null 而不是空串。 */
    static String safe(String value) {
        return value == null ? "" : value;
    }

    /** 把任意输入收敛到白名单分类，未知值一律归为 `other`——避免脏数据进库。 */
    public static String normalizeCategory(String category) {
        if (category == null) return CATEGORY_OTHER;
        for (String allowed : CATEGORIES) {
            if (allowed.equals(category)) return category;
        }
        return CATEGORY_OTHER;
    }

    /**
     * 崩溃报告里当天的日期键（yyyy-MM-dd，本地时区）——只用于「一天一次」的幂等判断。
     *
     * <p>放在这里而不是 {@code TelemetryReporter}，是为了让纯逻辑能在 JVM 单测里跑。
     */
    public static String dayKey(long millis) {
        return TimeUtil.formatDate(millis);
    }

    public static JSONObject buildPingPayload(String installId, int versionCode, String versionName,
                                              boolean isBeta, int sdk, String brand,
                                              String device, String abi) throws JSONException {
        JSONObject data = new JSONObject();
        data.put("install_id", safe(installId));
        data.put("version_code", versionCode);
        data.put("version_name", safe(versionName));
        data.put("is_beta", isBeta);
        data.put("sdk", sdk);
        data.put("brand", safe(brand));
        data.put("device", safe(device));
        data.put("abi", safe(abi));
        return data;
    }

    public static JSONObject buildFeedbackPayload(String installId, String category, String content,
                                                  String contact, long mid, int versionCode,
                                                  String versionName, int sdk, String brand,
                                                  String device) throws JSONException {
        JSONObject data = new JSONObject();
        data.put("install_id", safe(installId));
        data.put("category", normalizeCategory(category));
        data.put("content", safe(content));
        data.put("contact", safe(contact));
        data.put("mid", mid);
        data.put("version_code", versionCode);
        data.put("version_name", safe(versionName));
        data.put("sdk", sdk);
        data.put("brand", safe(brand));
        data.put("device", safe(device));
        return data;
    }

    /**
     * 崩溃报告的完整载荷。
     *
     * <p>比上游 {@code /terminal/upload/stack} 多带了这些字段（用户要求「发的多一点」）：
     * 匿名 install_id、异常类名与消息（与堆栈分开）、出错线程、崩溃前的页面轨迹、
     * 系统版本 / product / model / ABI / 内存 / 运行时长。堆栈本身仍是主体。
     */
    public static JSONObject buildCrashPayload(String installId, long mid, String exception,
                                               String message, String thread, String stack,
                                               String log, String extra, int versionCode,
                                               String versionName, int sdk, String release,
                                               String brand, String device, String product,
                                               String model, String abi, long ramTotal,
                                               long ramAvail, long uptimeSec) throws JSONException {
        JSONObject data = new JSONObject();
        data.put("install_id", safe(installId));
        data.put("mid", mid);
        data.put("exception", safe(exception));
        data.put("message", safe(message));
        data.put("thread", safe(thread));
        data.put("stack", safe(stack));
        data.put("log", safe(log));
        data.put("extra", safe(extra));
        data.put("version_code", versionCode);
        data.put("version_name", safe(versionName));
        data.put("sdk", sdk);
        data.put("release", safe(release));
        data.put("brand", safe(brand));
        data.put("device", safe(device));
        data.put("product", safe(product));
        data.put("model", safe(model));
        data.put("abi", safe(abi));
        data.put("ram_total", ramTotal);
        data.put("ram_avail", ramAvail);
        data.put("uptime_sec", uptimeSec);
        return data;
    }

    /**
     * 解析公告数组。服务端 {@code ctime} 是 **epoch 秒**，这里换算成毫秒后交给
     * {@link TimeUtil#formatDate(long)}（与上游 {@code AppInfoApi.getAnnouncementList()} 一致）。
     *
     * <p>缺 id 的条目直接跳过：没有 id 就无法参与「已读最大 id」的差量计算。
     */
    public static ArrayList<Announcement> parseAnnouncements(JSONArray data) {
        ArrayList<Announcement> list = new ArrayList<>();
        if (data == null) return list;
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null || !item.has("id")) continue;
            Announcement announcement = new Announcement();
            announcement.id = item.optInt("id", 0);
            announcement.ctime = TimeUtil.formatDate(item.optLong("ctime", 0) * 1000);
            announcement.title = item.optString("title", "");
            announcement.content = item.optString("content", "");
            list.add(announcement);
        }
        return list;
    }

    /** 把两路公告（上游 + 自建）按 id 倒序合并；id 相同的只保留一条。 */
    public static ArrayList<Announcement> mergeAnnouncements(ArrayList<Announcement> upstream,
                                                             ArrayList<Announcement> own) {
        ArrayList<Announcement> merged = new ArrayList<>();
        if (upstream != null) merged.addAll(upstream);
        if (own != null) {
            for (Announcement item : own) {
                boolean duplicated = false;
                for (Announcement exists : merged) {
                    if (exists.id == item.id) {
                        duplicated = true;
                        break;
                    }
                }
                if (!duplicated) merged.add(item);
            }
        }
        Collections.sort(merged, (a, b) -> Integer.compare(b.id, a.id));
        return merged;
    }

    // ==================== install_id ====================

    /**
     * 取（必要时生成）匿名安装标识：一个随机 UUID。
     *
     * <p>与 B 站账号无关、不含任何设备指纹，卸载重装即换新。首次调用时写入
     * {@link SettingsKeys#TELEMETRY_INSTALL_ID}。
     */
    public static String getInstallId() {
        String id = SharedPreferencesUtil.getString(SettingsKeys.TELEMETRY_INSTALL_ID, "");
        if (TextUtils.isEmpty(id)) {
            id = UUID.randomUUID().toString().replace("-", "");
            SharedPreferencesUtil.putString(SettingsKeys.TELEMETRY_INSTALL_ID, id);
        }
        return id;
    }

    /** 匿名统计是否开启。默认开，但**必须先就当前版本隐私说明点过「同意」**，未同意一律不上报。 */
    public static boolean isTelemetryEnabled() {
        return hasPrivacyConsent()
                && SharedPreferencesUtil.getBoolean(SettingsKeys.TELEMETRY_ENABLE, true);
    }

    /** 崩溃自动上报是否开启，默认开；同样以隐私说明的「同意」为前置条件。 */
    public static boolean isCrashReportAutoEnabled() {
        return hasPrivacyConsent()
                && SharedPreferencesUtil.getBoolean(SettingsKeys.CRASH_REPORT_AUTO, true);
    }

    // ==================== 隐私说明再确认 ====================

    /**
     * 是否已经就**当前版本**的隐私说明点过「同意」。
     *
     * <p>这是所有上报的唯一总闸：{@link #isTelemetryEnabled()} 与
     * {@link #isCrashReportAutoEnabled()} 都先问它，所以「没同意就绝不上报」这件事
     * 不依赖任何一个调用点是否记得判断。
     */
    public static boolean hasPrivacyConsent() {
        return SharedPreferencesUtil.getInt(SettingsKeys.PRIVACY_CONSENT_VERSION, 0) >= PRIVACY_VERSION;
    }

    /** 是否还需要弹一次隐私说明确认（当前版本还没表过态）。 */
    public static boolean needsPrivacyConsent() {
        return SharedPreferencesUtil.getInt(SettingsKeys.PRIVACY_PROMPTED_VERSION, 0) < PRIVACY_VERSION;
    }

    /**
     * 弹窗里点了「同意」：记下同意，并把两个上报开关复位为开。
     *
     * <p>为什么要复位开关而不是「保持用户原来的选择」：能走到这个弹窗，
     * 说明用户此前要么没表过态、要么上一版明确的选过「不同意」（那会把两个开关关掉）。
     * 两种情况下开关的当前值都不是「用户在有同意基础的前提下主动关的」，
     * 所以这里按默认值复位是符合直觉的——点完同意却什么都没发生才是坑。
     * 用户之后再想关，开关就在同一页，随时可关。
     */
    public static void acceptPrivacyConsent() {
        SharedPreferencesUtil.putInt(SettingsKeys.PRIVACY_CONSENT_VERSION, PRIVACY_VERSION);
        SharedPreferencesUtil.putInt(SettingsKeys.PRIVACY_PROMPTED_VERSION, PRIVACY_VERSION);
        SharedPreferencesUtil.putBoolean(SettingsKeys.TELEMETRY_ENABLE, true);
        SharedPreferencesUtil.putBoolean(SettingsKeys.CRASH_REPORT_AUTO, true);
    }

    /**
     * 弹窗里点了「不同意」：只记「已问过」，并把两个上报开关关掉。
     *
     * <p>记「已问过」是为了不再反复弹窗（用户已经明确回答过了）；
     * 同意版本号保持原值（0 或上一版），于是 {@link #hasPrivacyConsent()} 仍为 false——
     * 这就是「可以继续用，但不上报」的全部实现。App 不会因为不同而被拦在闪屏外。
     */
    public static void declinePrivacyConsent() {
        SharedPreferencesUtil.putInt(SettingsKeys.PRIVACY_PROMPTED_VERSION, PRIVACY_VERSION);
        SharedPreferencesUtil.putBoolean(SettingsKeys.TELEMETRY_ENABLE, false);
        SharedPreferencesUtil.putBoolean(SettingsKeys.CRASH_REPORT_AUTO, false);
    }

    /**
     * 用户在「设置 - 关于与帮助」里**主动打开**统计或崩溃上报开关时调用。
     *
     * <p>主动打开就是一个明确的意思表示，等同于重新同意；这里只落同意版本号，
     * **不碰任何开关**（尤其是不能顺手把另一个开关也打开——用户只点了一个）。
     */
    public static void recordPrivacyConsentFromSettings() {
        SharedPreferencesUtil.putInt(SettingsKeys.PRIVACY_CONSENT_VERSION, PRIVACY_VERSION);
        SharedPreferencesUtil.putInt(SettingsKeys.PRIVACY_PROMPTED_VERSION, PRIVACY_VERSION);
    }

    // ==================== 网络 ====================

    /**
     * 自建接口专用请求头：{@link AppInfoApi#customHeaders}（无 Cookie）的副本，额外带 install_id。
     *
     * <p>用副本而不是直接改 {@code customHeaders}，是因为那个列表同时被上游接口使用，
     * 往共享静态列表里加项会改变上游请求的内容。
     */
    public static ArrayList<String> headers() {
        ArrayList<String> list = new ArrayList<>(AppInfoApi.customHeaders);
        list.add("X-Install-Id");
        list.add(getInstallId());
        return list;
    }

    private static JSONObject post(String path, JSONObject body) throws IOException, JSONException {
        Response response = NetWorkUtil.postJson(BASE_URL + path, body.toString(), headers());
        if (response.body() == null) throw new IOException("empty response body");
        return new JSONObject(response.body().string());
    }

    private static JSONObject get(String path) throws IOException, JSONException {
        return NetWorkUtil.getJson(BASE_URL + path, headers());
    }

    private static void requireOk(JSONObject result) throws Exception {
        if (result.optInt("code", -1) != 0) {
            throw new Exception("错误：" + result.optString("msg", "unknown"));
        }
    }

    /**
     * 上报一次启动。服务端按 (install_id, 当天) 去重，因此一天内多次调用不会重复计数。
     *
     * @return 服务端是否接受（调用方通常不需要关心）
     */
    public static boolean ping() {
        try {
            JSONObject payload = buildPingPayload(
                    getInstallId(),
                    BuildConfig.VERSION_CODE,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.BETA,
                    Build.VERSION.SDK_INT,
                    Build.BRAND,
                    Build.DEVICE,
                    Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
            return post("/telemetry/ping", payload).optInt("code", -1) == 0;
        } catch (Exception e) {
            // 遥测是尽力而为：任何失败都不能影响用户使用，也不能弹窗打扰。
            Log.w("debug-terminal", "telemetry ping failed: " + e);
            return false;
        }
    }

    /**
     * 提交反馈。
     *
     * @param mid 用户显式勾选「附带我的账号」时传 UID，否则传 0
     * @return {@code ApiResult.code} 为服务端返回的反馈 id（成功）或负的错误码，{@code message} 为错误原因
     */
    public static ApiResult submitFeedback(String category, String content, String contact, long mid) {
        try {
            JSONObject payload = buildFeedbackPayload(
                    getInstallId(), category, content, contact, mid,
                    BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME,
                    Build.VERSION.SDK_INT, Build.BRAND, Build.DEVICE);
            JSONObject result = post("/feedback/submit", payload);
            if (result.optInt("code", -1) != 0) {
                return new ApiResult(-1, result.optString("msg", "unknown"));
            }
            return new ApiResult(result.optInt("id", -1), "");
        } catch (IOException e) {
            return new ApiResult(-2, "网络连接失败");
        } catch (JSONException e) {
            return new ApiResult(-3, "服务器返回了无法解析的内容");
        }
    }

    /**
     * 上传崩溃报告。成功时 {@code ApiResult.code} 就是给用户看的报错编号。
     */
    public static ApiResult uploadCrash(JSONObject payload) {
        try {
            JSONObject result = post("/upload/stack", payload);
            if (result.optInt("code", -1) != 0) {
                return new ApiResult(-1, result.optString("msg", "unknown"));
            }
            return new ApiResult(result.optInt("id", -1), "");
        } catch (IOException e) {
            return new ApiResult(-2, "网络连接失败");
        } catch (JSONException e) {
            return new ApiResult(-3, "服务器返回了无法解析的内容");
        }
    }

    /**
     * 启动时拉取自建公告源的增量并弹窗。
     *
     * <p>与上游 {@link AppInfoApi#checkAnnouncement()} 完全对称，但用的是
     * {@link SettingsKeys#TERMINAL_ANNOUNCEMENT_LAST} 这个独立计数——两边 id 空间
     * 靠 10 亿偏移隔开，不会互相把对方的已读位推高。
     */
    public static void checkAnnouncement() throws Exception {
        String url = "/announcement/get_list?from="
                + SharedPreferencesUtil.getInt(SettingsKeys.TERMINAL_ANNOUNCEMENT_LAST, -1);
        JSONObject result = get(url);
        requireOk(result);

        JSONArray data = result.optJSONArray("data");
        if (data == null) return;

        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            int id = item.getInt("id");
            if (SharedPreferencesUtil.getInt(SettingsKeys.TERMINAL_ANNOUNCEMENT_LAST, 0) < id) {
                SharedPreferencesUtil.putInt(SettingsKeys.TERMINAL_ANNOUNCEMENT_LAST, id);
            }
            MsgUtil.showText(item.getString("title"), item.getString("content"));
        }
    }

    /** 公告列表（全量，倒序）。 */
    public static ArrayList<Announcement> getOwnAnnouncementList() throws Exception {
        JSONObject result = get("/announcement/get_list");
        requireOk(result);
        return parseAnnouncements(result.optJSONArray("data"));
    }

    /**
     * 公告列表页用：把上游与自建两路合并后写入 [out]。
     *
     * <p>两路各自尽力而为：**任何一路失败都不影响另一路展示**。原实现是「一个源抛异常
     * 整页就报错」，用户会连原有的上游公告都看不到。
     *
     * @return 两路**都**成功时 true；只要有一路成功就返回 false 但仍会把该路结果写入 [out]，
     * 调用方据此决定是否提示「部分公告源不可用」
     */
    public static boolean loadMergedAnnouncements(ArrayList<Announcement> out) {
        ArrayList<Announcement> upstream = null;
        ArrayList<Announcement> own = null;
        try {
            upstream = AppInfoApi.getAnnouncementList();
        } catch (Exception e) {
            Log.w("debug-terminal", "upstream announcement failed: " + e);
        }
        try {
            own = getOwnAnnouncementList();
        } catch (Exception e) {
            Log.w("debug-terminal", "own announcement failed: " + e);
        }
        out.clear();
        out.addAll(mergeAnnouncements(upstream, own));
        return upstream != null && own != null;
    }
}
