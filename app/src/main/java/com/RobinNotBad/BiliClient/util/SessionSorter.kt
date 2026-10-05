package com.RobinNotBad.BiliClient.util

import com.RobinNotBad.BiliClient.model.PrivateMsgSession
import java.util.Collections

/**
 * 私信会话列表的本地排序。
 *
 * 为什么需要它：服务端 `get_sessions` 返回的顺序本身就是「置顶在前、其余按时间倒序」，
 * 但客户端原先又按「有未读的排前面」做了一次排序，结果是**置顶但没有未读的会话被
 * 挤到未读会话后面**，用户看到的现象就是「点了置顶、提示已置顶、[置顶] 前缀也出来了，
 * 但会话反而排到下面去了」。
 *
 * 现在的规则（优先级从高到低）：
 * 1. 置顶的会话排在最前（`topTs > 0`，服务端置顶时间戳，微秒级）；
 * 2. 都是置顶时，置顶时间越晚越靠前；
 * 3. 都未置顶时，有未读的排前面（保留用户想先看到未读的诉求）；
 * 4. 其余情况维持服务端返回的相对顺序。
 *
 * 排序是**稳定**的（[Collections.sort] 保证），所以第 4 条天然成立：
 * 服务端已经按时间倒序给好了，我们不再重新按时间排，避免把服务端的顺序打乱。
 */
object SessionSorter {

    /**
     * 原地排序会话列表。
     *
     * @param sessions 待排序的列表，会被原地修改
     */
    @JvmStatic
    fun sort(sessions: MutableList<PrivateMsgSession>) {
        Collections.sort(sessions) { o1, o2 -> compare(o1, o2) }
    }

    /**
     * 比较两个会话的展示优先级（纯函数，便于 JVM 单测）。
     *
     * @return 负数表示 [o1] 应排在 [o2] 前面
     */
    @JvmStatic
    fun compare(o1: PrivateMsgSession, o2: PrivateMsgSession): Int {
        // 1. 置顶优先
        val o1Top = o1.isTop
        val o2Top = o2.isTop
        if (o1Top != o2Top) return if (o1Top) -1 else 1

        // 2. 都置顶：置顶时间晚的靠前
        if (o1Top && o1.topTs != o2.topTs) {
            return if (o1.topTs > o2.topTs) -1 else 1
        }

        // 3. 都未置顶：有未读的靠前
        val o1Unread = o1.unread > 0
        val o2Unread = o2.unread > 0
        if (o1Unread != o2Unread) return if (o1Unread) -1 else 1

        // 4. 其余维持服务端原序（稳定排序不需要返回值表达「相等」以外的语义）
        return 0
    }
}
