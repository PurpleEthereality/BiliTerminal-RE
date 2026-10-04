package com.RobinNotBad.BiliClient.service.download

import com.RobinNotBad.BiliClient.model.DownloadSection
import com.RobinNotBad.BiliClient.service.DownloadService
import com.RobinNotBad.BiliClient.service.SpeedSampler
import com.RobinNotBad.BiliClient.service.formatDownloadSpeed

/**
 * 单个下载任务的进度快照（key 为 section.id）。
 *
 * 原本是 `DownloadService.Companion` 里的嵌套 data class，随进度映射一起搬到本包。
 * 之所以放在 `object` **外面**做顶层类：`DownloadProgressStore` 是 internal，
 * 而 `DownloadService.getDownloadProgress()` 是对外公开（@JvmStatic）的接口，
 * 公开成员不能暴露 internal 类型，所以类型本身必须是 public。
 */
data class DownloadProgressInfo(
    val progress: Float,
    val state: String,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0
)

/**
 * 下载进度、已下载字节、速度采样与暂停标志的持有者（E2 拆分 DownloadService 第 2 步）。
 *
 * 这些都是**与 Service 实例无关的进程级状态**：进度映射要跨 Activity 读、暂停标志要跨线程看、
 * 速度采样要跨批次复用，所以放在 object 里由 [DownloadService.Companion] 用 @JvmStatic 门面转发，
 * 对外契约（Java 侧仍写 `DownloadService.getDownloadProgress(...)`）一个字都不变。
 *
 * 刻意保留的现状（不要"顺手修"）：
 * - [speedStr] / [isSpeedMode] 不是 volatile。UI 线程直接读、下载线程直接写，这是既有数据竞争，
 *   加 volatile 属于行为变更（会改变可见性语义），不归本次重构管。
 * - [speedLock] 与 [DownloadService.Companion] 的监视器是两把独立的锁：速度采样全程只认
 *   [speedLock]，`DownloadService.start()` 的 @Synchronized 认 Companion 监视器，两者不得互换、合并。
 * - [totalBytesDownloaded] 允许被减（`addDownloadedBytes(-segBytes)` 回滚失败分片），不能用
 *   "只增"的假设去改写。
 */
internal object DownloadProgressStore {

    /** 速度显示字符串（非 volatile，现状保留） */
    var speedStr: String = ""

    /** 是否处于"高速下载"模式，仅用于通知与列表页加前缀（非 volatile，现状保留） */
    var isSpeedMode: Boolean = false

    // 全局已下载字节数（所有并行下载累计），用于聚合速度采样
    private val totalBytesDownloaded = java.util.concurrent.atomic.AtomicLong(0)

    // 聚合速度采样（单线程调用）
    private val speedSampler = SpeedSampler()
    private val speedLock = Any()

    // 下载进度追踪：key=section.id, value=进度信息（含阶段进度与已下载/总字节数）
    private val downloadProgressMap =
        java.util.concurrent.ConcurrentHashMap<Long, DownloadProgressInfo>()

    // 被用户暂停的任务：key=section.id。暂停后该任务的下载循环会尽快退出，状态置为 paused，
    // 调度器（只取 state="none"）不会重新拾取；恢复时清除标志并置回 none。
    val pausedMap = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    fun isPaused(id: Long): Boolean = pausedMap.containsKey(id)

    /**
     * 暂停单个下载任务（不影响其他并行任务）。
     * 正在下载的线程会在下一轮 IO 循环中检测到暂停标志并退出。
     */
    fun pauseDownload(id: Long) {
        pausedMap[id] = true
        DownloadRepository.setState(id, "paused")
    }

    /**
     * 恢复被暂停的下载任务，并触发调度器重新拾取。
     */
    fun resumeDownload(id: Long) {
        pausedMap.remove(id)
        DownloadRepository.setState(id, "none")
        DownloadService.start(id)
    }

    fun getDownloadProgress(id: Long): DownloadProgressInfo? {
        return downloadProgressMap[id]
    }

    fun getDownloadProgressMap(): java.util.concurrent.ConcurrentHashMap<Long, DownloadProgressInfo> {
        return downloadProgressMap
    }

    fun setDownloadProgress(id: Long, progress: Float, state: String) {
        downloadProgressMap[id] = DownloadProgressInfo(progress, state)
    }

    fun setDownloadProgress(id: Long, progress: Float, state: String,
                            downloadedBytes: Long, totalBytes: Long) {
        downloadProgressMap[id] =
            DownloadProgressInfo(progress, state, downloadedBytes, totalBytes)
    }

    fun removeDownloadProgress(id: Long) {
        downloadProgressMap.remove(id)
    }

    /** 该任务是否还有下载中的进度记录（调度器判"遗留记录"用，等同于原来直接 containsKey） */
    fun hasProgress(id: Long): Boolean = downloadProgressMap.containsKey(id)

    fun getDownloadedBytes(): Long = totalBytesDownloaded.get()

    fun addDownloadedBytes(bytes: Long) {
        totalBytesDownloaded.addAndGet(bytes)
    }

    fun resetDownloadedBytes() {
        totalBytesDownloaded.set(0)
    }

    /**
     * 根据当前数据库中的下载项与进度映射，计算本次批次的总体进度。
     */
    fun computeOverallProgress(sections: List<DownloadSection>): Float {
        var activeProgressSum = 0f
        var activeCount = 0
        var waitingCount = 0
        for (s in sections) {
            val info = downloadProgressMap[s.id]
            if (info != null) {
                activeProgressSum += info.progress.coerceIn(0f, 1f)
                activeCount++
            } else if (s.state == "none") {
                waitingCount++
            }
        }
        return DownloadService.batchStats.overallProgress(activeProgressSum, activeCount, waitingCount)
    }

    /** 当前仍在下载中的项目剩余字节数合计（用于预估剩余时间） */
    fun getActiveRemainingBytes(sections: List<DownloadSection>): Long {
        var remaining = 0L
        for (s in sections) {
            val info = downloadProgressMap[s.id]
            if (info != null && info.totalBytes > 0) {
                remaining += (info.totalBytes - info.downloadedBytes).coerceAtLeast(0)
            }
        }
        return remaining
    }

    /** 在批次开始时重置速度采样，避免把上一批次的字节计入 */
    fun resetSpeedSampling() {
        synchronized(speedLock) {
            speedSampler.reset(System.currentTimeMillis())
            speedStr = ""
        }
    }

    /** 周期性采样全局已下载字节数，得到所有并行下载的聚合速度 */
    fun sampleSpeed() {
        synchronized(speedLock) {
            val speed = speedSampler.sample(getDownloadedBytes(), System.currentTimeMillis())
            if (speed != null) {
                speedStr = formatDownloadSpeed(speed)
            }
        }
    }
}
