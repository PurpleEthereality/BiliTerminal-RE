package com.RobinNotBad.BiliClient.activity.video.series

import android.os.Bundle
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.video.SeriesCardAdapter
import com.RobinNotBad.BiliClient.api.SeriesApi
import com.RobinNotBad.BiliClient.model.Series
import com.RobinNotBad.BiliClient.util.CenterThreadPool

class UserSeriesActivity : RefreshListActivity() {

    private var mid: Long = 0

    // 26.10.04 批次 3（B5）：翻页要往首屏那个列表里追加，所以得留住 adapter 引用。
    private var seasonAdapter: SeriesCardAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mid = intent.getLongExtra("mid", 0)

        setPageName("投稿的系列")

        loadData(1)
        setOnRefreshListener { loadData(1) }
        setOnLoadMoreListener {
            loadData(it)
        }
    }

    private fun loadData(page: Int) {
        CenterThreadPool.run {
            try {
                val seasonList = ArrayList<Series>()
                val result = SeriesApi.getUserSeries(mid, page, seasonList)

                if (page == 1) {
                    if (seasonList.isEmpty()) {
                        runOnUiThread {
                            showEmptyView()
                            setRefreshing(false)
                        }
                        return@run
                    }

                    runOnUiThread {
                        val adapter = SeriesCardAdapter(this@UserSeriesActivity, seasonList)
                        seasonAdapter = adapter
                        setAdapter(adapter)
                        setRefreshing(false)
                        hideEmptyView()
                    }
                } else {
                    runOnUiThread {
                        // 26.10.04 批次 3（B5）修正：原实现只 notifyItemRangeInserted，
                        // 从没把新数据加进 adapter 的列表 —— 报出的新增数和 getItemCount() 对不上，
                        // RecyclerView 会判为不一致（"Inconsistency detected"）直接崩。
                        val adapter = seasonAdapter
                        if (adapter != null) {
                            val oldSize = adapter.seasonList.size
                            adapter.seasonList.addAll(seasonList)
                            adapter.notifyItemRangeInserted(oldSize, seasonList.size)
                        }
                        onLoadComplete()
                        setRefreshing(false)
                    }

                    if (result != 0) {
                        bottom = true
                    }
                }
            } catch (e: Exception) {
                if (page == 1) {
                    runOnUiThread {
                        showEmptyView()
                        setRefreshing(false)
                    }
                } else {
                    loadFail(e)
                }
            }
        }
    }
}