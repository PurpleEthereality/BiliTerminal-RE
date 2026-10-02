package com.RobinNotBad.BiliClient.activity.dynamic.send

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.EmoteActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.dynamic.DynamicHolder
import com.RobinNotBad.BiliClient.adapter.video.VideoCardHolder
import com.RobinNotBad.BiliClient.api.EmoteApi
import com.RobinNotBad.BiliClient.api.ImageApi
import com.RobinNotBad.BiliClient.model.Dynamic
import com.RobinNotBad.BiliClient.model.VideoInfo
import com.RobinNotBad.BiliClient.model.VoteDraft
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TerminalContext
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import java.io.Serializable

class SendDynamicActivity : BaseActivity() {

    private lateinit var editText: EditText
    private lateinit var voteEditArea: LinearLayout
    private lateinit var voteTitleEdit: EditText
    private lateinit var voteOptionsList: LinearLayout
    private lateinit var addOptionBtn: MaterialButton
    private lateinit var removeVoteBtn: MaterialButton
    private lateinit var addPicText: TextView
    private lateinit var picsPreview: LinearLayout
    private var voteDraft: VoteDraft? = null
    private val optionEditTexts = mutableListOf<EditText>()
    private var hasVote: Boolean = false

    /** 已选待上传的图片。 */
    private val imageUris = mutableListOf<Uri>()
    /** 防止连点发送导致重复上传/重复发布。 */
    private var sending: Boolean = false

    private val emoteLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.resultCode
        val data = result.data
        if (code == RESULT_OK && data != null && data.hasExtra("text")) {
            editText.append(data.getStringExtra("text"))
        }
    }

    /**
     * 选图。沿用本项目既有的图片选择方式（ACTION_GET_CONTENT 单选），
     * 不学上游的 GetMultipleContents：手表上一次选多张的体验与兼容性都没把握。
     */
    private val pickImageLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            if (imageUris.size >= ImageApi.MAX_IMAGE_COUNT) {
                MsgUtil.showMsg("最多${ImageApi.MAX_IMAGE_COUNT}张图片")
                return@registerForActivityResult
            }
            imageUris.add(uri)
            renderPicsPreview()
        }
    }

    @SuppressLint("InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        asyncInflate(R.layout.activity_send_dynamic) { layoutView, resId ->

            if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0L) {
                setResult(RESULT_CANCELED)
                finish()
                MsgUtil.showMsg("还没有登录喵~")
            }

            editText = findViewById(R.id.editText)
            val send = findViewById<MaterialCardView>(R.id.send)
            val addVote = findViewById<MaterialCardView>(R.id.add_vote)
            val addPic = findViewById<MaterialCardView>(R.id.add_pic)
            addPicText = findViewById(R.id.add_pic_text)
            picsPreview = findViewById(R.id.pics_preview)

            // 投票编辑区
            voteEditArea = findViewById(R.id.vote_edit_area)
            voteTitleEdit = findViewById(R.id.vote_title_edit)
            voteOptionsList = findViewById(R.id.vote_options_list)
            addOptionBtn = findViewById(R.id.add_option_btn)
            removeVoteBtn = findViewById(R.id.remove_vote_btn)

            val extraCard = findViewById<FrameLayout>(R.id.forwardCard)
            var video: VideoInfo? = null
            var forward: Dynamic? = null
            if (TerminalContext.getInstance().getForwardContent() is VideoInfo) {
                video = TerminalContext.getInstance().getForwardContent() as VideoInfo
            } else {
                forward = TerminalContext.getInstance().getForwardContent() as Dynamic?
            }
            if (forward != null) {
                val childCard = View.inflate(this, R.layout.cell_dynamic, extraCard)
                val holder = DynamicHolder(childCard, this, false)
                holder.showDynamic(this, forward, false)
            } else if (video != null) {
                val holder = VideoCardHolder(LayoutInflater.from(this).inflate(R.layout.cell_video_list, extraCard))
                holder.showVideoCard(video.toCard(), this)
            }

            // 转发场景不允许带图：转发的是别人的内容，再挂自己的图语义不成立，B 站也不接受
            val normalPublish = forward == null && video == null
            addPic.visibility = if (normalPublish) View.VISIBLE else View.GONE

            addPic.setOnClickListener {
                pickImageLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" })
            }

            // 添加投票按钮点击
            addVote.setOnClickListener {
                if (!hasVote) {
                    hasVote = true
                    voteEditArea.visibility = View.VISIBLE
                    addVote.visibility = View.GONE
                    // 默认添加两个选项
                    if (optionEditTexts.isEmpty()) {
                        addOptionEdit()
                        addOptionEdit()
                    }
                }
            }

            // 添加选项按钮
            addOptionBtn.setOnClickListener {
                if (optionEditTexts.size < 10) {
                    addOptionEdit()
                } else {
                    MsgUtil.showMsg("最多10个选项")
                }
            }

            // 移除投票按钮
            removeVoteBtn.setOnClickListener {
                clearVoteEdit()
                hasVote = false
                voteEditArea.visibility = View.GONE
                addVote.visibility = View.VISIBLE
            }

            send.setOnClickListener {
                if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.cookie_refresh, true)) {
                    MsgUtil.showDialog("无法发送", "上一次的Cookie刷新失败了，\n您可能需要重新登录以进行敏感操作", -1)
                    return@setOnClickListener
                }
                if (sending) {
                    MsgUtil.showMsg("正在发送中")
                    return@setOnClickListener
                }
                val text = editText.text.toString()

                // 处理投票草稿：投票区开着但内容不合法时，collectVoteDraft 已经提示过了，
                // 这里必须中止发送——否则会把投票悄悄丢掉、只发出正文
                val draft = collectVoteDraft()
                if (hasVote && draft == null) return@setOnClickListener

                // 带图与投票不能同时发：本项目的投票走 attach_card，与 pics 叠加未经验证，宁可不发
                if (imageUris.isNotEmpty() && draft != null) {
                    MsgUtil.showMsg("带图动态暂不支持同时发投票")
                    return@setOnClickListener
                }

                if (imageUris.isEmpty()) {
                    val result = Intent()
                    val bundle = this@SendDynamicActivity.intent.extras
                    if (bundle != null) result.putExtras(bundle)
                    result.putExtra("text", text)
                    if (draft != null) {
                        result.putExtra("voteDraft", draft as Serializable)
                    }
                    setResult(RESULT_OK, result)
                    finish()
                    return@setOnClickListener
                }

                // 带图：图片必须先上传图床换成 URL，再回传给 DynamicActivity 组装 pics
                sending = true
                MsgUtil.showMsg("正在上传图片...")
                val toUpload = ArrayList(imageUris)   // 快照，避免上传期间用户又删图导致并发修改
                CenterThreadPool.run {
                    try {
                        val pics = JSONArray()
                        for (uri in toUpload) {
                            val prepared = ImageApi.prepareImage(this@SendDynamicActivity, uri)
                            val uploaded = ImageApi.uploadImage(
                                prepared.data, prepared.fileName, prepared.mimeType, ImageApi.BIZ_DYNAMIC
                            ).getOrThrow()
                            pics.put(uploaded.toDynamicPicJson())
                        }
                        val result = Intent()
                        val bundle = this@SendDynamicActivity.intent.extras
                        if (bundle != null) result.putExtras(bundle)
                        result.putExtra("text", text)
                        result.putExtra("pics", pics.toString())
                        runOnUiThread {
                            setResult(RESULT_OK, result)
                            finish()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            sending = false
                            MsgUtil.err(e)
                        }
                    }
                }
            }

            findViewById<View>(R.id.emote).setOnClickListener {
                emoteLauncher.launch(Intent(this, EmoteActivity::class.java).putExtra("from", EmoteApi.BUSINESS_DYNAMIC))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        TerminalContext.getInstance().setForwardContent(null)
    }

    /**
     * 重绘已选图片预览。点缩略图可以移除该图。
     */
    private fun renderPicsPreview() {
        picsPreview.removeAllViews()
        if (imageUris.isEmpty()) {
            picsPreview.visibility = View.GONE
        } else {
            picsPreview.visibility = View.VISIBLE
            val size = (resources.displayMetrics.density * 56).toInt()
            for (uri in imageUris) {
                val imageView = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 4 }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setOnClickListener {
                        imageUris.remove(uri)
                        renderPicsPreview()
                    }
                }
                Glide.with(this).load(uri).centerCrop().into(imageView)
                picsPreview.addView(imageView)
            }
        }
        addPicText.text = if (imageUris.isEmpty()) "添加图片" else "添加图片(${imageUris.size})"
    }

    /**
     * 添加一个选项编辑输入框
     */
    private fun addOptionEdit() {
        val index = optionEditTexts.size + 1
        val editText = EditText(this).apply {
            hint = "选项 $index"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setBackgroundResource(R.drawable.background_edittext)
            setPadding(8, 4, 8, 4)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 4
            }
        }
        voteOptionsList.addView(editText)
        optionEditTexts.add(editText)
    }

    /**
     * 收集投票草稿
     */
    private fun collectVoteDraft(): VoteDraft? {
        if (!hasVote) return null
        val title = voteTitleEdit.text.toString().trim()
        val options = optionEditTexts.map { it.text.toString().trim() }.filter { it.isNotEmpty() }
        if (title.isEmpty()) {
            MsgUtil.showMsg("请填写投票标题")
            return null
        }
        if (options.size < 2) {
            MsgUtil.showMsg("至少需要2个选项")
            return null
        }
        val draft = VoteDraft()
        draft.title = title
        draft.options = options.toMutableList()
        return draft
    }

    /**
     * 清空投票编辑区
     */
    private fun clearVoteEdit() {
        voteTitleEdit.text.clear()
        voteOptionsList.removeAllViews()
        optionEditTexts.clear()
        voteDraft = null
    }
}