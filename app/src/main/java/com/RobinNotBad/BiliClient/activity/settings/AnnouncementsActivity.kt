package com.RobinNotBad.BiliClient.activity.settings

import android.os.Bundle
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.AnnouncementAdapter
import com.RobinNotBad.BiliClient.api.AppInfoApi
import com.RobinNotBad.BiliClient.model.Announcement
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil

class AnnouncementsActivity : RefreshListActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setPageName("公告列表")

        // 本页此前有两个问题：
        // 1. 没有接下拉刷新——RefreshListActivity 在 onCreate 里默认把 swipeRefreshLayout 置为
        //    disabled，只有子类调过 setOnRefreshListener 才会打开，所以这里既刷不了也重试不了；
        // 2. catch 分支没有复位转圈——接口一旦异常，页面会一直转圈，只能退出重进。
        setOnRefreshListener { loadAnnouncements() }
        setOnEmptyRetry { loadAnnouncements() }
        loadAnnouncements()
    }

    private fun loadAnnouncements() {
        setRefreshing(true)
        CenterThreadPool.run {
            try {
                val announcements: ArrayList<Announcement> = AppInfoApi.getAnnouncementList()
                runOnUiThread {
                    if (announcements.isEmpty()) showEmptyView() else hideEmptyView()
                    setAdapter(AnnouncementAdapter(this@AnnouncementsActivity, announcements))
                    setRefreshing(false)
                }
            } catch (e: Exception) {
                report(e)
                runOnUiThread {
                    showEmptyView()
                    setRefreshing(false)
                    MsgUtil.showMsg("连接到哔哩终端接口时发生错误")
                }
            }
        }
    }
}
