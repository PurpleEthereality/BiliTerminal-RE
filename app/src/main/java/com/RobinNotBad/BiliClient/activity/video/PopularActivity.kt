package com.RobinNotBad.BiliClient.activity.video

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter
import com.RobinNotBad.BiliClient.api.RecommendApi
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.view.ImageAutoLoadScrollListener

class PopularActivity : InstanceActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private var videoCardList: ArrayList<VideoCard>? = null
    private var videoCardAdapter: VideoCardAdapter? = null
    private var firstRefresh = true
    private var refreshing = false

    private var page = 1

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_main_refresh)
        setMenuClick()

        recyclerView = findViewById(R.id.recyclerView)
        ImageAutoLoadScrollListener.install(recyclerView)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        swipeRefreshLayout.setOnRefreshListener { loadPopular() }

        val title = findViewById<TextView>(R.id.pageName)
        title.text = "热门"

        loadPopular()
    }

    // 26.10.04 批次 3（B5）：本页早已改用 notifyItemRangeRemoved/Inserted，
    // 原来的 @SuppressLint("NotifyDataSetChanged") 是残留，已清掉。
    private fun loadPopular() {
        page = 1
        if (firstRefresh) {
            recyclerView.layoutManager = CustomLinearManager(this)
            videoCardList = ArrayList()
        } else {
            val last = videoCardList!!.size
            videoCardList!!.clear()
            videoCardAdapter!!.notifyItemRangeRemoved(0, last)
        }
        swipeRefreshLayout.setRefreshing(true)

        refreshing = true
        CenterThreadPool.run { addPopular() }
    }

    private fun addPopular() {
        runOnUiThread { swipeRefreshLayout.setRefreshing(true) }
        try {
            val list = ArrayList<VideoCard>()
            RecommendApi.getPopular(list, page)
            page++
            runOnUiThread {
                videoCardList!!.addAll(list)
                swipeRefreshLayout.setRefreshing(false)
                refreshing = false
                if (firstRefresh) {
                    firstRefresh = false
                    videoCardAdapter = VideoCardAdapter(this, videoCardList!!)
                    recyclerView.adapter = videoCardAdapter

                    recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                            super.onScrollStateChanged(recyclerView, newState)
                        }

                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                            super.onScrolled(recyclerView, dx, dy)
                            val manager = recyclerView.layoutManager as LinearLayoutManager
                            val lastItemPosition = manager.findLastCompletelyVisibleItemPosition()
                            val itemCount = manager.itemCount
                            if (lastItemPosition >= (itemCount - 3) && dy > 0 && !refreshing) {
                                refreshing = true
                                CenterThreadPool.run { addPopular() }
                            }
                        }
                    })
                } else {
                    videoCardAdapter!!.notifyItemRangeInserted(videoCardList!!.size - list.size, list.size)
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                // 失败时必须同时复位「转圈」和 refreshing 两个状态：
                // 此前只弹了错误提示，swipeRefreshLayout 会一直转，refreshing 也永远是 true，
                // 于是 onScrolled 里的 !refreshing 判定让「加载更多」被永久锁死。
                refreshing = false
                swipeRefreshLayout.setRefreshing(false)
                MsgUtil.err(e)
            }
        }
    }
}