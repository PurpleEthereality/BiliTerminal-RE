package com.RobinNotBad.BiliClient.activity.search

import android.os.Bundle
import android.view.View

import androidx.annotation.NonNull

import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter
import com.RobinNotBad.BiliClient.api.SearchApi
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool

import org.json.JSONArray
import org.json.JSONObject

// 搜索结果-番剧页
class SearchBangumiFragment : SearchFragment() {
    private var bangumiCardList = ArrayList<VideoCard>()
    private var bangumiCardAdapter: VideoCardAdapter? = null

    companion object {
        fun newInstance(): SearchBangumiFragment {
            return SearchBangumiFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onViewCreated(@NonNull view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bangumiCardList = ArrayList()
        bangumiCardAdapter = VideoCardAdapter(requireContext(), bangumiCardList)
        setAdapter(bangumiCardAdapter!!)

        setOnRefreshListener { refreshInternal() }
        setOnLoadMoreListener { page -> continueLoading(page) }
    }

    private fun continueLoading(page: Int) {
        CenterThreadPool.run {
            try {
                // 番剧走独立的 search_type=media_bangumi，data.result 就是一个番剧条目数组
                val result = SearchApi.searchType(keyword, page, "media_bangumi")
                // 同一个接口不同 search_type 的 result 形态并不统一（本项目 SearchLiveFragment 里
                // live 就是 {"live_room":[...]}），所以不用 as 强转 —— 强转失败会抛
                // ClassCastException 把整页打成"加载失败"；这里逐个 is 兜住，拿不到数组就当到底了
                val resultArray: JSONArray? = when (result) {
                    is JSONArray -> result
                    is JSONObject -> result.optJSONArray("media_bangumi")
                    else -> null
                }
                if (resultArray != null) {
                    if (page == 1) showEmptyView(false)
                    val list = ArrayList<VideoCard>()
                    SearchApi.getBangumiFromSearchResult(resultArray, list)
                    if (list.size == 0) bottom = true
                    else CenterThreadPool.runOnUiThread {
                        val lastSize = bangumiCardList.size
                        bangumiCardList.addAll(list)
                        bangumiCardAdapter!!.notifyItemRangeInserted(lastSize, bangumiCardList.size - lastSize)
                    }
                } else bottom = true
            } catch (e: Exception) {
                loadFail(e)
            }
            // 无论成功失败都必须复位下拉刷新，否则刷新圈会一直转（见 fork-fix-worklog P18）
            setRefreshing(false)
        }
    }

    override fun refreshInternal() {
        CenterThreadPool.runOnUiThread {
            page = 1
            if (this.bangumiCardAdapter == null)
                this.bangumiCardAdapter = VideoCardAdapter(this.requireContext(), this.bangumiCardList)
            val size_old = this.bangumiCardList.size
            this.bangumiCardList.clear()
            if (size_old != 0) this.bangumiCardAdapter!!.notifyItemRangeRemoved(0, size_old)
            CenterThreadPool.run { continueLoading(page) }
        }
    }
}
