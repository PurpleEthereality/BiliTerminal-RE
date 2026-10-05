package com.RobinNotBad.BiliClient.activity.reply

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Pair
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.EmoteActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.EmoteApi
import com.RobinNotBad.BiliClient.api.ReplyApi
import com.RobinNotBad.BiliClient.api.VipApi
import com.RobinNotBad.BiliClient.event.ReplyEvent
import com.RobinNotBad.BiliClient.model.Reply
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.card.MaterialCardView
import org.greenrobot.eventbus.EventBus
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

class WriteReplyActivity : BaseActivity() {

    companion object {
        private val msgMap = mapOf(
            -101 to "没有登录or登录信息有误？",
            -102 to "账号被封禁！",
            -509 to "请求过于频繁！",
            12015 to "需要评论验证码...？",
            12016 to "包含敏感内容！",
            12025 to "字数过多啦QAQ",
            12035 to "被拉黑了...",
            12051 to "重复评论，请勿刷屏！",
            // 12066 是服务端对带图评论的拒绝码，本地快照与上游 collector 都没有收录
            //（上游 action.md 该表末尾写「其他错误码有待补充」），此处只保证不再显示裸数字
            12066 to "图片信息异常，请重新选图后再试"
        )

        // 单张图片的绝对上限，超过就不传了（手表上传大文件基本必失败，还容易 OOM）
        private const val MAX_IMAGE_SIZE = 25L * 1024 * 1024
        // GIF 不能重新编码，只能原样透传，所以额外给一个更小的上限，避免动图把上传拖垮
        private const val GIF_MAX_SIZE = 20L * 1024 * 1024
        // PNG 原样透传的上限；超过这个体积还是压成 JPEG，否则上传体验会崩
        private const val PNG_MAX_SIZE = 8L * 1024 * 1024
        // 压缩时的最长边上限，避免手表直接解码原图就 OOM
        private const val MAX_COMPRESS_EDGE = 2048
    }

    private lateinit var editText: EditText
    private lateinit var imageText: TextView

    private val emoteLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.resultCode
        val data = result.data
        if (code == RESULT_OK && data != null && data.hasExtra("text")) {
            editText.append(data.getStringExtra("text"))
        }
    }

    private val imageList = ArrayList<String>()
    private val uploadDataList = ArrayList<ReplyApi.UploadImageData>()

    private val imageLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.resultCode
        val data = result.data
        if (code == RESULT_OK && data != null && data.data != null) {
            if (imageList.size >= 9) {
                MsgUtil.showMsg("最多只能添加9张图片喵~")
                return@registerForActivityResult
            }
            addImage(data.data!!)
        }
    }

    /**
     * 发送闸门。点下发送的**那一刻**就置位，用来挡住「请求还没回来又点一次」——
     * 原来的 `sent` 是等请求返回后才置 true，等待期里连点会发出两条评论。
     * 在后台线程里读写，所以加 @Volatile。
     */
    @Volatile
    private var sending: Boolean = false

    /**
     * 仍在上传中的图片张数。
     *
     * 选完图 [imageList] 里立刻就有这张图了，但压缩 + 上传还在后台跑。它挡的是一个更隐蔽的问题：
     * 图还没传完就发送时，[buildPictures] 只能拼出已成功的那几张，评论会**少图发出且毫无提示**。
     * 读写在主线程（[addImage] 由选图回调调用、减一也回到主线程），所以不需要加锁。
     */
    private var pendingUploads: Int = 0

    private var dontKyPlease: Boolean = true

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_write_reply)

        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0L) {
            MsgUtil.showMsg("还没有登录喵~")
            finish()
        }

        val intent = intent
        val oid = intent.getLongExtra("oid", 0)
        val rpid = intent.getLongExtra("rpid", 0)
        val parent = intent.getLongExtra("parent", 0)
        val replyType = intent.getIntExtra("replyType", ReplyApi.REPLY_TYPE_VIDEO)
        val parentSender = intent.getStringExtra("parentSender")
        val pos = intent.getIntExtra("pos", -1)

        editText = findViewById(R.id.editText)
        imageText = findViewById(R.id.imageText)
        val send = findViewById<MaterialCardView>(R.id.send)

        if (parentSender != null && parentSender.isNotEmpty()) {
            editText.setText("回复 @$parentSender :")
            editText.setSelection(editText.text.length)
        }

        send.setOnClickListener {
            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.cookie_refresh, true)) {
                if (sending) {
                    MsgUtil.showMsg("正在发送中")
                    return@setOnClickListener
                }
                // 图还没传完就发送 = 评论少图且没有任何提示，必须在这里拦住
                if (!ReplyApi.canSendReply(pendingUploads)) {
                    MsgUtil.showMsg(ReplyApi.uploadPendingTip(pendingUploads))
                    return@setOnClickListener
                }
                sending = true
                CenterThreadPool.run {
                    try {
                        val text = editText.text.toString()
                        if (text.isEmpty() && imageList.isEmpty()) {
                            runOnUiThread { MsgUtil.showMsg("还没输入内容呢~") }
                            return@run
                        }
                        if (checkKy(text) && dontKyPlease) {
                            MsgUtil.showDialog("保护措施……", getString(R.string.reply_dont_ky), 15)
                            dontKyPlease = false
                            return@run
                        }
                        // 进后台先给一次反馈：原先这段等待期界面毫无变化，点一下像没反应
                        runOnUiThread { MsgUtil.showMsg("正在发送…") }
                        try {
                            val pictures = buildPictures()
                            val result = ReplyApi.sendReply(oid, rpid, parent, text, replyType, pictures)
                            val resultCode = result.first
                            val resultReply = result.second

                            if (resultCode == 0) {
                                runOnUiThread { MsgUtil.showMsg("发送成功>w<") }
                                resultReply.forceDelete = true
                                resultReply.pubTime = "刚刚"
                                synchronized(uploadDataList) {
                                    for (uploadData in uploadDataList) {
                                        resultReply.pictureList.add(uploadData.image_url)
                                    }
                                }
                                EventBus.getDefault().post(ReplyEvent(1, resultReply, pos, oid))
                                finish()
                            } else {
                                val toast_msg = "评论发送失败：\n" + (msgMap.getOrDefault(resultCode, resultCode.toString()))
                                runOnUiThread { MsgUtil.showMsg(toast_msg) }
                            }
                        } catch (e: Exception) {
                            runOnUiThread { MsgUtil.err(e) }
                        }
                    } finally {
                        // 成功、失败、还是上面几个提前 return，都要把闸门松开，
                        // 否则用户在这条评论之后再想发就永远被挡
                        sending = false
                    }
                }
            } else
                MsgUtil.showDialog("无法发送", "上一次的Cookie刷新失败了，\n您可能需要重新登录以进行敏感操作", -1)
        }

        findViewById<android.view.View>(R.id.emote).setOnClickListener {
            emoteLauncher.launch(Intent(this, EmoteActivity::class.java).putExtra("from", EmoteApi.BUSINESS_REPLY))
        }

        findViewById<android.view.View>(R.id.image).setOnClickListener {
            // 带图评论仅大会员可用，点击前先检测大会员状态
            CenterThreadPool.run {
                try {
                    val vipInfo = VipApi.getVipInfo()
                    runOnUiThread {
                        if (vipInfo.isVip) {
                            if (imageList.size >= 9) {
                                MsgUtil.showMsg("最多只能添加9张图片喵~")
                            } else {
                                val pickIntent = Intent(Intent.ACTION_GET_CONTENT)
                                pickIntent.type = "image/*"
                                imageLauncher.launch(pickIntent)
                            }
                        } else {
                            MsgUtil.showMsg("带图评论仅大会员可用喵~")
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { MsgUtil.err(e) }
                }
            }
        }
    }

    private fun addImage(uri: Uri) {
        imageList.add(uri.toString())
        pendingUploads++
        updateImageText()
        CenterThreadPool.run {
            try {
                val prepared = prepareImage(uri)
                // 文件名与 MIME 都按图片真实格式传，否则服务端一律按 JPEG 解析，
                // GIF 会变成静帧、PNG 透明通道会被填成底色
                val data = ReplyApi.uploadReplyImage(
                    prepared.raw, prepared.fileName, prepared.mimeType, ReplyApi.BIZ_REPLY
                ).getOrNull()
                if (data == null) {
                    runOnUiThread {
                        MsgUtil.showMsg("图片上传失败")
                        imageList.remove(uri.toString())
                    }
                    return@run
                }
                synchronized(uploadDataList) {
                    uploadDataList.add(data)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    MsgUtil.showMsg("图片处理失败")
                    imageList.remove(uri.toString())
                }
            } finally {
                // 不管成功、失败还是提前 return，都要把「上传中」计数放开，
                // 否则这个计数永远回不到 0、发送会被永久拦住
                runOnUiThread {
                    pendingUploads--
                    updateImageText()
                }
            }
        }
    }

    /** 待上传的图片：raw 是原始字节，fileName/mimeType 决定服务端按什么格式解析。 */
    private class PreparedImage(val raw: ByteArray, val fileName: String, val mimeType: String)

    /**
     * 准备要上传的图片。
     *
     * 不能一律解码后压成 JPEG：那样 GIF 动图会变成一张静帧、PNG 的透明通道会被填成黑/白底，
     * 而这两类都是评论里很常见的图。所以这里按真实类型分流：
     * - GIF：动图无法用 [Bitmap] 重新编码，只能原样透传（超过 20MB 才拒绝）
     * - PNG 且不超过 8MB：原样透传，保住透明通道
     * - 其余（过大的 PNG、JPEG、WEBP 等）：按最长边采样后压成 JPEG 90
     */
    private fun prepareImage(uri: Uri): PreparedImage {
        val type = resolveImageType(uri)
        val now = System.currentTimeMillis()

        // 先按文件描述符探一下体积，超大图直接拒绝，避免在手表上把整张图读进内存就 OOM
        val declaredSize = getDeclaredSize(uri)
        if (declaredSize > MAX_IMAGE_SIZE) throw IOException("图片过大（超过25MB）")

        val raw = readAllBytes(uri)
        if (raw.size > MAX_IMAGE_SIZE) throw IOException("图片过大（超过25MB）")

        if (type == "image/gif") {
            if (raw.size > GIF_MAX_SIZE) throw IOException("GIF过大（超过20MB），请换一张")
            return PreparedImage(raw, "img_$now.gif", "image/gif")
        }
        if (type == "image/png" && raw.size <= PNG_MAX_SIZE) {
            return PreparedImage(raw, "img_$now.png", "image/png")
        }

        val bitmap = decodeScaled(raw) ?: throw IOException("解码图片失败")
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        bitmap.recycle()
        return PreparedImage(outputStream.toByteArray(), "img_$now.jpg", "image/jpeg")
    }

    /**
     * 判断图片真实类型。优先用 content provider 声明的类型；
     * 有些 provider 不返回类型，这时按文件头魔数嗅探兜底。
     */
    private fun resolveImageType(uri: Uri): String {
        val declared = contentResolver.getType(uri)?.lowercase(Locale.ROOT)
        if (declared != null && declared.startsWith("image/") && declared != "image/*") return declared
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(12)
                val len = input.read(head)
                sniffImageType(head, len)
            } ?: "image/jpeg"
        } catch (e: Exception) {
            "image/jpeg"
        }
    }

    private fun sniffImageType(head: ByteArray, len: Int): String {
        // 用字节值而不是字符字面量比较，避免编码/字符转换带来的歧义。
        // 注意必须 `and 0xFF`：Java 的 byte 是有符号的，0x89 直接 toInt 会变成负数
        fun b(index: Int) = head[index].toInt() and 0xFF

        if (len >= 6 && b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46)
            return "image/gif"                                  // "GIF"
        if (len >= 8 && b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47)
            return "image/png"                                  // ‰PNG
        if (len >= 3 && b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF)
            return "image/jpeg"                                 // JPEG SOI
        if (len >= 12 && b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50)
            return "image/webp"                                 // RIFF....WEBP
        return "image/jpeg"
    }

    private fun readAllBytes(uri: Uri): ByteArray {
        val inputStream: InputStream = contentResolver.openInputStream(uri) ?: throw IOException("无法读取图片")
        return inputStream.use { it.readBytes() }
    }

    /** 探测文件声明长度，拿不到就返回 -1（表示未知，不做预判）。 */
    private fun getDeclaredSize(uri: Uri): Long {
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    /** 先按最长边 2048 采样再解码，避免手表直接解码超大原图时 OOM。 */
    private fun decodeScaled(raw: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sampleSize = 1
        val longest = Math.max(bounds.outWidth, bounds.outHeight)
        while (longest / sampleSize > MAX_COMPRESS_EDGE) sampleSize *= 2
        val options = BitmapFactory.Options()
        options.inSampleSize = sampleSize
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
    }

    private fun buildPictures(): String {
        // 组装逻辑已抽到 ReplyApi.buildPictures（纯函数，带 JVM 单测），这里只负责加锁取快照
        synchronized(uploadDataList) {
            return ReplyApi.buildPictures(ArrayList(uploadDataList))
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateImageText() {
        val count = imageList.size
        val base = if (count == 0) getString(R.string.btn_image) else getString(R.string.btn_image) + "($count)"
        // 有图还在上传就在按钮上写明，别让用户以为「图片(1)」= 已经可以发了
        imageText.text = if (pendingUploads > 0) "$base・上传中($pendingUploads)" else base
    }

    private fun checkKy(str: String): Boolean {
        if (str.contains("哔哩终端")) return true
        if (str.contains("终端")) {
            return str.contains("表") || str.contains("b站") || str.contains("B站") || str.contains("bili") || str.contains("哔")
        }
        return false
    }
}
