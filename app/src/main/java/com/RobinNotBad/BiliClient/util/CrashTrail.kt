package com.RobinNotBad.BiliClient.util

import java.util.ArrayDeque

/**
 * 崩溃前「用户走过哪些页面」的环形缓冲。
 *
 * <p>为什么需要它：崩溃报告里最有用的一条信息是「崩溃前用户在干什么」，
 * 而 Android 10 起应用已经**读不到 logcat**（`READ_LOGS` 是系统权限），
 * 所以拿不到系统日志，只能自己记。这里只记页面名 + 时间，一个字符串，
 * 崩溃时从内存里直接读，不碰磁盘、不碰网络。
 *
 * <p>只在主进程被写入（[com.RobinNotBad.BiliClient.BiliTerminal] 的 Activity 生命周期回调里），
 * 崩溃页所在的 `:error_activity` 进程拿不到这份内存——所以
 * [com.RobinNotBad.BiliClient.ErrorCatch] 会在崩溃瞬间把它塞进 Intent 的 extra 里带过去。
 *
 * <p>容量固定为 [MAX]，用 [ArrayDeque] 而不是 ArrayList：崩溃本身就是异常路径，
 * 这里的一切都要是常数时间、零分配（超容量时删头节点，不做数组搬移）。
 */
object CrashTrail {

    /** 轨迹最多保留多少条。20 条足够覆盖「用户从列表点到详情再点到播放器」这种深度。 */
    private const val MAX = 20

    private val deque = ArrayDeque<String>()

    /** 记一次页面进入。线程安全：只可能被主线程调用，但加锁的代价可以忽略，换来不必担心调用方。 */
    @JvmStatic
    fun record(name: String) {
        val line = TimeUtil.format(System.currentTimeMillis(), "HH:mm:ss") + " " + name
        synchronized(deque) {
            if (deque.size >= MAX) deque.removeFirst()
            deque.addLast(line)
        }
    }

    /** 崩溃时快照：多行文本，每行形如 `21:03:11 VideoInfoActivity`。 */
    @JvmStatic
    fun snapshot(): String = synchronized(deque) { deque.joinToString("\n") }

    @JvmStatic
    fun clear() = synchronized(deque) { deque.clear() }
}
