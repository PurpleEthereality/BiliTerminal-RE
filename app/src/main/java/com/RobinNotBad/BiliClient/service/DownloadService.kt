package com.RobinNotBad.BiliClient.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.activity.video.local.DownloadListActivity
import com.RobinNotBad.BiliClient.activity.video.local.LocalListActivity
import com.RobinNotBad.BiliClient.api.PlayerApi
import com.RobinNotBad.BiliClient.helper.sql.DownloadSqlHelper
import com.RobinNotBad.BiliClient.service.download.DownloadPathSpec
import com.RobinNotBad.BiliClient.service.download.DownloadProgressMath
import com.RobinNotBad.BiliClient.service.download.DownloadProgressStore
import com.RobinNotBad.BiliClient.service.download.DownloadProgressInfo
import com.RobinNotBad.BiliClient.model.DownloadSection
import com.RobinNotBad.BiliClient.model.PlayerData
import com.RobinNotBad.BiliClient.model.SubtitleLink
import com.RobinNotBad.BiliClient.util.Aria2Util
import com.RobinNotBad.BiliClient.util.VideoMetaManager
import com.RobinNotBad.BiliClient.model.VideoMeta
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil
import okhttp3.Response
import okio.BufferedSink
import okio.Sink
import okio.buffer
import okio.sink
import org.json.JSONException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Timer
import java.util.TimerTask

class DownloadService : Service() {

    companion object {
        // 这几个字段会被下载协程与主线程同时读写，必须 @Volatile 保证可见性：
        // started 决定调度循环是否继续，exitCode/percent/state 决定通知与退出时的清理动作。
        @JvmStatic @Volatile var started: Boolean = false

        /**
         * 本服务的下载批次是否已在执行。
         *
         * [onStartCommand] 可能被重复投递（START_STICKY 重建、或多次 startForegroundService），
         * 没有这道守卫就会并发跑起两个下载批次、双线程写同一文件（审计 S6）。
         * 不能用 [started] 代替：start() 是先置 started 再 startForegroundService，
         * 首次合法调用进来时 started 已经是 true，用它判断会把正常请求也挡掉。
         */
        @Volatile private var batchRunning: Boolean = false

        @JvmStatic @Volatile var exitCode: Int = 0
        @JvmStatic @Volatile var percent: Float = -1f
        @JvmStatic @Volatile var state: String? = null
        @JvmStatic @Volatile var section: DownloadSection? = null
        // 速度显示字符串与"是否高速模式"由 DownloadProgressStore 持有，这里只留 @JvmStatic 门面，
        // Java/Kotlin 侧照旧写 DownloadService.speedStr / DownloadService.isSpeedMode。
        // 刻意保持非 volatile：UI 线程直接读、下载线程直接写是既有数据竞争，加 volatile 属于行为变更。
        @JvmStatic
        var speedStr: String
            get() = DownloadProgressStore.speedStr
            set(value) {
                DownloadProgressStore.speedStr = value
            }

        @JvmStatic
        var isSpeedMode: Boolean
            get() = DownloadProgressStore.isSpeedMode
            set(value) {
                DownloadProgressStore.isSpeedMode = value
            }

        private var firstDown: Long = -1

        // 本次下载批次的总体统计（总进度条、通知栏使用）
        @JvmStatic val batchStats = DownloadBatchStats()

        @JvmStatic var activeDownloadsCount: Int = 0
            private set

        // 被用户暂停的任务：key=section.id。暂停后该任务的下载循环会尽快退出，状态置为 paused，
        // 调度器（只取 state="none"）不会重新拾取；恢复时清除标志并置回 none。
        // 用带 getter 的 val 暴露（Java 侧是 getPausedMap() 静态方法）：调用方直接对返回的 map
        // add/remove（DownloadListActivity），必须仍是同一个 map 实例，不能在这里复制。
        @JvmStatic
        val pausedMap: java.util.concurrent.ConcurrentHashMap<Long, Boolean>
            get() = DownloadProgressStore.pausedMap

        // ---------- 以下为 DownloadProgressStore 的门面：实现全部搬到 service/download/ ----------

        @JvmStatic
        fun isPaused(id: Long): Boolean = DownloadProgressStore.isPaused(id)

        @JvmStatic
        fun pauseDownload(id: Long) = DownloadProgressStore.pauseDownload(id)

        @JvmStatic
        fun resumeDownload(id: Long) = DownloadProgressStore.resumeDownload(id)

        @JvmStatic
        fun getDownloadProgress(id: Long): DownloadProgressInfo? {
            return DownloadProgressStore.getDownloadProgress(id)
        }

        @JvmStatic
        fun getDownloadProgressMap(): java.util.concurrent.ConcurrentHashMap<Long, DownloadProgressInfo> {
            return DownloadProgressStore.getDownloadProgressMap()
        }

        @JvmStatic
        fun setDownloadProgress(id: Long, progress: Float, state: String) {
            DownloadProgressStore.setDownloadProgress(id, progress, state)
        }

        @JvmStatic
        fun setDownloadProgress(id: Long, progress: Float, state: String,
                                downloadedBytes: Long, totalBytes: Long) {
            DownloadProgressStore.setDownloadProgress(id, progress, state, downloadedBytes, totalBytes)
        }

        @JvmStatic
        fun removeDownloadProgress(id: Long) {
            DownloadProgressStore.removeDownloadProgress(id)
        }

        @JvmStatic
        fun getDownloadedBytes(): Long = DownloadProgressStore.getDownloadedBytes()

        @JvmStatic
        fun addDownloadedBytes(bytes: Long) {
            DownloadProgressStore.addDownloadedBytes(bytes)
        }

        @JvmStatic
        fun resetDownloadedBytes() {
            DownloadProgressStore.resetDownloadedBytes()
        }

        /**
         * 根据当前数据库中的下载项与进度映射，计算本次批次的总体进度。
         */
        @JvmStatic
        fun computeOverallProgress(sections: List<DownloadSection>): Float {
            return DownloadProgressStore.computeOverallProgress(sections)
        }

        /** 当前仍在下载中的项目剩余字节数合计（用于预估剩余时间） */
        @JvmStatic
        fun getActiveRemainingBytes(sections: List<DownloadSection>): Long {
            return DownloadProgressStore.getActiveRemainingBytes(sections)
        }

        // 下载结果码（含"任务被暂停不算失败"）统一见 DownloadPathSpec

        @JvmStatic
        fun getFirst(): DownloadSection? {
            var cursor: Cursor? = null
            var database: SQLiteDatabase? = null
            return try {
                val helper = DownloadSqlHelper(BiliTerminal.context)
                database = helper.readableDatabase

                if (firstDown >= 0)
                    cursor = database.rawQuery("select * from download where id=? limit 1",
                        arrayOf(firstDown.toString()))
                if (cursor == null)
                    cursor = database.rawQuery("select * from download where state=? limit 1", arrayOf("none"))

                firstDown = -1

                if (cursor == null || cursor.count == 0)
                    return null

                cursor.moveToFirst()
                DownloadSection(cursor)
            } catch (e: Exception) {
                MsgUtil.err(e)
                null
            } finally {
                cursor?.close()
                database?.close()
            }
        }

        @JvmStatic
        fun getAll(): ArrayList<DownloadSection>? {
            var cursor: Cursor? = null
            var database: SQLiteDatabase? = null
            return try {
                val helper = DownloadSqlHelper(BiliTerminal.context)
                database = helper.readableDatabase
                cursor = database.rawQuery("select * from download", null)
                if (cursor == null || cursor.count == 0)
                    return null

                val list = ArrayList<DownloadSection>()
                while (cursor.moveToNext()) {
                    list.add(DownloadSection(cursor))
                }
                list
            } catch (e: Exception) {
                MsgUtil.err(e)
                ArrayList()
            } finally {
                cursor?.close()
                database?.close()
            }
        }

        @JvmStatic
        fun deleteSection(id: Long) {
            var database: SQLiteDatabase? = null
            try {
                val helper = DownloadSqlHelper(BiliTerminal.context)
                database = helper.writableDatabase
                database.execSQL("delete from download where id=?", arrayOf<Any>(id))
                database.close()
            } catch (e: Exception) {
                MsgUtil.err(e)
            } finally {
                database?.close()
            }
        }

        @JvmStatic
        fun clear() {
            var database: SQLiteDatabase? = null
            try {
                val helper = DownloadSqlHelper(BiliTerminal.context)
                database = helper.writableDatabase
                database.execSQL("delete from download", arrayOf<Any>())
                database.close()
            } catch (e: Exception) {
                MsgUtil.err(e)
            } finally {
                database?.close()
            }
        }

        @JvmStatic
        fun setState(id: Long, state: String) {
            var database: SQLiteDatabase? = null
            try {
                val helper = DownloadSqlHelper(BiliTerminal.context)
                database = helper.writableDatabase
                database.execSQL("update download set state=? where id=?", arrayOf<Any>(state, id))
                database.close()
            } catch (e: Exception) {
                MsgUtil.err(e)
            } finally {
                database?.close()
            }
        }

        /**
         * 保存视频元数据到缓存文件夹
         */
        private fun saveVideoMeta(folder: File, title: String, aid: Long, cid: Long, qn: Int, downloadType: String) {
            try {
                val meta = VideoMeta()
                meta.title = title
                meta.aid = aid
                meta.cid = cid
                meta.qn = qn
                meta.downloadType = downloadType
                VideoMetaManager.saveMeta(folder, meta)
            } catch (e: Exception) {
                Logu.e("saveVideoMeta", "保存视频元数据失败: ${e.message}")
            }
        }

        /**
         * 更新视频元数据中的画质列表（下载完成后回调）
         */
        private fun updateVideoMetaQualityLists(folder: File, qnStrList: Array<String>?, qnValueList: IntArray?) {
            try {
                if (qnStrList == null && qnValueList == null) return
                val meta = VideoMetaManager.readMeta(folder)
                meta.qnStrList = qnStrList
                meta.qnValueList = qnValueList
                VideoMetaManager.saveMeta(folder, meta)
            } catch (e: Exception) {
                Logu.e("updateVideoMeta", "更新画质列表失败: ${e.message}")
            }
        }

        @JvmStatic
        fun startDownload(title: String, aid: Long, cid: Long, cover: String, qn: Int, downloadType: String,
                          audioUrl: String) {
            CenterThreadPool.run {
                var database: SQLiteDatabase? = null
                var cursor: Cursor? = null
                try {
                    val helper = DownloadSqlHelper(BiliTerminal.context)
                    database = helper.writableDatabase

                    cursor = database.rawQuery("select * from download where aid=? and cid=?",
                        arrayOf(aid.toString(), cid.toString()))
                    if (cursor != null && cursor.count > 0) {
                        MsgUtil.showMsg("该视频已在下载队列中")
                        return@run
                    }
                    cursor?.close()

                    database.execSQL(
                        "insert into download(type,state,aid,cid,qn,title,child,cover,download_type,audio_url) values(?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any>(DownloadPathSpec.TASK_VIDEO_SINGLE, "none", aid, cid, qn, title, "", GlideUtil.url(cover),
                            downloadType, audioUrl))

                    val path_single = FileUtil.getVideoDownloadPath(title, null)
                    path_single.mkdirs()

                    val file_sign = File(path_single, DownloadPathSpec.FILE_DOWNLOADING)
                    if (!file_sign.exists())
                        file_sign.createNewFile()

                    // 保存画质元数据
                    val qualityFile = File(path_single, DownloadPathSpec.FILE_QUALITY)
                    val qualityContent = if (DownloadPathSpec.TYPE_AUDIO_ONLY == downloadType) DownloadPathSpec.TYPE_AUDIO_ONLY else qn.toString()
                    qualityFile.writeText(qualityContent)

                    // 保存完整视频元数据到 .video_meta.json
                    saveVideoMeta(path_single, title, aid, cid, qn, downloadType)

                    val msg = if (DownloadPathSpec.TYPE_AUDIO_ONLY == downloadType) "已添加音频下载" else "已添加下载"
                    MsgUtil.showMsg(msg)

                    start(-1)
                } catch (e: Exception) {
                    MsgUtil.err(e)
                } finally {
                    cursor?.close()
                    database?.close()
                }
            }
        }

        @JvmStatic
        fun startDownload(parent: String, child: String, aid: Long, cid: Long, cover: String, qn: Int,
                          downloadType: String, audioUrl: String) {
            CenterThreadPool.run {
                var database: SQLiteDatabase? = null
                var cursor: Cursor? = null
                try {
                    val helper = DownloadSqlHelper(BiliTerminal.context)
                    database = helper.writableDatabase

                    cursor = database.rawQuery("select * from download where aid=? and cid=?",
                        arrayOf(aid.toString(), cid.toString()))
                    if (cursor != null && cursor.count > 0) {
                        MsgUtil.showMsg("该视频已在下载队列中")
                        return@run
                    }
                    cursor?.close()

                    database.execSQL(
                        "insert into download(type,state,aid,cid,qn,title,child,cover,download_type,audio_url) values(?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any>(DownloadPathSpec.TASK_VIDEO_MULTI, "none", aid, cid, qn, parent, child, GlideUtil.url(cover),
                            downloadType, audioUrl))

                    val path_page = FileUtil.getVideoDownloadPath(parent, child)
                    path_page.mkdirs()

                    val file_sign = File(path_page, DownloadPathSpec.FILE_DOWNLOADING)
                    if (!file_sign.exists())
                        file_sign.createNewFile()

                    // 保存画质元数据
                    val qualityFile = File(path_page, DownloadPathSpec.FILE_QUALITY)
                    val qualityContent = if (DownloadPathSpec.TYPE_AUDIO_ONLY == downloadType) DownloadPathSpec.TYPE_AUDIO_ONLY else qn.toString()
                    qualityFile.writeText(qualityContent)

                    // 保存完整视频元数据到 .video_meta.json
                    saveVideoMeta(path_page, child, aid, cid, qn, downloadType)

                    val msg = if (DownloadPathSpec.TYPE_AUDIO_ONLY == downloadType) "已添加音频下载" else "已添加下载"
                    MsgUtil.showMsg(msg)

                    start(-1)
                } catch (e: Exception) {
                    MsgUtil.err(e)
                } finally {
                    cursor?.close()
                    database?.close()
                }
            }
        }

        @JvmStatic
        @Synchronized
        fun start(first: Long) {
            // 检查后置位（check-then-act）必须在同一把锁里完成：
            // start() 的入口不止一个（添加下载、恢复暂停、下载页手动继续），
            // 并发进入时两个线程会同时通过 if 检查、各自启动一个下载批次，最终并发写同一批文件。
            if (started) {
                // 不重复启动，但要给出可感知的反馈，避免用户点了"继续下载"以为没生效
                MsgUtil.showMsg("下载队列已在进行中")
                return
            }
            started = true
            Logu.d("start")
            firstDown = first

            val context = BiliTerminal.context!!
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(Intent(context, DownloadService::class.java))
                else
                    context.startService(Intent(context, DownloadService::class.java))
            } catch (e: Exception) {
                started = false
                Logu.e("start", e.message ?: "启动下载服务失败")
                CenterThreadPool.runOnUiThread {
                    MsgUtil.showMsg("启动下载服务失败，请重试")
                }
            }
        }

        /** 在批次开始时重置速度采样，避免把上一批次的字节计入 */
        @JvmStatic
        fun resetSpeedSampling() {
            DownloadProgressStore.resetSpeedSampling()
        }

        /** 周期性采样全局已下载字节数，得到所有并行下载的聚合速度 */
        @JvmStatic
        fun sampleSpeed() {
            DownloadProgressStore.sampleSpeed()
        }
    }

    val NOTIFICATION_CHANNEL_ID = "biliterminal_download"
    val FOREGROUND_ID = 1027
    lateinit var statusBuilder: NotificationCompat.Builder
    lateinit var completionBuilder: NotificationCompat.Builder
    lateinit var notifyManager: NotificationManager

    private var exitMessage: String? = null

    private var toastTimer: Timer? = null
    private var notifyTimer: Timer? = null

    override fun onCreate() {
        super.onCreate()

        Logu.d("onCreate")

        notifyManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "哔哩终端下载服务",
                NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "哔哩终端下载服务"
            channel.setSound(null, null)
            channel.enableVibration(false)

            notifyManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, DownloadListActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        statusBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.icon)
            .setContentTitle("下载视频中")
            .setProgress(100, 0, false)
            .setContentIntent(pendingIntent)
            .setSound(null)
            .setVibrate(null)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        completionBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.icon)
            .setContentTitle("下载完成")
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setSound(null)
            .setVibrate(null)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    @SuppressLint("MutatingSharedPrefs")
    override fun onStartCommand(serviceIntent: Intent?, flags: Int, startId: Int): Int {
        Logu.d("onStartCommand")
        if (serviceIntent == null) {
            return START_STICKY
        }
        // 幂等守卫（审计 S6）：服务已在一个批次里时，重复的 onStartCommand 必须直接返回。
        // 否则第二个批次会重新执行 recoverStuckSections()，把第一个批次正在下载的 section
        // 由 "downloading" 改回 "none"，调度器随即二次拾取同一文件，两个线程写同一路径。
        // 早退发生在 startForeground 之前是安全的：首次调用已把服务拉成前台。
        if (batchRunning) {
            Logu.d("下载批次已在进行中，忽略重复的 onStartCommand")
            return START_STICKY
        }
        batchRunning = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_ID, statusBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FOREGROUND_ID, statusBuilder.build())
        }

        exitCode = DownloadPathSpec.ERR_UNKNOWN
        startNotifyProgress()

        CenterThreadPool.run {
            try {
                // 恢复上次崩溃/中断遗留的"下载中"记录，避免卡死
                recoverStuckSections()
                // 开始新批次，重置统计
                batchStats.reset()
                resetDownloadedBytes()
                resetSpeedSampling()
                activeDownloadsCount = 0

                val parallelCount = Aria2Util.getParallelDownloadVideos().coerceIn(1, 10)
                if (parallelCount <= 1)
                    sequentialDownload()
                else
                    parallelDownload(parallelCount)

                section = null
                refreshDownloadList()

                exitCode = DownloadPathSpec.NORMAL
                exitMessage = if (batchStats.failed > 0)
                    "${batchStats.failed} 个任务下载失败，请重试"
                else
                    "全部下载完成"
            } catch (e: Exception) {
                MsgUtil.err(e)
                exitCode = DownloadPathSpec.ERR_UNKNOWN
                exitMessage = "下载失败，未知错误"
            }

            stopSelf()
        }

        return Service.START_STICKY
    }

    private fun sequentialDownload() {
        while (started) {
            val section_tmp = getFirst()
            if (section_tmp == null)
                break

            section = section_tmp
            // 串行模式：遇到失败即停止，剩余任务保持排队，可再次启动续传
            if (!runDownloadSection(section!!))
                break
        }
    }

    private fun parallelDownload(parallelCount: Int) {
        val semaphore = java.util.concurrent.Semaphore(parallelCount)
        val activeCount = java.util.concurrent.atomic.AtomicInteger(0)

        while (started) {
            // 等待一个空闲槽位
            try {
                semaphore.acquire()
            } catch (e: InterruptedException) {
                break
            }

            if (!started) {
                semaphore.release()
                break
            }

            val sectionToProcess = getFirst()
            if (sectionToProcess == null) {
                // 没有可下载的任务了
                semaphore.release()
                if (activeCount.get() == 0)
                    break  // 无任务且无活跃下载，真正结束
                // 还有活跃下载在跑，等待后再试（允许新加入的任务被拾取）
                try {
                    Thread.sleep(500)
                } catch (ignored: InterruptedException) {
                }
                continue
            }

            // 立即标记为下载中，防止竞态条件导致同一任务被重复调度
            setState(sectionToProcess.id, "downloading")
            setDownloadProgress(sectionToProcess.id, 0f, "准备中")

            activeCount.incrementAndGet()
            activeDownloadsCount = activeCount.get()

            section = sectionToProcess
            state = "准备中"
            percent = 0f

            val taskSection = sectionToProcess
            CenterThreadPool.run {
                try {
                    // 单个任务失败不会中止整个批次，其余任务继续下载
                    runDownloadSection(taskSection)
                } catch (e: Exception) {
                    MsgUtil.err(e)
                } finally {
                    val remaining = activeCount.decrementAndGet()
                    activeDownloadsCount = remaining
                    semaphore.release()
                    refreshDownloadList()
                }
            }
        }

        // 等待所有活跃下载完成
        while (activeCount.get() > 0 && started) {
            try {
                Thread.sleep(300)
            } catch (ignored: InterruptedException) {
            }
        }
    }

    /**
     * 处理单个视频下载并统计成功/失败。
     * 失败项目会被置为终态(error)：不会被再次拾取，也不会卡在"下载中"。
     */
    private fun runDownloadSection(downloadSection: DownloadSection): Boolean {
        val success = try {
            processDownloadSection(downloadSection)
        } catch (e: Exception) {
            Logu.e("DownloadService", "下载异常: ${e.message}")
            MsgUtil.err(e)
            removeDownloadProgress(downloadSection.id)
            false
        }

        if (success) {
            batchStats.recordSuccess()
        } else {
            // 用户暂停的任务：不记为失败，状态保持"paused"，等待用户恢复
            if (isPaused(downloadSection.id)) {
                removeDownloadProgress(downloadSection.id)
                return true // 让串行/并行批次继续，不中断其他任务
            }
            batchStats.recordFailure()
            removeDownloadProgress(downloadSection.id)
            setState(downloadSection.id, "error")
        }
        return success
    }

    /** 恢复上次会话遗留的"下载中"记录，并清理残留进度 */
    private fun recoverStuckSections() {
        val all = getAll() ?: return
        for (s in all) {
            // 只重置「本进程没有线程在下载」的遗留记录：downloadProgressMap 里有条目的，
            // 说明有活跃下载线程正在写这个 section，改回 "none" 会让调度器二次拾取、
            // 双线程写同一文件（审计 S6）。同时顺手清掉它的残留进度，避免列表显示假进度。
            if (s.state == "downloading" && !DownloadProgressStore.hasProgress(s.id)) {
                setState(s.id, "none")
                removeDownloadProgress(s.id)
            }
        }
    }

    /**
     * 处理单个视频的下载流程，返回是否成功
     */
    private fun processDownloadSection(downloadSection: DownloadSection): Boolean {
        val url_video: String
        val url_danmaku: String
        val url_audio: String
        val useDash: Boolean // 是否使用DASH格式（需要合并音视频）
        try {
            val data = downloadSection.toPlayerData()

            if (downloadSection.isAudioOnly) {
                PlayerApi.getVideoDash(data)
                url_audio = if (!downloadSection.audioUrl.isNullOrEmpty())
                    downloadSection.audioUrl!!
                else
                    data.audioUrl
                url_video = ""
                useDash = false // 纯音频不需要合并
            } else {
                // 720P及以下用旧方法(MP4单文件)，1080P及以上用DASH新方法
                if (downloadSection.qn <= 64) {
                    PlayerApi.getVideo(data, true)
                    url_video = data.videoUrl
                    url_audio = ""
                    useDash = false
                } else {
                    PlayerApi.getVideoDash(data)
                    url_video = data.videoUrl
                    url_audio = data.audioUrl
                    // DASH下载需要音视频流都存在；无音轨视频（audio=null）只下视频流，不走合并
                    useDash = url_video.isNotEmpty() && url_audio.isNotEmpty()
                }
            }
            url_danmaku = data.danmakuUrl

            // 保存画质列表到元数据文件
            val downloadPath = downloadSection.getPath()
            if (downloadPath != null && downloadPath.exists()) {
                updateVideoMetaQualityLists(downloadPath, data.qnStrList, data.qnValueList)
            }
        } catch (e: JSONException) {
            setState(downloadSection.id, "error")
            notifyCompletion("下载链接获取失败：\n" + downloadSection.name_short, downloadSection.id.toInt())
            section = null
            refreshDownloadList()
            return false
        } catch (e: IOException) {
            exitCode = DownloadPathSpec.ERR_NETWORK
            setState(downloadSection.id, "none")
            return false
        }

        try {
            setState(downloadSection.id, "downloading")

            // 更新进度追踪
            setDownloadProgress(downloadSection.id, 0f, "开始下载")

            // 更新当前显示的section
            section = downloadSection
            percent = 0f
            state = "开始下载"
            refreshDownloadList()

            var file_sign: File? = null
            var result: Int

            when (downloadSection.type) {
                DownloadPathSpec.TASK_VIDEO_SINGLE -> {
                    val path_single = downloadSection.getPath()

                    file_sign = File(path_single, DownloadPathSpec.FILE_DOWNLOADING)
                    if (!file_sign.exists() && !file_sign.createNewFile()) {
                        exitCode = DownloadPathSpec.ERR_FILE
                        return false
                    }

                    if (!downloadAttachments(
                            downloadSection, url_danmaku,
                            File(path_single, DownloadPathSpec.FILE_COVER), path_single,
                            File(path_single, DownloadPathSpec.FILE_DANMAKU), useDash
                        )) {
                        return false
                    }

                    if (downloadSection.isAudioOnly) {
                        // 音频下载：先写临时文件，成功后替换，避免失败破坏旧文件
                        val audioTmp = File(path_single, DownloadPathSpec.FILE_AUDIO_TMP)
                        state = "下载音频"
                        setDownloadProgress(downloadSection.id, 0.2f, "下载音频")
                        result = downFile(url_audio, audioTmp, downloadSection.id, 0.2f)
                        if (result != DownloadPathSpec.NORMAL) {
                            exitCode = result
                            return false
                        }
                        if (!replaceTempOrFail(audioTmp, File(path_single, DownloadPathSpec.FILE_AUDIO))) return false
                    } else if (useDash) {
                        // DASH分段进度：视频(0-100%) → 音频(0-100%)；不合并，保留分离双文件直接播放
                        // 先下载到临时文件，全部成功后再替换正式文件（重新下载/切换清晰度时不破坏旧视频）
                        val videoTmp = File(path_single, DownloadPathSpec.FILE_VIDEO_TMP)
                        val audioTmp = File(path_single, DownloadPathSpec.FILE_AUDIO_TMP)
                        state = "下载视频"
                        setDownloadProgress(downloadSection.id, 0f, "下载视频")
                        result = downFile(url_video, videoTmp, downloadSection.id, 0f, 1.0f)
                        if (result != DownloadPathSpec.NORMAL) {
                            exitCode = result
                            return false
                        }
                        state = "下载音频"
                        setDownloadProgress(downloadSection.id, 0f, "下载音频")
                        result = downFile(url_audio, audioTmp, downloadSection.id, 0f, 1.0f)
                        if (result != DownloadPathSpec.NORMAL) {
                            exitCode = result
                            return false
                        }
                        if (!replaceTempOrFail(videoTmp, File(path_single, DownloadPathSpec.FILE_VIDEO))) return false
                        if (!replaceTempOrFail(audioTmp, File(path_single, DownloadPathSpec.FILE_AUDIO))) return false
                        setDownloadProgress(downloadSection.id, 1.0f, "下载完成")
                    } else {
                        // MP4格式或无音轨DASH：直接下载单个文件（音视频已合并/无音轨）
                        val videoTmp = File(path_single, DownloadPathSpec.FILE_VIDEO_TMP)
                        state = "下载视频"
                        setDownloadProgress(downloadSection.id, 0.2f, "下载视频")
                        result = downFile(url_video, videoTmp, downloadSection.id, 0.2f)
                        if (result != DownloadPathSpec.NORMAL) {
                            exitCode = result
                            return false
                        }
                        if (!replaceTempOrFail(videoTmp, File(path_single, DownloadPathSpec.FILE_VIDEO))) return false
                        // 清理旧 DASH 音频残留：从 DASH（video.mp4+audio.m4a）切换到普通 MP4 时，
                        // 旧的 audio.m4a 不会随新下载被覆盖，残留会导致播放器误判双文件、旧音频继续播放
                        val staleAudio = File(path_single, DownloadPathSpec.FILE_AUDIO)
                        if (staleAudio.exists()) staleAudio.delete()
                    }
                }
                DownloadPathSpec.TASK_VIDEO_MULTI -> {
                    val path_page = downloadSection.getPath()
                    val path_parent = path_page.parentFile

                    if (!path_page.exists() && !path_page.mkdirs()) {
                        exitCode = DownloadPathSpec.ERR_FILE
                        return false
                    }

                    file_sign = File(path_page, DownloadPathSpec.FILE_DOWNLOADING)
                    if (!file_sign.exists() && !file_sign.createNewFile()) {
                        exitCode = DownloadPathSpec.ERR_FILE
                        return false
                    }

                    if (!downloadAttachments(
                            downloadSection, url_danmaku,
                            File(path_parent, DownloadPathSpec.FILE_COVER), path_page,
                            File(path_page, DownloadPathSpec.FILE_DANMAKU), useDash
                        )) {
                        return false
                    }

                    if (downloadSection.isAudioOnly) {
                        // 音频下载：先写临时文件，成功后替换，避免失败破坏旧文件
                        val audioTmp = File(path_page, DownloadPathSpec.FILE_AUDIO_TMP)
                        state = "下载音频"
                        setDownloadProgress(downloadSection.id, 0.2f, "下载音频")
                        result = downFile(url_audio, audioTmp, downloadSection.id, 0.2f)
                        if (result != DownloadPathSpec.NORMAL) {
                            exitCode = result
                            return false
                        }
                        if (!replaceTempOrFail(audioTmp, File(path_page, DownloadPathSpec.FILE_AUDIO))) return false
                    } else if (useDash) {
                        // DASH分段进度：视频(0-100%) → 音频(0-100%)；不合并，保留分离双文件直接播放
                        // 先下载到临时文件，全部成功后再替换正式文件（重新下载/切换清晰度时不破坏旧视频）
                        val videoTmp = File(path_page, DownloadPathSpec.FILE_VIDEO_TMP)
                        val audioTmp = File(path_page, DownloadPathSpec.FILE_AUDIO_TMP)
                        state = "下载视频"
                        setDownloadProgress(downloadSection.id, 0f, "下载视频")
                        result = downFile(url_video, videoTmp, downloadSection.id, 0f, 1.0f)
                        if (result != DownloadPathSpec.NORMAL) {
                            exitCode = result
                            return false
                        }
                        state = "下载音频"
                        setDownloadProgress(downloadSection.id, 0f, "下载音频")
                        result = downFile(url_audio, audioTmp, downloadSection.id, 0f, 1.0f)
                        if (result != DownloadPathSpec.NORMAL) {
                            exitCode = result
                            return false
                        }
                        if (!replaceTempOrFail(videoTmp, File(path_page, DownloadPathSpec.FILE_VIDEO))) return false
                        if (!replaceTempOrFail(audioTmp, File(path_page, DownloadPathSpec.FILE_AUDIO))) return false
                        setDownloadProgress(downloadSection.id, 1.0f, "下载完成")
                    } else {
                        // MP4格式或无音轨DASH：直接下载单个文件（音视频已合并/无音轨）
                        val videoTmp = File(path_page, DownloadPathSpec.FILE_VIDEO_TMP)
                        state = "下载视频"
                        setDownloadProgress(downloadSection.id, 0.2f, "下载视频")
                        result = downFile(url_video, videoTmp, downloadSection.id, 0.2f)
                        if (result != DownloadPathSpec.NORMAL) {
                            exitCode = result
                            return false
                        }
                        if (!replaceTempOrFail(videoTmp, File(path_page, DownloadPathSpec.FILE_VIDEO))) return false
                        // 清理旧 DASH 音频残留：从 DASH（video.mp4+audio.m4a）切换到普通 MP4 时，
                        // 旧的 audio.m4a 不会随新下载被覆盖，残留会导致播放器误判双文件、旧音频继续播放
                        val staleAudio = File(path_page, DownloadPathSpec.FILE_AUDIO)
                        if (staleAudio.exists()) staleAudio.delete()
                    }
                }
            }

            notifyCompletion("下载成功：\n" + downloadSection.name_short, downloadSection.id.toInt())

            // 移除进度映射
            removeDownloadProgress(downloadSection.id)

            if (file_sign != null && file_sign.exists())
                file_sign.delete()

            deleteSection(downloadSection.id)
            refreshLocalList()

            return true
        } catch (e: IOException) {
            exitCode = DownloadPathSpec.ERR_FILE
            setState(downloadSection.id, "error")
            return false
        }
    }

    /**
     * 临时文件替换正式文件（备份→替换→恢复），返回是否成功。
     * 下载先写临时文件、全部成功后才替换，避免下载期间破坏旧视频；
     * 替换失败时恢复旧文件，保证重新下载（切换清晰度）不丢旧数据。
     *
     * 审计 M11-c：原来返回 void，替换失败只留一行日志，调用方照样
     * `notifyCompletion("下载成功")` 并删掉下载记录 —— 用户以为下好了，实际文件没换过去。
     */
    private fun safeReplaceTemp(tmpFile: File, finalFile: File): Boolean {
        if (!tmpFile.exists()) {
            Logu.e("DownloadService", "替换失败：临时文件不存在 ${tmpFile.name}")
            return false
        }
        val bakFile = File(finalFile.parentFile, finalFile.name + ".bak")
        var ok = false
        try {
            // 无旧文件，或旧文件成功备份到 .bak，才走正常替换
            if (!finalFile.exists() || finalFile.renameTo(bakFile)) {
                if (tmpFile.renameTo(finalFile)) {
                    if (bakFile.exists()) bakFile.delete()
                    ok = true
                } else {
                    // 替换失败：恢复旧文件，保留临时文件供排查
                    if (bakFile.exists()) bakFile.renameTo(finalFile)
                    Logu.e("DownloadService", "替换文件失败：${finalFile.name}，已保留旧文件")
                }
            } else {
                // 无法备份（罕见）：直接尝试覆盖（Android 上 rename 可覆盖目标）
                ok = tmpFile.renameTo(finalFile)
                if (ok) tmpFile.delete()
                else Logu.e("DownloadService", "备份与覆盖均失败：${finalFile.name}")
            }
        } catch (e: Exception) {
            Logu.e("DownloadService", "替换文件异常：${finalFile.name} ${e.message}")
            if (bakFile.exists() && !finalFile.exists()) {
                try { bakFile.renameTo(finalFile) } catch (_: Exception) {}
            }
        }
        return ok
    }

    /**
     * [safeReplaceTemp] 的调用方包装（审计 M11-c）。
     * 只负责置错误码并让调用方 return false —— 失败后的状态收敛
     * （recordFailure / setState "error" / exitMessage）统一由 runDownloadSection 处理，
     * 这里不要重复设置，也不要用共享的 section 字段去猜当前任务。
     */
    private fun replaceTempOrFail(tmpFile: File, finalFile: File): Boolean {
        if (safeReplaceTemp(tmpFile, finalFile)) return true
        exitCode = DownloadPathSpec.ERR_FILE
        return false
    }

    /**
     * 附件阶段（封面 + 字幕 + 弹幕）统一处理。
     * DASH 流程：进度条从 0 走满 100%（封面 0-40%，字幕 40-60%，弹幕 60-100%），
     * 后续视频/音频/合并阶段各自重置进度条再走满。
     * 其他流程（MP4 单文件/纯音频）：保持旧的比例区间（封面 0.05-0.1，字幕 0.1，弹幕 0.15）。
     * @return 是否成功；false 时调用方应直接结束下载
     */
    private fun downloadAttachments(
        downloadSection: DownloadSection,
        url_danmaku: String,
        coverFile: File,
        subtitleFolder: File,
        danmakuFile: File,
        useDash: Boolean
    ): Boolean {
        // 封面
        state = "下载封面"
        setDownloadProgress(downloadSection.id, if (useDash) 0f else 0.05f, "下载封面")
        if (!coverFile.exists() && downloadSection.url_cover.isNotEmpty()) {
            val result = if (useDash)
                downFile(downloadSection.url_cover, coverFile, downloadSection.id, 0f, 0.4f)
            else
                downFile(downloadSection.url_cover, coverFile, downloadSection.id, 0.05f, 0.1f)
            if (result != DownloadPathSpec.NORMAL) {
                exitCode = result
                return false
            }
        }

        if (!downloadSection.isAudioOnly) {
            // 字幕
            state = "下载字幕"
            setDownloadProgress(downloadSection.id, if (useDash) 0.4f else 0.1f, "下载字幕")
            downSubtitles(downloadSection.aid, downloadSection.cid, subtitleFolder)

            // 弹幕：DASH 传 0.95 使完成后进度为 1.0（附件阶段走满）
            state = "下载弹幕"
            setDownloadProgress(downloadSection.id, if (useDash) 0.6f else 0.15f, "下载弹幕")
            val result = if (useDash)
                downDanmaku(url_danmaku, danmakuFile, downloadSection.id, 0.95f)
            else
                downDanmaku(url_danmaku, danmakuFile, downloadSection.id, 0.15f)
            if (result != DownloadPathSpec.NORMAL) {
                exitCode = result
                return false
            }
        }
        return true
    }

    private fun startNotifyProgress() {
        // 幂等（审计 M11-d）：重复进入时不能无条件新建 Timer，否则旧 Timer 既没 cancel
        // 又丢掉了引用，泄漏一个线程并让它每秒继续往通知栏写。
        if (notifyTimer != null) return
        notifyTimer = Timer()
        notifyTimer!!.schedule(object : TimerTask() {
            override fun run() {
                try {
                    // 周期性采样所有并行下载的聚合速度
                    DownloadService.sampleSpeed()

                    if (section == null || notifyTimer == null)
                        return

                    val overall = DownloadService.computeOverallProgress(
                        DownloadService.getAll() ?: emptyList()
                    )
                    statusBuilder.setContentText(
                        "总进度 " + (overall * 100).toInt() + "% · " + (section?.name_short ?: "下载中")
                    )
                    statusBuilder.setProgress(100, (overall * 100).toInt(), false)
                    notifyManager.notify(FOREGROUND_ID, statusBuilder.build())
                } catch (e: Throwable) {
                    // TimerTask 抛出的未捕获异常会永久终止整个 Timer，进度通知从此静默失效且无任何提示。
                    // 这里必须吞掉异常让 Timer 继续跑，但要留下日志便于定位。
                    Logu.e("DownloadService", "刷新下载通知失败：${e.message}")
                }
            }
        }, 500, 1000)
    }

    private fun notifyExit(content: String) {
        MsgUtil.showMsg(content)
        notifyManager.cancel(FOREGROUND_ID)
        completionBuilder.setContentTitle("下载结束")
        completionBuilder.setContentText(content)
        completionBuilder.setProgress(0, 0, false)
        notifyManager.notify(2, completionBuilder.build())
    }

    private fun notifyCompletion(content: String, id: Int) {
        MsgUtil.showMsg(content)
        completionBuilder.setContentText(content)
        notifyManager.notify(id % 100 + 100, completionBuilder.build())
    }

    private fun refreshDownloadList() {
        if (DownloadListActivity.weakRef != null && DownloadListActivity.weakRef!!.get() != null) {
            DownloadListActivity.weakRef!!.get()!!.refreshList(true)
        }
    }

    private fun refreshLocalList() {
        val instance = BiliTerminal.getInstanceActivityOnTop()
        if (instance is LocalListActivity && !instance.isDestroyed)
            (instance as LocalListActivity).refresh()
    }

    private fun downSubtitles(aid: Long, cid: Long, folder: File): Int {
        try {
            val subtitleLinks = PlayerApi.getSubtitleLinks(aid, cid)
            if (subtitleLinks.size <= 1)
                return DownloadPathSpec.NORMAL

            val subtitleFolder = File(folder, DownloadPathSpec.DIR_SUBTITLES)
            // 审计 M11-e：mkdirs() / createNewFile() 在"目标已存在"时返回 false，
            // 原来用它判失败，导致目录或同名 JSON 已存在（重下、续传）时直接报 DownloadPathSpec.ERR_FILE。
            if (!subtitleFolder.exists() && !subtitleFolder.mkdirs())
                return DownloadPathSpec.ERR_FILE
            for (subtitleLink in subtitleLinks) {
                if (subtitleLink.id != -1L) {
                    val subtitleFile = File(subtitleFolder, subtitleLink.lang + ".json")
                    if (!resetFile(subtitleFile))
                        return DownloadPathSpec.ERR_FILE
                    val result = downFile(subtitleLink.url, subtitleFile)
                    if (result != DownloadPathSpec.NORMAL)
                        return result
                }
            }
        } catch (e: IOException) {
            return DownloadPathSpec.ERR_NETWORK
        } catch (e: JSONException) {
            return DownloadPathSpec.ERR_JSON
        }
        return DownloadPathSpec.NORMAL
    }

    @Throws(IOException::class)
    private fun downFile(url: String, file: File, sectionId: Long = -1, baseProgress: Float = 0f, endProgress: Float = 1.0f): Int {
        if (Aria2Util.isEnabled()) {
            isSpeedMode = true
            return downFileSpeed(url, file, sectionId, baseProgress, endProgress)
        }
        isSpeedMode = false
        return downFileNormal(url, file, sectionId, baseProgress, endProgress)
    }

    private fun resetFile(file: File): Boolean {
        return try {
            if (file.exists()) {
                file.delete() && file.createNewFile()
            } else {
                file.createNewFile()
            }
        } catch (e: IOException) {
            false
        }
    }

    @Throws(IOException::class)
    private fun downFileNormal(url: String, file: File, sectionId: Long = -1, baseProgress: Float = 0f, endProgress: Float = 1.0f): Int {
        val response: Response
        try {
            response = NetWorkUtil.get(url)
        } catch (e: IOException) {
            return DownloadPathSpec.ERR_NETWORK
        }
        // 校验响应码：防盗链失败(403)或URL过期时会返回非2xx，错误页不能当文件写入
        if (!response.isSuccessful) {
            Logu.e("DownloadService", "下载失败，HTTP ${response.code}: ${file.name}")
            response.close()
            return DownloadPathSpec.ERR_NETWORK
        }
        var inputStream: InputStream? = null
        var fileOutputStream: FileOutputStream? = null
        var result = DownloadPathSpec.NORMAL
        var fileIncomplete = false
        try {
            if (!resetFile(file))
                return DownloadPathSpec.ERR_FILE

            val body = response.body
            if (body == null) return DownloadPathSpec.ERR_NETWORK

            inputStream = body.byteStream()
            fileOutputStream = FileOutputStream(file)
            var len: Int
            val bytes = ByteArray(256 * 1024)
            val TotalFileSize = body.contentLength()
            var totalDown: Long = 0
            var lastProgressUpdate = 0L
            // 未知总大小时按 512KB 节流推进伪进度（封顶 90%），避免进度条卡死不动
            val progressUpdateInterval = if (TotalFileSize > 0) Math.max(TotalFileSize / 1000, 65536) else 512 * 1024
            var pseudoProgress = 0f
            while ((inputStream.read(bytes).also { len = it }) != -1 && started && !isPaused(sectionId)) {
                fileOutputStream.write(bytes, 0, len)
                totalDown += len
                addDownloadedBytes(len.toLong())
                if (totalDown - lastProgressUpdate >= progressUpdateInterval) {
                    lastProgressUpdate = totalDown
                    if (TotalFileSize > 0) {
                        percent = DownloadProgressMath.progressForBytes(totalDown, TotalFileSize, baseProgress, endProgress)
                    }

                    // 更新进度到进度映射表
                    if (sectionId > 0) {
                        val actualProgress = if (TotalFileSize > 0) {
                            DownloadProgressMath.progressForBytes(totalDown, TotalFileSize, baseProgress, endProgress)
                        } else {
                            pseudoProgress = DownloadProgressMath.pseudoProgressStep(pseudoProgress)
                            DownloadProgressMath.progressForPseudo(pseudoProgress, baseProgress, endProgress)
                        }
                        setDownloadProgress(sectionId, actualProgress, state ?: "下载中", totalDown, TotalFileSize)
                    }
                }
            }
            if (TotalFileSize <= 0) {
                percent = endProgress
            } else {
                percent = DownloadProgressMath.progressForBytes(totalDown, TotalFileSize, baseProgress, endProgress)
            }
            // 用户暂停：优先返回暂停信号（不视为错误，不清理半成品，恢复后重新下载覆盖）
            if (isPaused(sectionId)) {
                result = DownloadPathSpec.ERR_PAUSED
            } else if (TotalFileSize > 0 && totalDown < TotalFileSize) {
                fileIncomplete = true
                Logu.e("DownloadService", "下载不完整：${file.name} ${totalDown}/${TotalFileSize}")
                result = DownloadPathSpec.ERR_NETWORK
            } else if (!started) {
                result = DownloadPathSpec.ERR_UNKNOWN
            }
        } catch (e: IOException) {
            fileIncomplete = true
            result = DownloadPathSpec.ERR_FILE
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { fileOutputStream?.close() } catch (_: Exception) {}
            try { response.body?.close() } catch (_: Exception) {}
            response.close()
        }
        // 失败时清理半成品文件，避免残留损坏文件导致合并/播放异常
        if (result != DownloadPathSpec.NORMAL && fileIncomplete) {
            try { if (file.exists()) file.delete() } catch (_: Exception) {}
        }
        // 下载成功后把进度推进到阶段终点，保证阶段进度条走满（分段进度依赖）
        if (result == DownloadPathSpec.NORMAL && sectionId > 0) {
            setDownloadProgress(sectionId, endProgress, state ?: "下载中")
        }
        return result
    }

    @Throws(IOException::class)
    private fun downFileSpeed(url: String, file: File, sectionId: Long = -1, baseProgress: Float = 0f, endProgress: Float = 1.0f): Int {
        if (!resetFile(file))
            return DownloadPathSpec.ERR_FILE

        val client = NetWorkUtil.getOkHttpInstance()
        val headers = NetWorkUtil.webHeaders

        val headBuilder = okhttp3.Request.Builder().url(url).head()
        var i = 0
        while (i < headers.size) {
            headBuilder.addHeader(headers[i], headers[i + 1])
            i += 2
        }
        val headReq = headBuilder.build()

        val (totalSize, supportsRange) = try {
            client.newCall(headReq).execute().use { headRes ->
                val len = headRes.header("Content-Length")
                val size = if (len != null) len.toLong() else 0L
                val range = "bytes" == headRes.header("Accept-Ranges")
                Pair(size, range)
            }
        } catch (e: Exception) {
            Pair(0L, false)
        }

        return if (totalSize <= 0 || !supportsRange || totalSize <= 2 * 1024 * 1024) {
            downFileSpeedSingle(url, file, client, headers, totalSize, sectionId, baseProgress, endProgress)
        } else {
            downFileSpeedSeg(url, file, client, headers, totalSize, sectionId, baseProgress, endProgress)
        }
    }

    @Throws(IOException::class)
    private fun downFileSpeedSingle(url: String, file: File, client: okhttp3.OkHttpClient,
                                     headers: ArrayList<String>, totalSize: Long,
                                     sectionId: Long = -1, baseProgress: Float = 0f, endProgress: Float = 1.0f): Int {
        val reqBuilder = okhttp3.Request.Builder().url(url).get()
        var i = 0
        while (i < headers.size) {
            reqBuilder.addHeader(headers[i], headers[i + 1])
            i += 2
        }
        val request = reqBuilder.build()

        var effectiveTotalSize = totalSize
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return DownloadPathSpec.ERR_NETWORK
            val body = response.body
            if (body == null) return DownloadPathSpec.ERR_NETWORK
            if (effectiveTotalSize <= 0) effectiveTotalSize = body.contentLength()
            if (effectiveTotalSize <= 0) effectiveTotalSize = 1
            body.byteStream().use { inputStream ->
                FileOutputStream(file).use { fos ->
                    val buffer = ByteArray(65536)
                    var downloaded: Long = 0
                    var read: Int
                    var lastProgressUpdate = 0L
                    // 未知大小时按 512KB 节流推进伪进度（封顶 90%），避免进度条瞬间满或卡死
                    val progressUpdateInterval = if (effectiveTotalSize > 1) Math.max(effectiveTotalSize / 1000, 65536) else 512 * 1024
                    var pseudoProgress = 0f
                    while ((inputStream.read(buffer).also { read = it }) != -1 && started && !isPaused(sectionId)) {
                        fos.write(buffer, 0, read)
                        downloaded += read
                        addDownloadedBytes(read.toLong())
                        if (downloaded - lastProgressUpdate >= progressUpdateInterval) {
                            lastProgressUpdate = downloaded
                            val actualProgress = if (effectiveTotalSize > 1) {
                                DownloadProgressMath.progressForBytes(downloaded, effectiveTotalSize, baseProgress, endProgress)
                            } else {
                                pseudoProgress = DownloadProgressMath.pseudoProgressStep(pseudoProgress)
                                DownloadProgressMath.progressForPseudo(pseudoProgress, baseProgress, endProgress)
                            }
                            percent = actualProgress

                            // 更新进度到进度映射表
                            if (sectionId > 0) {
                                setDownloadProgress(sectionId, actualProgress, state ?: "下载中", downloaded, effectiveTotalSize)
                            }
                        }
                    }
                    percent = DownloadProgressMath.progressForBytes(downloaded, effectiveTotalSize, baseProgress, endProgress)
                }
            }
        }
        val singleResult = if (isPaused(sectionId)) DownloadPathSpec.ERR_PAUSED
            else if (started) DownloadPathSpec.NORMAL
            else DownloadPathSpec.ERR_UNKNOWN
        // 下载成功后把进度推进到阶段终点，保证阶段进度条走满（分段进度依赖）
        if (singleResult == DownloadPathSpec.NORMAL && sectionId > 0) {
            setDownloadProgress(sectionId, endProgress, state ?: "下载中")
        }
        return singleResult
    }

    @Throws(IOException::class)
    private fun downFileSpeedSeg(url: String, file: File, client: okhttp3.OkHttpClient,
                                  headers: ArrayList<String>, totalSize: Long,
                                  sectionId: Long = -1, baseProgress: Float = 0f, endProgress: Float = 1.0f): Int {
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(totalSize)
        }

        // 动态分片：约每 2MB 一片，上限受用户配置的分片数约束
        val segments = DownloadProgressMath.segmentCount(totalSize, Aria2Util.getSplit())
        val segmentLen = DownloadProgressMath.segmentLen(totalSize, segments)

        val totalDownloaded = java.util.concurrent.atomic.AtomicLong(0)
        val anyFailed = java.util.concurrent.atomic.AtomicBoolean(false)

        val threads = java.util.ArrayList<Thread>(segments)

        for (i in 0 until segments) {
            val segmentRange = DownloadProgressMath.segmentRange(i, segments, totalSize, segmentLen)
            val start = segmentRange.first
            val end = segmentRange.last
            val idx = i

            val thread = Thread({
                downloadSegment(url, file, client, headers, start, end, idx, sectionId, totalDownloaded, anyFailed)
            }, "DL-Segment-$idx")
            threads.add(thread)
            thread.start()
        }

        // 轮询进度，直到全部分片结束、失败、暂停或取消
        while (completedSegments(threads) < segments && started && !anyFailed.get() && !isPaused(sectionId)) {
            percent = DownloadProgressMath.progressForBytes(totalDownloaded.get(), totalSize, baseProgress, endProgress)

            // 更新进度到进度映射表
            if (sectionId > 0) {
                val actualProgress = DownloadProgressMath.progressForBytes(totalDownloaded.get(), totalSize, baseProgress, endProgress)
                setDownloadProgress(sectionId, actualProgress, state ?: "下载中", totalDownloaded.get(), totalSize)
            }

            try {
                Thread.sleep(200)
            } catch (ignored: InterruptedException) {
            }
        }

        // 等待线程结束（带超时），避免遗留写盘。
        // 超时是"整批分片"的总预算而不是每个线程各 30s（审计 M11-g）：
        // 原来逐个 t.join(30000)，32 个分片最坏会阻塞 16 分钟。
        val joinDeadline = System.currentTimeMillis() + 30_000L
        for (t in threads) {
            val remain = joinDeadline - System.currentTimeMillis()
            if (remain <= 0) break
            try {
                t.join(remain)
            } catch (ignored: InterruptedException) {
            }
        }

        // 用户暂停：直接返回暂停信号，不回退重下
        if (isPaused(sectionId)) return DownloadPathSpec.ERR_PAUSED

        // 完整性校验：任一失败或下载字节不足都视为失败，回退整文件单线程重下
        if (anyFailed.get() || totalDownloaded.get() < totalSize) {
            // 回退前必须把分片阶段已经计入的字节从全局计数里扣掉（审计 S7）：
            // downFileSpeedSingle 会用 FileOutputStream 截断重写整个文件，并再次
            // addDownloadedBytes()，不回滚就让 totalBytesDownloaded 把同一批字节算两遍，
            // sampleSpeed() 得出的速度与批次统计虚高。
            val segBytes = totalDownloaded.get()
            if (segBytes > 0) addDownloadedBytes(-segBytes)
            // 进度同步退回阶段起点：单线程重下会从 0 重新推进 percent，
            // 若保留分片阶段的高位进度，进度条会先满后退。
            if (sectionId > 0) {
                setDownloadProgress(sectionId, baseProgress, state ?: "下载中", 0L, totalSize)
            }
            return downFileSpeedSingle(url, file, client, headers, totalSize, sectionId, baseProgress, endProgress)
        }

        val segResult = if (started) DownloadPathSpec.NORMAL else DownloadPathSpec.ERR_UNKNOWN
        // 下载成功后把进度推进到阶段终点，保证阶段进度条走满（分段进度依赖）
        if (segResult == DownloadPathSpec.NORMAL && sectionId > 0) {
            setDownloadProgress(sectionId, endProgress, state ?: "下载中")
        }
        return segResult
    }

    /** 下载单个分片（最多重试 3 次），成功后累加已下载字节。 */
    private fun downloadSegment(url: String, file: File, client: okhttp3.OkHttpClient,
                                headers: ArrayList<String>, start: Long, end: Long, idx: Int,
                                sectionId: Long,
                                totalDownloaded: java.util.concurrent.atomic.AtomicLong,
                                anyFailed: java.util.concurrent.atomic.AtomicBoolean) {
        val expectLen = end - start + 1
        var attempts = 0
        while (attempts < 3 && started && !anyFailed.get() && !isPaused(sectionId)) {
            var written = 0L
            var ok = false
            try {
                val reqBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$start-$end")
                    .get()
                var j = 0
                while (j < headers.size) {
                    reqBuilder.addHeader(headers[j], headers[j + 1])
                    j += 2
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful || resp.body == null) throw IOException("HTTP ${resp.code}")
                    val buffer = ByteArray(65536)
                    var read: Int
                    resp.body!!.byteStream().use { inputStream ->
                        java.io.RandomAccessFile(file, "rw").use { rafInner ->
                            rafInner.seek(start)
                            while ((inputStream.read(buffer).also { read = it }) != -1 && started && !isPaused(sectionId)) {
                                rafInner.write(buffer, 0, read)
                                written += read
                                addDownloadedBytes(read.toLong())
                            }
                        }
                    }
                }
                ok = started && written == expectLen
            } catch (e: Exception) {
                ok = false
            }
            if (ok) {
                totalDownloaded.addAndGet(written)
                return
            }
            attempts++
            if (attempts < 3) {
                try {
                    Thread.sleep(500L * attempts)
                } catch (ignored: InterruptedException) {
                }
            }
        }
        // 暂停直接返回，不标记失败（避免整体回退重下）
        if (isPaused(sectionId)) return
        anyFailed.set(true)
    }

    /** 已结束的分片线程数（用于进度轮询退出条件）。 */
    private fun completedSegments(threads: java.util.ArrayList<Thread>): Int {
        var n = 0
        for (t in threads) if (!t.isAlive) n++
        return n
    }

    @Throws(IOException::class)
    private fun downDanmaku(danmaku: String, danmakuFile: File, sectionId: Long = -1, baseProgress: Float = 0f): Int {
        val response: Response
        try {
            response = NetWorkUtil.get(danmaku)
        } catch (e: IOException) {
            return DownloadPathSpec.ERR_NETWORK
        }
        var bufferedSink: BufferedSink? = null
        try {
            // 审计 M11-f：原实现不校验 HTTP 状态码，404/412 的错误页会被当成弹幕正文写进
            // danmaku.xml，播放时表现为"弹幕全空"却又是成功状态。
            if (!response.isSuccessful)
                return DownloadPathSpec.ERR_NETWORK
            if (!resetFile(danmakuFile))
                return DownloadPathSpec.ERR_FILE

            val sink: Sink = danmakuFile.sink()
            val decompressBytes = NetWorkUtil.decompress(response.body!!.bytes())
            bufferedSink = sink.buffer()
            bufferedSink.write(decompressBytes)
            bufferedSink.close()
            
            // 更新进度
            if (sectionId > 0) {
                setDownloadProgress(sectionId, baseProgress + 0.05f, "下载弹幕")
            }
        } catch (e: IOException) {
            return DownloadPathSpec.ERR_FILE
        } finally {
            bufferedSink?.close()
            response.body?.close()
            response.close()
        }
        return DownloadPathSpec.NORMAL
    }

    override fun onDestroy() {
        Logu.d("结束")

        started = false
        batchRunning = false
        percent = -1f
        state = null
        speedStr = ""
        getDownloadProgressMap().let { it.clear() }
        batchStats.reset()
        resetDownloadedBytes()
        activeDownloadsCount = 0

        toastTimer?.cancel()
        toastTimer = null

        notifyTimer?.cancel()
        notifyTimer = null

        if (exitMessage == null)
            exitMessage = "下载服务已退出"

        Logu.d("退出下载服务")
        if (section != null) {
            val id = section!!.id
            val folder = section!!.getPath()
            section = null

            CenterThreadPool.run {
                notifyExit(exitMessage!!)
                if (exitCode != DownloadPathSpec.NORMAL) {
                    setState(id, "none")
                    // 注意：这里绝不能删整个任务目录。单 P 任务的目录（FileUtil.getVideoDownloadPath(title, null)）
                    // 就是 <下载根>/<标题> 本身，递归删除会把上一次成功下载好的视频/音频/封面/弹幕一起清掉，
                    // 属于真实数据丢失。批次非正常结束（服务被回收、用户停止、中途出错）时只清理本次下载的
                    // 临时文件与 .DOWNLOADING 标记，成品一律保留。
                    FileUtil.cleanDownloadTempFiles(folder)
                }
                refreshDownloadList()
            }
        }

        super.onDestroy()
    }
}