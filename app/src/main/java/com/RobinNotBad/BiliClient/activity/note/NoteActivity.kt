package com.RobinNotBad.BiliClient.activity.note

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.TextView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.NoteApi
import com.RobinNotBad.BiliClient.model.Note
import com.RobinNotBad.BiliClient.model.NoteBlock
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

/**
 * 视频笔记（仅查看）。26.10.04 批次 6 的 C27。
 *
 * 入口是视频详情页的「笔记」按钮，intent 带 `aid`（可选带 `note_id`）。
 * 私有笔记一个稿件只有一篇，所以拿到 id 列表就直接读第一篇；
 * 没有笔记、没登录、正文为空都只给页面内的空态提示，不弹窗打断。
 *
 * 正文是 Quill delta 序列（见 `model/NoteBlock`），这里把块拼成一段带样式的文本塞进一个 TextView：
 * 手表屏幕小，为几个样式块单独做 RecyclerView + adapter 不划算；图片只做文字占位，跳转标签也只显示时间。
 */
class NoteActivity : BaseActivity() {

    private lateinit var titleView: TextView
    private lateinit var infoView: TextView
    private lateinit var contentView: TextView
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note)
        setPageName("笔记")

        titleView = findViewById(R.id.note_title)
        infoView = findViewById(R.id.note_info)
        contentView = findViewById(R.id.note_content)
        emptyView = findViewById(R.id.note_empty)

        val aid = intent.getLongExtra("aid", 0L)
        var noteId = intent.getStringExtra("note_id").orEmpty()

        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0L) {
            showEmpty("登录后才能看笔记喵~")
            return
        }
        if (aid <= 0L && noteId.isEmpty()) {
            showEmpty("笔记参数不对")
            return
        }

        CenterThreadPool.run {
            try {
                if (noteId.isEmpty()) {
                    val ids = NoteApi.getNoteIdsOfVideo(aid)
                    if (ids.isEmpty()) {
                        runOnUiThread { showEmpty("这个视频还没有笔记") }
                        return@run
                    }
                    noteId = ids[0]
                }
                val note = NoteApi.getNoteInfo(aid, noteId)
                if (note.noteId.isEmpty()) note.noteId = noteId
                runOnUiThread { showNote(note) }
            } catch (e: Exception) {
                // 不能把所有异常都压成一句「获取笔记失败」：断网、未登录、79502/79503
                // 的处置完全不同，用户只看到同一句话就没法反馈也没法自救
                val text = failureText(e)
                runOnUiThread { showEmpty(text) }
                MsgUtil.err(e)
            }
        }
    }

    /**
     * 失败提示分级：服务端有话说就转述，纯网络问题给网络文案，其余才是一般性失败。
     *
     * <p>判定逻辑本身是纯函数 {@link NoteApi#failureText(Throwable)}（带 JVM 单测），
     * 这里只负责切主线程显示。
     */
    private fun failureText(e: Exception): String = NoteApi.failureText(e)

    private fun showEmpty(text: String) {
        titleView.visibility = View.GONE
        infoView.visibility = View.GONE
        contentView.visibility = View.GONE
        emptyView.text = text
        emptyView.visibility = View.VISIBLE
    }

    @SuppressLint("SetTextI18n")
    private fun showNote(note: Note) {
        titleView.text = note.title.ifEmpty { "无标题笔记" }
        titleView.visibility = View.VISIBLE

        val info = ArrayList<String>()
        if (note.videoTitle.isNotEmpty()) info.add("视频：" + note.videoTitle)
        if (note.summary.isNotEmpty()) info.add(note.summary)
        infoView.text = info.joinToString("\n")
        infoView.visibility = if (info.isEmpty()) View.GONE else View.VISIBLE

        val content = renderBlocks(note.blocks)
        if (content.isEmpty()) {
            showEmpty("这篇笔记还没有正文")
            return
        }
        contentView.text = content
        contentView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
    }

    /** 把正文块拼成一段带样式的文本。 */
    private fun renderBlocks(blocks: List<NoteBlock>): CharSequence {
        val builder = SpannableStringBuilder()
        var orderedIndex = 0
        for (block in blocks) {
            when (block.type) {
                NoteBlock.TYPE_IMAGE -> {
                    builder.append("[图片]")
                }
                NoteBlock.TYPE_TAG -> {
                    builder.append(tagText(block))
                }
                else -> {
                    val prefix = when (block.list) {
                        "ordered" -> {
                            orderedIndex++
                            "$orderedIndex. "
                        }
                        "bullet" -> "• "
                        else -> ""
                    }
                    val start = builder.length
                    builder.append(prefix)
                    builder.append(block.text)
                    applyStyle(builder, block, start)
                }
            }
            // 有序列表的编号只在连续的列表块之间累加，中间插了普通段落就重新数
            if (block.list.isEmpty()) orderedIndex = 0
        }
        return builder
    }

    private fun tagText(block: NoteBlock): String {
        val time = NoteApi.formatTagSeconds(block.tagSeconds)
        return if (block.tagIndex >= 0) "[分P${block.tagIndex + 1} $time]" else "[视频进度 $time]"
    }

    private fun applyStyle(builder: SpannableStringBuilder, block: NoteBlock, start: Int) {
        val end = builder.length
        if (end <= start) return
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        if (block.bold) builder.setSpan(StyleSpan(Typeface.BOLD), start, end, flags)
        if (block.underline) builder.setSpan(UnderlineSpan(), start, end, flags)
        if (block.strike) builder.setSpan(StrikethroughSpan(), start, end, flags)
        parseColor(block.color)?.let {
            builder.setSpan(ForegroundColorSpan(it), start, end, flags)
        }
        parseColor(block.background)?.let {
            builder.setSpan(BackgroundColorSpan(it), start, end, flags)
        }
    }

    /** 颜色解析失败（服务端给了脏值）就当没设颜色，别让整页崩掉。 */
    private fun parseColor(value: String): Int? {
        if (value.isEmpty()) return null
        return try {
            Color.parseColor(value)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
