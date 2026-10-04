package com.RobinNotBad.BiliClient.activity.dynamic

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.base.RefreshMainActivity
import com.RobinNotBad.BiliClient.adapter.dynamic.DynamicAdapter
import com.RobinNotBad.BiliClient.adapter.dynamic.DynamicHolder
import com.RobinNotBad.BiliClient.api.DynamicApi
import com.RobinNotBad.BiliClient.api.EmoteApi
import com.RobinNotBad.BiliClient.api.VoteApi
import com.RobinNotBad.BiliClient.model.Dynamic
import com.RobinNotBad.BiliClient.model.VoteDraft
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import java.util.regex.Pattern

class DynamicActivity : RefreshMainActivity() {

    private var dynamicList: ArrayList<Dynamic>? = null
    private var dynamicAdapter: DynamicAdapter? = null
    var recentUpList: List<DynamicApi.UpInfo>? = null
    private var offset: Long = 0
    private var firstRefresh: Boolean = true
    private var type: String = "all"

    companion object {
        private val typeNameMap = mapOf(
            "全部" to "all",
            "视频投稿" to "video",
            "追番" to "pgc",
            "专栏" to "article"
        )

        fun getRelayDynamicLauncher(activity: BaseActivity): ActivityResultLauncher<Intent> {
            return activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val code = result.resultCode
                val data = result.data
                if (code == RESULT_OK && data != null) {
                    var text = data.getStringExtra("text")
                    if (TextUtils.isEmpty(text)) text = "转发动态"
                    val dynamicId = data.getLongExtra("dynamicId", -1)
                    // 转发自动引用所需的信息：DynamicHolder 放进启动 intent，SendDynamicActivity 原样回传
                    val authorName = data.getStringExtra("forwardAuthorName")
                    val authorMid = data.getLongExtra("forwardAuthorMid", 0)
                    val authorContent = data.getStringExtra("forwardContentText")
                    val finalText = text!!
                    CenterThreadPool.run {
                        try {
                            val atUids = HashMap<String, Long>()
                            val pattern = Pattern.compile("@(\\S+)\\s")
                            val matcher = pattern.matcher(finalText)
                            while (matcher.find()) {
                                val matchedString = matcher.group(1)
                                val uid: Long
                                if (DynamicApi.mentionAtFindUser(matchedString).also { uid = it } != -1L) {
                                    atUids[matchedString] = uid
                                }
                            }
                            val emoteTexts = EmoteApi.getEmoteTexts(EmoteApi.BUSINESS_DYNAMIC)
                            val dynId = DynamicApi.relayDynamic(finalText, atUids.ifEmpty { null }, dynamicId,
                                authorName, authorMid, authorContent, emoteTexts)
                            if (dynId != -1L) {
                                activity.runOnUiThread { MsgUtil.showMsg("转发成功~") }
                            } else {
                                activity.runOnUiThread { MsgUtil.showMsg("转发失败") }
                            }
                        } catch (e: Exception) {
                            activity.runOnUiThread { MsgUtil.err(e) }
                        }
                    }
                }
            }
        }

        /**
         * 编辑动态的结果接收器。
         *
         * <p>请求本身由 SendDynamicActivity 发（组 contents、判 @ 与表情它都现成），
         * 这里只把「改完的新正文」交还给发起方，用来刷新界面上那一条动态。
         * 收到的 extras 里没有 edit_dyn_id 时什么都不做，避免误伤其他返回路径。
         */
        fun getEditDynamicLauncher(activity: BaseActivity): ActivityResultLauncher<Intent> {
            return activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                // 不管成功失败都要清掉槽位，否则下一次编辑会调到上一次的回调
                val callback = activity.pendingDynamicEdit
                activity.pendingDynamicEdit = null
                if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
                val data = result.data
                if (data == null || data.getLongExtra("edit_dyn_id", -1L) <= 0L) return@registerForActivityResult
                val newText = data.getStringExtra("text") ?: return@registerForActivityResult
                callback?.invoke(newText)
            }
        }
    }

    val selectTypeLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.resultCode
        val data = result.data
        if (code == RESULT_OK && data != null && data.getStringExtra("item") != null) {
            val type = typeNameMap[data.getStringExtra("item")]
            if (type != null) {
                if (isRefreshing) {
                    MsgUtil.showMsg("还在加载中OvO")
                } else {
                    this.type = type
                    setRefreshing(true)
                    refreshDynamic()
                }
            }
        }
    }

    val writeDynamicLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.resultCode
        val data = result.data
        if (code == RESULT_OK && data != null) {
            val text = data.getStringExtra("text") ?: ""
            val voteDraft = data.getSerializableExtra("voteDraft") as? VoteDraft
            val picsJson = data.getStringExtra("pics")
            // 定时发布：秒级时间戳，0 表示不定时（只有发布链路吃这个字段）
            val timerSeconds = data.getLongExtra("timerPubTime", 0L)
            CenterThreadPool.run {
                try {
                    val pics = if (!picsJson.isNullOrEmpty()) org.json.JSONArray(picsJson) else null
                    if (text.isEmpty() && pics == null) {
                        runOnUiThread { MsgUtil.showMsg("还没输入内容呢~") }
                    } else {
                        // 如果有投票草稿，先创建投票
                        var voteId: Long = -1
                        if (voteDraft != null && voteDraft.isValid()) {
                            voteId = VoteApi.createVote(voteDraft)
                        }

                        val atUids = HashMap<String, Long>()
                        val pattern = Pattern.compile("@(\\S+)\\s")
                        val matcher = pattern.matcher(text)
                        while (matcher.find()) {
                            val matchedString = matcher.group(1)
                            val uid: Long
                            if (DynamicApi.mentionAtFindUser(matchedString).also { uid = it } != -1L) {
                                atUids[matchedString] = uid
                            }
                        }

                        // 表情文本用于把正文里的 [xxx] 拆成 type 9 表情节点
                        val emoteTexts = EmoteApi.getEmoteTexts(EmoteApi.BUSINESS_DYNAMIC)

                        // 定时发布走 option.timer_pub_time（秒级时间戳）；不定时就不传 option，
                        // 保持各条既有链路原样
                        val option = if (timerSeconds > 0) {
                            DynamicApi.buildPublishOption(false, null, null, timerSeconds.toInt())
                        } else null

                        val dynId: Long
                        if (voteId > 0) {
                            // 有投票，使用复杂动态发布并挂载投票
                            val attachCard = org.json.JSONObject().put("vote", org.json.JSONObject().put("vote_id", voteId))
                            val contents = DynamicApi.buildContents(text, atUids.ifEmpty { null }, emoteTexts)
                            dynId = DynamicApi.publishComplex(
                                contents, pics, option, null, if (pics != null) 2 else 1,
                                attachCard, null
                            )
                        } else if (pics != null) {
                            // 带图动态走 scene=2，图片已在 SendDynamicActivity 上传成 pics 节点
                            dynId = DynamicApi.publishImageContent(text, atUids.ifEmpty { null }, pics, option, emoteTexts)
                        } else if (atUids.isEmpty() && !DynamicApi.containsEmoteText(text, emoteTexts)) {
                            // 既没有 @ 也没有可用表情，继续走原来的纯文本接口，不无谓地换链路
                            dynId = if (option == null) DynamicApi.publishTextContent(text)
                            else DynamicApi.publishTextContent(text, null, option, null)
                        } else {
                            dynId = DynamicApi.publishTextContent(text, atUids.ifEmpty { null }, option, emoteTexts)
                        }
                        if (dynId != -1L) {
                            runOnUiThread { MsgUtil.showMsg(if (timerSeconds > 0) "已设置定时发布~" else "发送成功~") }
                            // 定时发布的动态此刻还没真正发出去，getDynamic 拿回的状态不对，
                            // 插进列表只会显示一条"将来才发"的动态；跳过本地插入，由下拉刷新兜底
                            if (timerSeconds == 0L) CenterThreadPool.run {
                                try {
                                    val dynamic = DynamicApi.getDynamic(dynId)
                                    runOnUiThread {
                                        // list 突变与 Adapter 通知必须在同一线程：
                                        // 后台改 list、主线程通知的话，RecyclerView 下一次 layout 读到的
                                        // 可能是改了一半的 list，会偶发 IndexOutOfBoundsException
                                        dynamicList!!.add(0, dynamic)
                                        if (type == "all") {
                                            dynamicAdapter!!.notifyItemInserted(0)
                                            dynamicAdapter!!.notifyItemRangeChanged(0, dynamicList!!.size)
                                        }
                                    }
                                } catch (e: Exception) {
                                    MsgUtil.err(e)
                                }
                            }
                        } else {
                            runOnUiThread { MsgUtil.showMsg("发送失败") }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { MsgUtil.err(e) }
                }
            }
        }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setMenuClick()
        Log.e("debug", "进入动态页")

        setOnRefreshListener { refreshDynamic() }
        setOnLoadMoreListener { page -> addDynamic(type) }
        // 空数据时可点击重试
        setOnEmptyRetry { refreshDynamic() }

        setPageName("动态")

        // 教程改由 BaseActivity 按 Tutorials 注册表集中触发

        loadRecentUpList()
        refreshDynamic()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshDynamic() {
        Log.e("debug", "刷新")
        if (firstRefresh) {
            dynamicList = ArrayList()
        } else {
            offset = 0
            bottom = false
            dynamicList!!.clear()
            dynamicAdapter!!.notifyDataSetChanged()
        }

        loadRecentUpList()
        addDynamic(type, true)
    }

    private fun addDynamic(type: String) {
        addDynamic(type, false)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun addDynamic(type: String, refresh: Boolean) {
        Log.e("debug", "加载下一页")
        CenterThreadPool.run {
            try {
                val list = ArrayList<Dynamic>()
                offset = DynamicApi.getDynamicList(list, offset, 0, type)
                bottom = (offset == -1L)
                setRefreshing(false)

                runOnUiThread {
                    dynamicList!!.addAll(list)
                    // 无内容时给出空态（一级页此前完全没有空态）
                    if (dynamicList!!.isEmpty()) showEmptyView() else hideEmptyView()
                    if (firstRefresh) {
                        firstRefresh = false
                        dynamicAdapter = DynamicAdapter(this, dynamicList!!, recyclerView, recentUpList)
                        setAdapter(dynamicAdapter!!)
                    } else {
                        if (refresh) {
                            dynamicAdapter!!.notifyDataSetChanged()
                        } else {
                            val offset = if (showRecentUp()) 2 else 1
                            dynamicAdapter!!.notifyItemRangeInserted(dynamicList!!.size - list.size + offset, list.size)
                        }
                    }
                    if (refresh) {
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, 0)
                    }
                }

            } catch (e: Exception) {
                loadFail(e)
            }
        }
    }

    private fun loadRecentUpList() {
        CenterThreadPool.run {
            try {
                recentUpList = DynamicApi.getRecentUpList()
                runOnUiThread {
                    if (dynamicAdapter != null) {
                        dynamicAdapter!!.recentUpList = recentUpList
                        val shouldShow = showRecentUp()
                        val currentItemCount = dynamicAdapter!!.itemCount
                        val newItemCount = (if (dynamicList != null) dynamicList!!.size + 1 else 1) + (if (shouldShow) 1 else 0)
                        if (currentItemCount != newItemCount) {
                            if (shouldShow) {
                                dynamicAdapter!!.notifyItemInserted(1)
                            } else {
                                dynamicAdapter!!.notifyItemRemoved(1)
                            }
                        } else if (shouldShow) {
                            dynamicAdapter!!.notifyItemChanged(1)
                        }
                    }
                }
            } catch (e: Exception) {
                recentUpList = null
            }
        }
    }

    private fun showRecentUp(): Boolean {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.RECENT_UP_DISPLAY_ENABLE, true)
                && recentUpList != null && recentUpList!!.isNotEmpty()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DynamicHolder.GO_TO_INFO_REQUEST && resultCode == RESULT_OK) {
            try {
                if (data != null && !isRefreshing) {
                    val adapterPosition = data.getIntExtra("position", 0)
                    val offset = if (showRecentUp()) 2 else 1
                    val realPosition = adapterPosition - offset
                    if (realPosition >= 0 && realPosition < dynamicList!!.size) {
                        DynamicHolder.removeDynamicFromList(dynamicList!!, realPosition, dynamicAdapter!!, showRecentUp())
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
    }
}