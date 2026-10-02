package com.RobinNotBad.BiliClient.activity.message

import android.os.Bundle
import android.util.Pair

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.message.NoticeAdapter
import com.RobinNotBad.BiliClient.api.MessageApi
import com.RobinNotBad.BiliClient.model.MessageCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil

class NoticeActivity : RefreshListActivity() {
    private var messageList: MutableList<MessageCard> = mutableListOf()
    private var noticeAdapter: NoticeAdapter? = null
    private var cursor: MessageCard.Cursor? = null
    private var pageType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setPageName("详情")

        // 跳转方未传 type 时必须直接结束：否则 when 全不匹配、cursor 保持 null，
        // 翻页时 cursor!! 必然 NPE（此前闪退的一个来源）。
        val type = intent.getStringExtra("type")
        if (type == null) {
            finish()
            return
        }
        pageType = type
        messageList = ArrayList()

        setOnLoadMoreListener { i -> continueLoading(i) }
        loadFirstPage()
    }

    /**
     * 首屏加载。
     *
     * 关键点：无论成功还是失败，都必须调用 setRefreshing(false) 复位刷新状态。
     * 基类 RefreshListActivity 的翻页入口守卫含 swipeRefreshLayout.isRefreshing，
     * 而该标志只有 setRefreshing(false) 会清掉 —— 首屏失败时若不复位，
     * 列表会永久卡死（转圈不停、之后任何下滑都不再触发加载）。
     */
    private fun loadFirstPage() {
        CenterThreadPool.run {
            try {
                var pair: Pair<MessageCard.Cursor, List<MessageCard>>?
                when (pageType) {
                    "like" -> {
                        pair = MessageApi.getLikeMsg(0, 0)
                        cursor = pair!!.first
                        messageList = pair.second as MutableList<MessageCard>
                    }
                    "reply" -> {
                        pair = MessageApi.getReplyMsg(0, 0)
                        cursor = pair!!.first
                        messageList = pair.second as MutableList<MessageCard>
                    }
                    "at" -> {
                        pair = MessageApi.getAtMsg(0, 0)
                        cursor = pair!!.first
                        messageList = pair.second as MutableList<MessageCard>
                    }
                    "system" -> {
                        // 系统通知接口没有游标：一次性列表，标记已到底，避免 continueLoading 里 cursor!! 崩溃
                        messageList = MessageApi.getSystemMsg() as MutableList<MessageCard>
                        cursor = null
                        bottom = true
                    }
                }

                val list = messageList
                runOnUiThread {
                    // Activity 已销毁时不再构造/绑定 adapter，避免泄漏与 WrongThread 异常
                    if (isDestroyed) return@runOnUiThread
                    noticeAdapter = NoticeAdapter(this, list)
                    setAdapter(noticeAdapter!!)
                    setRefreshing(false)
                }
            } catch (e: Exception) {
                MsgUtil.err(e)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    // 复位刷新状态并结束翻页，否则列表永久卡在"刷新中"、再也刷不出来
                    bottom = true
                    setRefreshing(false)
                }
            }
        }
    }

    private fun continueLoading(i: Int) {
        CenterThreadPool.run {
            try {
                val cur = cursor
                // 系统通知无游标、首屏失败也已置 bottom=true：直接复位返回，
                // 否则走到下面的 cursor!!（旧代码）必然 NPE
                if ("system" == pageType || cur == null) {
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        bottom = true
                        setRefreshing(false)
                    }
                    return@run
                }

                val lastSize = messageList.size
                var pair: Pair<MessageCard.Cursor, List<MessageCard>>?
                when (pageType) {
                    "like" -> {
                        pair = MessageApi.getLikeMsg(cur.id, cur.time)
                        cursor = pair!!.first
                        messageList.addAll(pair.second)
                    }
                    "reply" -> {
                        pair = MessageApi.getReplyMsg(cur.id, cur.time)
                        cursor = pair!!.first
                        messageList.addAll(pair.second)
                    }
                    "at" -> {
                        pair = MessageApi.getAtMsg(cur.id, cur.time)
                        cursor = pair!!.first
                        messageList.addAll(pair.second)
                    }
                    else -> {
                        // 未知 type（理论上不会发生）：不要继续翻页，直接收尾
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            bottom = true
                            setRefreshing(false)
                        }
                        return@run
                    }
                }

                // 列表变更与状态读取统一收敛到主线程，避免后台线程改 UI 数据；
                // bottom 是基类的普通字段（非 @Volatile），从后台线程写它有可见性风险
                val newCursor = cursor
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    noticeAdapter?.notifyItemRangeInserted(lastSize, messageList.size - lastSize)
                    bottom = newCursor?.is_end ?: true
                    setRefreshing(false)
                }
            } catch (e: Exception) {
                MsgUtil.err(e)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    // 失败时必须结束翻页并复位刷新；此前 catch 只 page-- 且不置 bottom，
                    // 用户每次滑到底都会重复请求、重复失败
                    page--
                    bottom = true
                    setRefreshing(false)
                }
            }
        }
    }
}
