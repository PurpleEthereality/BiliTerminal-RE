package com.RobinNotBad.BiliClient.activity.user.favorite

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
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
import com.RobinNotBad.BiliClient.util.TerminalContext
import com.RobinNotBad.BiliClient.util.TerminalDialog

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

    /** 多选删除：模式开关与已勾选的 aid（adapter 持有同一个集合引用） */
    private var selectionMode = false
    private val selectedAids = LinkedHashSet<Long>()
    private var manageToggle: TextView? = null
    private var manageDelete: TextView? = null

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
        setupManageBar()
        setOnLoadMoreListener { page -> continueLoading(page) }
        loadFirstPage()
    }

    private fun setupSortBar() {
        findViewById<View>(R.id.sortBar).visibility = View.VISIBLE
        // 列表一滚动就把排序行收回去（手表屏小，收起来多露出一行视频）。
        // 多选条（manageBar）故意不在这里：用户正勾着视频呢，条自己收走就没法点删除了。
        setupAutoHideBars(findViewById(R.id.sortBar))
        sortFavTime = findViewById(R.id.sortFavTime)
        sortView = findViewById(R.id.sortView)
        sortPubtime = findViewById(R.id.sortPubtime)
        unselectedColor = sortFavTime?.currentTextColor ?: unselectedColor

        sortFavTime?.setOnClickListener { switchSort(FavoriteApi.ORDER_FAV_TIME) }
        sortView?.setOnClickListener { switchSort(FavoriteApi.ORDER_VIEW) }
        sortPubtime?.setOnClickListener { switchSort(FavoriteApi.ORDER_PUBTIME) }
        updateSortColors()

        // 临时诊断埋点（26.10.04 批次 7）：用户在真机上反馈「没看到排序行」，静态排查已确认
        // 点亮是无条件的、资源也进了包，需要一行日志确认运行时状态。
        // 真机排查：adb logcat -s "debug-收藏夹排序"（应看到 visibility=0 VISIBLE）。
        Log.e(
            "debug-收藏夹排序",
            "sortBar.visibility=${findViewById<View>(R.id.sortBar).visibility} mediaId=$mediaId fid=$fid readOnly=$readOnly"
        )
    }

    private fun switchSort(order: String) {
        if (order == sortOrder) return
        sortOrder = order
        updateSortColors()
        // 排序是服务端参数，必须从第一页重拉；重拉前先退出多选，避免勾选状态指向旧列表
        if (selectionMode) exitSelectionMode()
        // 列表马上要回到第一页，用户已经滚不回去了，排序行必须回到位
        expandAutoHideBars()
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

    /**
     * 多选条：只在「能写」且**已经进入多选**时才出现。
     *
     * 它不进 [setupAutoHideBars]，可见性完全由多选态决定——这一点很关键：
     * 自动收回只认「可见且自然高度 > 0」的条，如果这条平时也常亮，滚动时会把它一起收走，
     * 多选时就点不到「删除」了。
     *
     * 入口改为「长按条目 → 菜单里选『多选』」（见 [showManageMenu]）：常亮的一整行
     * 只为了一个偶尔才用的功能占掉手表上宝贵的竖向空间，不划算。
     */
    private fun setupManageBar() {
        if (!writable) return
        manageToggle = findViewById(R.id.manageToggle)
        manageDelete = findViewById(R.id.manageDelete)
        if (unselectedColor == 0) unselectedColor = manageToggle?.currentTextColor ?: unselectedColor
        manageToggle?.setOnClickListener { if (selectionMode) exitSelectionMode() else enterSelectionMode() }
        manageDelete?.setOnClickListener { confirmBatchDelete() }
        updateManageBar()
    }

    private fun enterSelectionMode() {
        selectionMode = true
        selectedAids.clear()
        videoCardAdapter?.let {
            it.selectionMode = true
            it.notifyDataSetChanged()
        }
        updateManageBar()
        MsgUtil.showMsg("点条目勾选，再点「删除」")
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedAids.clear()
        videoCardAdapter?.let {
            it.selectionMode = false
            it.notifyDataSetChanged()
        }
        updateManageBar()
    }

    private fun toggleSelected(position: Int) {
        val card = videoList.getOrNull(position) ?: return
        if (!selectedAids.add(card.aid)) selectedAids.remove(card.aid)
        videoCardAdapter?.notifyItemChanged(position)
        updateManageBar()
    }

    private fun updateManageBar() {
        findViewById<View>(R.id.manageBar).visibility = if (selectionMode) View.VISIBLE else View.GONE
        manageToggle?.text = if (selectionMode) "退出多选" else "多选"
        manageToggle?.setTextColor(if (selectionMode) ColorScheme.PRIMARY else unselectedColor)
        manageDelete?.text = if (selectedAids.isEmpty()) "删除" else "删除(${selectedAids.size})"
        // 没选东西时把「删除」压暗，点了给提示而不是弹一个空确认框
        manageDelete?.alpha = if (selectedAids.isEmpty()) 0.5f else 1f
        manageDelete?.setTextColor(if (selectedAids.isEmpty()) unselectedColor else ColorScheme.PRIMARY)
    }

    private fun confirmBatchDelete() {
        if (selectedAids.isEmpty()) {
            MsgUtil.showMsg("先选几条吧~")
            return
        }
        val targets = videoList.filter { selectedAids.contains(it.aid) }
        if (targets.isEmpty()) {
            MsgUtil.showMsg("先选几条吧~")
            return
        }
        TerminalDialog.confirm(
            context = this,
            title = "删除收藏内容",
            message = "确定把选中的 ${targets.size} 条从收藏夹里移除吗？",
            confirmText = "删除"
        ) {
            CenterThreadPool.run {
                try {
                    val code = FavoriteApi.batchDeleteResources(mediaId, targets)
                    runOnUiThread {
                        if (code == 0) {
                            MsgUtil.showMsg("已删除 ${targets.size} 条")
                            applyRemoved(targets)
                            exitSelectionMode()
                        } else {
                            MsgUtil.showMsg(FavoriteApi.resourceErrorMsg(code))
                        }
                    }
                } catch (e: Exception) {
                    report(e)
                }
            }
        }.show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyRemoved(removed: List<VideoCard>) {
        videoList.removeAll(removed)
        videoCardAdapter?.notifyDataSetChanged()
        if (videoList.isEmpty()) showEmptyView()
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
        adapter.selectedAids = selectedAids
        // 虚拟合集模式：点击收藏夹内视频，将当前收藏夹所有视频组成合集播放
        val virtualCollection = SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.VIRTUAL_COLLECTION_ENABLE, true)
        adapter.onItemClickListener = { position, videoCard ->
            if (selectionMode) {
                // 多选模式下点击 = 勾选/取消勾选
                toggleSelected(position)
            } else if (virtualCollection) {
                val folderId = if (mediaId > 0) mediaId else fid
                playFavoriteVirtualCollection(position, videoCard.aid, folderId, folderName)
            } else {
                // 关掉虚拟合集时回到默认行为：进视频详情页
                TerminalContext.getInstance().enterVideoDetailPage(this, videoCard.aid, videoCard.bvid, "video")
            }
        }

        adapter.setOnLongClickListener { position -> onItemLongClick(position) }
    }

    private fun onItemLongClick(position: Int): Boolean {
        // 多选模式下长按也是勾选，不再弹管理菜单
        if (selectionMode) {
            toggleSelected(position)
            return true
        }
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
        // 多选的入口。原来靠列表顶上一整行常亮的「多选」按钮，用户反馈那一行平时白占地方，
        // 所以把入口收进长按菜单、并把那一行改成只在多选态出现（见 setupManageBar）。
        if (writable) actions.add("多选" to { enterSelectionMode() })
        actions.add("取消收藏" to { confirmRemoveFavorite(card) })
        // 「取消收藏」是破坏性操作，用危险色标出来。
        // 用 indexOfFirst 而不是写死下标：上面每加一项都要记得改下标，早晚会标错行。
        TerminalDialog.menu(
            context = this,
            title = card.title,
            items = actions.map { it.first },
            danger = setOf(actions.indexOfFirst { it.first == "取消收藏" })
        ) { which -> actions[which].second() }.show()
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
                    TerminalDialog.menu(
                        context = this@FavoriteVideoListActivity,
                        title = if (move) "移动到…" else "复制到…",
                        items = targets.map { it.name }
                    ) { which -> transfer(card, targets[which].mediaId, move) }.show()
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
        TerminalDialog.confirm(
            context = this,
            title = "取消收藏",
            message = "确定把《${card.title}》从收藏夹里去掉吗？",
            confirmText = "确定"
        ) {
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
        }.show()
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
