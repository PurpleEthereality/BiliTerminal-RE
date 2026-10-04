package com.RobinNotBad.BiliClient.model;

import static com.RobinNotBad.BiliClient.api.ReplyApi.TOP_TIP;
import static com.RobinNotBad.BiliClient.api.ReplyApi.isDislikedAction;
import static com.RobinNotBad.BiliClient.api.ReplyApi.isLikedAction;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.util.EmoteUtil;
import com.RobinNotBad.BiliClient.util.JsonUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.RobinNotBad.BiliClient.util.TimeUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Reply implements Serializable {
    public long rpid;
    public long oid;
    public long root;
    public long parent;
    public boolean forceDelete;
    public String ofBvid = "";
    public String pubTime;
    public UserInfo sender;
    public CharSequence message;
    public ArrayList<String> pictureList = new ArrayList<>();
    public int likeCount;
    public boolean upLiked;
    public boolean upReplied;
    public boolean liked;
    /** 是否已点踩（服务端 action==2）。与 {@link #liked} 互斥，由 {@link #parseAction(int)} 统一判定。 */
    public boolean disliked;
    public int childCount;
    public boolean isDynamic;
    public ArrayList<Reply> childMsgList = new ArrayList<>();
    public boolean isTop;
    public long voteId;  // 评论中的投票 id，-1 表示无投票

    public Reply() {
    }

    /**
     * @param isRoot    是否是根评论
     * @param replyJson 评论json对象
     * @throws JSONException json解析异常
     */
    public Reply(boolean isRoot, JSONObject replyJson) throws JSONException {
        this.rpid = replyJson.getLong("rpid");
        this.oid = replyJson.getLong("oid");
        this.root = replyJson.getLong("root");
        this.parent = replyJson.getLong("parent");
        this.sender = new UserInfo(replyJson.getJSONObject("member"));

        JSONObject content = replyJson.getJSONObject("content");

        JSONObject replyCtrl = replyJson.getJSONObject("reply_control");
        long ctime = replyJson.getLong("ctime") * 1000;

        String time;
        if (System.currentTimeMillis() - ctime < 3 * 24 * 60 * 60 * 1000 && replyCtrl.has("time_desc")) {
            time = replyCtrl.getString("time_desc");
        } else {
            time = TimeUtil.formatDateTime(ctime, Locale.SIMPLIFIED_CHINESE);
        }

        if (replyCtrl.has("location") && !replyCtrl.isNull("location")) {
            // location 形如 "IP属地：xx"，去掉前缀只留地址；空串或异常格式时直接用原值，避免越界
            String location = replyCtrl.getString("location");
            if (location.length() > 5) location = location.substring(5);
            time += " | IP:" + location;
        }
        this.pubTime = time;
        this.voteId = -1;

        if (replyCtrl.has("is_up_top")) {
            if (replyCtrl.getBoolean("is_up_top")) {
                this.isTop = true;
            }
        }

        SpannableStringBuilder messageSpannable = new SpannableStringBuilder((isTop ? TOP_TIP : "")
                + StringUtil.htmlToString(content.getString("message")));

        if (isTop) StringUtil.setTopSpan(messageSpannable);

        this.likeCount = replyJson.getInt("like");
        parseAction(replyJson.optInt("action", 0));

        if (content.has("emote") && !content.isNull("emote")) {
            ArrayList<Emote> emoteList = new ArrayList<>();
            JSONObject emoteJson = content.getJSONObject("emote");
            ArrayList<String> emoteKeys = JsonUtil.getJsonKeys(emoteJson);

            for (String emoteKey : emoteKeys) {
                JSONObject key = emoteJson.getJSONObject(emoteKey);
                emoteList.add(new Emote(
                        emoteKey,
                        key.getString("url"),
                        key.getJSONObject("meta").getInt("size")
                ));
            }

            EmoteUtil.textReplaceEmote(messageSpannable.toString(), emoteList, 1.0f, BiliTerminal.context, messageSpannable);
        }

        // 检测并替换投票占位符 {vote:vote_id}
        Pattern votePattern = Pattern.compile("\\{vote:(\\d+)\\}");
        Matcher voteMatcher = votePattern.matcher(messageSpannable);
        if (voteMatcher.find()) {
            this.voteId = Long.parseLong(voteMatcher.group(1));
            String voteLabel = " 投票 ";
            int start = voteMatcher.start();
            int end = voteMatcher.end();
            messageSpannable.replace(start, end, voteLabel);
            messageSpannable.setSpan(new ForegroundColorSpan(0xFFFE679A), start, start + voteLabel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            messageSpannable.setSpan(new UnderlineSpan(), start, start + voteLabel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        StringUtil.setLink(messageSpannable);

        if (content.has("at_name_to_mid") && !content.isNull("at_name_to_mid")) {
            JSONObject jsonObject = content.getJSONObject("at_name_to_mid");
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long val = jsonObject.getLong(key);
                StringUtil.setSingleAt(messageSpannable, key, val);
            }
        }

        JSONObject upAction = replyJson.getJSONObject("up_action");
        this.upLiked = upAction.getBoolean("like");
        this.upReplied = upAction.getBoolean("reply");


        if (isRoot) {
            if (content.has("pictures") && !content.isNull("pictures")) {
                ArrayList<String> pictureList = new ArrayList<>();
                JSONArray pictures = content.getJSONArray("pictures");
                for (int j = 0; j < pictures.length(); j++) {
                    JSONObject picture = pictures.getJSONObject(j);
                    pictureList.add(picture.getString("img_src"));
                }
                this.pictureList = pictureList;
            }

            this.childCount = replyJson.getInt("rcount");

            if (replyJson.has("replies") && !replyJson.isNull("replies")) {
                ArrayList<Reply> childMsgList = new ArrayList<>();
                JSONArray childReplies = replyJson.getJSONArray("replies");
                for (int j = 0; j < childReplies.length(); j++) {
                    JSONObject childReply = childReplies.getJSONObject(j);
                    childMsgList.add(new Reply(false, childReply));
                }
                this.childMsgList = childMsgList;
            }
        }

        this.message = messageSpannable;
    }

    /**
     * 纯逻辑：把服务端评论的 {@code action} 字段落到 {@link #liked}/{@link #disliked} 两个互斥状态上。
     *
     * <p>0=无操作、1=已点赞、2=已点踩（bilibili-API/docs/comment/readme.md）。
     * 旧实现只判 {@code action == 1}，于是「我踩过的评论」重进页面后显示成「没操作过」，
     * UI 上就会允许再踩一次（服务端返回「已经点过踩了」）。
     *
     * @param action 服务端返回的 action 字段，未知值按「无操作」处理
     */
    public void parseAction(int action) {
        this.liked = isLikedAction(action);
        this.disliked = isDislikedAction(action);
    }

    /**
     * 纯逻辑：清掉一批评论里的置顶标记，返回被清掉的条数。
     *
     * <p>服务端一个评论区**只有一个置顶位**（再置顶别的评论会回 12029），
     * 而 {@link #isTop} 是逐条布尔。置顶成功后若不清理旧标记，列表里会同时
     * 出现两条带「[置顶]」前缀的评论。抽成纯函数便于 JVM 单测。
     *
     * <p>走 {@link #setTopFlag(boolean)} 而不是直接改字段，是因为显示文本里的「[置顶]」
     * 前缀是构造时就拼好的，只翻布尔值会导致 {@code notifyItemChanged} 之后界面纹丝不动。
     *
     * @param replies 要清理的评论列表，允许为 null
     * @return 真正被清掉的条数（用于判断要不要刷新列表）
     */
    public static int clearTopFlags(List<Reply> replies) {
        if (replies == null) return 0;
        int cleared = 0;
        for (Reply reply : replies) {
            if (reply != null && reply.isTop) {
                reply.setTopFlag(false);
                cleared++;
            }
        }
        return cleared;
    }

    /**
     * 同步「置顶」状态到显示文本：置顶时补上 {@code [置顶]} 前缀与主色 span，取消时切掉。
     *
     * <p>为什么要动文本而不是只改 {@link #isTop}：前缀在构造方法里就拼进了 {@link #message}，
     * 适配器绑定时是 {@code textView.text = reply.message}，只翻布尔值重新绑定看不出任何变化。
     * 前缀永远在第 0 位，所以取消置顶时按长度切掉即可；后面的 span（表情、@、投票、
     * 超链接）由 SpannableStringBuilder 自动平移。
     *
     * <p>JVM 单测里 {@link #message} 为 null（没有构造过），此时只翻状态，
     * 所以这个方法和 {@link #clearTopFlags(List)} 都能在纯 JVM 下断言。
     *
     * @param top 目标状态
     */
    public void setTopFlag(boolean top) {
        if (this.isTop == top) return;
        CharSequence text = this.message;
        if (text instanceof SpannableStringBuilder) {
            SpannableStringBuilder builder = (SpannableStringBuilder) text;
            if (top) {
                builder.insert(0, TOP_TIP);
                StringUtil.setTopSpan(builder);
            } else if (builder.length() >= TOP_TIP.length()
                    && TOP_TIP.contentEquals(builder.subSequence(0, TOP_TIP.length()))) {
                builder.delete(0, TOP_TIP.length());
            }
        }
        this.isTop = top;
    }
}
