package com.RobinNotBad.BiliClient.adapter

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.NonNull
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.button.MaterialButton
import com.RobinNotBad.BiliClient.activity.vote.VoteInfoActivity
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.ImageViewerActivity
import com.RobinNotBad.BiliClient.activity.reply.ReplyInfoActivity
import com.RobinNotBad.BiliClient.activity.reply.WriteReplyActivity
import com.RobinNotBad.BiliClient.api.ReplyApi
import com.RobinNotBad.BiliClient.api.VoteApi
import com.RobinNotBad.BiliClient.model.Reply
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.ui.widget.RadiusBackgroundSpan
import com.RobinNotBad.BiliClient.ui.appearance.ColorScheme
import com.RobinNotBad.BiliClient.listener.OnItemClickListener
import java.io.IOException
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import org.json.JSONException

@SuppressLint("ClickableViewAccessibility")
class ReplyAdapter(
    val context: Context,
    val replyList: ArrayList<Reply>,
    val oid: Long,
    val up_mid: Long,
    val root: Long,
    val type: Int,
    var sort: Int,
    val replyType: Int
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var isDetail: Boolean = false
    var isManager: Boolean = false
    var count: Long = -1
    var listener: OnItemClickListener? = null

    private val roundSmallPx = context.resources.getDimension(R.dimen.round_small).toInt()
    private val likeDrawable0 = ContextCompat.getDrawable(context, R.drawable.icon_reply_like0)
    private val likeDrawable1 = ContextCompat.getDrawable(context, R.drawable.icon_reply_like1)

    // 点赞去重：记录正在请求中的评论 rpid。
    // 不加这个的话用户连点会连发好几次点赞/取消请求，服务端按最后一次算而界面按每次算，
    // 结果就是点赞状态和服务端对不上。用同步 Set 是因为它会被点击线程和请求线程同时访问。
    private val likingRpids: MutableSet<Long> = Collections.synchronizedSet(HashSet<Long>())

    fun setOnSortSwitchListener(listener: OnItemClickListener) {
        this.listener = listener
    }

    // 主评论列表（非详情页）的排序名。0/1 位置写「未知排序」是因为主列表走服务端排序的 2/3 两档
    private val sortNames = arrayOf("未知排序", "未知排序", "时间排序", "热度排序")

    /**
     * 排序按钮上的文案。
     *
     * 详情页（楼中楼）用 [Reply.SORT_TIME]/[Reply.SORT_LIKE] 这两档本地排序，
     * 主列表用服务端的 2/3 两档，取值不一样必须分开取名——否则详情页永远显示「未知排序」。
     */
    private fun sortLabel(): String =
        if (isDetail) (if (sort == Reply.SORT_TIME) "时间排序" else "热度排序")
        else sortNames.getOrElse(sort) { "未知排序" }

    /**
     * 把「点踩/取消点踩」的结果落到本地状态与视图上。
     *
     * 服务端点踩成功会同时消去该评论的点赞，反向亦然（见 bilibili-API/docs/comment/action.md）。
     * 所以这里必须把 liked/disliked 两个互斥状态一起改，否则界面上会出现「已赞 + 已踩」并存的假象，
     * 下次重新拉取列表时又突然变回去，用户会以为按钮坏了。
     */
    private fun applyDislikeResult(holder: ReplyHolder, reply: Reply, disliked: Boolean) {
        if (disliked && reply.liked) {
            // 本地把点赞撤掉时，计数和图标要同步回落到未赞状态
            reply.liked = false
            reply.likeCount = (reply.likeCount - 1).coerceAtLeast(0)
            holder.likeCount.text = StringUtil.toWan(reply.likeCount.toLong())
            holder.likeCount.setTextColor(Color.rgb(0xff, 0xff, 0xff))
            holder.likeCount.setCompoundDrawablesWithIntrinsicBounds(likeDrawable0, null, null, null)
        }
        reply.disliked = disliked
        if (disliked) holder.dislikeBtn.setColorFilter(ColorScheme.LIKE_COLOR)
        else holder.dislikeBtn.clearColorFilter()
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 0) {
            val view = LayoutInflater.from(this.context).inflate(R.layout.cell_reply_action, parent, false)
            return WriteReply(view)
        } else {
            val view = LayoutInflater.from(this.context).inflate(R.layout.cell_reply_list, parent, false)
            return ReplyHolder(view)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(@NonNull holder: RecyclerView.ViewHolder, @SuppressLint("RecyclerView") position: Int) {
        if (holder is WriteReply) {
            val writeReply = holder as WriteReply
            writeReply.write_reply.setOnClickListener {
                val intent = Intent()
                intent.setClass(context, WriteReplyActivity::class.java)
                intent.putExtra("oid", oid)
                intent.putExtra("rpid", root)
                intent.putExtra("parent", root)
                intent.putExtra("parentSender", "")
                intent.putExtra("replyType", replyType)
                context.startActivity(intent)
            }
            if (isDetail) {
                // 评论详情页也要能排序。服务端的楼中楼接口（/x/v2/reply/reply）没有 sort 参数，
                // 传了也不生效，所以这里的排序完全在客户端做（Reply.sortReplies），
                // 点一下即时重排、不重新请求，见 ReplyInfoActivity.setOnSortSwitch。
                writeReply.sort.visibility = View.VISIBLE
                writeReply.count_label.visibility = View.GONE
                writeReply.sort.text = sortLabel()
                writeReply.sort.setOnClickListener {
                    listener?.onItemClick(0)
                    writeReply.sort.text = sortLabel()
                }
            } else {
                writeReply.sort.text = sortLabel()
                writeReply.sort.setOnClickListener {
                    listener?.onItemClick(0)
                    writeReply.sort.text = sortLabel()
                }
                writeReply.count_label.text = "共" + count + "条评论"
            }
        }
        if (holder is ReplyHolder) {
            val realPosition: Int
            if (isDetail) {
                realPosition = if (position != 0) position - 1 else 0
            } else {
                realPosition = position - 1
            }
            if (realPosition < 0 || realPosition >= replyList.size)
                return

            val replyHolder = holder as ReplyHolder
            val reply = replyList[realPosition]
            if (reply == null || reply.sender == null)
                return

            if (GlideUtil.url(reply.sender!!.avatar) != replyHolder.lastAvatarUrl) {
                replyHolder.lastAvatarUrl = GlideUtil.url(reply.sender!!.avatar)
                Glide.with(BiliTerminal.context!!).asDrawable().load(GlideUtil.url(reply.sender!!.avatar))
                        .transition(GlideUtil.getTransitionOptions())
                        .placeholder(R.mipmap.akari)
                        .error(R.mipmap.akari)
                        .apply(RequestOptions.circleCropTransform())
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .into(replyHolder.replyAvatar)
            }

            val sender = reply.sender!!
            val name_str = SpannableStringBuilder()

            if (!TextUtils.isEmpty(sender.vip_nickname_color)
                    && !SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NO_VIP_COLOR, false))
                replyHolder.userName.setTextColor(Color.parseColor(sender.vip_nickname_color))

            if (sender.mid == up_mid) {
                name_str.append(" UP ")
                name_str.append(reply.sender!!.name)
                name_str.setSpan(
                        RadiusBackgroundSpan(2, roundSmallPx,
                                Color.WHITE, ColorScheme.PRIMARY),
                        0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                name_str.setSpan(RelativeSizeSpan(0.8f), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else
                name_str.append(sender.name)
            var last_length = name_str.length
            name_str.append(" ").append(sender.level.toString())
            if (sender.is_senior_member == 1)
                name_str.append("+")
            name_str.setSpan(StringUtil.getLevelBadge(context, sender), last_length + 1, name_str.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            if (!TextUtils.isEmpty(sender.medal_name)) {
                last_length = name_str.length
                name_str.append("  ").append(sender.medal_name).append("Lv").append(sender.medal_level.toString())
                        .append(" ")
                name_str.setSpan(
                        RadiusBackgroundSpan(2, roundSmallPx,
                                Color.WHITE, Color.argb(140, 158, 186, 232)),
                        last_length + 1, name_str.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                name_str.setSpan(RelativeSizeSpan(0.8f), last_length + 1, name_str.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            replyHolder.userName.text = name_str

            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.REPLY_MARQUEE_NAME, true)) {
                replyHolder.userName.setSingleLine(true)
                replyHolder.userName.maxLines = 1
            } else {
                replyHolder.userName.setSingleLine(false)
                replyHolder.userName.maxLines = 3
            }

            replyHolder.message.text = reply.message
            StringUtil.setCopy(replyHolder.message)
            replyHolder.message.setOnTouchListener(StringUtil.ClickableSpanTouchListener.getInstance())

            replyHolder.likeCount.text = StringUtil.toWan(reply.likeCount.toLong())

            if (reply.liked) {
                replyHolder.likeCount.setTextColor(ColorScheme.LIKE_COLOR)
                replyHolder.likeCount.setCompoundDrawablesWithIntrinsicBounds(
                        likeDrawable1, null, null, null)
            } else {
                replyHolder.likeCount.setTextColor(Color.rgb(0xff, 0xff, 0xff))
                replyHolder.likeCount.setCompoundDrawablesWithIntrinsicBounds(
                        likeDrawable0, null, null, null)
            }

            // 已踩状态：icon_dislike 是单色矢量，用同一套高亮色即可，不必再配一个图标资源。
            // 这一步原来完全没做，所以「我踩过的评论」重进页面后看起来像没操作过。
            if (reply.disliked) {
                replyHolder.dislikeBtn.setColorFilter(ColorScheme.LIKE_COLOR)
            } else {
                replyHolder.dislikeBtn.clearColorFilter()
            }

            if (reply.childCount != 0 && !(realPosition == 0 && isDetail)) {
                replyHolder.childReplyCard.visibility = View.VISIBLE
                replyHolder.childCount.setTextColor(ColorScheme.PRIMARY)

                if (reply.upReplied)
                    replyHolder.childCount.text = "UP主在内 共" + reply.childCount + "条回复"
                else
                    replyHolder.childCount.text = "共" + reply.childCount + "条回复"

                if (reply.childMsgList != null && replyHolder.childReplies != null) {
                    val childCount = reply.childMsgList!!.size
                    val existingViewCount = replyHolder.childReplies!!.childCount

                    for (i in 0 until childCount) {
                        val child = reply.childMsgList!![i]
                        if (child == null || child.sender == null)
                            continue

                        val childMsg = SpannableStringBuilder()
                        if (child.sender!!.mid == up_mid) {
                            childMsg.append(" UP ")
                            childMsg.append(child.sender!!.name)
                            childMsg.setSpan(RadiusBackgroundSpan(2,
                                    roundSmallPx, Color.WHITE,
                                    ColorScheme.PRIMARY), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            childMsg.setSpan(RelativeSizeSpan(0.8f), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        } else
                            childMsg.append(child.sender!!.name)

                        childMsg.append("：").append(child.message)

                        val textView: TextView
                        if (i < existingViewCount) {
                            textView = replyHolder.childReplies!!.getChildAt(i) as TextView
                            textView.visibility = View.VISIBLE
                        } else {
                            @SuppressLint("InflateParams")
                            val newTextView = LayoutInflater.from(context)
                                    .inflate(R.layout.cell_reply_child, null) as TextView
                            replyHolder.childReplies!!.addView(newTextView)
                            textView = newTextView
                        }
                        textView.text = childMsg
                    }

                    for (i in childCount until existingViewCount) {
                        replyHolder.childReplies!!.getChildAt(i).visibility = View.GONE
                    }
                }
            } else
                replyHolder.childReplyCard.visibility = View.GONE

            if (reply.upLiked)
                replyHolder.upLiked.visibility = View.VISIBLE
            else
                replyHolder.upLiked.visibility = View.GONE

            replyHolder.pubDate.text = reply.pubTime

            if (reply.pictureList != null && !reply.pictureList!!.isEmpty()) {
                replyHolder.imageCard.visibility = View.VISIBLE
                replyHolder.imageCount.visibility = View.VISIBLE

                val firstImageUrl = GlideUtil.url(reply.pictureList!![0])
                if (firstImageUrl != replyHolder.lastImageUrl) {
                    replyHolder.lastImageUrl = firstImageUrl
                    Glide.with(BiliTerminal.context!!).asDrawable().load(firstImageUrl)
                            .transition(GlideUtil.getTransitionOptions())
                            .placeholder(R.mipmap.placeholder)
                            .error(R.mipmap.placeholder)
                            .format(DecodeFormat.PREFER_RGB_565)
                            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                            .into(replyHolder.imageCard)
                }

                replyHolder.imageCount.text = "共" + reply.pictureList!!.size + "张图片"
                replyHolder.imageCard.setOnClickListener {
                    val intent = Intent()
                    intent.setClass(context, ImageViewerActivity::class.java)
                    intent.putExtra("imageList", reply.pictureList)
                    context.startActivity(intent)
                }
            } else {
                replyHolder.imageCount.visibility = View.GONE
                replyHolder.imageCard.visibility = View.GONE
            }

            replyHolder.childReplyCard.setOnClickListener { startReplyInfoActivity(reply) }
            if (!isDetail) {
                replyHolder.itemView.setOnClickListener { startReplyInfoActivity(reply) }
                replyHolder.message.setOnClickListener {
                    if (reply.voteId > 0) {
                        // 点击投票标签，展示投票信息
                        showVoteDialog(reply.voteId, replyHolder.message)
                    } else {
                        startReplyInfoActivity(reply)
                    }
                }
            }

            replyHolder.replyAvatar.setOnClickListener {
                BiliTerminal.jumpToUser(context, reply.sender!!.mid)
            }

            replyHolder.likeCount.setOnClickListener {
                // 客户端去重：同一条评论的点赞请求还没回来之前，后续连点直接忽略，
                // 否则会连发好几次点赞/取消，服务端只认最后一次，界面状态就和服务端对不上了
                if (!likingRpids.add(reply.rpid)) {
                    MsgUtil.showMsg("正在处理中")
                    return@setOnClickListener
                }
                CenterThreadPool.run {
                    try {
                        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0L) {
                            (context as Activity).runOnUiThread { MsgUtil.showMsg("还没有登录喵~") }
                            return@run
                        }
                        if (!reply.liked) {
                            try {
                                // 点赞必须带上评论所属的评论区类型：动态/专栏的评论传 1 会被服务端拒绝
                                val likeCode = ReplyApi.likeReply(oid, reply.rpid, replyType, true)
                                if (likeCode == 0) {
                                    reply.liked = true
                                    // 点赞会同时消去点踩（服务端语义），本地状态必须一起改
                                    reply.disliked = false
                                    (context as Activity).runOnUiThread {
                                        MsgUtil.showMsg("点赞成功")
                                        replyHolder.dislikeBtn.clearColorFilter()
                                        replyHolder.likeCount.text = StringUtil.toWan((++reply.likeCount).toLong())
                                        replyHolder.likeCount.setTextColor(ColorScheme.LIKE_COLOR)
                                        replyHolder.likeCount.setCompoundDrawablesWithIntrinsicBounds(
                                                likeDrawable1, null, null,
                                                null)
                                    }
                                } else
                                    (context as Activity).runOnUiThread {
                                        MsgUtil.showMsg(ReplyApi.actionErrorMsg(likeCode).ifEmpty { "点赞失败" })
                                    }
                            } catch (e: IOException) {
                                e.printStackTrace()
                            } catch (e: JSONException) {
                                e.printStackTrace()
                            }
                        } else {
                            try {
                                val unlikeCode = ReplyApi.likeReply(oid, reply.rpid, replyType, false)
                                if (unlikeCode == 0) {
                                    reply.liked = false
                                    (context as Activity).runOnUiThread {
                                        MsgUtil.showMsg("取消成功")
                                        replyHolder.likeCount.text = StringUtil.toWan((--reply.likeCount).toLong())
                                        replyHolder.likeCount.setTextColor(Color.rgb(0xff, 0xff, 0xff))
                                        replyHolder.likeCount.setCompoundDrawablesWithIntrinsicBounds(
                                                likeDrawable0, null, null,
                                                null)
                                    }
                                } else
                                    (context as Activity).runOnUiThread {
                                        MsgUtil.showMsg(ReplyApi.actionErrorMsg(unlikeCode).ifEmpty { "取消失败" })
                                    }
                            } catch (e: IOException) {
                                e.printStackTrace()
                            } catch (e: JSONException) {
                                e.printStackTrace()
                            }
                        }
                    } finally {
                        // 成功、失败、未登录、抛异常都必须摘掉标志，
                        // 否则这条评论会永久卡在"正在处理中"，再也点不动
                        likingRpids.remove(reply.rpid)
                    }
                }
            }

            replyHolder.dislikeBtn.setOnClickListener {
                // 复用点赞那把锁：赞和踩在服务端互斥，两个请求并发会互相抵消，
                // 所以「这条评论有反馈请求在飞」时，赞和踩都要一起挡住
                if (!likingRpids.add(reply.rpid)) {
                    MsgUtil.showMsg("正在处理中")
                    return@setOnClickListener
                }
                CenterThreadPool.run {
                    try {
                        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0L) {
                            (context as Activity).runOnUiThread { MsgUtil.showMsg("还没有登录喵~") }
                            return@run
                        }
                        val target = !reply.disliked
                        try {
                            val hateCode = ReplyApi.dislikeReply(oid, reply.rpid, replyType, target)
                            if (hateCode == 0) {
                                (context as Activity).runOnUiThread {
                                    applyDislikeResult(replyHolder, reply, target)
                                    MsgUtil.showMsg(if (target) "已点踩" else "已取消")
                                }
                            } else {
                                val msg = ReplyApi.actionErrorMsg(hateCode)
                                (context as Activity).runOnUiThread {
                                    MsgUtil.showMsg(msg.ifEmpty { "操作失败" })
                                }
                            }
                        } catch (e: IOException) {
                            e.printStackTrace()
                        } catch (e: JSONException) {
                            e.printStackTrace()
                        }
                    } finally {
                        likingRpids.remove(reply.rpid)
                    }
                }
            }

            if (isManager || reply.sender!!.mid == SharedPreferencesUtil.getLong("mid", 0)) {
                replyHolder.item_reply_delete.visibility = View.VISIBLE
                replyHolder.item_reply_delete.setOnClickListener { MsgUtil.showMsg("长按操作") }
                replyHolder.item_reply_delete.setOnLongClickListener {
                    showManageMenu(reply)
                    true
                }
            } else
                replyHolder.item_reply_delete.visibility = View.GONE

            replyHolder.replyBtn.setOnClickListener {
                val noParent = isDetail && realPosition == 0
                val intent = Intent()
                intent.setClass(context, WriteReplyActivity::class.java)
                intent.putExtra("oid", oid)
                intent.putExtra("rpid", if (noParent) root else reply.rpid)
                intent.putExtra("parent", if (noParent) root else reply.rpid)
                intent.putExtra("replyType", replyType)
                intent.putExtra("pos", realPosition)
                if (root != 0L && !noParent)
                    intent.putExtra("parentSender", reply.sender!!.name)
                else
                    intent.putExtra("parentSender", "")
                context.startActivity(intent)
            }
        }
    }

    /**
     * 评论管理菜单（长按最后一列的操作按钮弹出）。
     *
     * <p>取代原来「连点两次长按才删除」的交互：删除、置顶、取消置顶都收进一个弹窗，
     * 用户看得见每条操作是干什么的，也不必再记「再长按一次」。
     *
     * <p>置顶项只在 [isManager] 为 true（视频 UP 主 / 合作稿 staff）时出现。
     * 服务端对置顶的硬性要求是「本评论区的一级评论」（错误码 12030）且一个区只有一个
     * 置顶位（12029），最终以服务端判定为准；这里不展示必然失败的入口只是为了少打扰，
     * 评论区管理员（非 UP）的场景留给以后，判定逻辑与权限完全分开。
     */
    private fun showManageMenu(reply: Reply) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (isManager) {
            actions.add((if (reply.isTop) "取消置顶" else "置顶评论") to
                    { setReplyTop(reply, !reply.isTop) })
        }
        actions.add("删除评论" to { confirmDeleteReply(reply) })
        AlertDialog.Builder(context)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .show()
    }

    /**
     * 置顶 / 取消置顶一条评论。
     *
     * <p>两个容易踩的点：
     * ① 服务端一个评论区只有一个置顶位（再置顶别的会回 12029），而 [Reply.isTop] 是逐条布尔，
     * 所以置顶成功后必须把本地其它条目的标记一起清掉，否则列表里会同时出现两条「[置顶]」；
     * ② `[置顶]` 前缀是构造 [Reply] 时拼进显示文本的，只改布尔值界面不会变，
     * 因此统一走 [Reply.setTopFlag] 让它同步增删前缀，再 notify 重新绑定。
     */
    private fun setReplyTop(reply: Reply, top: Boolean) {
        CenterThreadPool.run {
            try {
                val code = ReplyApi.topReply(oid, reply.rpid, replyType, top)
                (context as Activity).runOnUiThread {
                    if (code == 0) {
                        if (top) Reply.clearTopFlags(replyList)
                        reply.setTopFlag(top)
                        notifyItemRangeChanged(0, itemCount)
                        MsgUtil.showMsg(if (top) "置顶成功~" else "已取消置顶")
                    } else {
                        MsgUtil.showMsg(ReplyApi.actionErrorMsg(code).ifEmpty { "操作失败（$code）" })
                    }
                }
            } catch (e: Exception) {
                (context as Activity).runOnUiThread { MsgUtil.err(e) }
            }
        }
    }

    private fun confirmDeleteReply(reply: Reply) {
        AlertDialog.Builder(context)
                .setTitle("删除评论")
                .setMessage("删除后无法恢复，确定删除这条评论吗？")
                .setPositiveButton("删除") { _, _ -> deleteReply(reply) }
                .setNegativeButton("取消", null)
                .show()
    }

    private fun deleteReply(reply: Reply) {
        CenterThreadPool.run {
            try {
                val result = ReplyApi.deleteReply(oid, reply.rpid, replyType)
                (context as Activity).runOnUiThread {
                    if (result == 0) {
                        // 数据改动必须和 notify 一样在主线程执行：原来 removeAt 在后台线程做、
                        // notify 在主线程做，主线程读到的可能还是旧列表，会删错行甚至下标越界崩溃。
                        // 位置现取现用（列表可能在弹窗打开期间被刷新过），不再用绑定时捕获的下标。
                        val index = replyList.indexOf(reply)
                        if (index >= 0 && index < replyList.size) {
                            replyList.removeAt(index)
                            // adapter 第 0 位是"写评论"头部（详情页里是根评论占第 0 位），
                            // 列表项下标换算成 adapter 下标要 +1
                            val adapterPosition = if (isDetail && index == 0) 0 else index + 1
                            notifyItemRemoved(adapterPosition)
                            notifyItemRangeChanged(adapterPosition, replyList.size + 1 - adapterPosition)
                        }
                        MsgUtil.showMsg("删除成功~")
                        if (index == 0 && isDetail) {
                            (context as Activity).finish()
                        }
                    } else {
                        val msg = when (result) {
                            -404 -> "没有这条评论！"
                            -403 -> "权限不足！"
                            else -> "操作失败：$result"
                        }
                        MsgUtil.showMsg(msg)
                    }
                }
            } catch (e: Exception) {
                (context as Activity).runOnUiThread { MsgUtil.err(e) }
            }
        }
    }

    override fun onViewRecycled(@NonNull holder: RecyclerView.ViewHolder) {
        if (holder is ReplyHolder) {
            val replyHolder = holder as ReplyHolder
            replyHolder.lastAvatarUrl = null
            replyHolder.lastImageUrl = null
        }
        super.onViewRecycled(holder)
    }

    fun startReplyInfoActivity(reply: Reply?) {
        if (reply == null)
            return
        val rpid = reply.rpid
        val oid = reply.oid
        val intent = Intent()
        intent.setClass(context, ReplyInfoActivity::class.java)
        intent.putExtra("rpid", rpid)
        intent.putExtra("oid", oid)
        intent.putExtra("type", replyType)
        intent.putExtra("up_mid", up_mid)
        intent.putExtra("is_manager", isManager)
        context.startActivity(intent)
    }

    /**
     * 跳转到投票详情页
     */
    private fun showVoteDialog(voteId: Long, anchor: View) {
        val intent = Intent(context, VoteInfoActivity::class.java)
        intent.putExtra(VoteInfoActivity.EXTRA_VOTE_ID, voteId)
        context.startActivity(intent)
    }

    override fun getItemCount(): Int {
        return if (replyList != null) replyList.size + 1 else 1
    }

    override fun getItemViewType(position: Int): Int {
        if (isDetail && position == 1) {
            return 0
        } else if (!isDetail && position == 0) {
            return 0
        }
        return 1
    }

    class ReplyHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        val replyAvatar: ImageView = itemView.findViewById(R.id.replyAvatar)
        val dislikeBtn: ImageView = itemView.findViewById(R.id.dislikeBtn)
        val childReplies: LinearLayout = itemView.findViewById(R.id.repliesList)
        val message: TextView = itemView.findViewById(R.id.replyText)
        val userName: TextView = itemView.findViewById(R.id.replyUsername)
        val pubDate: TextView = itemView.findViewById(R.id.replyPubDate)
        val childCount: TextView = itemView.findViewById(R.id.repliesControl)
        val likeCount: TextView = itemView.findViewById(R.id.likes)
        val replyBtn: TextView = itemView.findViewById(R.id.replyBtn)
        val upLiked: TextView = itemView.findViewById(R.id.upLiked)
        val imageCount: TextView = itemView.findViewById(R.id.imageCount)
        val item_reply_delete: TextView = itemView.findViewById(R.id.item_reply_delete)
        val childReplyCard: LinearLayout = itemView.findViewById(R.id.repliesCard)
        val imageCard: ImageView = itemView.findViewById(R.id.imageCard)
        var lastAvatarUrl: String? = null
        var lastImageUrl: String? = null
    }

    class WriteReply(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        val write_reply: MaterialButton = itemView.findViewById(R.id.write_reply)
        val sort: MaterialButton = itemView.findViewById(R.id.sort)
        val count_label: TextView = itemView.findViewById(R.id.count_label)
    }
}
