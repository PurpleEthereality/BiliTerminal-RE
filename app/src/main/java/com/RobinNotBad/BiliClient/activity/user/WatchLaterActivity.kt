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

class WatchLaterActivity : RefreshListActivity() {

    /** 接口返回的完整列表（不受筛选影响），删除时两张表要同步改 */
    private val allList = ArrayList<VideoCard>()

    /** 当前展示给 adapter 的列表（= allList 或它的「未看完」子集），adapter 持有同一个引用 */
    private val shownList = ArrayList<VideoCard>()

    private var adapter: VideoCardAdapter? = null
    private var showUnfinishedOnly = false

    private lateinit var filterAll: TextView
    private lateinit var filterUnfinished: TextView
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
        filterAll = findViewById(R.id.filterAll)
        filterUnfinished = findViewById(R.id.filterUnfinished)
        unselectedColor = filterAll.currentTextColor
        filterAll.setOnClickListener {
            if (showUnfinishedOnly) {
                showUnfinishedOnly = false
                applyFilter()
            }
        }
        filterUnfinished.setOnClickListener {
            if (!showUnfinishedOnly) {
                showUnfinishedOnly = true
                applyFilter()
            }
        }
        updateFilterColors()

        loadWatchLater()
    }

    private fun loadWatchLater() {
        CenterThreadPool.run {
            try {
                val list = WatchLaterApi.getWatchLaterList()
                runOnUiThread {
                    allList.clear()
                    allList.addAll(list)
                    if (adapter == null) {
                        adapter = VideoCardAdapter(this, shownList).also { it.setOnLongClickListener(::onItemLongClick) }
                        setAdapter(adapter!!)
                    }
                    applyFilter(sortWithMode = true)
                    setRefreshing(false)
                }
            } catch (e: Exception) {
                loadFail(e)
            }
        }
    }

    /**
     * 用当前筛选档位重建展示列表。
     *
     * @param sortWithMode 为 true 时保留「未看完」筛选档位（刷新后仍生效）；为 false 时同上，
     *                     参数仅用于区分调用来源，避免误把档位重置。
     */
    private fun applyFilter(sortWithMode: Boolean = false) {
        shownList.clear()
        shownList.addAll(WatchLaterApi.filterUnfinished(allList, showUnfinishedOnly))
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
