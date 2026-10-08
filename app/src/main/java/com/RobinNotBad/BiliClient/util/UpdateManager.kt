package com.RobinNotBad.BiliClient.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.model.UpdateConfig
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

object UpdateManager {

    private const val APK_FILE_NAME = "bili_terminal_update.apk"
    private const val TAG = "更新检查"

    @Volatile
    private var downloadCanceled = false

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Volatile
    private var cachedConfig: UpdateConfig? = null

    data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long, val progress: Float)

    fun getCurrentVersion(): Int {
        return try {
            BiliTerminal.context?.let {
                it.packageManager.getPackageInfo(it.packageName, 0).versionCode ?: 0
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun getCachedConfig(): UpdateConfig? = cachedConfig

    fun checkUpdate(onResult: (UpdateConfig) -> Unit, onError: (String) -> Unit) {
        CenterThreadPool.run {
            try {
                val config = doFetchUpdateConfig()
                CenterThreadPool.runOnUiThread { onResult(config) }
            } catch (e: IOException) {
                CenterThreadPool.runOnUiThread { onError("网络错误：${e.message}") }
            } catch (e: Exception) {
                CenterThreadPool.runOnUiThread { onError("解析错误：${e.message}") }
            }
        }
    }

    fun hasUpdate(config: UpdateConfig): Boolean {
        val hasUpdate = config.versionCode > getCurrentVersion()
        if (!hasUpdate) {
            deleteOldApkFile()
        }
        return hasUpdate
    }

    private fun getApkFile(context: Context? = BiliTerminal.context): File? {
        return context?.let {
            val apkDir = File(it.externalCacheDir ?: it.cacheDir, "update")
            File(apkDir, APK_FILE_NAME)
        }
    }

    fun deleteOldApkFile() {
        try {
            val apkFile = getApkFile()
            if (apkFile?.exists() == true) {
                apkFile.delete()
            }
        } catch (e: Exception) {
        }
    }

    /**
     * 更新检查的来源，按顺序尝试，**前一个失败才用后一个**：
     * 1. Gitee 发行版（首发渠道，国内直连快）；
     * 2. GitHub 发行版（兜底）。
     *
     * 两者都是公开仓库，读 release **不需要 token**。
     * 注意：这里不再读 123pan 上单独部署的 config.json —— 版本信息以发行版本身为准，
     * 少一处要人工同步的远端文件（历史上它就漂移过）。
     */
    private val releaseSources = listOf(
        "Gitee" to "https://gitee.com/api/v5/repos/zisekongling/bili-terminal-re/releases/latest",
        "GitHub" to "https://api.github.com/repos/PurpleEthereality/BiliTerminal-RE/releases/latest"
    )

    private fun doFetchUpdateConfig(): UpdateConfig {
        val errors = ArrayList<String>()
        for ((name, url) in releaseSources) {
            try {
                return fetchRelease(url)
            } catch (e: Exception) {
                Logu.e(TAG, "从 $name 取发行版信息失败：${e.message}")
                errors.add("$name：${e.message}")
            }
        }
        throw IOException("所有更新源都失败（${errors.joinToString("；")}）")
    }

    private fun fetchRelease(url: String): UpdateConfig {
        val request = Request.Builder().url(url).get().build()
        // use{} 保证失败分支抛异常时连接也归还（OkHttp 只在 body 读到 EOF 时才自动归还）
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("服务器响应错误: ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("响应体为空")
            parseRelease(body)
        }
    }

    /**
     * 解析发行版 JSON（Gitee / GitHub 字段形状一致）→ [UpdateConfig]。
     *
     * 版本号优先取 CI 写进 Release 说明的机器可读元数据，缺失时按 tag 推算；
     * 下载直链按设备 ABI 从附件里挑（Gitee 还会自动带 `{tag}.zip` 源码归档，必须按精确文件名匹配）。
     */
    private fun parseRelease(jsonStr: String): UpdateConfig {
        val json = JSONObject(jsonStr)
        val tag = json.optString("tag_name", "")
        val body = json.optString("body", "")
        val meta = UpdateRelease.parseMeta(body, tag)

        if (meta.versionCode <= 0) {
            // 宁可报错也不要把 0 当成版本号：否则客户端会「永远收不到更新」且毫无提示
            throw IOException("无法确定发行版版本号（tag=「$tag」，且 Release 说明里没有元数据）")
        }

        val assetsJson = json.optJSONArray("assets")
        val assets = ArrayList<UpdateRelease.ReleaseAsset>()
        if (assetsJson != null) {
            for (i in 0 until assetsJson.length()) {
                val a = assetsJson.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                val downloadUrl = a.optString("browser_download_url", "")
                if (name.isNotEmpty() && downloadUrl.isNotEmpty()) {
                    assets.add(UpdateRelease.ReleaseAsset(name, downloadUrl))
                }
            }
        }
        val downloadUrl = UpdateRelease.pickApkUrl(assets, Build.SUPPORTED_ABIS.toList())
            ?: throw IOException("发行版里找不到可用的 APK 附件（共 ${assets.size} 个附件）")

        val config = UpdateConfig(
            meta.versionCode,
            meta.versionName,
            UpdateRelease.stripMeta(body),
            downloadUrl,
            meta.forceUpdate
        )
        cachedConfig = config
        return config
    }

    fun downloadApk(
        url: String,
        context: Context,
        onProgress: (DownloadProgress) -> Unit,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        downloadCanceled = false
        CenterThreadPool.run {
            try {
                val apkDir = File(context.externalCacheDir ?: context.cacheDir, "update")
                if (!apkDir.exists()) apkDir.mkdirs()

                val existingFile = File(apkDir, APK_FILE_NAME)
                var downloadedBytes = if (existingFile.exists()) existingFile.length() else 0L

                val requestBuilder = Request.Builder().url(url).get()
                if (downloadedBytes > 0) {
                    requestBuilder.addHeader("Range", "bytes=$downloadedBytes-")
                }

                var response = okHttpClient.newCall(requestBuilder.build()).execute()

                if (!response.isSuccessful && response.code != 206) {
                    // 请求失败：丢弃残片从头重试
                    response.close()
                    downloadedBytes = 0L
                    existingFile.delete()
                    val retryRequest = Request.Builder().url(url).get().build()
                    response = okHttpClient.newCall(retryRequest).execute()
                    if (!response.isSuccessful) {
                        // 重试仍失败：body 不会有人读，必须显式关闭，否则连接不归还
                        response.close()
                        CenterThreadPool.runOnUiThread { onError("下载失败: ${response.code}") }
                        return@run
                    }
                } else if (downloadedBytes > 0 && response.code != 206) {
                    // 服务器忽略 Range 头返回 200 全量：必须丢弃旧残片从头写，
                    // 否则全量内容会被追加到残片之后，产出永久损坏的 APK
                    existingFile.delete()
                }

                val result = writeResponseToFile(response, existingFile, onProgress)

                // 26.10.04 批次 4（E6）：交给系统安装器之前先自查包名与签名。
                // 不通过就删掉文件（继续留残片会被下次的 Range 续传当成"已下载一部分"，永远修不好），
                // 并把中文原因交给调用方展示。
                val verifyResult = ApkVerifier.verify(context, result)
                if (!verifyResult.ok) {
                    result.delete()
                    CenterThreadPool.runOnUiThread { onError("安装包校验失败：${verifyResult.message}") }
                    return@run
                }

                CenterThreadPool.runOnUiThread { onComplete(result) }
            } catch (e: CancellationException) {
            } catch (e: IOException) {
                CenterThreadPool.runOnUiThread { onError("下载失败：${e.message}") }
            } catch (e: Exception) {
                CenterThreadPool.runOnUiThread { onError("下载出错：${e.message}") }
            }
        }
    }

    @Throws(IOException::class, CancellationException::class)
    private fun writeResponseToFile(
        response: okhttp3.Response,
        file: File,
        onProgress: (DownloadProgress) -> Unit
    ): File {
        val body = response.body ?: throw IOException("响应体为空")
        val remainingBytes = body.contentLength()
        val inputStream: InputStream = body.byteStream()
        val append = file.exists()
        val outputStream = FileOutputStream(file, append)

        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalRead = if (append) file.length() else 0L
        
        val totalSize = if (remainingBytes > 0) {
            if (append) remainingBytes + totalRead else remainingBytes
        } else {
            0L
        }
        
        var lastReportTime = System.currentTimeMillis()

        try {
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (downloadCanceled) {
                    throw CancellationException("下载已取消")
                }
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastReportTime >= 200) {
                    lastReportTime = now
                    val progress = if (totalSize > 0) totalRead.toFloat() / totalSize.toFloat() else 0f
                    CenterThreadPool.runOnUiThread {
                        onProgress(DownloadProgress(totalRead, totalSize, progress.coerceIn(0f, 1f)))
                    }
                }
            }
            outputStream.flush()
        } finally {
            inputStream.close()
            outputStream.close()
            response.close()
        }

        CenterThreadPool.runOnUiThread {
            onProgress(DownloadProgress(totalRead, totalRead, 1f))
        }
        return file
    }

    fun cancelDownload() {
        downloadCanceled = true
    }

    fun installApk(context: Context, apkFile: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }

        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val resInfoList = context.packageManager.queryIntentActivities(intent, 0)
            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                context.grantUriPermission(packageName, uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }

        context.startActivity(intent)
    }
}