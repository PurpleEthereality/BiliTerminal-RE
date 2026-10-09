package com.RobinNotBad.BiliClient.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.TextView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.AppInfoApi
import com.RobinNotBad.BiliClient.api.TerminalApi
import com.RobinNotBad.BiliClient.model.ApiResult
import com.RobinNotBad.BiliClient.service.DownloadService
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.CrashReporter
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.google.android.material.button.MaterialButton

class CatchActivity : BaseActivity() {
    private var openStack: Boolean = false

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_catch)

        val reasonView = findViewById<TextView>(R.id.catch_reason)
        val stackView = findViewById<TextView>(R.id.stack)
        val btnUpload = findViewById<MaterialButton>(R.id.upload_btn)

        val intent = intent
        val stack = intent.getStringExtra("stack")

        stackView.text = stack

        // 26.10.09（自建崩溃报告）：这些 extra 由 ErrorCatch 在崩溃瞬间塞进来，
        // 因为主进程马上就被 killProcess，页面轨迹之类的东西只在那一刻存在。
        val exception = intent.getStringExtra("exception")
        val message = intent.getStringExtra("message")
        val crashThread = intent.getStringExtra("thread")
        val trail = intent.getStringExtra("trail")
        val uptime = intent.getLongExtra("uptime", 0L)

        val autoStatusView = findViewById<TextView>(R.id.auto_report_status)

        // 自动上报走自建服务器，与下面那个「手动上传到上游」的按钮互相独立：
        // 上游那套要求已登录、且按异常类型白名单把一部分崩溃挡在门外（见 allowUpload），
        // 而自动上报要的正是这些没被人为挑过的样本。开关默认开，用户可在设置里关。
        if (TerminalApi.isCrashReportAutoEnabled()) {
            autoStatusView.visibility = android.view.View.VISIBLE
            autoStatusView.text = "正在自动上报错误报告…"
            CenterThreadPool.run {
                val res = CrashReporter.upload(
                    this@CatchActivity, exception, message, crashThread, stack, trail, uptime
                )
                runOnUiThread {
                    autoStatusView.text = if (res.code >= 0)
                        "已自动上报错误报告\n报错ID：${res.code}\n如需补充说明请到「设置 - 关于 - 反馈与建议」"
                    else
                        "自动上报失败：${res.message}\n（可在「设置 - 关于与帮助」里关闭自动上报）"
                }
            }
        }

        findViewById<android.view.View>(R.id.exit_btn).setOnClickListener { android.os.Process.killProcess(android.os.Process.myPid()) }

        var reasonStr: SpannableString? = null

        if (stack != null) {

            var allowUpload = false

            if (stack.contains("java.lang.NumberFormatException"))
                reasonStr = SpannableString("可能的崩溃原因：\n数值转换出错")
            else if (stack.contains("java.lang.UnsatisfiedLinkError"))
                reasonStr = SpannableString("可能的崩溃原因：\n外部库加载出错，可能设备太老或修改了安装包")
            else if (stack.contains("org.json.JSONException"))
                reasonStr = SpannableString("可能的崩溃原因：\n数据解析错误")
            else if (stack.contains("java.lang.OutOfMemoryError"))
                reasonStr = SpannableString("可能的崩溃原因：\n内存爆了，这在小内存设备上很正常")
            else
                allowUpload = true

            if (allowUpload) btnUpload.setOnClickListener {
                btnUpload.isEnabled = false
                if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, -1) == -1L)
                    MsgUtil.toast("我们不对未登录时遇到的问题负责\n——除非它真的经常出现且非常影响使用")
                else {
                    CenterThreadPool.run {
                        val res: ApiResult = AppInfoApi.uploadStack(stack, this@CatchActivity)
                        runOnUiThread {
                            if (res.code >= 0)
                                btnUpload.text = "请带着你的报错ID：${res.code}\n和你崩溃前进行的操作\n去找开发者\n（提醒：开发者不保证会修好也不保证随时回复你）"
                            else btnUpload.text = res.message

                            if (res.code == -1) btnUpload.isEnabled = true
                        }
                    }
                }
            }
            else btnUpload.text = "此类型报错不可上传\n非特殊情况请勿打扰开发者谢谢喵"

        } else finish()

        findViewById<android.view.View>(R.id.restart_btn).setOnClickListener {
            finish()
            stopService(Intent(this, DownloadService::class.java))
            startActivity(Intent(this, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Process.killProcess(Process.myPid())
        }

        if (reasonStr != null) {
            reasonStr.setSpan(StyleSpan(Typeface.BOLD), 0, 8, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            reasonView.text = reasonStr
        } else reasonView.text = "未知的崩溃原因"

        stackView.setOnClickListener {
            openStack = !openStack
            if (openStack) stackView.maxLines = 200
            else stackView.maxLines = 5
        }

        StringUtil.setCopy(stackView)
    }

    override fun eventBusEnabled(): Boolean {
        return false
    }
}