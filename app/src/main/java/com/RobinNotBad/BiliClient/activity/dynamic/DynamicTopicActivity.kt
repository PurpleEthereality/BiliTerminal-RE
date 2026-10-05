package com.RobinNotBad.BiliClient.activity.dynamic

import android.content.Intent
import android.os.Bundle
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.dynamic.TopicAdapter
import com.RobinNotBad.BiliClient.adapter.dynamic.TopicDynamicAdapter
import com.RobinNotBad.BiliClient.api.TopicApi
import com.RobinNotBad.BiliClient.model.Dynamic
import com.RobinNotBad.BiliClient.util.CenterThreadPool

/**
 * 话题页（26.10.04 批次 6 的 C10）。一个页面两种形态：
 *
 * - **不带 `topic_id`**：话题广场，列热门话题（`/x/topic/pub/search`，`keywords` 留空）。
 *   点一个话题就用同一个 Activity 带 `topic_id` 再开一次，返回即回到广场。
 * - **带 `topic_id`**：该话题下的动态列表（`/x/polymer/web-dynamic/v1/feed/topic`），
 *   滚动到底按返回的 `offset` 翻页。
 *
 * 之所以自建广场而不是只做「从动态点进话题」：话题 id 无法从动态正文反推
 * （`RICH_TEXT_NODE_TYPE_TOPIC` 只带搜索页跳转链接），没有广场就无从进入。
 *
 * 广场数据源原为 `/x/topic/web/dynamic/rcmd`，该端点已废弃（恒返回 `data:null`），
 * 26.10.05 换成 `pub/search`，详见 [com.RobinNotBad.BiliClient.api.TopicApi.getRecommendedTopics]。
 */
class DynamicTopicActivity : RefreshListActivity() {

    private val dynamicList = ArrayList<Dynamic>()
    private var topicId: Long = 0L
    private var topicName: String = ""
    private var offset: String = ""
    private var topicAdapter: TopicAdapter? = null
    private var dynamicAdapter: TopicDynamicAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        topicId = intent.getLongExtra("topic_id", 0L)
        topicName = intent.getStringExtra("topic_name").orEmpty()

        if (isTopicMode()) {
            setPageName(topicName.ifEmpty { "话题" })
            loadTopicDynamics(refresh = true)
        } else {
            setPageName("话题广场")
            loadPlaza()
        }

        setOnRefreshListener {
            if (isTopicMode()) loadTopicDynamics(refresh = true) else loadPlaza()
        }
        setOnLoadMoreListener { loadTopicDynamics(refresh = false) }
        setOnEmptyRetry(Runnable {
            if (isTopicMode()) loadTopicDynamics(refresh = true) else loadPlaza()
        })
    }

    private fun isTopicMode(): Boolean = topicId > 0

    /** 话题广场：话题列表，只有一页（数据源见 [TopicApi.getRecommendedTopics]）。 */
    private fun loadPlaza() {
        bottom = true
        CenterThreadPool.run {
            try {
                val topics = TopicApi.getRecommendedTopics()
                topicAdapter = TopicAdapter(topics) { topic ->
                    startActivity(
                        Intent(this@DynamicTopicActivity, DynamicTopicActivity::class.java)
                            .putExtra("topic_id", topic.id)
                            .putExtra("topic_name", topic.name)
                    )
                }
                setAdapter(topicAdapter!!)
                if (topics.isEmpty()) {
                    // 空态要说清是"服务端没给数据"而不是用户操作错了（26.10.05）。
                    // 旧文案"啥都木有~"会让用户以为是自己网络/操作的问题。
                    // 这里显式覆盖 setOnEmptyRetry 写入的通用文案。
                    emptyView?.text = "话题广场没有取到数据\n可能是服务端暂时没有下发\n点我重试"
                    showEmptyView()
                } else hideEmptyView()
                setRefreshing(false)
            } catch (e: Exception) {
                loadFail(e)
            }
        }
    }

    /** 话题下的动态列表；[refresh] 为 true 时清空重来。 */
    private fun loadTopicDynamics(refresh: Boolean) {
        if (refresh && !swipeRefreshLayout.isRefreshing) setRefreshing(true)
        CenterThreadPool.run {
            try {
                if (refresh) {
                    offset = ""
                    dynamicList.clear()
                }
                val lastSize = dynamicList.size
                offset = TopicApi.getTopicDynamicList(dynamicList, topicId, offset)
                bottom = offset.isEmpty()

                if (refresh) {
                    dynamicAdapter = TopicDynamicAdapter(this@DynamicTopicActivity, dynamicList, topicName)
                    setAdapter(dynamicAdapter!!)
                } else if (dynamicList.size > lastSize) {
                    runOnUiThread {
                        dynamicAdapter?.notifyItemRangeInserted(lastSize + 1, dynamicList.size - lastSize)
                    }
                }
                if (dynamicList.isEmpty()) showEmptyView() else hideEmptyView()
                setRefreshing(false)
            } catch (e: Exception) {
                loadFail(e)
            }
        }
    }
}
