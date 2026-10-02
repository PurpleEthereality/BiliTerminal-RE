package com.RobinNotBad.BiliClient.adapter.video

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.video.info.VideoInfoActivity
import com.RobinNotBad.BiliClient.api.BangumiApi
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.util.TerminalContext
import com.RobinNotBad.BiliClient.util.ToolsUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.RobinNotBad.BiliClient.ui.appearance.ColorScheme

class VideoCardHolder(@androidx.annotation.NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
    lateinit var title: TextView
    lateinit var upName: TextView
    lateinit var viewCount: TextView
    lateinit var cover: ImageView
    private var lastCoverUrl: String? = null
    private var boundVideoCard: VideoCard? = null
    private var boundContext: Context? = null

    init {
        title = itemView.findViewById(R.id.text_title)
        upName = itemView.findViewById(R.id.text_upname)
        viewCount = itemView.findViewById(R.id.text_viewcount)
        cover = itemView.findViewById(R.id.img_cover)
    }

    private var longPressRunnable: Runnable? = null
    private var customClickCallback: (() -> Unit)? = null

    fun bindClick(videoCard: VideoCard, context: Context, position: Int, longClickListener: View.OnLongClickListener?) {
        this.boundVideoCard = videoCard
        this.boundContext = context

        // 先清除旧的触摸检测
        longPressRunnable?.let { itemView.removeCallbacks(it) }
        customClickCallback = null

        // 设置点击事件
        itemView.setOnClickListener {
            if (customClickCallback != null) {
                customClickCallback!!.invoke()
            } else if (boundVideoCard != null && boundContext != null) {
                when (boundVideoCard!!.type) {
                    "video" -> TerminalContext.getInstance().enterVideoDetailPage(boundContext!!, boundVideoCard!!.aid, boundVideoCard!!.bvid, "video")
                    "media_bangumi" -> {
                        val card = boundVideoCard!!
                        val ctx = boundContext!!
                        if (card.epid > 0L) {
                            // 历史记录来源的番剧卡片：aid(history.oid) 是剧集 avid，不能当 media_id。
                            // 直接拿它去请求，接口一样返回 code=0，但 season_id=0、标题为空，详情页是空壳，
                            // 所以先用 epid 反查 media_id；反查是网络请求，放线程池，别卡主线程
                            CenterThreadPool.run {
                                val mediaId = BangumiApi.getMdidFromEpid(card.epid)
                                if (mediaId != null && mediaId > 0L) {
                                    // 顺便把 epid 和已看进度（秒）带进详情页，用于定位上次观看的那一集
                                    val intent = Intent(ctx, VideoInfoActivity::class.java)
                                    intent.putExtra("aid", mediaId)
                                    intent.putExtra("type", "media")
                                    intent.putExtra("epid", card.epid)
                                    // 键名带 _sec 后缀：PlayerActivity 读的 extra 也叫 "progress" 但单位是毫秒，
                                    // 两者若被同一条 Intent 转发会静默差 1000 倍，所以这里用独立键名
                                    intent.putExtra("progress_sec", card.progress)
                                    intent.putExtra("seekReply", -1L)
                                    ctx.startActivity(intent)
                                } else MsgUtil.showMsg("番剧信息获取失败")
                            }
                        } else {
                            // 追番列表/动态等来源的卡片，aid 已经是 media_id，走原来的跳转
                            TerminalContext.getInstance().enterVideoDetailPage(ctx, card.aid, null, "media")
                        }
                    }
                }
            }
        }

        if (longClickListener != null) {
            itemView.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressRunnable = Runnable { longClickListener.onLongClick(v) }
                        v.postDelayed(longPressRunnable, 200)
                        false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressRunnable?.let { v.removeCallbacks(it) }
                        longPressRunnable = null
                        false
                    }
                    else -> false
                }
            }
        } else {
            itemView.setOnTouchListener(null)
        }
    }

    /**
     * 设置自定义点击回调，覆盖默认的视频详情页跳转
     */
    fun setCustomClickCallback(callback: () -> Unit) {
        this.customClickCallback = callback
    }

    @SuppressLint("SetTextI18n")
    fun showVideoCard(videoCard: VideoCard, context: Context) {
        val strUpName = videoCard.upName
        if (strUpName == null || strUpName.isEmpty()) {
            upName.visibility = View.GONE
        } else
            upName.text = strUpName

        val strViewCount = videoCard.view
        if (strViewCount == null || strViewCount.isEmpty()) {
            viewCount.visibility = View.GONE
        } else {
            viewCount.text = strViewCount
        }

        try {
            // 封面占半屏以上：用 url_hq（80q/1024w）而不是列表默认的 512w，
            // 否则在 1080p 屏幕上被放大后明显发虚
            val coverUrl = GlideUtil.url_hq(videoCard.cover)
            if (coverUrl != lastCoverUrl) {
                lastCoverUrl = coverUrl
                requestManager.asDrawable().load(coverUrl)
                    .transition(GlideUtil.getTransitionOptions())
                    .placeholder(R.mipmap.placeholder)
                    .error(R.mipmap.placeholder)
                    .format(DecodeFormat.PREFER_ARGB_8888)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .apply(getRequestOptions())
                    .into(cover)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        when (videoCard.type) {
            "live" -> {
                val sstrLive = SpannableString("[直播]" + StringUtil.htmlToString(videoCard.title))
                sstrLive.setSpan(TITLE_COLOR_SPAN, 0, 4,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                title.text = sstrLive
            }
            "series" -> {
                val sstrSeries = SpannableString("[系列]" + StringUtil.htmlToString(videoCard.title))
                sstrSeries.setSpan(TITLE_COLOR_SPAN, 0, 4,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                title.text = sstrSeries
            }
            else -> title.text = StringUtil.htmlToString(videoCard.title)
        }
    }

    companion object {
        private val requestManager = Glide.with(BiliTerminal.context!!)
        private val TITLE_COLOR_SPAN = ForegroundColorSpan(ColorScheme.PRIMARY)

        @JvmStatic
        fun getRequestOptions(): RequestOptions {
            val cornerRadius = ToolsUtil.dp2px(5f)
            // 不再写死 override(400, 225) + sizeMultiplier(0.85)：
            // 那会把解码尺寸锁在约 340×191 的绝对像素，而列表封面显示区在 1080p 屏上约 519px 宽，
            // 必然被放大糊掉；交给 Glide 按 ImageView 实测尺寸解码即可。
            return RequestOptions()
                .transform(CenterCrop(), RoundedCorners(cornerRadius))
        }
    }
}