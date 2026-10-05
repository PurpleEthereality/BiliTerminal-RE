package com.RobinNotBad.BiliClient.activity.settings.login

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.SplashActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.SpecialLoginParser
import com.google.android.material.card.MaterialCardView
import org.json.JSONException
import org.json.JSONObject

class SpecialLoginActivity : BaseActivity() {

    private lateinit var textInput: EditText

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_special)
        Logu.i("debug", "使用特殊登录方式")

        textInput = findViewById(R.id.loginInput)
        val confirm = findViewById<MaterialCardView>(R.id.confirm)
        val pasteLogin = findViewById<MaterialCardView>(R.id.pasteLogin)
        val pasteDesc = findViewById<TextView>(R.id.pasteDesc)
        val refuse = findViewById<MaterialCardView>(R.id.refuse)
        val copy = findViewById<MaterialCardView>(R.id.copy)
        val desc = findViewById<TextView>(R.id.desc)

        val intent = intent

        if (intent.getBooleanExtra("login", true)) {
            refuse.setOnClickListener {
                if (intent.getBooleanExtra("from_setup", false))
                    startActivity(Intent(this, SplashActivity::class.java))
                else finish()
            }

            confirm.setOnClickListener {
                loginWith(textInput.text.toString())
            }

            // 直接读剪贴板登录：先过 SpecialLoginParser 校验（必须是 JSON 且带必要字段），
            // 不通过就只提示、绝不写任何登录态。
            pasteLogin.setOnClickListener {
                val clipText = readClipboardText()
                if (clipText.isNullOrBlank()) {
                    MsgUtil.showMsg("剪贴板里没有内容，请先复制登录信息")
                    return@setOnClickListener
                }
                loginWith(clipText)
            }
        } else {
            desc.setText(R.string.special_login_export)

            val jsonObject = JSONObject()
            try {
                jsonObject.put("cookies", SharedPreferencesUtil.getString("cookies", ""))
                jsonObject.put("refresh_token", SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, ""))
                jsonObject.put("access_key", SharedPreferencesUtil.getString(SharedPreferencesUtil.access_key, ""))
            } catch (e: JSONException) {
                e.printStackTrace()
            }
            textInput.setText(jsonObject.toString())
            textInput.clearFocus()

            // 导出模式下没有「登录」这件事，粘贴登录按钮一并隐藏
            pasteLogin.visibility = View.GONE
            pasteDesc.visibility = View.GONE

            refuse.visibility = View.GONE
            if (BiliTerminal.isDebugBuild()) {
                confirm.setOnClickListener {
                    try {
                        val input = JSONObject(textInput.text.toString())
                        val cookies = input.getString("cookies")
                        NetWorkUtil.setCookiesString(cookies)
                        if (input.has("refresh_token")) {
                            SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, input.getString("refresh_token"))
                        }
                        if (input.has("access_key")) {
                            SharedPreferencesUtil.putString(SharedPreferencesUtil.access_key, input.getString("access_key"))
                        }
                        runOnUiThread { MsgUtil.showMsg("导入成功") }

                        NetWorkUtil.refreshHeaders()
                    } catch (e: JSONException) {
                        runOnUiThread { MsgUtil.showMsg("请检查输入的内容，不要有多余空格或字符") }
                    }
                }
            } else confirm.visibility = View.GONE
            copy.setOnClickListener {
                val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = ClipData.newPlainText("label", textInput.text)
                clipboardManager.setPrimaryClip(clipData)
                MsgUtil.showMsg("已复制")
            }
        }
    }

    /**
     * 校验并应用一份登录信息（文本框输入与剪贴板内容共用同一条链路）。
     * 校验失败只提示、不写入任何登录态。
     */
    private fun loginWith(loginInfo: String) {
        when (val result = SpecialLoginParser.parse(loginInfo)) {
            is SpecialLoginParser.Result.Failure -> MsgUtil.showMsg(result.message)
            is SpecialLoginParser.Result.Success -> {
                SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, result.mid)
                SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf, NetWorkUtil.getInfoFromCookie("bili_jct", result.cookies))
                NetWorkUtil.setCookiesString(result.cookies)
                SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, result.refreshToken)
                result.accessKey?.let { SharedPreferencesUtil.putString(SharedPreferencesUtil.access_key, it) }
                MsgUtil.showMsg("登录成功！")
                SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.setup, true)

                AccountManager.saveCurrentAccount()

                val intent1 = Intent()
                intent1.setClass(this@SpecialLoginActivity, SplashActivity::class.java)
                startActivity(intent1)
                finish()
            }
        }
    }

    /** 读取剪贴板纯文本；剪贴板为空或系统拒绝读取（无焦点等）时返回 null。 */
    private fun readClipboardText(): String? {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        return try {
            val clip = clipboardManager.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0).coerceToText(this)?.toString()
        } catch (e: Exception) {
            Logu.e("debug", "读取剪贴板失败：${e.message}")
            null
        }
    }

}