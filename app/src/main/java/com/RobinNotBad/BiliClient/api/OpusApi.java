package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.Opus;
import com.RobinNotBad.BiliClient.model.OpusParagraph;
import com.RobinNotBad.BiliClient.model.Stats;
import com.RobinNotBad.BiliClient.model.UserInfo;
import com.RobinNotBad.BiliClient.util.JsonUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;

import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class OpusApi {

    /**
     * 获取图文/专栏详情。
     *
     * <p>图文动态（id &gt; 1亿）优先走官方 opus/detail 接口直取 detail JSON，接口不可用时才退回
     * 抓 www.bilibili.com 页面；专栏（cv 号，id &lt;= 1亿）官方接口不认（实测 code=4101105），
     * 继续走页面抓取。两条路径拿到的 detail 结构一致（都有 basic/modules），共用同一段解析。
     */
    public static Opus getOpus(long id) throws IOException, JSONException {
        Opus opus = new Opus();
        opus.type = Opus.TYPE_DYNAMIC;
        opus.id = id;

        // 图文动态优先走接口直取。上游 90058f5 的判定与回退条件在这里完整保留：
        //   - 只对动态（id > 1e8）启用；
        //   - 接口报错（网络失败 / WBI 签名失败 / -352 风控 / 结构变更）→ 回退网页抓取，
        //     绝不把"能用的抓取"换成"不能用的接口"；
        //   - 接口正常但明确没有图文数据（data.item 为空，如纯文字等旧式动态）→ 转旧版动态详情页；
        //   - data.fallback 指向别的载体（已知 type=2 为专栏）→ 回退网页抓取，
        //     网页会 301 到 read/cv{id}，正文照样能渲染。
        // 收益：原先无论如何都要整页下载 opus/{id}，再在整页文本里搜 "detail" 抠 SSR JSON，
        // 页面体积远大于数据本身且可能带多级 301（上游实测 2~3 秒 → 1 秒内）。
        JSONObject detail = null;
        if (id > 100000000) {
            try {
                // 实测该接口不校验 WBI 签名，但为与上游保持一致仍做签名，防官方后续收紧。
                String apiUrl = ConfInfoApi.signWBI("https://api.bilibili.com/x/polymer/web-dynamic/v1/opus/detail"
                        + "?timezone_offset=-480&features=htmlNewStyle&id=" + id);
                JSONObject root = NetWorkUtil.getJson(apiUrl);
                int code = root.optInt("code", -1);
                JSONObject data = root.optJSONObject("data");
                JSONObject item = data == null ? null : data.optJSONObject("item");
                JSONObject fallback = data == null ? null : data.optJSONObject("fallback");
                if (code != 0 || data == null) {
                    Logu.w("OpusApi", "opus/detail code=" + code + "，回退网页抓取 id=" + id);
                } else if (fallback != null && fallback.optLong("id", 0) > 0) {
                    Logu.w("OpusApi", "opus/detail 返回 fallback（实为其它载体），回退网页抓取 id=" + id);
                } else if (item == null || item.isNull("modules")) {
                    // 接口明确回答"这个 id 没有图文数据"：纯文字等旧式动态，交给旧版动态详情页渲染
                    opus.type = Opus.TYPE_DYNAMIC_OLD_STYLE;
                    return opus;
                } else {
                    detail = item; // 接口直接给了图文数据，跳过下面的整页抓取
                }
            } catch (Exception e) {
                // 接口不可用不致命：记录后继续走下面的网页抓取
                Logu.w("OpusApi", "opus/detail 不可用，回退网页抓取：" + e);
            }
        }

        // 网页兜底路径（专栏只能走这里）：
        //   专栏 id <= 1亿  -> https://www.bilibili.com/read/cv{id}
        //   动态 id >  1亿  -> https://www.bilibili.com/opus/{id}
        // 该方式不依赖 WBI 签名与登录态，在官方接口受风控/未登录场景下也能稳定加载正文。
        String url;
        if (id > 100000000)
            url = "https://www.bilibili.com/opus/" + id; // 动态 id 走 opus 页面抓取
        else url = "https://www.bilibili.com/read/cv" + id; // 专栏走 read/cv 页面抓取

        try {
            if (detail == null) detail = fetchDetailFromHtml(url);
            if (detail == null || detail.isNull("modules")) {
                // 整页也抠不到 detail（风控页/结构变更）：动态转旧版动态详情页；
                // 专栏直接抛错，让 OpusInfoActivity 的 onFailure 显示"加载失败"，
                // 而不是像以前那样返回一个各字段为空的 opus、留下一张没有任何内容的空白页。
                if (id > 100000000) {
                    opus.type = Opus.TYPE_DYNAMIC_OLD_STYLE;
                    return opus;
                }
                throw new JSONException("未能解析出图文内容，可能被风控拦截，请稍后重试");
            }

            analyzeCommentInfo(opus, detail, id);

            JSONArray modules = detail.getJSONArray("modules");

            for (int i = 0; i < modules.length(); i++) {
                JSONObject module = modules.getJSONObject(i);
                switch (module.optString("module_type")) {
                    case "MODULE_TYPE_TITLE":
                        JSONObject moduleTitle = module.optJSONObject("module_title");
                        if (moduleTitle != null) opus.title = moduleTitle.optString("text", "");
                        break;
                    case "MODULE_TYPE_TOP":
                        ArrayList<String> topImages = new ArrayList<>();
                        JSONObject module_top = module.optJSONObject("module_top");
                        if (module_top != null) {
                            JSONObject display = module_top.optJSONObject("display");
                            if (display != null) {
                                int displayType = display.optInt("type");
                                if (displayType == 1) {
                                    JSONObject album = display.optJSONObject("album");
                                    if (album != null) {
                                        JSONArray pics = album.optJSONArray("pics");
                                        if (pics != null) {
                                            for (int j = 0; j < pics.length(); j++) {
                                                JSONObject pic = pics.optJSONObject(j);
                                                if (pic != null) topImages.add(pic.optString("url", ""));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        opus.topImages = topImages;
                        break;
                    case "MODULE_TYPE_AUTHOR":
                        JSONObject module_author = module.optJSONObject("module_author");    //我感觉b站也是一个巨大的草台班子，用户信息格式都好几种，头像有avatar有face有head的，他们自己的程序员不累吗……
                        if (module_author == null) break;
                        UserInfo author = new UserInfo();
                        author.mid = module_author.optLong("mid", 0);
                        author.name = module_author.optString("name", "");
                        author.followed = module_author.optBoolean("following", false);
                        author.avatar = module_author.optString("face", module_author.optString("avatar", ""));
                        JSONObject vip = module_author.optJSONObject("vip");
                        if (vip != null)
                            author.vip_nickname_color = vip.optString("nickname_color", "");

                        opus.pubTime = module_author.optString("pub_time", "");
                        opus.upInfo = author;
                        break;
                    case "MODULE_TYPE_CONTENT":
                        JSONObject moduleContent = module.optJSONObject("module_content");
                        if (moduleContent != null) {
                            JSONArray paragraphs = moduleContent.optJSONArray("paragraphs");
                            if (paragraphs != null) opus.paragraphs = analyzeParagraphs(paragraphs);
                        }
                        break;
                    case "MODULE_TYPE_STAT":
                        opus.stats = Stats.fromOpus(module.optJSONObject("module_stat"));
                        android.util.Log.e("debug-专栏", "module_stat=" + opus.stats.toString() + " pubTime=" + opus.pubTime + " id=" + opus.id);
                        break;
                }
            }

            if (opus.upInfo == null) opus.upInfo = new UserInfo();
            if (opus.stats == null) opus.stats = new Stats();
            // 专栏（cv号）：按文章渲染，保证 OpusContentAdapter 正常展示 cv 号、阅读数等信息
            if (id <= 100000000) opus.type = Opus.TYPE_ARTICLE;
            android.util.Log.e("debug-专栏", "最终 stats view=" + opus.stats.view + " like=" + opus.stats.like + " coin=" + opus.stats.coin + " fav=" + opus.stats.favorite + " reply=" + opus.stats.reply + " pubTime=" + opus.pubTime + " upName=" + (opus.upInfo != null ? opus.upInfo.name : "null") + " id=" + opus.id + " commentType=" + opus.commentType);
        } catch (IllegalArgumentException e) { // 取不出来的时候，会重定向，但重定向的域名是//开头的，会报错
            //这里给opus设置一个参数，让OpusInfoActivity跳转到旧版的DynamicInfoActivity，从而无需重写解析
            //判断方式很简单粗暴，看报错信息里有没有URL这个关键字，有就是跳转错误
            if (id > 100000000) {
                String errMsg = e.getMessage();
                if (errMsg != null && errMsg.contains("URL")) opus.type = Opus.TYPE_DYNAMIC_OLD_STYLE;
                else MsgUtil.err(e);
                return opus;
            }
            // 专栏（cv号）：正文没抓到就直接抛出，由 OpusInfoActivity 的 onFailure 提示"加载失败"；
            // 原先返回空 opus 只会留一张空白页，用户分不清是加载失败还是内容本身为空
            throw e;
        } catch (IOException e) {
            // HTML 页面请求失败（如被风控拦截）：动态降级到旧版动态详情；
            // 专栏同样抛出，让页面显示"加载失败"而不是空白页
            if (id > 100000000) {
                opus.type = Opus.TYPE_DYNAMIC_OLD_STYLE;
                return opus;
            }
            throw e;
        }

        // 能走到这里说明内容已解析完成
        // B站是会做图文的
        opus.cover = "";
        // 兜底保证关键字段非空，避免详情页/适配器空指针
        if (opus.upInfo == null) opus.upInfo = new UserInfo();
        if (opus.stats == null) opus.stats = new Stats();
        return opus;
    }

    /**
     * 网页兜底路径：抓取 opus/read 页面，从 SSR 内嵌的 __INITIAL_STATE__.detail 里抠出 detail JSON。
     * 官方接口不可用（风控/未登录/结构变更）时靠它保证内容照样能加载。
     *
     * @return detail；页面被风控拦截或抠不出内容时返回 null
     */
    private static JSONObject fetchDetailFromHtml(String url) throws IOException, JSONException {
        // 抓取 HTML 页面并从中提取 detail。B 站对无完整 Cookie 的请求可能返回风控/异常页
        // （不含 __INITIAL_STATE__.detail），此时内容不可用，重试整个抓取以提升成功率。
        String html = null;
        for (int retry = 0; retry < 3; retry++) {
            Response response = NetWorkUtil.getHtml(url);
            // /read/cv{id} 有多层301重定向（加斜杠、跳转到/opus/），循环跟随直到拿到最终页面
            for (int i = 0; i < 5; i++) {
                String location = response.header("Location");
                if (location == null || location.isEmpty()) break;
                // Location 可能是 "//www.bilibili.com/..." 这种协议相对地址，直接丢给
                // Request.Builder.url 会抛 IllegalArgumentException。基于当前响应的 URL
                // 解析成绝对地址，既兼容协议相对/相对路径，也避免白名单比对上拿到 null host。
                HttpUrl target = response.request().url().resolve(location);
                response.close();
                // 安全边界（审计 P9）：跟跳会带上完整登录 Cookie，目标必须落在 B 站域名白名单内；
                // 命中不了就放弃这次抓取，绝不把凭据带到任意主机。
                if (target == null || !NetWorkUtil.isBilibiliHost(target.host())) return null;
                response = NetWorkUtil.getHtml(target.toString());
            }
            ResponseBody responseBody = response.body();
            if (responseBody != null) {
                html = responseBody.string();
                android.util.Log.e("debug-专栏", "第" + (retry + 1) + "次 html长度=" + html.length() + " 含detail=" + html.contains("\"detail\":") + " 含INITIAL_STATE=" + html.contains("__INITIAL_STATE__"));
                if (html.contains("\"detail\"") && html.contains("__INITIAL_STATE__")) break; // 拿到正常内容页
            }
            if (html == null) html = "";
        }

        String detailStr = JsonUtil.search(html, "detail", "");
        if (detailStr.isEmpty()) {
            android.util.Log.e("debug-专栏", "detail为空");
            return null;
        }
        return new JSONObject(detailStr);  //效率不高 能用就行 死去的jsonUtil居然还能发光发热
    }

    public static void analyzeCommentInfo(Opus opus, JSONObject detail, long id) {
        JSONObject basic = detail.optJSONObject("basic");
        if (basic != null) {
            String commentIdStr = basic.optString("comment_id_str", "0");
            try {
                opus.commentId = Long.parseLong(commentIdStr);
            } catch (NumberFormatException ignored) {
                opus.commentId = 0;
            }
            opus.commentType = basic.optInt("comment_type", 0);
        }

        if (opus.commentId == 0) opus.commentId = id;
        if (opus.commentType == 0) opus.commentType = 17;
    }

    public static OpusParagraph[] analyzeParagraphs(JSONArray jsonArray) throws JSONException {
        OpusParagraph[] paragraphs = new OpusParagraph[jsonArray.length()];
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject paragraphJson = jsonArray.getJSONObject(i);
            try {
                OpusParagraph paragraph = new OpusParagraph(paragraphJson);
                paragraphs[i] = paragraph;
            } catch (Exception e) {
                // 单个段落解析失败（如某字段结构异常）不应中断整篇正文解析，
                // 用文本段落占位，保证其余内容正常渲染。
                OpusParagraph fallback = new OpusParagraph();
                fallback.type = OpusParagraph.TYPE_TEXT;
                fallback.content = "";
                paragraphs[i] = fallback;
            }
        }
        return paragraphs;
    }

    /**
     * Opus/动态点赞
     *
     * @param dynId 动态id
     * @param up    true=点赞，false=取消赞
     * @return resultCode
     */
    public static int likeOpus(long dynId, boolean up) throws IOException {
        String csrf = NetWorkUtil.currentCsrf();
        String url = "https://api.bilibili.com/x/dynamic/feed/dyn/thumb?csrf=" + csrf;
        JSONObject payload = new JSONObject();
        try {
            payload.put("dyn_id_str", String.valueOf(dynId));
            payload.put("up", up ? 1 : 2);
            payload.put("csrf", csrf);
        } catch (JSONException ignored) {
            return -1;
        }
        Response resp = NetWorkUtil.postJson(url, payload.toString(), NetWorkUtil.webHeaders);
        if (resp == null) return -1;
        ResponseBody respBody = resp.body();
        if (respBody == null) return -1;
        try {
            JSONObject respJson = new JSONObject(respBody.string());
            return respJson.getInt("code");
        } catch (JSONException ignored) {
            return -1;
        }
    }

    public static void analyzeOldStyleDynamic(Opus opus, JSONObject item) throws JSONException {
        JSONObject basic = item.getJSONObject("basic");
        opus.commentId = Long.parseLong(basic.optString("comment_id_str", "0"));
        opus.commentType = basic.optInt("comment_type");

        String dynamicType = item.getString("type");

        if (item.isNull("modules")) return;
        JSONObject modules = item.getJSONObject("modules");

        //up主信息
        UserInfo author = new UserInfo();
        if (!modules.isNull("module_author")) {
            JSONObject module_author = modules.getJSONObject("module_author");
            author.mid = module_author.getLong("mid");
            author.name = module_author.getString("name");
            author.followed = module_author.optBoolean("following", false);
            author.avatar = module_author.getString("face");
            if (!module_author.isNull("vip"))
                author.vip_nickname_color = module_author.getJSONObject("vip").optString("nickname_color", "");
            opus.pubTime = module_author.getString("pub_time");
        }
        opus.upInfo = author;

        if (dynamicType.equals("DYNAMIC_TYPE_NONE")) {
            opus.content = "[动态不存在]";
            return;
        }

        //动态主体内容
        JSONObject module_dynamic = modules.getJSONObject("module_dynamic");

        ArrayList<OpusParagraph> paragraphList = new ArrayList<>();

        if (!module_dynamic.isNull("desc")) {
            JSONObject object = new JSONObject();
            object.put("para_type", OpusParagraph.TYPE_TEXT_OPUS);
            object.put("data", module_dynamic.getJSONObject("desc").getJSONArray("rich_text_nodes"));
            paragraphList.add(new OpusParagraph(object));
        }

        if (!module_dynamic.isNull("major")) {
            JSONObject major = module_dynamic.getJSONObject("major");

            if (!major.isNull("opus")) {
                JSONObject dynamic_opus = major.getJSONObject("opus");
                JSONArray opus_pics = dynamic_opus.getJSONArray("pics");

                // 为了排版正常，这里必须把列表完整传递给OpusParagraph，让OpusParagraph那边解析
                // 这么干主要是为了适配这神秘的代码结构，我研究OpusParagraph的使用方法就研究了半天
                // by Moye

                JSONObject object = new JSONObject();
                object.put("para_type", OpusParagraph.TYPE_TEXT_OPUS);
                object.put("data", dynamic_opus.getJSONObject("summary").getJSONArray("rich_text_nodes"));
                paragraphList.add(new OpusParagraph(object));

                object = new JSONObject();
                object.put("para_type", OpusParagraph.TYPE_PIC);
                object.put("pic", new JSONObject().put("pics", opus_pics));
                paragraphList.add(new OpusParagraph(object));
            }

            if (!major.isNull("archive")) {
                // 这里是视频卡片
            }

        }

        opus.paragraphs = paragraphList.toArray(new OpusParagraph[0]);

        JSONObject module_stat = modules.getJSONObject("module_stat");
        Stats stats = new Stats();
        stats.reply = module_stat.getJSONObject("comment").getInt("count");
        stats.like = module_stat.getJSONObject("like").getInt("count");

        opus.stats = stats;
    }
}
