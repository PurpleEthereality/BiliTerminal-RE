package com.RobinNotBad.BiliClient.activity.user.favorite

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter
import com.RobinNotBad.BiliClient.api.FavoriteApi
import com.RobinNotBad.BiliClient.api.PlayerApi
import com.RobinNotBad.BiliClient.model.PlayerData
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.ui.appearance.ColorScheme
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

class FavoriteVideoListActivity : RefreshListActivity() {

    private var mid: Long = 0
    private var fid: Long = 0
    private var mediaId: Long = 0
    private var folderName: String = "收藏夹"

    /** 别人的收藏夹：不能复制/移动/删除 */
    private var readOnly: Boolean = false

    /** 自己的收藏夹且拿得到 media_id：长按弹管理菜单 */
    private var writable: Boolean = false

    private var videoList: ArrayList<VideoCard> = ArrayList()
    private var videoCardAdapter: VideoCardAdapter? = null

    /** 当前排序（x/v3/fav/resource/list 的 order 参数） */
    private var sortOrder: String = FavoriteApi.ORDER_FAV_TIME

    private var sortFavTime: TextView? = null
    private var sortView: TextView? = null
    private var sortPubtime: TextView? = null

    /** 未选中档位的颜色，创建时从布局里的默认文字色抓一次 */
    private var unselectedColor: Int = 0

    // 没有 media_id 的老兜底：4 秒内连点两次长按才删
    private var longClickPosition = -1
    private var longClickTimestamp: Long = 0

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        mid = intent.getLongExtra("mid", 0)
        fid = intent.getLongExtra("fid", 0)
        mediaId = intent.getLongExtra("mediaId", 0)
        folderName = intent.getStringExtra("name") ?: "收藏夹"

        setPageName(folderName)

        videoList = ArrayList()

        // 自己的收藏夹现在也会带 mediaId（排序/复制/移动都要它）；
        // 没带 readOnly 的旧调用方按原逻辑兜底：有 media_id 就当他人收藏夹只读
        readOnly = intent.getBooleanExtra("readOnly", mediaId > 0)
        writable = !readOnly && mediaId > 0

        setupSortBar()
        setOnLoadMoreListener { page -> continueLoading(page) }
        loadFirstPage()
    }

    private fun setupSortBar() {
        findViewById<View>(R.id.sortBar).visibility = View.VISIBLE
        sortFavTime = findViewById(R.id.sortFavTime)
        sortView = findViewById(R.id.sortView)
        sortPubtime = findViewById(R.id.sortPubtime)
        unselectedColor = sortFavTime?.currentTextColor ?: unselectedColor

        sortFavTime?.setOnClickListener { switchSort(FavoriteApi.ORDER_FAV_TIME) }
        sortView?.setOnClickListener { switchSort(FavoriteApi.ORDER_VIEW) }
        sortPubtime?.setOnClickListener { switchSort(FavoriteApi.ORDER_PUBTIME) }
        updateSortColors()
    }

    private fun switchSort(order: String) {
        if (order == sortOrder) return
        sortOrder = order
        updateSortColors()
        // 排序是服务端参数，必须从第一页重拉
        page = 1
        bottom = false
        videoList.clear()
        videoCardAdapter?.notifyDataSetChanged()
        loadFirstPage()
    }

    private fun updateSortColors() {
        sortFavTime?.setTextColor(if (sortOrder == FavoriteApi.ORDER_FAV_TIME) ColorScheme.PRIMARY else unselectedColor)
        sortView?.setTextColor(if (sortOrder == FavoriteApi.ORDER_VIEW) ColorScheme.PRIMARY else unselectedColor)
        sortPubtime?.setTextColor(if (sortOrder == FavoriteApi.ORDER_PUBTIME) ColorScheme.PRIMARY else unselectedColor)
    }

    /** 按当前排序拉一页 */
    private fun fetch(page: Int): Int =
        if (mediaId > 0) FavoriteApi.getFolderVideosNew(mediaId, page, videoList, sortOrder)
        else FavoriteApi.getFolderVideos(mid, fid, page, videoList, sortOrder)

    private fun loadFirstPage() {
        CenterThreadPool.run {
            try {
                val result = fetch(page)
                if (result == -1) {
                    loadFail()
                    return@run
                }
                if (result == 1) bottom = true
                runOnUiThread {
                    if (videoCardAdapter == null) {
                        videoCardAdapter = VideoCardAdapter(this@FavoriteVideoListActivity, videoList).also { bindAdapter(it) }
                        setAdapter(videoCardAdapter!!)
                    } else {
                        videoCardAdapter!!.notifyDataSetChanged()
                    }
                    if (videoList.isEmpty()) showEmptyView() else hideEmptyView()
                    setRefreshing(false)
                }
            } catch (e: Exception) {
                loadFail(e)
            }
        }
    }

    private fun bindAdapter(adapter: VideoCardAdapter) {
        // 虚拟合集模式：点击收藏夹内视频，将当前收藏夹所有视频组成合集播放
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.VIRTUAL_COLLECTION_ENABLE, true)) {
            adapter.onItemClickListener = { position, videoCard ->
                val folderId = if (mediaId > 0) mediaId else fid
                playFavoriteVirtualCollection(position, videoCard.aid, folderId, folderName)
            }
        }

        adapter.setOnLongClickListener { position -> onItemLongClick(position) }
    }

    private fun onItemLongClick(position: Int): Boolean {
        // 自己的收藏夹：长按弹管理菜单（复制/移动/取消收藏）
        if (writable) {
            showManageMenu(position)
            return true
        }
        // 拿不到 media_id 的老链路：保留原有的连点两次长按删除
        if (!readOnly) {
            val timestamp = System.currentTimeMillis()
            if (longClickPosition == position && timestamp - longClickTimestamp < 4000) {
                CenterThreadPool.run {
                    try {
                        val delResult = FavoriteApi.deleteFavorite(videoList[position].aid, fid)
                        longClickPosition = -1
                        if (delResult == 0) runOnUiThread {
                            MsgUtil.showMsg("删除成功")
                            videoList.removeAt(position)
                            videoCardAdapter!!.notifyItemRemoved(position)
                            videoCardAdapter!!.notifyItemRangeChanged(position, videoList.size - position)
                        }
                        else
                            runOnUiThread { MsgUtil.showMsg("删除失败，错误码：$delResult") }
                    } catch (e: Exception) {
                        report(e)
                    }
                }
            } else {
                longClickPosition = position
                longClickTimestamp = timestamp
                MsgUtil.showMsg("再次长按管理")
            }
            return true
        }
        return false
    }

    private fun showManageMenu(position: Int) {
        val card = videoList.getOrNull(position) ?: return
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add("复制到…" to { showTargetFolderPicker(card, false) })
        actions.add("移动到…" to { showTargetFolderPicker(card, true) })
        actions.add("取消收藏" to { confirmRemoveFavorite(card) })
        AlertDialog.Builder(this)
            .setTitle(card.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    /** 拉自己的收藏夹列表让用户选目标（排除当前这个） */
    private fun showTargetFolderPicker(card: VideoCard, move: Boolean) {
        CenterThreadPool.run {
            try {
                val targets = FavoriteApi.getFavoriteFolders(mid).filter { it.mediaId > 0 && it.mediaId != mediaId }
                runOnUiThread {
                    if (targets.isEmpty()) {
                        MsgUtil.showMsg("没有别的收藏夹可以放喵~")
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(this)
                        .setTitle(if (move) "移动到…" else "复制到…")
                        .setItems(targets.map { it.name }.toTypedArray()) { _, which ->
                            transfer(card, targets[which].mediaId, move)
                        }
                        .show()
                }
            } catch (e: Exception) {
                report(e)
            }
        }
    }

    private fun transfer(card: VideoCard, targetMediaId: Long, move: Boolean) {
        CenterThreadPool.run {
            try {
                val code = if (move) FavoriteApi.moveResources(mediaId, targetMediaId, listOf(card))
                else FavoriteApi.copyResources(mediaId, targetMediaId, listOf(card))
                runOnUiThread {
                    if (code == 0) {
                        MsgUtil.showMsg(if (move) "已移动" else "已复制")
                        if (move) removeItem(card)
                    } else {
                        MsgUtil.showMsg(FavoriteApi.resourceErrorMsg(code))
                    }
                }
            } catch (e: Exception) {
                report(e)
            }
        }
    }

    private fun confirmRemoveFavorite(card: VideoCard) {
        AlertDialog.Builder(this)
            .setTitle("取消收藏")
            .setMessage("确定把《${card.title}》从收藏夹里去掉吗？")
            .setPositiveButton("确定") { _, _ ->
                CenterThreadPool.run {
                    try {
                        val code = FavoriteApi.deleteFavorite(card.aid, fid)
                        runOnUiThread {
                            if (code == 0) {
                                MsgUtil.showMsg("已取消收藏")
                                removeItem(card)
                            } else {
                                MsgUtil.showMsg("取消失败，错误码：$code")
                            }
                        }
                    } catch (e: Exception) {
                        report(e)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun removeItem(card: VideoCard) {
        val index = videoList.indexOf(card)
        if (index < 0) return
        videoList.removeAt(index)
        videoCardAdapter?.notifyItemRemoved(index)
        videoCardAdapter?.notifyItemRangeChanged(index, videoList.size - index)
        if (videoList.isEmpty()) showEmptyView()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun continueLoading(page: Int) {
        CenterThreadPool.run {
            try {
                val lastSize = videoList.size
                val result = fetch(page)
                if (result != -1) {
                    Log.e("debug", "下一页")
                    runOnUiThread { videoCardAdapter!!.notifyItemRangeInserted(lastSize, videoList.size - lastSize) }
                    if (result == 1) {
                        Log.e("debug", "到底了")
                        bottom = true
                    }
                }
                setRefreshing(false)
            } catch (e: Exception) {
                loadFail(e)
            }
        }
    }

    /**
     * 播放收藏夹虚拟合集：将收藏夹内的所有视频组成合集，传入播放器实现连续播放
     * @param startPosition 点击的视频在列表中的位置
     * @param startAid 点击的视频aid（作为起始播放位置）
     * @param folderId 收藏夹ID
     * @param folderName 收藏夹名称
     */
    private fun playFavoriteVirtualCollection(startPosition: Int, startAid: Long, folderId: Long, folderName: String) {
        if (videoList.isEmpty()) {
            MsgUtil.showMsg("收藏夹为空")
            return
        }

        // 在后台线程获取起始视频的cid
        CenterThreadPool.run {
            try {
                // 构建合集的pagenames和cids
                val pagenames = ArrayList<String>()
                val cids = ArrayList<Long>()
                var startPageIndex = 0
                var firstCid: Long = 0

                for ((i, v) in videoList.withIndex()) {
                    pagenames.add(v.title)
                    cids.add(v.aid) // 使用aid作为标识，实际播放时会重新获取cid
                    if (v.aid == startAid) {
                        startPageIndex = i
                    }
                }

                // 获取起始视频的cid
                val firstAid = if (startPosition < videoList.size) videoList[startPosition].aid else startAid
                val videoInfo = com.RobinNotBad.BiliClient.api.VideoInfoApi.getVideoInfo(firstAid)
                if (videoInfo != null && videoInfo.cids.isNotEmpty()) {
                    firstCid = videoInfo.cids[0]
                }

                if (firstCid <= 0) {
                    runOnUiThread { MsgUtil.showMsg("获取视频信息失败") }
                    return@run
                }

                // 构建PlayerData
                val playerData = PlayerData(PlayerData.TYPE_VIDEO)
                playerData.title = "$folderName（虚拟合集）"
                playerData.aid = firstAid
                playerData.cid = firstCid
                playerData.pagenames = pagenames
                playerData.cids = cids
                playerData.currentPageIndex = startPageIndex

                // 在主线程启动播放
                runOnUiThread {
                    try {
                        PlayerApi.startGettingUrl(playerData)
                    } catch (e: Exception) {
                        MsgUtil.err(e)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { MsgUtil.err(e) }
            }
        }
    }
}
