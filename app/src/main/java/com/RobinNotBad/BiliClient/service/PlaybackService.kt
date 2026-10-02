package com.RobinNotBad.BiliClient.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.player.PlayerActivity
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

/**
 * 后台/熄屏播放的前台服务与通知栏遥控。
 *
 * 设计上刻意保持"服务是壳、播放器是主"：
 * - 播放器实例（IjkMediaPlayer、弹幕、全部状态）仍然只归 [PlayerActivity] 持有，
 *   本服务不接管、也不重建任何播放逻辑，只在进程被保活期间每秒刷一次通知。
 * - 服务通过 [WeakReference] 反查播放页状态，反过来播放页通过本服务的公开方法
 *   转发"暂停/播放/关闭"指令，全程在主线程执行。
 *
 * 之所以必须用前台服务而不是普通后台线程：Android 8 起后台进程随时可能被回收，
 * 而本服务的目标恰恰是"熄屏后不被回收"。
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.RobinNotBad.BiliClient.action.PLAYBACK_TOGGLE"
        const val ACTION_STOP = "com.RobinNotBad.BiliClient.action.PLAYBACK_STOP"

        private const val CHANNEL_ID = "playback_channel"
        private const val NOTIFICATION_ID = 1028 // 1027 被 DownloadService 占用，勿改
        private const val REFRESH_INTERVAL_MS = 1000L

        /** 只持有弱引用：播放页被销毁后服务必须能自己结束，不能把 Activity 泄漏到进程级 */
        @Volatile
        private var playerRef: WeakReference<PlayerActivity>? = null

        /**
         * 启动后台播放保活。调用点只在 [PlayerActivity.onPause]，
         * 此时应用仍处于"有可见界面"的豁免范围内，不会被 Android 12 的后台启动限制拦截。
         */
        @JvmStatic
        fun start(context: Context, activity: PlayerActivity) {
            playerRef = WeakReference(activity)
            val intent = Intent(context, PlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ 的 ForegroundServiceStartNotAllowedException、厂商省电策略等
                playerRef = null
                Logu.e("PlaybackService.start", e.message ?: "启动后台播放服务失败")
                CenterThreadPool.runOnUiThread { MsgUtil.showMsg("后台播放启动失败") }
            }
        }

        /** 回到前台或退出播放页时调用，通知栏遥控不应残留 */
        @JvmStatic
        fun stop(context: Context) {
            playerRef = null
            try {
                context.stopService(Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                Logu.e("PlaybackService.stop", e.message ?: "停止后台播放服务失败")
            }
        }
    }

    private lateinit var notifyManager: NotificationManager
    private var timer: Timer? = null

    override fun onCreate() {
        super.onCreate()
        notifyManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "后台播放", NotificationManager.IMPORTANCE_LOW)
            channel.description = "后台/熄屏播放时的常驻遥控通知"
            channel.setShowBadge(false)
            channel.setSound(null, null)
            channel.enableVibration(false)
            notifyManager.createNotificationChannel(channel)
        }
        startUpdateTimer()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须先 startForeground：startForegroundService 后 5 秒内不挂通知会被系统杀进程
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        when (intent?.action) {
            ACTION_TOGGLE -> player()?.serviceTogglePlay()
            ACTION_STOP -> {
                player()?.serviceStopPlayback()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务里划掉时进程可能很快被杀，先补一次进度上报
        player()?.serviceReportNow()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        playerRef = null
        super.onDestroy()
    }

    private fun player(): PlayerActivity? = playerRef?.get()

    private fun startUpdateTimer() {
        timer?.cancel()
        timer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    try {
                        val a = player()
                        if (a == null || a.isFinishing || a.serviceGone()) {
                            // 播放页没了，服务自己收尾
                            stopSelf()
                            cancel()
                        } else {
                            notifyManager.notify(NOTIFICATION_ID, buildNotification())
                        }
                    } catch (e: Exception) {
                        // TimerTask 抛未捕获异常会永久终止整个 Timer，通知从此静默失效
                        Logu.e("PlaybackService.timer", e.message ?: "刷新播放通知失败")
                    }
                }
            }, REFRESH_INTERVAL_MS, REFRESH_INTERVAL_MS)
        }
    }

    private fun buildNotification(): Notification {
        val a = player()
        val playing = a?.serviceIsPlaying() == true
        val position = a?.servicePositionMs() ?: 0
        val duration = a?.serviceDurationMs() ?: 0
        val title = a?.serviceTitle()?.takeIf { it.isNotBlank() } ?: "RE：哔哩终端正在播放"

        val toggleIntent = PendingIntent.getService(
            this, 0,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 用 FLAG_ACTIVITY_REORDER_TO_FRONT 复用已有任务，尽量不新建播放页实例；
        // 回到前台后 onResume 会自行停掉本服务。
        val contentIntent = PendingIntent.getActivity(
            this, 2,
            (packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, PlayerActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.icon)
            .setContentTitle(title)
            .setContentText("${formatTime(position)} / ${formatTime(duration)}")
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { if (duration > 0) setProgress(duration, position.coerceIn(0, duration), false) }
            .clearActions()
            .addAction(
                if (playing) R.drawable.btn_player_pause else R.drawable.btn_player_play,
                if (playing) "暂停" else "播放", toggleIntent
            )
            .addAction(0, "关闭", stopIntent)
            .build()
    }

    private fun formatTime(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
    }
}
