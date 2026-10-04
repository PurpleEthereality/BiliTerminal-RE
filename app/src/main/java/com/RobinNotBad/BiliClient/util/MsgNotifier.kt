package com.RobinNotBad.BiliClient.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.message.MessageActivity

/**
 * 新消息的通知栏通知（26.10.04 批次 5 的 C14）。
 *
 * 设计取舍：
 * - **只做通知栏提醒，不做 RemoteInput 速回**（用户拍板）：速回要额外申请权限、
 *   处理跨进程回复广播，而手表上打字成本本身就高，收益不抵复杂度。点通知＝打开消息页。
 * - **只在打开应用时检查，不做后台定时**（用户拍板）：项目里没有 WorkManager /
 *   AlarmManager 依赖，也不为此新增（见 `AGENTS.md`「不轻易引入新第三方库」）。
 *   触发点是 `BiliTerminal.onCreate` 里既有的未读检查。
 * - 通知 ID 避开既有两个：1027 = `DownloadService`、1028 = `PlaybackService`。
 */
object MsgNotifier {

    const val CHANNEL_ID = "private_msg_channel"

    /** 1027 被 DownloadService 占用、1028 被 PlaybackService 占用，勿改。 */
    private const val NOTIFICATION_ID = 1029

    /**
     * 是否该弹通知：开关打开、当前有未读、且未读比上次检查时变多。
     *
     * 抽成纯函数是因为错判的后果一头是"该提醒不提醒"，另一头是"每次打开应用都被骚扰"，
     * 两种都不会报错，只能靠单测锁死。
     */
    fun shouldNotify(previousUnread: Int, currentUnread: Int, enabled: Boolean): Boolean =
        enabled && currentUnread > 0 && currentUnread > previousUnread

    /**
     * 通知正文：私信与其它未读（回复/点赞/@）分开报，便于一眼判断值不值得点进去。
     */
    fun summaryText(privateMsgUnread: Int, otherUnread: Int): String {
        val parts = ArrayList<String>(2)
        if (privateMsgUnread > 0) parts.add("$privateMsgUnread 条新私信")
        if (otherUnread > 0) parts.add("$otherUnread 条新消息")
        return if (parts.isEmpty()) "有新消息" else parts.joinToString("、")
    }

    /**
     * 发通知。任何异常都只记日志——通知失败绝不能影响未读检查本身。
     */
    fun notifyNewMessages(context: Context, privateMsgUnread: Int, otherUnread: Int) {
        try {
            val manager = NotificationManagerCompat.from(context)
            // 13+ 未授予 POST_NOTIFICATIONS，或用户在系统设置里关掉了，直接放弃
            if (!manager.areNotificationsEnabled()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "新消息", NotificationManager.IMPORTANCE_DEFAULT
                )
                channel.description = "收到新私信 / 新消息时的提醒"
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }

            val intent = Intent(context, MessageActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.icon)
                .setContentTitle("有新消息喵~")
                .setContentText(summaryText(privateMsgUnread, otherUnread))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Logu.e("MsgNotifier", "发送通知失败: ${e.message}")
        }
    }

    /**
     * 撤销通知。进入消息页把未读清零时调用，否则"看过了通知还挂着"。
     */
    fun cancel(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Logu.e("MsgNotifier", "撤销通知失败: ${e.message}")
        }
    }
}
