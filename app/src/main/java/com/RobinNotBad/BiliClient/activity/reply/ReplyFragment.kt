package com.RobinNotBad.BiliClient.activity.reply

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.Pair
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.RobinNotBad.BiliClient.activity.base.RefreshListFragment
import com.RobinNotBad.BiliClient.adapter.ReplyAdapter
import com.RobinNotBad.BiliClient.api.ReplyApi
import com.RobinNotBad.BiliClient.event.ReplyEvent
import com.RobinNotBad.BiliClient.model.Reply
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

class ReplyFragment : RefreshListFragment() {

    private var dontload: Boolean = false
    var aid: Long = 0
    var mid: Long = 0
    var sort: Int = 3
    var type: Int = 0
    var count: Int = 0
    var replyList: ArrayList<Reply>? = null
    var replyAdapter: ReplyAdapter? = null
    var replyType: Int = ReplyApi.REPLY_TYPE_VIDEO
    private var seek: Long = 0

    // 翻页游标会被后台线程读写，标 @Volatile 保证可见性：
    // 否则主线程刚清空的游标，后台线程可能还拿着旧值去请求上一页
    @Volatile
    private var pagination: String = ""

    private var isManager: Boolean = false

    // 加载世代号：每次 refresh（下拉刷新、换排序、换视频）都自增。
    // 所有异步回调都要跟出发时记下的世代号比对，不是当前世代就直接丢弃；
    // 否则旧请求的响应会覆盖新请求刚拉到的列表，评论区表现为"跳来跳去"
    @Volatile
    private var loadGeneration: Int = 0

    // 是否真的拉到评论末尾。用 @Volatile 是因为它会在后台线程的重试循环里被反复读、在主线程被写；
    // 父类的 bottom 是给滚动监听用的界面状态，不能拿来当线程间的同步标志
    @Volatile
    private var isEnd: Boolean = false

    companion object {
        // 单次翻页的总超时上限。没有这个兜底时，只要服务端把连接吊住，
        // isLoading / isRefreshing 就永远不复位，评论区从此彻底卡死（再也上拉不动）
        private const val LOAD_DEADLINE_MS = 30_000L

        // 连续空页的容忍次数。服务端偶发返回空页就判定"到底了"会丢内容，
        // 但无限重试又会造成请求风暴，所以两者取一个上限
        private const val MAX_EMPTY_PAGES = 5
        fun newInstance(aid: Long, type: Int): ReplyFragment {
            val fragment = ReplyFragment()
            val args = Bundle()
            args.putLong("aid", aid)
            args.putInt("type", type)
            fragment.arguments = args
            return fragment
        }

        fun newInstance(aid: Long, type: Int, dontload: Boolean): ReplyFragment {
            val fragment = ReplyFragment()
            val args = Bundle()
            args.putLong("aid", aid)
            args.putInt("type", type)
            args.putBoolean("dontload", dontload)
            fragment.arguments = args
            return fragment
        }

        fun newInstance(aid: Long, type: Int, seek_rpid: Long): ReplyFragment {
            val fragment = ReplyFragment()
            val args = Bundle()
            args.putLong("aid", aid)
            args.putInt("type", type)
            args.putLong("seek", seek_rpid)
            fragment.arguments = args
            return fragment
        }

        fun newInstance(aid: Long, type: Int, dontload: Boolean, seek_rpid: Long): ReplyFragment {
            val fragment = ReplyFragment()
            val args = Bundle()
            args.putLong("aid", aid)
            args.putInt("type", type)
            args.putBoolean("dontload", dontload)
            args.putLong("seek", seek_rpid)
            fragment.arguments = args
            return fragment
        }

        fun newInstance(aid: Long, type: Int, count: Int, seek_rpid: Long, up_mid: Long): ReplyFragment {
            val fragment = ReplyFragment()
            val args = Bundle()
            args.putLong("aid", aid)
            args.putInt("count", count)
            args.putInt("type", type)
            args.putLong("seek", seek_rpid)
            args.putLong("mid", up_mid)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (arguments != null) {
            aid = arguments!!.getLong("aid", 0)
            count = arguments!!.getInt("count", 0)
            type = arguments!!.getInt("type", 0)
            replyType = type
            dontload = arguments!!.getBoolean("dontload", false)
            seek = arguments!!.getLong("seek", -1)
            mid = arguments!!.getLong("mid", -1)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setForceSingleColumn()
        super.onViewCreated(view, savedInstanceState)

        if (SharedPreferencesUtil.getBoolean("ui_landscape", false)) {
            val windowManager = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= 17) display.getRealMetrics(metrics)
            else display.getMetrics(metrics)
            val paddings = metrics.widthPixels / 6
            recyclerView.setPadding(paddings, 0, paddings, 0)
        }

        setOnRefreshListener { refresh(aid) }
        setOnLoadMoreListener { continueLoading(it) }

        Log.e("debug-av号", aid.toString())

        replyList = ArrayList()

        if (!dontload) refresh(aid)
    }

    fun setManager(source: Any?) {
        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0L) return

        try {
            if (source != null) {
                if (source is List<*>) {
                    val staffs = source as List<UserInfo>
                    for (userInfo in staffs) {
                        if (userInfo.mid == SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0)) {
                            isManager = true
                            break
                        }
                    }
                } else if (source is UserInfo) {
                    isManager = source.mid == SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0)
                }
            }
        } catch (e: Exception) {
            MsgUtil.err(e)
        }
    }

    private fun createReplyAdapter(): ReplyAdapter {
        return ReplyAdapter(requireContext(), replyList!!, aid, mid, 0L, replyType, sort, replyType)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun continueLoading(page: Int) {
        // 已经到底或不再允许上拉，直接复位并撤退：这一步避免了"到底后每次滚动都再发一轮请求"
        if (isEnd || bottom) {
            setRefreshing(false)
            return
        }

        // 记下本次加载的世代号与截止时间。世代号用于丢弃过期响应，截止时间用于给重试兜底，
        // 两者共同保证既不会用旧数据覆盖新列表，也不会因为重试把请求打爆
        val generation = loadGeneration
        val deadline = System.currentTimeMillis() + LOAD_DEADLINE_MS

        CenterThreadPool.run {
            var emptyPages = 0
            try {
                while (!isEnd && !bottom && emptyPages < MAX_EMPTY_PAGES && System.currentTimeMillis() < deadline) {
                    // 每轮开始前先确认自己还是当前世代，过期就立刻撤退，绝不碰数据源
                    if (generation != loadGeneration) return@run

                    val list = ArrayList<Reply>()
                    val pageState = ReplyApi.getRepliesLazy(aid, 0, pagination, type, sort, list)
                    val result = pageState.first

                    if (generation != loadGeneration) return@run

                    if (result == -1) {
                        // 失败也必须复位加载状态，否则下拉/上拉会永远卡在转圈
                        runOnUiThread {
                            if (generation == loadGeneration) loadFail()
                        }
                        return@run
                    }

                    if (result == ReplyApi.PAGE_EMPTY) {
                        // 服务端偶发空页：带着原游标重试同一页，重试次数和总时长都受上面的循环条件约束
                        emptyPages++
                        continue
                    }

                    val nextPagination = pageState.second
                    val finalEnd = result == 1

                    if (list.isEmpty()) {
                        // 空列表且已到底，直接收尾
                        if (finalEnd) {
                            runOnUiThread {
                                if (generation != loadGeneration) return@runOnUiThread
                                pagination = nextPagination
                                isEnd = true
                                bottom = true
                                setRefreshing(false)
                            }
                            return@run
                        }
                        emptyPages++
                        continue
                    }

                    // 数据改动与 notify 必须同在主线程执行，否则列表下标会错位甚至越界崩溃
                    runOnUiThread {
                        if (generation != loadGeneration) return@runOnUiThread
                        Log.e("debug", if (finalEnd) "到底了" else "下一页")
                        // 只有真正拿到响应才推进游标，避免空页把游标冲掉导致整段内容缺失
                        pagination = nextPagination
                        if (finalEnd) {
                            isEnd = true
                            bottom = true
                        }
                        replyList!!.addAll(list)
                        if (replyAdapter != null)
                            replyAdapter!!.notifyItemRangeInserted(replyList!!.size - list.size + 1, list.size)
                        setRefreshing(false)
                    }
                    return@run
                }

                // 走到这里说明是空页用尽或超时退出。这里一定要复位加载状态并封口，
                // 否则滚动监听会不停地再次触发加载，形成请求风暴
                runOnUiThread {
                    if (generation != loadGeneration) return@runOnUiThread
                    isEnd = true
                    bottom = true
                    setRefreshing(false)
                }
                if (generation == loadGeneration && emptyPages >= MAX_EMPTY_PAGES)
                    MsgUtil.showMsgLong("暂时没有更多评论了")
            } catch (e: Exception) {
                // 异常路径同样要复位加载状态并回退页码，这是父类"加载完必须 setRefreshing(false)"的硬约定
                runOnUiThread {
                    if (generation == loadGeneration) loadFail(e)
                }
            }
        }
    }

    fun notifyReplyInserted(replyEvent: ReplyEvent) {
        if (replyEvent.oid != aid) return
        val reply = replyEvent.message ?: return
        // 这个回调来自 EventBus 的 ThreadMode.ASYNC，实际跑在后台线程上。
        // 因此数据的增删和 RecyclerView 的通知必须一起丢到主线程执行：
        // 后台改数据源、主线程读数据源会出现"数据改了但通知的位号对不上"，
        // 轻则插入位置错乱，重则下标越界直接崩
        runOnUiThread {
            val list = replyList ?: return@runOnUiThread
            val adapter = replyAdapter ?: return@runOnUiThread
            if (reply.root == 0L) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return@runOnUiThread
                // findFirstCompletelyVisibleItemPosition 返回的是 adapter 位置，
                // 而 adapter 第 0 位是"写评论"头部，所以要减 1 才是数据列表下标
                var dataIndex = Math.max(layoutManager.findFirstCompletelyVisibleItemPosition() - 1, 0)
                if (dataIndex > list.size) dataIndex = list.size
                list.add(dataIndex, reply)
                // 上面减了 1，这里加回来才是这条评论真正的 adapter 位置
                val adapterPos = dataIndex + 1
                adapter.notifyItemInserted(adapterPos)
                adapter.notifyItemRangeChanged(adapterPos, list.size + 1 - adapterPos)
                layoutManager.scrollToPositionWithOffset(adapterPos, 0)
            } else if (replyEvent.pos >= 0L && replyEvent.pos < list.size) {
                // 加下标保护：这个事件的 pos 是对端发事件时的位置，越界会直接崩
                list[replyEvent.pos].childMsgList.add(reply)
                list[replyEvent.pos].childCount++
                adapter.notifyItemChanged(replyEvent.pos + 1)
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refresh(aid: Long) {
        // 进入新世代：此前所有在途请求的响应都会被下面的世代号比对丢弃
        val generation = ++loadGeneration
        pagination = ""
        isEnd = false
        bottom = false
        this.aid = aid
        setRefreshing(true)
        CenterThreadPool.run {
            try {
                val list = ArrayList<Reply>()
                val pageState = ReplyApi.getRepliesLazy(aid, seek, "", type, sort, list)

                if (generation != loadGeneration) return@run
                val result = pageState.first
                val nextPagination = pageState.second
                if (result == -1) {
                    runOnUiThread {
                        if (generation == loadGeneration) loadFail()
                    }
                    return@run
                }
                val finalEnd = result == 1
                runOnUiThread {
                    // 不是当前世代说明又发起了新的刷新，转圈由新世代负责，这里不能去动它
                    if (generation != loadGeneration) return@runOnUiThread
                    // 已经不在界面上也要复位加载状态，守住"任何路径都必须 setRefreshing(false)"这条约定
                    if (!isAdded) {
                        setRefreshing(false)
                        return@runOnUiThread
                    }
                    Log.e("debug", if (finalEnd) "到底了" else "下一页")
                    pagination = nextPagination
                    if (finalEnd) {
                        isEnd = true
                        bottom = true
                    }
                    if (replyList != null) replyList!!.clear()
                    else replyList = ArrayList()
                    replyList!!.addAll(list)
                    if (replyAdapter == null) {
                        val adapter = createReplyAdapter()
                        replyAdapter = adapter
                        adapter.count = count.toLong()
                        adapter.isManager = isManager
                        setOnSortSwitch()
                        setAdapter(adapter)
                    } else {
                        replyAdapter!!.notifyDataSetChanged()
                    }
                    // 列表已经交给界面了，加载状态必须复位，否则翻页会永久卡死
                    setRefreshing(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (generation == loadGeneration) loadFail(e)
                }
            }
        }
    }

    private fun setOnSortSwitch() {
        replyAdapter!!.setOnSortSwitchListener {
            sort = if (sort == 2) 3 else 2
            replyAdapter!!.sort = this.sort
            refresh(aid)
        }
    }
}