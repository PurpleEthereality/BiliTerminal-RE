package com.RobinNotBad.BiliClient.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.RobinNotBad.BiliClient.BuildConfig
import com.RobinNotBad.BiliClient.api.TerminalApi
import com.RobinNotBad.BiliClient.model.ApiResult
import org.json.JSONObject

/**
 * 崩溃报告的采集与上传。
 *
 * <p>分工：[com.RobinNotBad.BiliClient.ErrorCatch] 在**崩溃瞬间**（主进程，随时会死）
 * 只做最便宜的事——把异常类名、消息、线程名和页面轨迹塞进 Intent；
 * 真正的采集与上传放在崩溃页所在的 `:error_activity` 进程里做（见
 * [com.RobinNotBad.BiliClient.activity.CatchActivity]）。那边内存、文件、网络都是干净的，
 * 不会把「崩溃页都打不开」变成第二种崩溃。
 *
 * <p>上传是**尽力而为**：任何异常都吞掉并返回失败 [ApiResult]，绝不能让崩溃页再崩一次。
 */
object CrashReporter {

    /** 从 Intent extra 里带过来的那些「崩溃瞬间才拿得到」的字段。 */
    @JvmStatic
    fun buildPayload(
        context: Context?,
        exception: String?,
        message: String?,
        thread: String?,
        stack: String?,
        trail: String?,
        uptimeSec: Long,
        extra: String? = null
    ): JSONObject {
        val manager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        var ramTotal = 0L
        var ramAvail = 0L
        try {
            if (manager != null) {
                val info = ActivityManager.MemoryInfo()
                manager.getMemoryInfo(info)
                ramTotal = info.totalMem
                ramAvail = info.availMem
            }
        } catch (ignored: Exception) {
            // 采集内存失败不影响报告主体
        }

        val mid = if (SharedPreferencesUtil.getBoolean(SettingsKeys.FEEDBACK_ATTACH_MID, false))
            SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) else 0L

        return TerminalApi.buildCrashPayload(
            /* installId = */ TerminalApi.getInstallId(),
            /* mid = */ mid,
            /* exception = */ exception,
            /* message = */ message,
            /* thread = */ thread,
            /* stack = */ stack,
            /* log = */ trail,
            /* extra = */ extra ?: "",
            /* versionCode = */ BuildConfig.VERSION_CODE,
            /* versionName = */ BuildConfig.VERSION_NAME,
            /* sdk = */ Build.VERSION.SDK_INT,
            /* release = */ Build.VERSION.RELEASE,
            /* brand = */ Build.BRAND,
            /* device = */ Build.DEVICE,
            /* product = */ Build.PRODUCT,
            /* model = */ Build.MODEL,
            /* abi = */ if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "",
            /* ramTotal = */ ramTotal,
            /* ramAvail = */ ramAvail,
            /* uptimeSec = */ uptimeSec
        )
    }

    /** 采集 + 上传。返回成功时 `code` 就是给用户看的报错编号。 */
    @JvmStatic
    fun upload(
        context: Context?,
        exception: String?,
        message: String?,
        thread: String?,
        stack: String?,
        trail: String?,
        uptimeSec: Long
    ): ApiResult {
        return try {
            val payload = buildPayload(context, exception, message, thread, stack, trail, uptimeSec)
            TerminalApi.uploadCrash(payload)
        } catch (e: Exception) {
            ApiResult(-4, "崩溃报告生成失败")
        }
    }

    /** 进程已运行时长（秒）。放在这里以便崩溃页与反馈页共用同一套取值逻辑。 */
    @JvmStatic
    fun uptimeSeconds(): Long = SystemClock.elapsedRealtime() / 1000
}
