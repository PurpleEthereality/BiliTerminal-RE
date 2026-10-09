package com.RobinNotBad.BiliClient.activity.settings

import android.os.Bundle
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.AnnouncementAdapter
import com.RobinNotBad.BiliClient.api.TerminalApi
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
            // 26.10.09：上游（api.biliterminal.cn）与自建（rebiliterminal.zsapp.asia）两路公告合并。
            // loadMergedAnnouncements 内部对两路各自 try/catch，任何一路挂掉都不影响另一路展示——
            // 旧实现是「上游一个源抛异常整页就报错」，用户会连能取到的公告也看不到。
            // 返回 false 表示至少有一路失败，但 out 里仍有成功那一路的内容。
            val announcements = ArrayList<Announcement>()
            val allOk = TerminalApi.loadMergedAnnouncements(announcements)
            runOnUiThread {
                if (announcements.isEmpty()) showEmptyView() else hideEmptyView()
                setAdapter(AnnouncementAdapter(this@AnnouncementsActivity, announcements))
                setRefreshing(false)
                if (!allOk && announcements.isNotEmpty()) MsgUtil.showMsg("部分公告源连不上，已显示能取到的内容")
            }
        }
    }
}
