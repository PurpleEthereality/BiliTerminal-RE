package com.RobinNotBad.BiliClient.adapter.message

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.message.PrivateMsgActivity
import com.RobinNotBad.BiliClient.api.PrivateMsgApi
import com.RobinNotBad.BiliClient.model.PrivateMessage
import com.RobinNotBad.BiliClient.model.PrivateMsgSession
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.ui.widget.RadiusBackgroundSpan
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TerminalDialog
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import org.json.JSONException
import com.RobinNotBad.BiliClient.ui.appearance.ColorScheme

class PrivateMsgSessionsAdapter(
    val context: Context,
    val sessionsList: ArrayList<PrivateMsgSession>,
    val userMap: HashMap<Long, UserInfo>?,
    private val onSessionsChanged: (() -> Unit)? = null
) : RecyclerView.Adapter<PrivateMsgSessionsAdapter.PrivateMsgSessionsHolder>() {

    private val cardRoundRadius: Int = context.resources.getDimension(R.dimen.card_round).toInt()

    companion object {
        private const val BADGE_TEXT_COLOR = Color.WHITE
        private val BADGE_BG_COLOR = ColorScheme.PRIMARY
        private const val BADGE_TEXT = "  未读 "
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PrivateMsgSessionsHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.cell_user_list, parent, false)
        return PrivateMsgSessionsHolder(view)
    }

    override fun onBindViewHolder(holder: PrivateMsgSessionsHolder, position: Int) {
        if (position < 0 || position >= sessionsList.size)
            return
        val msgContent = sessionsList[position] ?: return

        try {
            if (msgContent.content != null)
                when (msgContent.contentType) {
                    PrivateMessage.TYPE_TEXT -> {
                        holder.contentText.text = msgContent.content.getString("content")
                    }
                    PrivateMessage.TYPE_PIC -> {
                        holder.contentText.text = "[图片消息]"
                    }
                    PrivateMessage.TYPE_VIDEO, PrivateMessage.TYPE_PIC_CARD, PrivateMessage.TYPE_NOMAL_CARD -> {
                        holder.contentText.text = msgContent.content.getString("title")
                    }
                    PrivateMessage.TYPE_TEXT_WITH_VIDEO -> {
                        holder.contentText.text = msgContent.content.getString("reply_content")
                    }
                    PrivateMessage.TYPE_RETRACT -> {
                        holder.contentText.text = "[撤回消息]"
                    }
                    else -> {
                        holder.contentText.text = ""
                    }
                }
            else
                holder.contentText.text = ""

            holder.contentText.ellipsize = TextUtils.TruncateAt.END

            val user = userMap?.get(msgContent.talkerUid)
            if (user != null) {
                // 置顶态没有布尔字段，靠 top_ts 是否非零判断
                val displayName = if (msgContent.isTop()) "[置顶] ${user.name}" else user.name
                if (msgContent.unread > 0 && SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PRIVATE_MSG_UNREAD_BADGE_ENABLE, false)) {
                    val nameStr = SpannableStringBuilder(displayName)
                    val nameLength = displayName.length
                    nameStr.append(BADGE_TEXT)
                    nameStr.setSpan(
                        RadiusBackgroundSpan(1, cardRoundRadius, BADGE_TEXT_COLOR, BADGE_BG_COLOR),
                        nameLength + 1, nameStr.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                    )
                    holder.nameText.text = nameStr
                } else {
                    holder.nameText.text = displayName
                }
                Glide.with(BiliTerminal.context!!).asDrawable().load(GlideUtil.url(user.avatar))
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .placeholder(R.mipmap.akari)
                    .error(R.mipmap.akari)
                    .apply(RequestOptions.circleCropTransform())
                    .into(holder.avatarView)
            }

            holder.itemView.setOnClickListener {
                val intent = Intent(context, PrivateMsgActivity::class.java)
                intent.putExtra("uid", msgContent.talkerUid)
                context.startActivity(intent)
            }
            holder.itemView.setOnLongClickListener {
                showSessionMenu(msgContent)
                true
            }
        } catch (err: JSONException) {
            Log.e("PrivateMsgUserAdapter", err.toString())
        }
    }

    /**
     * 会话项长按菜单：置顶/取消置顶、删除会话、查看用户主页。
     * 这两个接口都只改服务端的会话列表（删除不会清聊天记录），所以成功后统一重新拉一次列表。
     */
    private fun showSessionMenu(session: PrivateMsgSession) {
        val actions = arrayOf(
            if (session.isTop()) "取消置顶" else "置顶会话",
            "删除会话",
            "查看用户主页"
        )
        // 第 1 项（删除会话）是破坏性操作，用危险色标出来
        TerminalDialog.menu(
            context = context,
            title = "会话操作",
            items = actions.toList(),
            danger = setOf(1)
        ) { which ->
            when (which) {
                0 -> setSessionTop(session, !session.isTop())
                1 -> confirmRemoveSession(session)
                else -> BiliTerminal.jumpToUser(context, session.talkerUid)
            }
        }.show()
    }

    private fun confirmRemoveSession(session: PrivateMsgSession) {
        TerminalDialog.confirm(
            context = context,
            title = "删除会话",
            message = "只会把会话从列表里移除，不会删除聊天记录。",
            confirmText = "删除",
            onConfirm = { removeSession(session) }
        ).show()
    }

    private fun setSessionTop(session: PrivateMsgSession, top: Boolean) {
        CenterThreadPool.run {
            try {
                val code = PrivateMsgApi.setSessionTop(
                    session.talkerUid, PrivateMsgApi.SESSION_TYPE_USER, top
                )
                CenterThreadPool.runOnUiThread {
                    val error = PrivateMsgApi.sessionErrorMsg(code)
                    if (error.isEmpty()) {
                        MsgUtil.showMsg(if (top) "已置顶" else "已取消置顶")
                        onSessionsChanged?.invoke()
                    } else {
                        MsgUtil.showMsg(error)
                    }
                }
            } catch (e: Exception) {
                CenterThreadPool.runOnUiThread { MsgUtil.err(e) }
            }
        }
    }

    private fun removeSession(session: PrivateMsgSession) {
        CenterThreadPool.run {
            try {
                val code = PrivateMsgApi.removeSession(
                    session.talkerUid, PrivateMsgApi.SESSION_TYPE_USER
                )
                CenterThreadPool.runOnUiThread {
                    val error = PrivateMsgApi.sessionErrorMsg(code)
                    if (error.isEmpty()) {
                        val index = sessionsList.indexOfFirst { it.talkerUid == session.talkerUid }
                        if (index >= 0) {
                            sessionsList.removeAt(index)
                            notifyItemRemoved(index)
                        }
                        MsgUtil.showMsg("已从会话列表移除")
                        onSessionsChanged?.invoke()
                    } else {
                        MsgUtil.showMsg(error)
                    }
                }
            } catch (e: Exception) {
                CenterThreadPool.runOnUiThread { MsgUtil.err(e) }
            }
        }
    }

    override fun getItemCount(): Int {
        return sessionsList.size
    }

    class PrivateMsgSessionsHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        lateinit var avatarView: ImageView
        lateinit var nameText: TextView
        lateinit var contentText: TextView

        init {
            avatarView = itemView.findViewById(R.id.userAvatar)
            nameText = itemView.findViewById(R.id.userName)
            contentText = itemView.findViewById(R.id.userDesc)
        }
    }
}
