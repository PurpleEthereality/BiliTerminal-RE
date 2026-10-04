package com.RobinNotBad.BiliClient.activity.video

import android.annotation.SuppressLint
import android.os.Bundle
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.RefreshMainActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter
import com.RobinNotBad.BiliClient.api.RecommendApi
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool

//推荐页面
//2023-07-13

class RecommendActivity : RefreshMainActivity() {

    private var videoCardList: MutableList<VideoCard>? = null
    private var videoCardAdapter: VideoCardAdapter? = null
    private var firstRefresh = true
    private var freshType = 3
    private val loadedBvids = mutableSetOf<String>()

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setMenuClick()

        setOnRefreshListener { refreshRecommend() }
        setOnLoadMoreListener { addRecommend() }
        // 空数据时可点击重试，而不是只能退出重进
        setOnEmptyRetry { refreshRecommend() }

        setPageName("推荐")

        recyclerView.setHasFixedSize(true)

        // 教程（推荐页引导 + 主题说明）改由 BaseActivity 按 Tutorials 注册表集中触发

        refreshRecommend()
    }

    // 26.10.04 批次 3（B5）：本页早已改用 notifyItemRangeRemoved/Inserted，
    // 原来的 @SuppressLint("NotifyDataSetChanged") 与两处 Log.e("debug") 都是残留，已清掉。
    private fun refreshRecommend() {
        freshType = 3
        loadedBvids.clear()
        if (firstRefresh) {
            videoCardList = ArrayList()
        } else {
            val last = videoCardList!!.size
            videoCardList!!.clear()
            videoCardAdapter!!.notifyItemRangeRemoved(0, last)
        }

        addRecommend()
    }

    private fun addRecommend() {
        val requestFreshType = freshType
        freshType = if (freshType == 3) 4 else 3
        CenterThreadPool.run {
            try {
                val list = ArrayList<VideoCard>()
                RecommendApi.getRecommend(list, requestFreshType)
                setRefreshing(false)

                runOnUiThread {
                    val newItems = list.filter { loadedBvids.add(it.bvid) }
                    if (newItems.isEmpty()) {
                        setRefreshing(false)
                        // 首屏就拉不到内容时给出空态，否则用户只看到"转圈消失 + 一片空白"
                        if (videoCardList!!.isEmpty()) showEmptyView()
                        return@runOnUiThread
                    }
                    hideEmptyView()
                    videoCardList!!.addAll(newItems)
                    if (firstRefresh) {
                        firstRefresh = false
                        videoCardAdapter = VideoCardAdapter(this, videoCardList!!)
                        setAdapter(videoCardAdapter!!)
                    } else {
                        videoCardAdapter!!.notifyItemRangeInserted(videoCardList!!.size - newItems.size, newItems.size)
                    }
                }
            } catch (e: Exception) {
                loadFail(e)
            }
        }
    }
}