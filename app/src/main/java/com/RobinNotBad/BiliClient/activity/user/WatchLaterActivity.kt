package com.RobinNotBad.BiliClient.activity.user

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter
import com.RobinNotBad.BiliClient.api.WatchLaterApi
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.ui.appearance.ColorScheme
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.TerminalDialog

class WatchLaterActivity : RefreshListActivity() {

    /** 接口返回的完整列表（不受筛选影响），删除时两张表要同步改 */
    private val allList = ArrayList<VideoCard>()

    /** 当前展示给 adapter 的列表（= allList 或它的「未看完」子集），adapter 持有同一个引用 */
    private val shownList = ArrayList<VideoCard>()

    private var adapter: VideoCardAdapter? = null
    private var showUnfinishedOnly = false

    private lateinit var filterAll: TextView
    private lateinit var filterUnfinished: TextView
    private var clearWatchedEntry: TextView? = null
    private var unselectedColor = 0

    private var longClickPosition = -1
    private var longClickTimestamp: Long = 0

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setPageName("稍后再看")
        recyclerView.setHasFixedSize(true)

        // 筛选条：默认隐藏的分组，只有本页把它亮出来
        findViewById<View>(R.id.filterBar).visibility = View.VISIBLE
        // 列表一滚动就把这条收回去（手表屏小，收起来多露出一行视频）
        setupAutoHideBars(findViewById(R.id.filterBar))
        filterAll = findViewById(R.id.filterAll)
        filterUnfinished = findViewById(R.id.filterUnfinished)
        unselectedColor = filterAll.currentTextColor
        filterAll.setOnClickListener {
            if (showUnfinishedOnly) {
                showUnfinishedOnly = false
                reloadForFilter()
            }
        }
        filterUnfinished.setOnClickListener {
            if (!showUnfinishedOnly) {
                showUnfinishedOnly = true
                reloadForFilter()
            }
        }
        updateFilterColors()

        clearWatchedEntry = findViewById(R.id.clearWatched)
        clearWatchedEntry?.visibility = View.VISIBLE
        clearWatchedEntry?.setOnClickListener { confirmClearWatched() }

        loadWatchLater()
    }

    /**
     * 切档位要**重新请求**：筛选是服务端 viewed 参数，不是本地过滤，本地那张表里
     * 只有当前档位的数据（PiliPlus 同样每档一个 controller 各自请求）。
     */
    private fun reloadForFilter() {
        applyFilter()          // 先更新高亮与「加载中」的空列表观感
        // 切档位后列表从第一页重来，用户已经滚不回「上一屏」了，工具条必须回到位，
        // 否则会一直收着，只能靠再滚一下才出来
        expandAutoHideBars()
        setRefreshing(true)
        loadWatchLater()
    }

    private fun loadWatchLater() {
        CenterThreadPool.run {
            try {
                val viewed = if (showUnfinishedOnly) {
                    WatchLaterApi.VIEWED_UNFINISHED
                } else {
                    WatchLaterApi.VIEWED_ALL
                }
                val list = WatchLaterApi.getWatchLaterList(viewed)
                runOnUiThread {
                    // 切档位是并发请求，回来时档位可能已变，丢弃过期响应
                    if (viewed != currentViewed()) return@runOnUiThread
                    allList.clear()
                    allList.addAll(list)
                    if (adapter == null) {
                        adapter = VideoCardAdapter(this, shownList).also { it.setOnLongClickListener(::onItemLongClick) }
                        setAdapter(adapter!!)
                    }
                    applyFilter()
                    setRefreshing(false)
                }
            } catch (e: Exception) {
                loadFail(e)
            }
        }
    }

    private fun currentViewed(): Int =
        if (showUnfinishedOnly) WatchLaterApi.VIEWED_UNFINISHED else WatchLaterApi.VIEWED_ALL

    /**
     * 「清除所有已看完」——破坏性且不可撤销，必须先过二次确认，确认按钮走危险色。
     */
    private fun confirmClearWatched() {
        TerminalDialog.confirm(
            context = this,
            title = "清除所有已看完",
            message = "将把所有看完了的稿件从稍后再看里移除，此操作不可撤销。确定继续吗？",
            confirmText = "清除"
        ) {
            clearWatched()
        }.show()
    }

    private fun clearWatched() {
        CenterThreadPool.run {
            try {
                val code = WatchLaterApi.clearWatched()
                runOnUiThread {
                    if (code == 0) {
                        MsgUtil.showMsg("已清除所有已看完")
                        loadWatchLater()
                    } else {
                        MsgUtil.showMsg("清除失败，错误码：$code")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { MsgUtil.err(e) }
            }
        }
    }

    /**
     * 用当前筛选档位重建展示列表。
     *
     * 「未看完」是**服务端**过滤（viewed=2），真实数据由 [loadWatchLater] 拉取，
     * 这里只做一次本地兜底过滤：服务端若忽略了 viewed 参数、或返回了脏数据，
     * 也不会把「已看完」的混进「未看完」档位。
     */
    private fun applyFilter() {
        shownList.clear()
        shownList.addAll(
            WatchLaterApi.filterUnfinished(allList, showUnfinishedOnly)
        )
        updateFilterColors()
        adapter?.notifyDataSetChanged()
        if (shownList.isEmpty()) showEmptyView() else hideEmptyView()
    }

    private fun updateFilterColors() {
        filterAll.setTextColor(if (showUnfinishedOnly) unselectedColor else ColorScheme.PRIMARY)
        filterUnfinished.setTextColor(if (showUnfinishedOnly) ColorScheme.PRIMARY else unselectedColor)
    }

    /** 连点两次长按删除（沿用原有交互，C18 只加筛选，不改删除手势） */
    private fun onItemLongClick(position: Int) {
        val timestamp = System.currentTimeMillis()
        if (longClickPosition == position && timestamp - longClickTimestamp < 4000) {
            if (position < 0 || position >= shownList.size) return
            val card = shownList[position]
            CenterThreadPool.run {
                try {
                    val result = WatchLaterApi.delete(card.aid)
                    longClickPosition = -1
                    if (result == 0) runOnUiThread {
                        MsgUtil.showMsg("删除成功")
                        shownList.removeAt(position)
                        allList.remove(card)
                        adapter?.notifyItemRemoved(position)
                        adapter?.notifyItemRangeChanged(position, shownList.size - position)
                        if (shownList.isEmpty()) showEmptyView()
                    } else {
                        runOnUiThread { MsgUtil.showMsg("删除失败，错误码：" + result) }
                    }
                } catch (e: Exception) {
                    report(e)
                }
            }
        } else {
            longClickPosition = position
            longClickTimestamp = timestamp
            MsgUtil.showMsg("再次长按删除")
        }
    }
}
