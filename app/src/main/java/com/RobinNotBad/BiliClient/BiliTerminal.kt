package com.RobinNotBad.BiliClient

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.activity.settings.UpdateActivity
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity
import com.RobinNotBad.BiliClient.api.DynamicApi
import com.RobinNotBad.BiliClient.api.MessageApi
import com.RobinNotBad.BiliClient.tutorial.TutorialStore
import com.RobinNotBad.BiliClient.util.BangumiUpdateChecker
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.CrashTrail
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgNotifier
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.PerformanceManager
import com.RobinNotBad.BiliClient.util.SettingsKeys
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TerminalContext
import com.RobinNotBad.BiliClient.util.UpdateManager
import me.ele.uetool.UETool
import org.json.JSONException
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * 应用入口（AndroidManifest.xml 的 `android:name=".BiliTerminal"` 指向本类）。
 *
 * 全局 Context 取 [context]，它是**静态字段**而非 getter——这样 Java 侧依旧是
 * `BiliTerminal.context` 的字段读法（零方法调用、零入口判空），Kotlin 侧按需 `!!`。
 */
class BiliTerminal : Application() {

    companion object {

        @SuppressLint("StaticFieldLeak")
        @JvmField
        var context: Context? = null

        @JvmField
        var DPI_FORCE_CHANGE = false

        private var instance: WeakReference<InstanceActivity> = WeakReference(null)

        @Volatile
        private var forceUpdateBlocking = false

        @Volatile
        private var forceUpdateVersionCode = 0

        @Volatile
        private var forceUpdateVersionName: String? = null

        @Volatile
        private var forceUpdateDescription: String? = null

        @Volatile
        private var forceUpdateDownloadUrl: String? = null

        @JvmStatic
        fun clearForceUpdate() {
            forceUpdateBlocking = false
            forceUpdateVersionCode = 0
            forceUpdateVersionName = null
            forceUpdateDescription = null
            forceUpdateDownloadUrl = null
            SharedPreferencesUtil.removeValue("force_update_required")
            SharedPreferencesUtil.removeValue("force_update_version_code")
            SharedPreferencesUtil.removeValue("force_update_version_name")
            SharedPreferencesUtil.removeValue("force_update_description")
            SharedPreferencesUtil.removeValue("force_update_download_url")
        }

        @JvmStatic
        fun setInstance(instanceActivity: InstanceActivity) {
            instance = WeakReference(instanceActivity)
        }

        @JvmStatic
        fun getInstanceActivityOnTop(): InstanceActivity? = instance.get()

        /**
         * 重写attachBaseContext方法，用于调整应用内dpi
         * 尝试下这种风格代码是否会导致低版本设备异常
         *
         * 参数/返回保持可空：旧 Java 版无 `@Nullable`/`@NonNull` 注解，是平台类型，
         * 而 `SplashActivity.attachBaseContext` 把入参声明成了 `Context?`。写成非空会编译失败。
         *
         * @param old The origin context.
         */
        @JvmStatic
        fun getFitDisplayContext(old: Context?): Context? {
            val dpiTimes = SharedPreferencesUtil.getFloat("dpi", 1.0F)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return old
            if (!DPI_FORCE_CHANGE && dpiTimes == 1.0F) return old
            return try {
                val ctx = old!!
                val displayMetrics = ctx.resources.displayMetrics
                val configuration = ctx.resources.configuration
                configuration.densityDpi = (displayMetrics.densityDpi * dpiTimes).toInt()
                ctx.createConfigurationContext(configuration)
            } catch (e: Exception) {
                //MsgUtil.err(e,old);
                old
            }
        }

        @JvmStatic
        @Throws(PackageManager.NameNotFoundException::class)
        @Suppress("DEPRECATION")
        fun getVersion(): Int =
            context!!.packageManager.getPackageInfo(context!!.packageName, 0).versionCode

        /**
         * 是否 Debug 构建。
         * 用 BuildConfig.DEBUG（编译期常量）而不是比较 BUILD_TYPE 字符串：前者能被 R8
         * 常量折叠，从而把 debug-only 分支整体 strip；后者是运行期判断，永远消除不掉（审计 M12-d）。
         */
        @JvmStatic
        fun isDebugBuild(): Boolean = BuildConfig.DEBUG

        /**
         * UETool 悬浮窗请求码（入口 Activity onActivityResult 使用）。
         *
         * 原先这几个 UETool 辅助方法挂在 [BiliTerminalApp] 上——那是个从未被实例化的
         * Application 死类（Manifest 的 Application 一直是 [BiliTerminal]），
         * 只有 SplashActivity 借它的 companion 当工具类用（审计 M13-e）。
         * 现收拢到真正的 Application 类里，死类已删除。
         */
        @JvmStatic
        val REQUEST_OVERLAY_PERMISSION_FOR_UETOOL = 10086

        /**
         * 检查是否拥有系统悬浮窗绘制权限（兼容 Android M 以下）。
         * @param context 任意可用 Context（通常传 Activity）
         */
        @JvmStatic
        fun canDrawOverlaysCompat(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
        }

        /** 跳转到系统设置页申请悬浮窗权限。 */
        @JvmStatic
        fun requestOverlayPermission(activity: Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !canDrawOverlaysCompat(activity)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION_FOR_UETOOL)
            }
        }

        /**
         * 显示 UETool 调试悬浮窗（仅 Debug 构建有真实实现；Release 依赖 uetool-no-op 空实现）。
         */
        @JvmStatic
        fun showUEToolMenu() {
            if (isDebugBuild()) {
                try {
                    UETool.showUETMenu()
                } catch (e: Exception) {
                    // 兜底：防止 WindowManager / Context 异常导致应用崩溃
                    e.printStackTrace()
                }
            }
        }

        @JvmStatic
        fun jumpToVideo(context: Context, aid: Long) {
            TerminalContext.getInstance().enterVideoDetailPage(context, aid)
        }

        @JvmStatic
        fun jumpToVideo(context: Context, bvid: String) {
            TerminalContext.getInstance().enterVideoDetailPage(context, bvid)
        }

        @JvmStatic
        fun jumpToArticle(context: Context, cvid: Long) {
            TerminalContext.getInstance().enterArticleDetailPage(context, cvid)
        }

        @JvmStatic
        fun jumpToUser(context: Context, mid: Long) {
            val intent = Intent()
            intent.setClass(context, UserInfoActivity::class.java)
            intent.putExtra("mid", mid)
            context.startActivity(intent)
        }
    }

    /**
     * 崩溃页 `CatchActivity` 所在进程的后缀（见 AndroidManifest 里它的 `android:process`）
     */
    private val errorProcessSuffix = ":error_activity"

    /**
     * 取当前进程名。
     *
     * 26.10.04 批次 3（B8）：minSdk 24 用不了 API 28 才有的 `Application.getProcessName()`，
     * 只能读 `/proc/self/cmdline`——内核把进程名按 NUL 结尾写进去，`readLine` 会把那个 NUL 一起带回来。
     */
    private fun currentProcessName(): String = try {
        java.io.RandomAccessFile("/proc/self/cmdline", "r").use { file ->
            file.readLine()?.trimEnd('\u0000') ?: packageName
        }
    } catch (e: Exception) {
        packageName
    }

    /**
     * 日志开关（两个进程都要设：崩溃页自己也会打日志）
     */
    private fun applyLogSwitches() {
        val debugBuild = isDebugBuild()
        Logu.LOGV_ENABLED = SharedPreferencesUtil.getBoolean("dev_logv", debugBuild)
        Logu.LOGD_ENABLED = SharedPreferencesUtil.getBoolean("dev_logd", debugBuild)
        Logu.LOGI_ENABLED = SharedPreferencesUtil.getBoolean("dev_logi", debugBuild)
    }

    override fun onCreate() {
        super.onCreate()

        // 26.10.04 批次 3（B8）：崩溃页跑在独立进程 :error_activity 里，这里只做**最小初始化**——
        // 够让崩溃页把堆栈显示出来即可。其余（教程键迁移、性能检测、强制更新、未读轮询、
        // 自动更新检查、全局异常捕获）全部不碰：主进程刚崩溃、随时会被 killProcess，
        // 错误进程里再跑网络与磁盘逻辑，只会把"崩溃页都打不开"变成第二种崩溃。
        if (currentProcessName().endsWith(errorProcessSuffix)) {
            if (context == null) {
                SharedPreferencesUtil.sharedPreferences = getSharedPreferences("default", MODE_PRIVATE)
                context = getFitDisplayContext(this)
                applyLogSwitches()
            }
            return
        }

        if (context == null) {
            SharedPreferencesUtil.sharedPreferences = getSharedPreferences("default", MODE_PRIVATE)
            context = getFitDisplayContext(this)

            // 教程系统重构：一次性把旧键迁移到新 id（旧系统拿数组下标当 tag 且错位，详见 docs/tutorial-system-redesign.md）
            TutorialStore.migrateLegacyKeys()

            // 初始化性能管理器 - 设备检测与自适应优化
            PerformanceManager.init(this)

            forceUpdateBlocking = SharedPreferencesUtil.getBoolean("force_update_required", false)
            if (forceUpdateBlocking) {
                forceUpdateVersionCode = SharedPreferencesUtil.getInt("force_update_version_code", 0)
                forceUpdateVersionName = SharedPreferencesUtil.getString("force_update_version_name", null)
                forceUpdateDescription = SharedPreferencesUtil.getString("force_update_description", null)
                forceUpdateDownloadUrl = SharedPreferencesUtil.getString("force_update_download_url", null)
                // 已更新到强制要求的版本（安装完成重启后），解除拦截
                try {
                    if (forceUpdateVersionCode > 0 && getVersion() >= forceUpdateVersionCode) {
                        clearForceUpdate()
                    }
                } catch (ignored: PackageManager.NameNotFoundException) {
                }
            }

            registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

                override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (forceUpdateBlocking && activity !is UpdateActivity) {
                        val intent = Intent(activity, UpdateActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        intent.putExtra("has_config", true)
                        intent.putExtra("version_code", forceUpdateVersionCode)
                        intent.putExtra("version_name", forceUpdateVersionName)
                        intent.putExtra("description", forceUpdateDescription)
                        intent.putExtra("download_url", forceUpdateDownloadUrl)
                        intent.putExtra("force_update", true)
                        activity.startActivity(intent)
                        activity.finish()
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {
                    // 26.10.09（自建崩溃报告）：崩溃报告里最有用的一条是「崩溃前用户在干什么」，
                    // 而 Android 10 起应用读不到 logcat（READ_LOGS 是系统权限），只能自己记页面轨迹
                    // （见 CrashTrail）。记在 resumed 而不是 created：从返回栈重入某个页面时
                    // created 不会重放，记在 created 会让轨迹断掉。
                    CrashTrail.record(activity.javaClass.simpleName)
                }

                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })

            val errorCatch = ErrorCatch.getInstance()
            errorCatch.init(context)

            applyLogSwitches()

            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.DYNAMIC_UPDATE_CHECK_ENABLE, true)
                && SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) != 0L
            ) {
                CenterThreadPool.run {
                    try {
                        val updateBaseline = SharedPreferencesUtil.getLong("dynamic_update_baseline", 0)
                        val updateNum = DynamicApi.checkDynamicUpdate("all", updateBaseline)
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, updateNum)
                    } catch (e: IOException) {
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, 0)
                    } catch (e: JSONException) {
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, 0)
                    }
                }
            }

            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.MESSAGE_UPDATE_CHECK_ENABLE, true)
                && SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) != 0L
            ) {
                CenterThreadPool.run {
                    try {
                        // 先记下上次的未读数：只有"变多了"才弹通知（见 MsgNotifier.shouldNotify）
                        val previousUnread = SharedPreferencesUtil.getInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, 0)
                        val messageUnread = MessageApi.checkMessageUnread()
                        val privateMsgUnread = MessageApi.checkPrivateMsgUnread()
                        val totalUnread = messageUnread + privateMsgUnread
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, totalUnread)

                        val notifyEnabled = SharedPreferencesUtil.getBoolean(SettingsKeys.PRIVATE_MSG_NOTIFY_ENABLE, true)
                        if (MsgNotifier.shouldNotify(previousUnread, totalUnread, notifyEnabled)) {
                            context?.let { MsgNotifier.notifyNewMessages(it, privateMsgUnread, messageUnread) }
                        }
                    } catch (e: IOException) {
                        // 检查失败不再把未读数清零：清零会让下一次成功检查把"老未读"当成新增未读，
                        // 网络抖一次就重复弹通知。保留上次已知值，既不误报也不丢提示。
                        Logu.w("BiliTerminal", "未读检查失败: ${e.message}")
                    } catch (e: JSONException) {
                        Logu.w("BiliTerminal", "未读检查失败: ${e.message}")
                    }
                }
            }

            // C16：追番更新提醒。只在这里检查（用户拍板不做后台定时），失败保持旧快照不清空。
            if (SharedPreferencesUtil.getBoolean(SettingsKeys.BANGUMI_UPDATE_NOTIFY_ENABLE, true)
                && SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) != 0L
            ) {
                CenterThreadPool.run {
                    try {
                        context?.let { BangumiUpdateChecker.checkAndNotify(it) }
                    } catch (e: IOException) {
                        Logu.w("BiliTerminal", "追番更新检查失败: ${e.message}")
                    } catch (e: JSONException) {
                        Logu.w("BiliTerminal", "追番更新检查失败: ${e.message}")
                    }
                }
            }

            checkAppUpdate()
        }
    }

    private fun checkAppUpdate() {
        if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.AUTO_UPDATE_CHECK_ENABLE, true)) {
            return
        }
        // 强制更新生效中：不重复检查更新，避免网络返回异常或配置变更时误解除拦截
        if (forceUpdateBlocking) {
            return
        }
        CenterThreadPool.run {
            try {
                UpdateManager.checkUpdate(
                    onResult = { config ->
                        if (UpdateManager.hasUpdate(config)) {
                            if (config.isForceUpdate) {
                                forceUpdateVersionCode = config.versionCode
                                forceUpdateVersionName = config.versionName
                                forceUpdateDescription = config.description
                                forceUpdateDownloadUrl = config.downloadUrl
                                forceUpdateBlocking = true
                                SharedPreferencesUtil.putBoolean("force_update_required", true)
                                SharedPreferencesUtil.putInt("force_update_version_code", config.versionCode)
                                SharedPreferencesUtil.putString("force_update_version_name", config.versionName)
                                SharedPreferencesUtil.putString("force_update_description", config.description)
                                SharedPreferencesUtil.putString("force_update_download_url", config.downloadUrl)
                                CenterThreadPool.runOnUiThread {
                                    val intent = Intent(context, UpdateActivity::class.java)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    intent.putExtra("has_config", true)
                                    intent.putExtra("version_code", config.versionCode)
                                    intent.putExtra("version_name", config.versionName)
                                    intent.putExtra("description", config.description)
                                    intent.putExtra("download_url", config.downloadUrl)
                                    intent.putExtra("force_update", true)
                                    context!!.startActivity(intent)
                                }
                            } else {
                                clearForceUpdate()
                                val lastNewVersion = SharedPreferencesUtil.getInt("update_last_new_version", 0)
                                if (config.versionCode != lastNewVersion) {
                                    CenterThreadPool.runOnUiThread {
                                        MsgUtil.showMsg("发现新版本 " + config.versionName)
                                    }
                                }
                            }
                        } else {
                            clearForceUpdate()
                        }
                    },
                    onError = { }
                )
            } catch (e: Exception) {
            }
        }
    }
}
