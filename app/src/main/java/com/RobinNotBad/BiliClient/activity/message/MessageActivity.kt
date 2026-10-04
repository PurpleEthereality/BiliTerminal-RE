package com.RobinNotBad.BiliClient.activity.message

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView

import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.adapter.message.PrivateMsgSessionsAdapter
import com.RobinNotBad.BiliClient.api.MessageApi
import com.RobinNotBad.BiliClient.api.PrivateMsgApi
import com.RobinNotBad.BiliClient.model.PrivateMsgSession
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgNotifier
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.card.MaterialCardView

import org.json.JSONObject
import java.util.Collections

class MessageActivity : InstanceActivity() {
    private lateinit var sessionsView: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    /**
     * Android 13+ 弹通知需要 POST_NOTIFICATIONS 运行时权限；没授权时
     * [MsgNotifier.notifyNewMessages] 会因 `areNotificationsEnabled()` 为 false 直接放弃。
     * 放在消息页请求最自然：用户此刻正在看消息，能理解为什么要通知。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetTextI18n", "InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        asyncInflate(R.layout.activity_message) { _, _ ->
            swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
            swipeRefreshLayout.isEnabled = false
            swipeRefreshLayout.isRefreshing = true

            val settingBtn = findViewById<MaterialCardView>(R.id.setting_btn)
            settingBtn.setOnClickListener {
                val intent = Intent(this, MessageSettingsActivity::class.java)
                startActivity(intent)
            }

            val reply = findViewById<MaterialCardView>(R.id.reply)
            reply.setOnClickListener {
                val intent = Intent()
                intent.setClass(this, NoticeActivity::class.java)
                intent.putExtra("type", "reply")
                startActivity(intent)
                (findViewById<TextView>(R.id.reply_text)).text = "回复我的"
            }

            val like = findViewById<MaterialCardView>(R.id.like)
            like.setOnClickListener {
                val intent = Intent()
                intent.setClass(this, NoticeActivity::class.java)
                intent.putExtra("type", "like")
                startActivity(intent)
                (findViewById<TextView>(R.id.like_text)).text = "收到的赞"
            }

            val at = findViewById<MaterialCardView>(R.id.at)
            at.setOnClickListener {
                val intent = Intent()
                intent.setClass(this, NoticeActivity::class.java)
                intent.putExtra("type", "at")
                startActivity(intent)
                (findViewById<TextView>(R.id.at_text)).text = "@我"
            }

            val system = findViewById<MaterialCardView>(R.id.system)
            system.setOnClickListener {
                val intent = Intent()
                intent.setClass(this, NoticeActivity::class.java)
                intent.putExtra("type", "system")
                startActivity(intent)
            }

            sessionsView = findViewById(R.id.sessions_list)
            sessionsView.isNestedScrollingEnabled = false

            loadSessions()

            val scrollView = findViewById<View>(R.id.scrollView)
            scrollView.isFocusable = true
            scrollView.isFocusableInTouchMode = true
            scrollView.requestFocus()

            // 教程改由 BaseActivity 按 Tutorials 注册表集中触发
        }
    }

    /**
     * 请求通知权限（仅 Android 13+）。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * 拉会话列表并刷新界面。置顶/删除会话后由适配器回调再次调用（服务端才是唯一真相）。
     */
    private fun loadSessions() {
        CenterThreadPool.run {
            try {
                val stats = MessageApi.getUnread()
                val sessionsList = PrivateMsgApi.getSessionsList(20)
                Collections.sort(sessionsList) { o1, o2 ->
                    val o1Unread = o1.unread > 0
                    val o2Unread = o2.unread > 0
                    if (o1Unread && !o2Unread) {
                        -1
                    } else if (!o1Unread && o2Unread) {
                        1
                    } else {
                        0
                    }
                }
                val uidList = ArrayList<Long>()
                for (item in sessionsList) {
                    uidList.add(item.talkerUid)
                }
                val userMap = PrivateMsgApi.getUsersInfo(uidList)
                val adapter = PrivateMsgSessionsAdapter(this, sessionsList, userMap) { loadSessions() }
                runOnUiThread {
                    swipeRefreshLayout.isRefreshing = false
                    try {
                        (findViewById<TextView>(R.id.reply_text)).text = "回复我的" +
                                (if ((stats.getInt("reply") > 0)) ("(" + stats.getInt("reply") + "未读)") else "")
                        (findViewById<TextView>(R.id.like_text)).text =
                            "收到的赞" + (if ((stats.getInt("like") > 0)) ("(" + stats.getInt("like") + "未读)") else "")
                        (findViewById<TextView>(R.id.at_text)).text =
                            "@我" + (if ((stats.getInt("at") > 0)) ("(" + stats.getInt("at") + "未读)") else "")
                        sessionsView.layoutManager = CustomLinearManager(this)
                        sessionsView.adapter = adapter
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, 0)
                        // 未读已清零，挂着的通知也该撤掉（否则"看过了通知还在"）
                        MsgNotifier.cancel(this)
                    } catch (e: Exception) {
                        MsgUtil.err(e)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { MsgUtil.err(e) }
            }
        }
    }
}